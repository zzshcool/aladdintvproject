package com.aladdin.tv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.aladdin.tv.config.AppConfig
import com.aladdin.tv.data.model.Channel
import com.aladdin.tv.data.model.ChannelHealthStatus
import com.aladdin.tv.util.DeviceCapabilityUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

sealed class PlayerState {
    object Idle : PlayerState()
    object Buffering : PlayerState()
    object Playing : PlayerState()
    data class Error(val message: String, val retryCount: Int, val maxRetries: Int) : PlayerState()
}

/**
 * 阿拉丁TV 專用 Media3 ExoPlayer 管理器
 * 針對晶晨 Amlogic S905X4 晶片調校低延遲起播、硬體解碼、自適應選軌、備援切換與真實解析度檢測
 */
@OptIn(UnstableApi::class)
class AladdinPlayerManager(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private var exoPlayer: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var currentChannel: Channel? = null
    private var currentSessionToken: Long = 0L

    private var playbackAllowed = false
    private var qualityPreference = AppConfig.QUALITY_AUTO
    private var resumeRetry = false
    private var retryCount = 0
    private var retryJob: Job? = null
    private var stablePlaybackJob: Job? = null

    // 掉幀統計與冷卻
    private var droppedFramesCount = 0
    private var lastDropCheckTime = 0L

    private val _playerState = MutableStateFlow<PlayerState>(PlayerState.Idle)
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    // 真實解碼解析度標籤 (如 "4K", "1080P", "720P", "LIVE")
    private val _currentResolution = MutableStateFlow("LIVE")
    val currentResolution: StateFlow<String> = _currentResolution.asStateFlow()

    // 畫質選取與降級原因追蹤 (診斷用)
    private val _qualityDowngradeReason = MutableStateFlow("未限制")
    val qualityDowngradeReason: StateFlow<String> = _qualityDowngradeReason.asStateFlow()

    var onChannelHealthChanged: ((channelId: String, status: ChannelHealthStatus, message: String?) -> Unit)? = null
    var onExoPlayerSourceFailed: ((channel: Channel, sourceIndex: Int, sessionToken: Long, reason: String) -> Unit)? = null
    var onBackupSourceSwitched: ((newChannel: Channel) -> Unit)? = null

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(AppConfig.HTTP_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(AppConfig.HTTP_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val okHttpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        .setUserAgent(AppConfig.USER_AGENT)

    init {
        initializePlayer()
    }

    private fun initializePlayer() {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                AppConfig.MIN_BUFFER_MS,
                AppConfig.MAX_BUFFER_MS,
                AppConfig.BUFFER_FOR_PLAYBACK_MS,
                AppConfig.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(0, false)
            .build()

        val renderersFactory = DefaultRenderersFactory(context.applicationContext)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(context.applicationContext)
            .setDataSourceFactory(okHttpDataSourceFactory)

        val selector = DefaultTrackSelector(context.applicationContext)
        this.trackSelector = selector

        exoPlayer = ExoPlayer.Builder(context.applicationContext, renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(selector)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                playWhenReady = true

                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        val token = currentSessionToken
                        val channel = currentChannel ?: return
                        if (isPlaying) {
                            stablePlaybackJob?.cancel()
                            stablePlaybackJob = scope.launch(Dispatchers.Main) {
                                val startPos = exoPlayer?.currentPosition ?: 0L
                                delay(1000)
                                if (currentSessionToken != token || exoPlayer?.isPlaying != true) return@launch
                                val midPos = exoPlayer?.currentPosition ?: 0L
                                delay(1000)
                                if (currentSessionToken != token || exoPlayer?.isPlaying != true) return@launch
                                val endPos = exoPlayer?.currentPosition ?: 0L

                                // 驗證持續前進的播放證據：連續兩個觀測區間都必須有實質正向前進，移除停止前進仍可能通過的 endPos > 0
                                val isAdvancing = (midPos > startPos + 150L) && (endPos > midPos + 150L)
                                if (exoPlayer?.isPlaying == true && isAdvancing) {
                                    retryCount = 0
                                    retryJob?.cancel()
                                    _playerState.value = PlayerState.Playing
                                    onChannelHealthChanged?.invoke(channel.id, ChannelHealthStatus.NORMAL, "正常")
                                }
                            }
                        } else {
                            stablePlaybackJob?.cancel()
                            val curState = exoPlayer?.playbackState
                            if (curState == Player.STATE_BUFFERING) {
                                _playerState.value = PlayerState.Buffering
                            } else if (curState == Player.STATE_IDLE) {
                                _playerState.value = PlayerState.Idle
                            }
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        val token = currentSessionToken
                        val channel = currentChannel ?: return

                        when (state) {
                            Player.STATE_IDLE -> {
                                stablePlaybackJob?.cancel()
                                _playerState.value = PlayerState.Idle
                            }
                            Player.STATE_BUFFERING -> {
                                stablePlaybackJob?.cancel()
                                _playerState.value = PlayerState.Buffering
                                val msg = if (channel.currentSourceIndex > 0) {
                                    "切換備援 (${channel.currentSourceIndex + 1}/${channel.streamSources.size})"
                                } else {
                                    "緩衝中"
                                }
                                onChannelHealthChanged?.invoke(channel.id, ChannelHealthStatus.CONNECTING, msg)
                            }
                            Player.STATE_READY -> {
                                // 依規範：STATE_READY 不等於播放成功，等待 onIsPlayingChanged 與播放進度證據
                                if (exoPlayer?.playWhenReady == false) {
                                    stablePlaybackJob?.cancel()
                                }
                            }
                            Player.STATE_ENDED -> {
                                stablePlaybackJob?.cancel()
                                triggerAutoRetry(channel, token, "直播串流中斷")
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        stablePlaybackJob?.cancel()
                        val channel = currentChannel ?: return
                        
                        // 深入解析實際 HTTP 狀態碼與根本原因
                        var httpStatusCode: Int? = null
                        var dnsFailure = false
                        var connectionFailure = false
                        var timeoutFailure = false
                        var cause: Throwable? = error.cause
                        while (cause != null) {
                            dnsFailure = dnsFailure || cause is java.net.UnknownHostException
                            connectionFailure = connectionFailure || cause is java.net.ConnectException
                            timeoutFailure = timeoutFailure || cause is java.net.SocketTimeoutException
                            if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                                httpStatusCode = cause.responseCode
                                break
                            }
                            cause = cause.cause
                        }

                        val (errorMsg, isFatalImmediate) = when (httpStatusCode) {
                            401, 403 -> "未授權或地區限制 (HTTP $httpStatusCode)" to true
                            404, 410 -> "串流來源不存在 (HTTP $httpStatusCode)" to true
                            500, 502, 503, 504 -> "伺服器暫時維護中 (HTTP $httpStatusCode)" to false
                            else -> {
                                if (dnsFailure) {
                                    "來源網域無法解析，請確認網路或選擇其他線路" to false
                                } else if (timeoutFailure) {
                                    "來源回應逾時" to false
                                } else if (connectionFailure) {
                                    "無法連接來源伺服器" to false
                                } else if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) {
                                    "格式不支援" to true
                                } else {
                                    (error.localizedMessage ?: "串流異常") to false
                                }
                            }
                        }
                        
                        if (isFatalImmediate) {
                            retryJob?.cancel()
                            // 永久性致命錯誤 (如 HTTP 403 / 404 或格式不支援)，不再無效重試，立即由備援處理
                            onExoPlayerSourceFailed?.invoke(channel, channel.currentSourceIndex, currentSessionToken, errorMsg)
                        } else {
                            triggerAutoRetry(channel, currentSessionToken, errorMsg)
                        }
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        val w = videoSize.width
                        val h = videoSize.height
                        val label = when {
                            w >= 3840 || h >= 2160 -> "4K"
                            w >= 1920 || h >= 1080 -> "1080P"
                            w >= 1280 || h >= 720 -> "720P"
                            w > 0 -> "${h}P"
                            else -> "LIVE"
                        }
                        _currentResolution.value = label
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        for (group in tracks.groups) {
                            if (group.type == C.TRACK_TYPE_VIDEO && group.isSelected) {
                                for (i in 0 until group.length) {
                                    if (group.isTrackSelected(i)) {
                                        val format = group.getTrackFormat(i)
                                        val w = format.width
                                        val h = format.height
                                        val mime = format.sampleMimeType ?: "video/hevc"
                                        val fps = if (format.frameRate > 0) format.frameRate.toDouble() else null
                                        if (w >= 3840 || h >= 2160) {
                                            val supported = DeviceCapabilityUtils.isFormatCodecSupported(mime, w, h, fps)
                                            if (!supported) {
                                                _qualityDowngradeReason.value = "格式不相容降級: 硬體不支援 $mime ${w}x$h"
                                                applyQualityCap(1920, 1080)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                })

                // 監聽掉幀進行自適應保護
                addAnalyticsListener(object : AnalyticsListener {
                    override fun onDroppedVideoFrames(
                        eventTime: AnalyticsListener.EventTime,
                        droppedFrames: Int,
                        elapsedMs: Long
                    ) {
                        val now = System.currentTimeMillis()
                        if (now - lastDropCheckTime > 10_000L) {
                            droppedFramesCount = 0
                            lastDropCheckTime = now
                        }
                        droppedFramesCount += droppedFrames
                        if (droppedFramesCount > 40) {
                            _qualityDowngradeReason.value = "嚴重掉幀 (>40幀) 自動降級至 1080P"
                            applyQualityCap(1920, 1080)
                            droppedFramesCount = 0
                        }
                    }
                })
            }
    }

    /**
     * 套用畫質偏好上限 (自動 / 最高 1080p / 允許 4K)
     */
    fun setQualityPreference(preference: Int) {
        qualityPreference = preference
        if (trackSelector == null) return
        val capResult = DeviceCapabilityUtils.check4kCapability(context)

        val (maxWidth, maxHeight) = when (preference) {
            AppConfig.QUALITY_1080P -> {
                _qualityDowngradeReason.value = "使用者設定上限 1080P"
                Pair(1920, 1080)
            }
            AppConfig.QUALITY_4K -> {
                if (capResult.isSupported) {
                    _qualityDowngradeReason.value = "允許 4K (螢幕輸出與解碼支援)"
                } else {
                    _qualityDowngradeReason.value = "使用者偏好 4K (${capResult.downgradeReason})"
                }
                Pair(3840, 2160)
            }
            else -> { // QUALITY_AUTO
                if (capResult.isSupported) {
                    _qualityDowngradeReason.value = "自動 4K (螢幕輸出與硬解支援)"
                    Pair(3840, 2160)
                } else {
                    _qualityDowngradeReason.value = "自動降級至 1080P (${capResult.downgradeReason})"
                    Pair(1920, 1080)
                }
            }
        }

        applyQualityCap(maxWidth, maxHeight)
    }

    private fun applyQualityCap(width: Int, height: Int) {
        trackSelector?.let { selector ->
            val parameters = selector.parameters.buildUpon()
                .setMaxVideoSize(width, height)
                .build()
            selector.parameters = parameters
        }
    }

    fun attachPlayerView(playerView: PlayerView) {
        playerView.player = exoPlayer
    }

    fun play(channel: Channel, sessionToken: Long = -1L) {
        if (channel.url.isBlank()) return

        if (sessionToken != -1L) {
            currentSessionToken = sessionToken
        } else {
            currentSessionToken++
        }
        val token = currentSessionToken

        retryJob?.cancel()
        stablePlaybackJob?.cancel()
        retryCount = 0
        currentChannel = channel
        resumeRetry = false
        droppedFramesCount = 0
        lastDropCheckTime = 0L
        setQualityPreference(qualityPreference)
        _currentResolution.value = channel.currentSource?.label ?: "LIVE"

        val connectingMsg = if (channel.currentSourceIndex > 0) {
            "切換備援 (${channel.currentSourceIndex + 1}/${channel.streamSources.size})"
        } else {
            "連線中"
        }
        onChannelHealthChanged?.invoke(channel.id, ChannelHealthStatus.CONNECTING, connectingMsg)

        exoPlayer?.let { player ->
            _playerState.value = PlayerState.Buffering
            val mediaItem = MediaItem.Builder().setUri(channel.url).build()
            player.stop()
            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = playbackAllowed
        }
    }

    private fun triggerAutoRetry(channel: Channel, token: Long, reason: String) {
        if (token != currentSessionToken) return
        if (!playbackAllowed) {
            resumeRetry = true
            return
        }

        retryJob?.cancel()
        stablePlaybackJob?.cancel()

        if (retryCount < AppConfig.MAX_RETRY_COUNT) {
            retryCount++
            _playerState.value = PlayerState.Error(
                message = reason,
                retryCount = retryCount,
                maxRetries = AppConfig.MAX_RETRY_COUNT
            )
            onChannelHealthChanged?.invoke(
                channel.id,
                ChannelHealthStatus.CONNECTING,
                "重試中 ($retryCount/${AppConfig.MAX_RETRY_COUNT})"
            )

            retryJob = scope.launch(Dispatchers.Main) {
                delay(AppConfig.RETRY_INTERVAL_MS * (1L shl (retryCount - 1)))
                if (playbackAllowed && token == currentSessionToken) {
                    exoPlayer?.let { player ->
                        val mediaItem = MediaItem.fromUri(channel.url)
                        player.setMediaItem(mediaItem)
                        player.prepare()
                        player.playWhenReady = playbackAllowed
                    }
                }
            }
        } else {
            // 目前來源重試耗盡，不直接切換，交給主調度器執行跨播放器備援
            _playerState.value = PlayerState.Error(
                message = "重試耗盡：$reason",
                retryCount = retryCount,
                maxRetries = AppConfig.MAX_RETRY_COUNT
            )
            onExoPlayerSourceFailed?.invoke(channel, channel.currentSourceIndex, token, reason)
        }
    }

    fun setPlayWhenReady(playWhenReady: Boolean) {
        playbackAllowed = playWhenReady
        if (!playWhenReady) {
            resumeRetry = resumeRetry || retryJob?.isActive == true
            retryJob?.cancel()
            stablePlaybackJob?.cancel()
        } else if (resumeRetry && currentChannel != null) {
            resumeRetry = false
            currentChannel?.let { channel -> exoPlayer?.setMediaItem(MediaItem.fromUri(channel.url)) }
            exoPlayer?.prepare()
        }
        exoPlayer?.playWhenReady = playWhenReady
    }

    fun playbackPosition(): Long? = exoPlayer?.takeIf { playbackAllowed && it.isPlaying }?.currentPosition

    fun stop() {
        resumeRetry = false
        retryJob?.cancel()
        stablePlaybackJob?.cancel()
        exoPlayer?.stop()
        _playerState.value = PlayerState.Idle
        _currentResolution.value = "LIVE"
    }

    fun release() {
        retryJob?.cancel()
        stablePlaybackJob?.cancel()
        exoPlayer?.run {
            stop()
            clearMediaItems()
            release()
        }
        exoPlayer = null
        currentChannel = null
    }
}
