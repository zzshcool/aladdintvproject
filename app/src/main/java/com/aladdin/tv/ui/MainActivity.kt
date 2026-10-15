package com.aladdin.tv.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.aladdin.tv.R
import com.aladdin.tv.config.AppConfig
import com.aladdin.tv.data.model.Channel
import com.aladdin.tv.data.model.ChannelHealthStatus
import com.aladdin.tv.data.model.StreamType
import com.aladdin.tv.data.repository.ChannelRepository
import com.aladdin.tv.databinding.ActivityMainBinding
import com.aladdin.tv.player.AladdinPlayerManager
import com.aladdin.tv.player.PlayerState
import com.aladdin.tv.ui.adapter.ChannelAdapter
import com.aladdin.tv.ui.adapter.SourceAdapter
import com.aladdin.tv.ui.view.YouTubeIframePlayerView
import com.aladdin.tv.util.SystemUiUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 阿拉丁TV 主播放頁面
 * 負責 ExoPlayer 與 YouTube 內嵌播放器協調、遙控器按鍵劫持、畫質偏好與獨立健康狀態燈聯動
 */
class MainActivity : AppCompatActivity() {

    enum class TvControlMode {
        TV_APP,           // 電視 APP 操作模式 (預設：方向鍵切台/OSD/列表)
        WEB_INTERACTION,  // 官方網頁互動模式 (方向鍵與確認鍵交由 WebView 操作)
        PANEL_OPEN,       // 列表/線路/設定面板開啟 (焦點完全限制在面板內)
        FULLSCREEN        // 網頁全螢幕模式
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: ChannelRepository
    private lateinit var playerManager: AladdinPlayerManager
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var sourceAdapter: SourceAdapter

    private var currentControlMode: TvControlMode = TvControlMode.TV_APP
    private var currentChannelList = listOf<Channel>()
    private var currentChannelIndex = 0
    private var currentPlayingChannel: Channel? = null
    private var currentSessionToken: Long = 0L
    private var currentAttemptId: Long = 0L
    private var youtubeResolveJob: Job? = null
    private var activeYouTubeVideoId: String? = null
    private var youtubeRefreshAttempted = false
    private var youtubeWebWatch = false
    private var confirmedWebAttemptId = -1L
    private var webAdAttemptId = -1L
    private var webAdPlaying = false
    private val triedSourceIds = mutableSetOf<String>()

    // 播放診斷與監控狀態
    private var lastDiagAttemptResult: String = "尚未起播"
    private var lastDiagRawErrorCode: String = "無 (0)"
    private var lastDiagSuccessTime: String = "尚無"
    private var lastDiagVerificationTime: String = "尚無"

    // 定時器 Job 管理
    private var osdDismissJob: Job? = null
    private var drawerDismissJob: Job? = null
    private var numberKeyDebounceJob: Job? = null
    private var ytStablePlaybackJob: Job? = null
    private var bufferingWatchdogJob: Job? = null
    private var webWatchWatchdogJob: Job? = null
    private var healthExpiryScanJob: Job? = null
    private val numberInputBuffer = StringBuilder()

    // 遙控器按鍵成對消費追蹤 (防止 DOWN 開啟面板後 UP 洩漏點擊新面板項目)
    private val consumedDownKeyCodes = mutableSetOf<Int>()

    // 雙擊退出防誤觸標記
    private var lastBackPressTime = 0L

    // 手勢辨識 (手機觸控端)
    private lateinit var gestureDetector: GestureDetector

    /**
     * 按鍵與狀態軌跡偵錯日誌 (嚴禁記錄帳密、Cookie 或授權 Token)
     */
    private fun logKeyEvent(event: KeyEvent, phase: String) {
        val focusViewName = currentFocus?.let { "${it.javaClass.simpleName}#${it.id}" } ?: "null"
        val isWvInteracting = binding.youtubePlayerView.isWebInteractionEnabled()
        val isCustomFs = binding.youtubePlayerView.isCustomFullscreenActive()
        val visiblePanels = buildString {
            if (binding.layoutDrawer.isVisible) append("Drawer,")
            if (binding.layoutSourceSelection.isVisible) append("SourceSelect,")
            if (binding.layoutSettings.isVisible) append("Settings,")
            if (binding.layoutYoutubeError.isVisible) append("YtError,")
            if (binding.layoutError.isVisible) append("ExoError,")
            if (binding.layoutOsd.isVisible) append("Osd,")
            if (isEmpty()) append("None")
        }
        android.util.Log.d(
            "AladdinKeyRouter",
            "[$phase] code=${event.keyCode} act=${event.action} rep=${event.repeatCount} mode=$currentControlMode focus=$focusViewName panels=$visiblePanels wvInter=$isWvInteracting customFs=$isCustomFs"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repository = ChannelRepository(this)
        applySavedTheme()

        SystemUiUtils.hideSystemBars(window)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        playerManager = AladdinPlayerManager(this, lifecycleScope)
        playerManager.setQualityPreference(repository.getSavedQualityPref())

        initViews()
        initYouTubePlayer()
        initSettingsPanel()
        initGestureDetector()
        observeData()
        startHealthExpiryScanner()

        lifecycleScope.launch {
            repository.loadChannels()
        }
    }

    override fun onResume() {
        super.onResume()
        SystemUiUtils.hideSystemBars(window)
        playerManager.setPlayWhenReady(true)
        val channel = currentPlayingChannel
        if (channel != null) {
            if (channel.streamType == StreamType.YOUTUBE || channel.currentSource?.type == StreamType.WEB_VIEW) {
                binding.youtubePlayerView.resumeVideo()
            }
            // 恢復前台時重新建立有期限的播放監測，不因背景暫停扣除來源健康分數
            startBufferingWatchdog(channel, currentAttemptId)
        }
    }

    override fun onPause() {
        super.onPause()
        // 進入背景時暫停相關監測與工作，避免扣除來源健康分數
        bufferingWatchdogJob?.cancel()
        webWatchWatchdogJob?.cancel()
        ytStablePlaybackJob?.cancel()

        playerManager.setPlayWhenReady(false)
        if (currentPlayingChannel?.streamType == StreamType.YOUTUBE || currentPlayingChannel?.currentSource?.type == StreamType.WEB_VIEW) {
            binding.youtubePlayerView.pauseVideo()
        }
    }

    override fun onDestroy() {
        youtubeResolveJob?.cancel()
        super.onDestroy()
        osdDismissJob?.cancel()
        drawerDismissJob?.cancel()
        numberKeyDebounceJob?.cancel()
        ytStablePlaybackJob?.cancel()
        bufferingWatchdogJob?.cancel()
        webWatchWatchdogJob?.cancel()
        healthExpiryScanJob?.cancel()
        binding.youtubePlayerView.release()
        playerManager.release()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            SystemUiUtils.hideSystemBars(window)
        }
    }

    private fun applySavedTheme() {
        val nightMode = when (repository.getSavedTheme()) {
            AppConfig.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            AppConfig.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    private fun initViews() {
        playerManager.attachPlayerView(binding.playerView)

        playerManager.onChannelHealthChanged = { channelId, status, message ->
            currentPlayingChannel?.let { ch ->
                repository.updateChannelHealth(channelId, status, message, ch.currentSourceIndex, ch.streamSources.size)
            } ?: repository.updateChannelHealth(channelId, status, message)
        }

        playerManager.onExoPlayerSourceFailed = { channel, sourceIndex, sessionToken, reason ->
            if (sessionToken == currentSessionToken) {
                val failedUrl = channel.streamSources.getOrNull(sourceIndex)?.url ?: ""
                repository.recordSourceFailure(failedUrl, reason)
                lastDiagRawErrorCode = "ExoPlayer 播放異常"
                lastDiagAttemptResult = "嘗試失敗: $reason"
                lastDiagVerificationTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                triggerNextFallback(channel, currentAttemptId, reason)
            }
        }

        playerManager.onBackupSourceSwitched = { updatedChannel ->
            currentPlayingChannel = updatedChannel
            val idx = currentChannelList.indexOfFirst { it.id == updatedChannel.id }
            if (idx != -1) {
                currentChannelList = currentChannelList.toMutableList().apply { set(idx, updatedChannel) }
                channelAdapter.submitList(currentChannelList)
            }
        }

        channelAdapter = ChannelAdapter(
            getChannelHealth = { channel -> repository.getChannelHealth(channel) },
            onChannelClick = { channel, position ->
                playChannel(position)
                hideDrawer()
            }
        )

        binding.rvChannels.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = channelAdapter
            setHasFixedSize(true)
        }

        binding.btnOpenSettings.setOnClickListener {
            hideDrawer()
            showSettingsPanel()
        }
        binding.layoutTouchControls.isVisible = packageManager.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_TOUCHSCREEN
        )
        if (binding.layoutTouchControls.isVisible) {
            for (list in listOf(binding.rvChannels, binding.rvStreamSources)) {
                list.addOnChildAttachStateChangeListener(object : androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener {
                    override fun onChildViewAttachedToWindow(view: View) {
                        view.isFocusableInTouchMode = false
                    }
                    override fun onChildViewDetachedFromWindow(view: View) = Unit
                })
            }
        }
        binding.btnTouchChannels.setOnClickListener {
            exitWebInteraction()
            hideSourceSelectionPanel()
            hideSettingsPanel()
            showDrawer()
        }
        binding.btnTouchSources.setOnClickListener {
            exitWebInteraction()
            showSourceSelectionPanel()
        }
        binding.btnTouchExit.setOnClickListener {
            playerManager.stop()
            binding.youtubePlayerView.stopAndClear()
            finish()
        }
        binding.btnTouchWeb.setOnClickListener {
            if (binding.youtubePlayerView.isVisible) {
                hideDrawer()
                hideSettingsPanel()
                hideSourceSelectionPanel()
                if (binding.youtubePlayerView.isWebInteractionEnabled()) exitWebInteraction()
                else enterWebInteraction()
            }
        }
        binding.btnTouchSettings.setOnClickListener {
            exitWebInteraction()
            hideSourceSelectionPanel()
            hideDrawer()
            showSettingsPanel()
        }

        // 線路選擇面板適配器與事件
        sourceAdapter = SourceAdapter { source, position ->
            currentPlayingChannel?.let { ch ->
                repository.saveUserSelectedSource(ch.id, source.sourceId)
                repository.saveUserSelectedSource(ch.canonicalId, source.sourceId)
                hideSourceSelectionPanel()
                Toast.makeText(this@MainActivity, "已選取線路：${source.label ?: "線路 ${position + 1}"}", Toast.LENGTH_SHORT).show()
                playChannelStream(ch, position, isAutoFallback = false)
            }
        }
        binding.rvStreamSources.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = sourceAdapter
            setHasFixedSize(true)
        }
        binding.btnCloseSourceSelection.setOnClickListener {
            hideSourceSelectionPanel()
        }

        // 原生播放錯誤出口按鈕
        binding.btnErrorRetry.setOnClickListener {
            binding.layoutError.isVisible = false
            currentPlayingChannel?.let { ch ->
                playChannelStream(ch, ch.currentSourceIndex, isAutoFallback = false)
            }
        }
        binding.btnErrorSelectSource.setOnClickListener {
            binding.layoutError.isVisible = false
            showSourceSelectionPanel()
        }
        binding.btnErrorOpenDrawer.setOnClickListener {
            binding.layoutError.isVisible = false
            showDrawer()
        }

        // 網頁互動模式退出條點擊
        binding.layoutWebInteractionBanner.setOnClickListener {
            exitWebInteraction()
        }
    }

    /**
     * 初始化官方 YouTube 內嵌播放器與錯誤處理
     */
    private fun initYouTubePlayer() {
        binding.youtubePlayerView.listener = object : YouTubeIframePlayerView.YouTubePlayerListener {
            override fun onReady(
                attemptId: Long,
                channelId: String,
                sourceId: String,
                playMode: YouTubeIframePlayerView.PlayMode
            ) {
                // 不以 onReady 判定播放成功，嚴格等待實際播放狀態與持續前進之進度證據
            }

            override fun onPlaying(
                attemptId: Long,
                channelId: String,
                sourceId: String,
                playMode: YouTubeIframePlayerView.PlayMode
            ) {
                if (attemptId != currentAttemptId || channelId != currentPlayingChannel?.id) return
                bufferingWatchdogJob?.cancel()
                binding.layoutLoading.isVisible = false
                binding.layoutYoutubeError.isVisible = false
            }

            override fun onPlayingConfirmed(
                attemptId: Long,
                channelId: String,
                sourceId: String,
                playMode: YouTubeIframePlayerView.PlayMode,
                currentTimeSeconds: Double,
                isLiveConfirmed: Boolean
            ) {
                if (attemptId != currentAttemptId || channelId != currentPlayingChannel?.id) return
                val channel = currentPlayingChannel ?: return
                bufferingWatchdogJob?.cancel()
                webWatchWatchdogJob?.cancel()
                confirmedWebAttemptId = attemptId
                binding.layoutLoading.isVisible = false
                binding.layoutYoutubeError.isVisible = false

                val currentUrl = channel.currentSource?.url ?: ""
                repository.recordSourceSuccess(currentUrl)
                repository.rememberWatchedChannel(channel)

                val nowStr = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date())
                lastDiagSuccessTime = nowStr
                lastDiagVerificationTime = nowStr
                lastDiagAttemptResult = if (isLiveConfirmed) "播放成功 (直播確認)" else "網頁媒體播放中／直播狀態未確認"

                ytStablePlaybackJob?.cancel()
                ytStablePlaybackJob = lifecycleScope.launch {
                    delay(AppConfig.STABLE_PLAYBACK_DELAY_MS)
                    if (currentAttemptId == attemptId && currentPlayingChannel?.id == channelId) {
                        val (status, msg) = if (!isLiveConfirmed) {
                            // 無法區分廣告與內容或未確認直播時，顯示未確認，不得假裝直播綠燈
                            ChannelHealthStatus.CONNECTING to "網頁媒體播放中／直播狀態未確認"
                        } else if (playMode == YouTubeIframePlayerView.PlayMode.IFRAME) {
                            ChannelHealthStatus.NORMAL to getString(R.string.status_normal)
                        } else {
                            // 官方網頁觀看模式：具有實際播放證據，顯示為官方網頁觀看
                            ChannelHealthStatus.NORMAL to getString(R.string.youtube_status_web_watch)
                        }
                        repository.updateChannelHealth(
                            channel.id,
                            status,
                            msg,
                            channel.currentSourceIndex,
                            channel.streamSources.size
                        )
                    }
                }
            }

            override fun onBuffering(
                attemptId: Long,
                channelId: String,
                sourceId: String,
                playMode: YouTubeIframePlayerView.PlayMode
            ) {
                if (attemptId != currentAttemptId || channelId != currentPlayingChannel?.id) return
                val channel = currentPlayingChannel ?: return
                confirmedWebAttemptId = -1L
                ytStablePlaybackJob?.cancel()
                binding.layoutLoading.isVisible = true
                startBufferingWatchdog(channel, attemptId)

                val msg = if (channel.currentSourceIndex > 0) {
                    getString(R.string.status_switching_backup, channel.currentSourceIndex + 1, channel.streamSources.size)
                } else {
                    getString(R.string.buffering)
                }
                repository.updateChannelHealth(
                    channel.id,
                    ChannelHealthStatus.CONNECTING,
                    msg,
                    channel.currentSourceIndex,
                    channel.streamSources.size
                )
            }

            override fun onEnded(
                attemptId: Long,
                channelId: String,
                sourceId: String,
                playMode: YouTubeIframePlayerView.PlayMode
            ) {
                if (attemptId != currentAttemptId || channelId != currentPlayingChannel?.id) return
                val channel = currentPlayingChannel ?: return
                ytStablePlaybackJob?.cancel()
                webWatchWatchdogJob?.cancel()
                lastDiagAttemptResult = "直播串流已結束"
                lastDiagVerificationTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                if (refreshExpiredYouTubeId(channel)) return
                if (repository.isAutoFallbackEnabled()) {
                    triggerNextFallback(channel, attemptId, "直播串流已結束")
                } else {
                    showManualPlaybackError(channel, "直播串流已結束")
                }
            }

            override fun onAdStateChanged(attemptId: Long, channelId: String, sourceId: String, isAdPlaying: Boolean) {
                if (attemptId != currentAttemptId || channelId != currentPlayingChannel?.id) return
                if (webAdAttemptId == attemptId && webAdPlaying == isAdPlaying) return
                webAdAttemptId = attemptId
                webAdPlaying = isAdPlaying
                val channel = currentPlayingChannel ?: return
                if (isAdPlaying) {
                    confirmedWebAttemptId = -1L
                    // 廣告播放中：暫停緩衝看門狗，避免廣告期間被誤判逾時換線
                    bufferingWatchdogJob?.cancel()
                    webWatchWatchdogJob?.cancel()
                    binding.layoutLoading.isVisible = false
                    repository.updateChannelHealth(
                        channel.id,
                        ChannelHealthStatus.CONNECTING,
                        "官方廣告播放中 (請稍候...)",
                        channel.currentSourceIndex,
                        channel.streamSources.size
                    )
                } else if (confirmedWebAttemptId != attemptId) {
                    // 廣告結束切入正片：重啟監聽
                    startWebWatchWatchdog(channel, sourceId, attemptId)
                }
            }

            override fun onLoginRequired(attemptId: Long, channelId: String, sourceId: String) {
                if (attemptId != currentAttemptId || channelId != currentPlayingChannel?.id) return
                val channel = currentPlayingChannel ?: return
                bufferingWatchdogJob?.cancel()
                webWatchWatchdogJob?.cancel()
                binding.layoutLoading.isVisible = false
                showManualPlaybackError(channel, "此來源需要官方登入，目前不支援在 TV 播放")
            }

            override fun onGeoBlocked(
                attemptId: Long,
                channelId: String,
                sourceId: String,
                rawReason: String
            ) {
                if (attemptId != currentAttemptId || channelId != currentPlayingChannel?.id) return
                bufferingWatchdogJob?.cancel()
                ytStablePlaybackJob?.cancel()
                webWatchWatchdogJob?.cancel()
                binding.layoutLoading.isVisible = false

                val channel = currentPlayingChannel ?: return
                val failedUrl = channel.currentSource?.url ?: ""

                lastDiagRawErrorCode = "Geo-blocking (地區限制)"
                lastDiagAttemptResult = "此 YouTube 影片在目前地區不可播放"
                lastDiagVerificationTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                repository.recordSourceFailure(failedUrl, rawReason)

                // 清楚標明為地區限制，手動模式維持原線路等待使用者決定，絕不自動亂跳
                showManualPlaybackError(channel, "此 YouTube 影片在目前地區不可播放", allowWebWatch = false)
            }

            override fun onError(
                attemptId: Long,
                channelId: String,
                sourceId: String,
                playMode: YouTubeIframePlayerView.PlayMode,
                errorCode: Int,
                rawReason: String,
                description: String
            ) {
                if (attemptId != currentAttemptId || channelId != currentPlayingChannel?.id) return
                bufferingWatchdogJob?.cancel()
                ytStablePlaybackJob?.cancel()
                webWatchWatchdogJob?.cancel()
                binding.layoutLoading.isVisible = false

                val channel = currentPlayingChannel ?: return
                val failedUrl = channel.currentSource?.url ?: ""

                lastDiagRawErrorCode = "$errorCode ($rawReason)"
                lastDiagAttemptResult = "嘗試失敗: $description"
                lastDiagVerificationTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                repository.recordSourceFailure(failedUrl, description)

                if (errorCode in setOf(2, 100) && refreshExpiredYouTubeId(channel)) return

                val isAutoFallback = repository.isAutoFallbackEnabled()

                // 若在 IFrame 模式收到 101/150 (官方禁止第三方嵌入)：
                if (playMode == YouTubeIframePlayerView.PlayMode.IFRAME && (errorCode == 101 || errorCode == 150)) {
                    val vid = currentYouTubeWatchTarget()
                    if (vid.isNotBlank()) {
                        if (isAutoFallback) {
                            // 自動備援模式：自動切換至官方網頁觀看
                            Toast.makeText(this@MainActivity, getString(R.string.youtube_switching_web_watch), Toast.LENGTH_SHORT).show()
                            repository.updateChannelHealth(channel.id, ChannelHealthStatus.CONNECTING, getString(R.string.youtube_status_web_watch), channel.currentSourceIndex, channel.streamSources.size)
                            currentAttemptId++
                            val webAttemptId = currentAttemptId
                            youtubeWebWatch = true
                            binding.youtubePlayerView.loadWebWatch(channel.id, sourceId, webAttemptId, vid)
                            startWebWatchWatchdog(channel, sourceId, webAttemptId)
                            return
                        } else {
                            // 手動主導模式：禁止靜默切換，顯示提示並提供「以官方網頁觀看」按鈕供使用者決定
                            showManualPlaybackError(channel, "頻道官方禁止第三方嵌入 (150)", allowWebWatch = true)
                            return
                        }
                    }
                }

                // 手動主導模式 vs 自動備援模式
                if (isAutoFallback) {
                    triggerNextFallback(channel, attemptId, description)
                } else {
                    showManualPlaybackError(channel, description)
                }
            }

            override fun onAutoplayBlocked(
                attemptId: Long,
                channelId: String,
                sourceId: String,
                playMode: YouTubeIframePlayerView.PlayMode
            ) {
                if (attemptId != currentAttemptId || channelId != currentPlayingChannel?.id) return
                bufferingWatchdogJob?.cancel()
                webWatchWatchdogJob?.cancel()
                binding.layoutLoading.isVisible = false
                binding.layoutYoutubeError.isVisible = true
                binding.tvYoutubeErrorMsg.text = getString(R.string.youtube_autoplay_blocked)
                binding.btnYoutubeRetry.text = getString(R.string.btn_play_manual)
                binding.btnYoutubeWebWatch.isVisible = false
                binding.btnYoutubeRetry.requestFocus()
            }
        }

        binding.btnYoutubeRetry.setOnClickListener {
            binding.layoutYoutubeError.isVisible = false
            if (binding.btnYoutubeRetry.text == getString(R.string.btn_play_manual)) {
                binding.btnYoutubeRetry.text = getString(R.string.btn_retry)
                currentPlayingChannel?.let { ch ->
                    startBufferingWatchdog(ch, currentAttemptId)
                }
                binding.youtubePlayerView.triggerManualPlay()
            } else {
                currentPlayingChannel?.let { ch ->
                    // 手動重試只重試當前線路，絕不偷偷重置為 index 0
                    playChannelStream(ch, ch.currentSourceIndex, isAutoFallback = false)
                }
            }
        }

        binding.btnYoutubeSelectSource.setOnClickListener {
            binding.layoutYoutubeError.isVisible = false
            showSourceSelectionPanel()
        }

        binding.btnYoutubeWebWatch.setOnClickListener {
            binding.layoutYoutubeError.isVisible = false
            currentPlayingChannel?.let { ch ->
                val src = ch.currentSource
                val vid = currentYouTubeWatchTarget()
                if (vid.isNotBlank()) {
                    currentAttemptId++
                    val webAttemptId = currentAttemptId
                    youtubeWebWatch = true
                    Toast.makeText(this@MainActivity, getString(R.string.youtube_switching_web_watch), Toast.LENGTH_SHORT).show()
                    binding.youtubePlayerView.loadWebWatch(ch.id, src?.sourceId ?: "", webAttemptId, vid)
                    startWebWatchWatchdog(ch, src?.sourceId ?: "", webAttemptId)
                }
            }
        }

        binding.btnYoutubeNext.setOnClickListener {
            binding.layoutYoutubeError.isVisible = false
            showDrawer()
        }
    }

    /**
     * 初始化設定面板控制項與按鈕監聽
     */
    private fun initSettingsPanel() {
        binding.btnFavoriteChannel.setOnClickListener {
            currentPlayingChannel?.let { repository.toggleFavorite(it) }
            updateFavoriteButton()
        }
        binding.btnFavoriteList.setOnClickListener { showSavedChannels(false) }
        binding.btnRecentChannels.setOnClickListener { showSavedChannels(true) }
        binding.btnRefreshLiveId.setOnClickListener {
            val channel = currentPlayingChannel ?: return@setOnClickListener
            val handle = channel.currentSource?.liveChannelHandle
            if (handle.isNullOrBlank()) {
                Toast.makeText(this, R.string.no_live_handle, Toast.LENGTH_SHORT).show()
            } else {
                repository.invalidateYouTubeResolution(handle)
                playChannelStream(channel, channel.currentSourceIndex, false)
                hideSettingsPanel()
            }
        }
        binding.btnHiddenChannels.setOnClickListener {
            val hidden = repository.getHiddenChannels().sortedBy { it.name }
            if (hidden.isEmpty()) {
                Toast.makeText(this, R.string.no_hidden_channels, Toast.LENGTH_SHORT).show()
            } else {
                AlertDialog.Builder(this).setTitle(R.string.restore_hidden_channel)
                    .setItems(hidden.map { it.name }.toTypedArray()) { _, index ->
                        val selected = hidden[index]
                        repository.restoreHiddenChannel(selected)
                        val restored = repository.channels.value.firstOrNull { it.canonicalId == selected.canonicalId }
                        if (restored != null) {
                            hideSettingsPanel()
                            playChannelStream(restored, restored.currentSourceIndex, false)
                        }
                    }.setNegativeButton(android.R.string.cancel, null).show()
            }
        }
        binding.btnHideChannel.setOnClickListener {
            val channel = currentPlayingChannel ?: return@setOnClickListener
            AlertDialog.Builder(this).setTitle(R.string.hide_current_channel)
                .setMessage(getString(R.string.hide_channel_confirmation, channel.name))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    repository.hideChannel(channel)
                    youtubeResolveJob?.cancel()
                    currentAttemptId++
                    bufferingWatchdogJob?.cancel()
                    webWatchWatchdogJob?.cancel()
                    ytStablePlaybackJob?.cancel()
                    playerManager.stop()
                    binding.youtubePlayerView.stopAndClear()
                    currentPlayingChannel = null
                    channelAdapter.setPlayingIndex(-1)
                    hideSettingsPanel()
                    repository.channels.value.firstOrNull()?.let { playChannelStream(it, it.currentSourceIndex, false) }
                }.setNegativeButton(android.R.string.cancel, null).show()
        }
        binding.btnCloseSettings.setOnClickListener {
            hideSettingsPanel()
        }

        // 主題外觀切換
        binding.btnThemeSystem.setOnClickListener { updateTheme(AppConfig.THEME_SYSTEM) }
        binding.btnThemeDark.setOnClickListener { updateTheme(AppConfig.THEME_DARK) }
        binding.btnThemeLight.setOnClickListener { updateTheme(AppConfig.THEME_LIGHT) }

        // 畫質上限切換
        binding.btnQualityAuto.setOnClickListener { updateQualityPref(AppConfig.QUALITY_AUTO) }
        binding.btnQuality1080p.setOnClickListener { updateQualityPref(AppConfig.QUALITY_1080P) }
        binding.btnQuality4k.setOnClickListener { updateQualityPref(AppConfig.QUALITY_4K) }

        // 頻道來源手動更新
        binding.btnRefreshChannels.setOnClickListener {
            Toast.makeText(this, "正在連線更新頻道來源清單…", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val isSuccess = repository.fetchAndRefreshFromRemote()
                refreshSettingsSourceInfo()
                val msg = if (isSuccess) getString(R.string.source_status_success) else getString(R.string.source_status_fallback)
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }

        // 自動備援切換按鈕 (手動主導 vs 自動備援)
        updateAutoFallbackButtonState()
        binding.btnToggleAutoFallback.setOnClickListener {
            val nextState = !repository.isAutoFallbackEnabled()
            repository.setAutoFallbackEnabled(nextState)
            updateAutoFallbackButtonState()
            val stateMsg = if (nextState) "已啟用自動備援切換 (故障時自動換線)" else "已關閉自動備援 (手動主導模式)"
            Toast.makeText(this@MainActivity, stateMsg, Toast.LENGTH_SHORT).show()
        }

        // 清除網頁快取與登入資料
        binding.btnClearWebData.setOnClickListener {
            binding.youtubePlayerView.clearSiteData()
            Toast.makeText(this@MainActivity, "已清除本站網頁快取與登入資料", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateAutoFallbackButtonState() {
        val isAuto = repository.isAutoFallbackEnabled()
        binding.btnToggleAutoFallback.text = if (isAuto) {
            "自動備援切換：已啟用 (故障時自動換線)"
        } else {
            "自動備援切換：已關閉 (手動主導)"
        }
    }

    private fun updateTheme(themeMode: Int) {
        repository.saveTheme(themeMode)
        applySavedTheme()
        Toast.makeText(this, "主題外觀已套用", Toast.LENGTH_SHORT).show()
    }

    private fun updateQualityPref(pref: Int) {
        repository.saveQualityPref(pref)
        playerManager.setQualityPreference(pref)
        Toast.makeText(this, "畫質偏好上限已套用", Toast.LENGTH_SHORT).show()
    }

    private fun showSettingsPanel() {
        currentControlMode = TvControlMode.PANEL_OPEN
        binding.youtubePlayerView.setFocusIsolationEnabled(true)
        hideDrawer()
        hideSourceSelectionPanel()
        binding.layoutYoutubeError.isVisible = false
        binding.layoutError.isVisible = false
        refreshSettingsSourceInfo()
        refreshPlaybackDiagnostics()
        updateAutoFallbackButtonState()
        binding.layoutSettings.isVisible = true
        binding.btnThemeSystem.requestFocus()
    }

    private fun hideSettingsPanel() {
        binding.layoutSettings.isVisible = false
        updateControlModeAndFocusIsolationAfterPanelClose()
    }

    private fun refreshSettingsSourceInfo() {
        binding.tvSourceLastUpdated.text = String.format(getString(R.string.source_last_updated), repository.getLastUpdateTime())
        binding.tvSourceStatusDesc.text = repository.getSourceStatus()
        val count = repository.getChannelsCount()
        val totalSources = repository.getTotalSourcesCount()
        binding.tvSourceChannelCount.text = "有效頻道：共 $count 台 ($totalSources 條線路)"
        refreshPlaybackDiagnostics()
    }

    /**
     * 更新設定面板中的即時播放診斷資訊 (網址已脫敏，防止 Cookie / Token 洩漏)
     */
    private fun refreshPlaybackDiagnostics() {
        updateFavoriteButton()
        val versionHeader = "版本：v${AppConfig.VERSION_NAME} (Build ${AppConfig.VERSION_CODE} · ${AppConfig.BUILD_ID})"
        val ch = currentPlayingChannel
        if (ch == null) {
            binding.tvDiagChannel.text = "$versionHeader\n頻道：無"
            binding.tvDiagSource.text = "線路：無"
            binding.tvDiagMode.text = "模式：無"
            binding.tvDiagQualityStrategy.text = "4K策略：自動"
            binding.tvDiagResult.text = "本輪嘗試：尚未起播"
            return
        }

        binding.tvDiagChannel.text = "$versionHeader\n頻道：CH %02d %s".format(ch.number, ch.name)

        val src = ch.currentSource
        if (src != null) {
            val sanitized = src.getSanitizedUrl()
            val srcIdx = ch.currentSourceIndex + 1
            val total = ch.streamSources.size
            val label = src.label ?: "預設線路"
            binding.tvDiagSource.text = "線路：$label ($srcIdx/$total)\n位址：$sanitized"
            if (src.type == StreamType.YOUTUBE) {
                val resolution = src.liveChannelHandle?.let { repository.getYouTubeResolution(it) }
                val resolvedTime = resolution?.second?.let { SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault()).format(Date(it)) } ?: "尚無"
                binding.tvDiagSource.append("\n本次影片 ID：${activeYouTubeVideoId ?: "官方直播入口"}\n解析時間：$resolvedTime")
            }
        } else {
            binding.tvDiagSource.text = "線路：無可用線路"
        }

        val modeDesc = when (src?.type) {
            StreamType.YOUTUBE -> if (youtubeWebWatch) "模式：YouTube 官方網頁觀看" else "模式：YouTube 官方 IFrame (網頁自適應，獨立於 Media3)"
            StreamType.WEB_VIEW -> "模式：官方網頁觀看 (WebView 自適應，獨立於 Media3)"
            else -> "模式：Media3 原生硬解 (支援畫質上限限制)"
        }
        binding.tvDiagMode.text = modeDesc

        val qualityStrategyText = if (src?.type == StreamType.YOUTUBE || src?.type == StreamType.WEB_VIEW) {
            "4K策略：網頁自適應 (由官方伺服器調配)"
        } else {
            "4K策略：${playerManager.qualityDowngradeReason.value}"
        }
        binding.tvDiagQualityStrategy.text = qualityStrategyText

        binding.tvDiagResult.text = "結果：$lastDiagAttemptResult\n錯誤碼：$lastDiagRawErrorCode\n最後驗證：$lastDiagVerificationTime | 成功：$lastDiagSuccessTime"
        currentPlayingChannel?.currentSource?.url?.let { url ->
            repository.getSourceLastFailure(url)?.let { reason ->
                binding.tvDiagResult.append("\n此線路最近失敗：$reason")
            }
        }
    }

    private fun startHealthExpiryScanner() {
        healthExpiryScanJob = lifecycleScope.launch {
            while (true) {
                delay(AppConfig.HEALTH_EXPIRY_SCAN_INTERVAL_MS)
                repository.scanExpiredHealthStatuses()
            }
        }
    }

    private fun observeData() {
        // 1. 監聽頻道清單更新 (保留當前頻道位置)
        lifecycleScope.launch {
            repository.channels.collect { channels ->
                if (channels.isEmpty()) {
                    currentChannelList = emptyList()
                    currentChannelIndex = 0
                    channelAdapter.submitList(emptyList())
                    binding.tvDrawerChannelCount.text = "共 0 台 (0 條線路)"
                    return@collect
                }
                if (channels.isNotEmpty()) {
                    currentChannelList = channels
                    channelAdapter.submitList(channels)
                    val totalSources = channels.sumOf { it.streamSources.size }
                    binding.tvDrawerChannelCount.text = "共 ${channels.size} 台 ($totalSources 條線路)"

                    val playing = currentPlayingChannel
                    if (playing != null) {
                        val matchIndex = channels.indexOfFirst {
                            (it.canonicalId.isNotBlank() && it.canonicalId == playing.canonicalId) ||
                            it.id == playing.id ||
                            it.name == playing.name
                        }
                        if (matchIndex != -1) {
                            val matchedChannel = channels[matchIndex]
                            val currentUrl = playing.currentSource?.url
                            val newSourceIndex = if (currentUrl != null) {
                                matchedChannel.streamSources.indexOfFirst { it.url == currentUrl }.let {
                                    if (it != -1) it else matchedChannel.currentSourceIndex.coerceIn(0, (matchedChannel.streamSources.size - 1).coerceAtLeast(0))
                                }
                            } else {
                                matchedChannel.currentSourceIndex
                            }
                            val updatedPlaying = matchedChannel.withSourceIndex(newSourceIndex)
                            currentChannelIndex = matchIndex
                            currentPlayingChannel = updatedPlaying
                            channelAdapter.setPlayingIndex(matchIndex)
                        }
                    } else if (channelAdapter.getPlayingIndex() == -1) {
                        val savedId = repository.getLastChannelId()
                        val savedUrl = repository.getLastChannelUrl()
                        val found = repository.findChannelHierarchically(channels, savedId, savedUrl)
                        val targetIndex = found?.first ?: 0
                        val targetSourceIdx = found?.second ?: 0
                        currentChannelIndex = targetIndex
                        val targetChannel = channels[targetIndex]
                        channelAdapter.setPlayingIndex(targetIndex)
                        playChannelStream(targetChannel, targetSourceIdx, isAutoFallback = false)
                    }
                }
            }
        }

        // 2. 監聽各頻道健康狀態變更，僅局部刷新受影響項目
        lifecycleScope.launch {
            repository.healthMap.collect { healthMap ->
                for ((channelId, _) in healthMap) {
                    channelAdapter.notifyHealthChanged(channelId)
                }
            }
        }

        // 3. 監聽 ExoPlayer 實際解碼解析度
        lifecycleScope.launch {
            playerManager.currentResolution.collect { res ->
                if (currentPlayingChannel?.streamType != StreamType.YOUTUBE &&
                    currentPlayingChannel?.currentSource?.type != StreamType.WEB_VIEW) {
                    binding.tvOsdResolution.text = res
                }
            }
        }

        // 4. 監聽 ExoPlayer 狀態
        lifecycleScope.launch {
            playerManager.playerState.collect { state ->
                val curType = currentPlayingChannel?.currentSource?.type
                if (curType == StreamType.YOUTUBE || curType == StreamType.WEB_VIEW) return@collect

                when (state) {
                    is PlayerState.Idle -> {
                        binding.layoutLoading.isVisible = false
                        binding.layoutError.isVisible = false
                    }
                    is PlayerState.Buffering -> {
                        binding.layoutLoading.isVisible = true
                        binding.layoutError.isVisible = false
                        startBufferingWatchdog(currentPlayingChannel, currentAttemptId)
                    }
                    is PlayerState.Playing -> {
                        bufferingWatchdogJob?.cancel()
                        binding.layoutLoading.isVisible = false
                        binding.layoutError.isVisible = false
                        val nowStr = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date())
                        lastDiagSuccessTime = nowStr
                        lastDiagVerificationTime = nowStr
                        lastDiagAttemptResult = "播放成功 (持續前進)"
                        currentPlayingChannel?.currentSource?.url?.let {
                            repository.recordSourceSuccess(it)
                        }
                        currentPlayingChannel?.let { repository.rememberWatchedChannel(it) }
                    }
                    is PlayerState.Error -> {
                        binding.layoutLoading.isVisible = false
                        binding.layoutError.isVisible = true
                        binding.tvErrorMsg.text = state.message
                        binding.tvErrorRetryCountdown.text =
                            "正在進行自動重試 (${state.retryCount}/${state.maxRetries})…"
                    }
                }
            }
        }
    }

    /**
     * 切換頻道並執行雙播放器無縫分流
     */
    private fun playChannel(index: Int) {
        if (currentChannelList.isEmpty()) return

        val validIndex = when {
            index < 0 -> currentChannelList.size - 1
            index >= currentChannelList.size -> 0
            else -> index
        }

        currentChannelIndex = validIndex
        val channel = currentChannelList[validIndex]
        channelAdapter.setPlayingIndex(validIndex)

        playChannelStream(channel, 0, isAutoFallback = false)
    }

    /**
     * 核心播放調度器：統一處理首次播放、重試與備援切換，嚴格隔離舊工作階段與雙重聲音
     */
    private fun playChannelStream(channel: Channel, sourceIndex: Int, isAutoFallback: Boolean, refreshingLiveId: Boolean = false) {
        youtubeResolveJob?.cancel()
        activeYouTubeVideoId = null
        youtubeWebWatch = false
        confirmedWebAttemptId = -1L
        webAdAttemptId = -1L
        webAdPlaying = false
        if (!refreshingLiveId) youtubeRefreshAttempted = false
        currentSessionToken++
        currentAttemptId++
        val sessionToken = currentSessionToken
        val attemptId = currentAttemptId

        bufferingWatchdogJob?.cancel()
        ytStablePlaybackJob?.cancel()
        webWatchWatchdogJob?.cancel()

        val activeChannel = channel.withSourceIndex(sourceIndex)
        currentPlayingChannel = activeChannel

        // 切台重置：恢復 TV_APP 控制模式，隱藏所有錯誤浮層，鎖定電視盒根焦點
        currentControlMode = TvControlMode.TV_APP
        binding.layoutWebInteractionBanner.isVisible = false
        binding.youtubePlayerView.setWebInteractionEnabled(false)
        binding.youtubePlayerView.setFocusIsolationEnabled(true)
        binding.layoutError.isVisible = false
        binding.layoutYoutubeError.isVisible = false
        binding.rootContainer.requestFocus()

        val timeNow = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        lastDiagAttemptResult = "連線嘗試中 (Attempt #$attemptId)"
        lastDiagRawErrorCode = "無 (0)"
        lastDiagVerificationTime = timeNow

        val currentSource = activeChannel.currentSource
        if (currentSource == null) {
            showPlaybackExhausted(activeChannel, getString(R.string.status_all_sources_failed))
            return
        }

        if (!isAutoFallback) {
            triedSourceIds.clear()
        }
        triedSourceIds.add(currentSource.sourceId)

        // 同步 Adapter 內部頻道來源索引
        val listIdx = currentChannelList.indexOfFirst { it.id == activeChannel.id }
        if (listIdx != -1) {
            currentChannelIndex = listIdx
            channelAdapter.setPlayingIndex(listIdx)
        }
        if (listIdx != -1 && currentChannelList[listIdx].currentSourceIndex != sourceIndex) {
            currentChannelList = currentChannelList.toMutableList().apply { set(listIdx, activeChannel) }
            channelAdapter.submitList(currentChannelList)
        }

        repository.saveLastChannel(activeChannel.id, activeChannel.url)
        showOsd(activeChannel)

        val connectingMsg = if (activeChannel.streamSources.size > 1) {
            getString(R.string.status_switching_backup, sourceIndex + 1, activeChannel.streamSources.size)
        } else {
            getString(R.string.status_connecting)
        }
        repository.updateChannelHealth(
            activeChannel.id,
            ChannelHealthStatus.CONNECTING,
            connectingMsg,
            sourceIndex,
            activeChannel.streamSources.size
        )

        // 啟動緩衝卡滯看門狗 (串流長時間無回應時自動切換備援)
        startBufferingWatchdog(activeChannel, attemptId)

        val sourceId = currentSource.sourceId

        when (currentSource.type) {
            StreamType.YOUTUBE -> {
                // 切至 YouTube 官方嵌入播放器，停止並隱藏 ExoPlayer，先清理前一個可能殘留的 WebView 狀態
                playerManager.stop()
                binding.playerView.isVisible = false
                binding.layoutError.isVisible = false
                binding.layoutYoutubeError.isVisible = false
                binding.youtubePlayerView.stopAndClear()
                binding.youtubePlayerView.isVisible = true

                binding.tvOsdResolution.text = currentSource.label ?: "YOUTUBE"
                val defaultVideoId = currentSource.youtubeVideoId?.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) }
                val handle = currentSource.liveChannelHandle
                if (!handle.isNullOrBlank()) {
                    bufferingWatchdogJob?.cancel()
                    youtubeResolveJob = lifecycleScope.launch {
                        val resolved = repository.resolveYouTubeLiveVideoId(handle)
                        if (currentAttemptId == attemptId) {
                            activeYouTubeVideoId = resolved
                            startBufferingWatchdog(activeChannel, attemptId)
                            if (resolved != null) {
                                binding.youtubePlayerView.loadIFrame(activeChannel.id, sourceId, attemptId, resolved)
                            } else {
                                youtubeWebWatch = true
                                val target = currentYouTubeWatchTarget()
                                binding.youtubePlayerView.loadWebWatch(activeChannel.id, sourceId, attemptId, target)
                                startWebWatchWatchdog(activeChannel, sourceId, attemptId)
                                lastDiagAttemptResult = getString(R.string.live_resolution_unconfirmed)
                            }
                            binding.rootContainer.requestFocus()
                        }
                    }
                } else {
                    activeYouTubeVideoId = defaultVideoId
                    if (defaultVideoId != null) {
                        binding.youtubePlayerView.loadIFrame(activeChannel.id, sourceId, attemptId, defaultVideoId)
                    } else {
                        showManualPlaybackError(activeChannel, getString(R.string.invalid_youtube_id), allowWebWatch = true)
                    }
                    binding.rootContainer.requestFocus()
                }
            }
            StreamType.WEB_VIEW -> {
                // 官方網頁觀看模式 (如 ofiii)，網頁 URL 嚴禁直接交給 Media3，使用 WebView 載入
                playerManager.stop()
                binding.playerView.isVisible = false
                binding.layoutError.isVisible = false
                binding.layoutYoutubeError.isVisible = false
                binding.youtubePlayerView.stopAndClear()
                binding.youtubePlayerView.isVisible = true

                binding.tvOsdResolution.text = currentSource.label ?: "WEB"
                binding.youtubePlayerView.loadWebWatch(activeChannel.id, sourceId, attemptId, currentSource.url)
                startWebWatchWatchdog(activeChannel, sourceId, attemptId)
                binding.rootContainer.requestFocus()
            }
            else -> {
                // 切至 ExoPlayer 原生硬解播放器，停止並清空 YouTube WebView，杜絕雙重聲音
                binding.youtubePlayerView.stopAndClear()
                binding.youtubePlayerView.isVisible = false
                binding.layoutYoutubeError.isVisible = false
                binding.playerView.isVisible = true

                binding.tvOsdResolution.text = currentSource.label ?: "LIVE"
                playerManager.play(activeChannel, sessionToken)
                binding.rootContainer.requestFocus()
            }
        }
    }

    private fun currentYouTubeWatchTarget(): String {
        activeYouTubeVideoId?.let { return "https://www.youtube.com/watch?v=$it" }
        val source = currentPlayingChannel?.currentSource ?: return ""
        source.liveChannelHandle?.takeIf { it.startsWith("@") }?.let {
            return "https://www.youtube.com/@${java.net.URLEncoder.encode(it.substring(1), "UTF-8")}/live"
        }
        return source.url
    }

    private fun refreshExpiredYouTubeId(channel: Channel): Boolean {
        val source = channel.currentSource ?: return false
        val handle = source.liveChannelHandle ?: return false
        if (source.type != StreamType.YOUTUBE || youtubeRefreshAttempted) return false
        youtubeRefreshAttempted = true
        repository.invalidateYouTubeResolution(handle)
        playChannelStream(channel, channel.currentSourceIndex, false, refreshingLiveId = true)
        return true
    }

    private fun updateFavoriteButton() {
        val favorite = currentPlayingChannel?.let { repository.isFavorite(it) } == true
        binding.btnFavoriteChannel.setText(if (favorite) R.string.remove_favorite else R.string.add_favorite)
    }

    private fun showSavedChannels(recent: Boolean) {
        val available = repository.channels.value
        val channels = if (recent) repository.getRecentChannelIds().mapNotNull { id -> available.firstOrNull { it.canonicalId == id } }
            else available.filter { repository.isFavorite(it) }
        if (channels.isEmpty()) {
            Toast.makeText(this, if (recent) R.string.no_recent_channels else R.string.no_favorite_channels, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this).setTitle(if (recent) R.string.recent_channels else R.string.favorite_channels)
            .setItems(channels.map { it.name }.toTypedArray()) { _, index ->
                hideSettingsPanel()
                hideDrawer()
                val selected = repository.applyUserSelectedSources(listOf(channels[index])).first()
                playChannelStream(selected, selected.currentSourceIndex, false)
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun startBufferingWatchdog(channel: Channel?, attemptId: Long) {
        if (channel == null) return
        val timeoutMs = when (channel.currentSource?.type) {
            StreamType.HLS -> AppConfig.NATIVE_BUFFERING_TIMEOUT_MS
            StreamType.WEB_VIEW -> AppConfig.YOUTUBE_WEB_WATCH_TIMEOUT_MS
            else -> AppConfig.YOUTUBE_BUFFERING_TIMEOUT_MS
        }
        bufferingWatchdogJob?.cancel()
        bufferingWatchdogJob = lifecycleScope.launch {
            delay(timeoutMs)
            if (currentAttemptId == attemptId) {
                if (channel.currentSource?.type == StreamType.HLS &&
                    playerManager.playerState.value == PlayerState.Playing) return@launch
                val url = channel.currentSource?.url ?: ""
                if (url.isNotBlank()) {
                    repository.recordSourceFailure(url, "緩衝逾時")
                }
                val timeoutMsg = getString(R.string.youtube_error_timeout)
                if (repository.isAutoFallbackEnabled()) {
                    triggerNextFallback(channel, attemptId, timeoutMsg)
                } else {
                    showManualPlaybackError(channel, timeoutMsg)
                }
            }
        }
    }

    private fun startWebWatchWatchdog(channel: Channel, sourceId: String, attemptId: Long) {
        webWatchWatchdogJob?.cancel()
        webWatchWatchdogJob = lifecycleScope.launch {
            delay(AppConfig.YOUTUBE_WEB_WATCH_TIMEOUT_MS)
            if (currentAttemptId == attemptId) {
                val url = channel.currentSource?.url ?: ""
                if (url.isNotBlank()) {
                    repository.recordSourceFailure(url)
                }
                val timeoutMsg = getString(R.string.youtube_error_web_watch_failed)
                if (repository.isAutoFallbackEnabled()) {
                    triggerNextFallback(channel, attemptId, timeoutMsg)
                } else {
                    showManualPlaybackError(channel, timeoutMsg)
                }
            }
        }
    }

    /**
     * 當前頻道手動模式播放受阻，不自行換線，呈現明確原因與「重試」「選其他線路」「返回列表」出口
     */
    private fun showManualPlaybackError(channel: Channel, reason: String, allowWebWatch: Boolean = false) {
        bufferingWatchdogJob?.cancel()
        ytStablePlaybackJob?.cancel()
        webWatchWatchdogJob?.cancel()
        binding.layoutLoading.isVisible = false

        repository.updateChannelHealth(
            channel.id,
            ChannelHealthStatus.FAILED,
            reason,
            channel.currentSourceIndex,
            channel.streamSources.size
        )

        val srcName = channel.currentSource?.label ?: "線路 ${channel.currentSourceIndex + 1}"

        // 彈出錯誤提示時啟動焦點隔離，阻止背景 WebView/全螢幕視圖爭奪焦點
        binding.youtubePlayerView.setFocusIsolationEnabled(true)
        currentControlMode = TvControlMode.PANEL_OPEN

        if (channel.streamType == StreamType.YOUTUBE || channel.currentSource?.type == StreamType.WEB_VIEW) {
            binding.layoutYoutubeError.isVisible = true
            binding.layoutError.isVisible = false
            binding.tvYoutubeErrorMsg.text = "【$srcName】$reason"
            binding.btnYoutubeRetry.text = getString(R.string.btn_retry)
            binding.btnYoutubeWebWatch.isVisible = allowWebWatch
            binding.btnYoutubeRetry.requestFocus()
        } else {
            binding.layoutError.isVisible = true
            binding.layoutYoutubeError.isVisible = false
            binding.tvErrorMsg.text = "【$srcName】$reason"
            binding.tvErrorRetryCountdown.text = "請選擇「重試此線路」或「選擇其他線路」"
            binding.btnErrorRetry.requestFocus()
        }
    }

    /**
     * 備援調度器：尋找當前頻道尚未嘗試的備用串流
     */
    private fun triggerNextFallback(channel: Channel, attemptId: Long, reason: String) {
        if (attemptId != currentAttemptId) return
        if (!repository.isAutoFallbackEnabled()) {
            showManualPlaybackError(channel, reason)
            return
        }

        bufferingWatchdogJob?.cancel()
        ytStablePlaybackJob?.cancel()
        webWatchWatchdogJob?.cancel()

        // 使用穩定 source ID 記錄本輪已嘗試來源，不依賴可能因更新而變動的陣列索引
        val nextIndex = channel.streamSources.indices.firstOrNull { idx ->
            val src = channel.streamSources[idx]
            src.sourceId !in triedSourceIds
        }

        if (nextIndex != null) {
            val nextSource = channel.streamSources[nextIndex]
            val typeDesc = when (nextSource.type) {
                StreamType.YOUTUBE -> "YouTube 官方直播"
                StreamType.WEB_VIEW -> "官方網頁觀看"
                else -> "HLS 備援線路"
            }
            Toast.makeText(this, "切換至備援線路 ($typeDesc)", Toast.LENGTH_SHORT).show()
            playChannelStream(channel, nextIndex, isAutoFallback = true)
        } else {
            showPlaybackExhausted(channel, reason)
        }
    }

    /**
     * 當前頻道所有可用來源皆已耗盡，顯示紅燈與錯誤狀態
     */
    private fun showPlaybackExhausted(channel: Channel, reason: String) {
        bufferingWatchdogJob?.cancel()
        ytStablePlaybackJob?.cancel()
        webWatchWatchdogJob?.cancel()
        binding.layoutLoading.isVisible = false

        repository.updateChannelHealth(
            channel.id,
            ChannelHealthStatus.FAILED,
            getString(R.string.status_all_sources_failed),
            channel.currentSourceIndex,
            channel.streamSources.size
        )

        if (channel.streamType == StreamType.YOUTUBE) {
            binding.layoutYoutubeError.isVisible = true
            binding.tvYoutubeErrorMsg.text = "播放失敗：$reason\n(${getString(R.string.status_all_sources_failed)})"
            binding.btnYoutubeRetry.text = getString(R.string.btn_retry)
            binding.btnYoutubeRetry.requestFocus()
        } else {
            binding.layoutError.isVisible = true
            binding.tvErrorMsg.text = "播放失敗：$reason"
            binding.tvErrorRetryCountdown.text = getString(R.string.status_all_sources_failed)
        }
    }

    private fun playPreviousChannel() {
        playChannel(currentChannelIndex - 1)
    }

    private fun playNextChannel() {
        playChannel(currentChannelIndex + 1)
    }

    private fun showOsd(channel: Channel) {
        osdDismissJob?.cancel()

        binding.tvOsdChannelNum.text = String.format("CH %02d", channel.number)
        binding.tvOsdChannelName.text = channel.name

        val groupLabel = buildString {
            append(channel.group)
            if (channel.isGeoBlocked) append(" · 地區限制")
            if (channel.isPartTime) append(" · 非24H")
        }
        binding.tvOsdChannelGroup.text = groupLabel

        val src = channel.currentSource
        val srcLabel = src?.label ?: "LIVE"
        val lineInfo = if (channel.streamSources.size > 1) " (${channel.currentSourceIndex + 1}/${channel.streamSources.size})" else ""
        binding.tvOsdResolution.text = "$srcLabel$lineInfo"

        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        binding.tvOsdTime.text = timeFormat.format(Date())

        binding.layoutOsd.alpha = 1.0f
        binding.layoutOsd.isVisible = true

        osdDismissJob = lifecycleScope.launch {
            delay(AppConfig.OSD_AUTO_DISMISS_MS)
            binding.layoutOsd.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction { binding.layoutOsd.isVisible = false }
                .start()
        }
    }

    /**
     * 開啟手動線路選擇面板 (遙控器主導，移動焦點預覽，確認切換，取消不變更)
     */
    private fun showSourceSelectionPanel() {
        val channel = currentPlayingChannel ?: return
        currentControlMode = TvControlMode.PANEL_OPEN
        binding.youtubePlayerView.setFocusIsolationEnabled(true)
        hideDrawer()
        hideSettingsPanel()
        binding.layoutYoutubeError.isVisible = false
        binding.layoutError.isVisible = false

        val total = channel.streamSources.size
        val lineCountStr = if (total <= 1) "僅 1 條線路" else "$total 條線路"
        binding.tvSourceSelectionTitle.text = "${channel.name} · 線路清單 ($lineCountStr)"
        sourceAdapter.submitSources(channel.streamSources, channel.currentSourceIndex)
        binding.layoutSourceSelection.isVisible = true

        binding.rvStreamSources.post {
            binding.rvStreamSources.findViewHolderForAdapterPosition(channel.currentSourceIndex)?.itemView?.requestFocus()
                ?: binding.rvStreamSources.requestFocus()
        }
    }

    /**
     * 關閉手動線路選擇面板 (保留原線路)
     */
    private fun hideSourceSelectionPanel() {
        binding.layoutSourceSelection.isVisible = false
        updateControlModeAndFocusIsolationAfterPanelClose()
    }

    /**
     * 進入官方網頁互動模式 (允許方向鍵與確認鍵操作官方播放器)
     */
    private fun enterWebInteraction() {
        currentControlMode = TvControlMode.WEB_INTERACTION
        binding.youtubePlayerView.setFocusIsolationEnabled(false)
        binding.youtubePlayerView.setWebInteractionEnabled(true)
        binding.layoutWebInteractionBanner.isVisible = true
        Toast.makeText(this, "已進入網頁互動模式，可操作官方播放器 (按 [MENU] 或 [返回] 隨時返回電視控制)", Toast.LENGTH_LONG).show()
    }

    /**
     * 退出官方網頁互動模式 (恢復電視 APP 收視模式)
     */
    private fun exitWebInteraction() {
        binding.youtubePlayerView.setWebInteractionEnabled(false)
        binding.layoutWebInteractionBanner.isVisible = false
        updateControlModeAndFocusIsolationAfterPanelClose()
        Toast.makeText(this, "已返回 TV APP 操作模式", Toast.LENGTH_SHORT).show()
    }

    private fun showDrawer() {
        if (binding.layoutSettings.isVisible || binding.layoutSourceSelection.isVisible) return

        drawerDismissJob?.cancel()
        currentControlMode = TvControlMode.PANEL_OPEN
        binding.youtubePlayerView.setFocusIsolationEnabled(true)
        binding.layoutYoutubeError.isVisible = false
        binding.layoutError.isVisible = false
        binding.layoutDrawer.isVisible = true

        binding.rvChannels.scrollToPosition(currentChannelIndex)
        binding.rvChannels.post {
            binding.rvChannels.findViewHolderForAdapterPosition(currentChannelIndex)?.itemView?.requestFocus()
                ?: binding.rvChannels.requestFocus()
        }

        resetDrawerDismissTimer()
    }

    private fun hideDrawer() {
        drawerDismissJob?.cancel()
        binding.layoutDrawer.isVisible = false
        updateControlModeAndFocusIsolationAfterPanelClose()
    }

    /**
     * 面板關閉後的合理控制模式與焦點恢復
     * 只有在所有面板 (抽屜、線路、設定、錯誤浮層) 均關閉時才解除焦點隔離
     */
    private fun updateControlModeAndFocusIsolationAfterPanelClose() {
        val anyPanelVisible = binding.layoutDrawer.isVisible ||
                binding.layoutSourceSelection.isVisible ||
                binding.layoutSettings.isVisible ||
                binding.layoutYoutubeError.isVisible ||
                binding.layoutError.isVisible

        if (!anyPanelVisible) {
            binding.youtubePlayerView.setFocusIsolationEnabled(false)
            currentControlMode = if (binding.layoutWebInteractionBanner.isVisible) {
                TvControlMode.WEB_INTERACTION
            } else {
                TvControlMode.TV_APP
            }
            if (currentControlMode == TvControlMode.TV_APP) {
                binding.rootContainer.requestFocus()
            }
        }
    }

    private fun resetDrawerDismissTimer() {
        drawerDismissJob?.cancel()
        drawerDismissJob = lifecycleScope.launch {
            delay(AppConfig.DRAWER_AUTO_DISMISS_MS)
            hideDrawer()
        }
    }

    private var lastChannelSwitchTime = 0L

    /**
     * 電視盒統一按鍵路由核心 (在 Window 層級分流，徹底杜絕 WebView 搶奪電視控制焦點)
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        logKeyEvent(event, "dispatch")
        val keyCode = event.keyCode
        val action = event.action

        // A. 成對消費保護：若此按鍵已在 ACTION_DOWN 時被消費，對應的 ACTION_UP 必須成對攔截，絕不洩漏給新面板
        if (action == KeyEvent.ACTION_UP && consumedDownKeyCodes.remove(keyCode)) {
            logKeyEvent(event, "consumed_paired_up")
            return true
        }

        // B. 全域選單鍵 (MENU)：在任何情況下皆為開啟/關閉選台抽屜或退出互動模式之保證入口
        if (action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_MENU) {
            consumedDownKeyCodes.add(keyCode)
            if (currentControlMode == TvControlMode.WEB_INTERACTION) {
                exitWebInteraction()
                showDrawer()
                return true
            }
            if (binding.layoutSourceSelection.isVisible) {
                hideSourceSelectionPanel()
                return true
            }
            if (binding.layoutSettings.isVisible) {
                hideSettingsPanel()
                return true
            }
            if (binding.layoutDrawer.isVisible) {
                hideDrawer()
                return true
            } else {
                showDrawer()
                return true
            }
        }

        // C. 全域返回鍵 (BACK)：按層次逐級退出
        if (action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
            consumedDownKeyCodes.add(keyCode)
            return handleBackPressHierarchically()
        }

        // D. 專屬滾動面板 (抽屜列表、線路選擇列表、設定面板) 開啟時：
        // 只有這三個真正需要上下垂直捲動的面板，上下方向鍵才交給 RecyclerView / ScrollView 內部處理
        val isScrollPanelOpen = binding.layoutDrawer.isVisible ||
                binding.layoutSourceSelection.isVisible ||
                binding.layoutSettings.isVisible

        if (isScrollPanelOpen) {
            currentControlMode = TvControlMode.PANEL_OPEN
            resetDrawerDismissTimer()
            // 長按清單導航 (repeatCount >= 0) 正常由 RecyclerView / ScrollView 內部處理
            return super.dispatchKeyEvent(event)
        }

        if (currentControlMode == TvControlMode.WEB_INTERACTION &&
            !binding.layoutYoutubeError.isVisible && !binding.layoutError.isVisible) {
            return super.dispatchKeyEvent(event)
        }

        // E. 核心換台鍵：最高特權！
        // 無論目前是正常播放、緩衝中、黑畫面、YouTube 錯誤 (150/地區限制) 還是 ExoPlayer 錯誤，
        // 只要抽屜/線路清單未開啟，按下上下鍵一律立刻切台！絕不被任何錯誤浮層或 WebView 阻擋！
        val isChannelUpKey = keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                keyCode == KeyEvent.KEYCODE_CHANNEL_UP ||
                keyCode == KeyEvent.KEYCODE_PAGE_UP

        val isChannelDownKey = keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN ||
                keyCode == KeyEvent.KEYCODE_PAGE_DOWN

        if (isChannelUpKey || isChannelDownKey) {
            if (action == KeyEvent.ACTION_DOWN) {
                val now = System.currentTimeMillis()
                // 防抖 250ms，且切台為一次性動作 (repeatCount == 0)，避免長按硬體連發狂跳台
                if (now - lastChannelSwitchTime >= 250L && event.repeatCount == 0) {
                    lastChannelSwitchTime = now
                    consumedDownKeyCodes.add(keyCode)
                    // 立刻關閉任何錯誤浮層
                    binding.layoutYoutubeError.isVisible = false
                    binding.layoutError.isVisible = false
                    updateControlModeAndFocusIsolationAfterPanelClose()
                    if (isChannelUpKey) {
                        playPreviousChannel()
                    } else {
                        playNextChannel()
                    }
                }
            }
            return true
        }

        // F. 錯誤浮層 (layoutYoutubeError / layoutError) 顯示時之其他輔助按鍵導航
        if (binding.layoutYoutubeError.isVisible || binding.layoutError.isVisible) {
            if (action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        return super.dispatchKeyEvent(event)
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        return super.dispatchKeyEvent(event)
                    }
                }
            }
            return super.dispatchKeyEvent(event)
        }

        // G. 官方網頁互動模式 (WEB_INTERACTION)：將按鍵傳給 WebView 操作官方播放器
        if (currentControlMode == TvControlMode.WEB_INTERACTION) {
            return super.dispatchKeyEvent(event)
        }

        // H. TV APP 日常收視模式 (預設模式)：
        // 確認鍵開抽屜、左鍵開抽屜、右鍵開線路面板、數字鍵跳台由 Activity 處理
        if (action == KeyEvent.ACTION_DOWN) {
            if (handleTvAppKeyDown(keyCode, event)) {
                consumedDownKeyCodes.add(keyCode)
                return true
            }
        } else if (action == KeyEvent.ACTION_UP) {
            // 消費掉 TV 控制鍵對應的 UP 事件，避免漏給背景 View
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    /**
     * 逐層安全返回 (依目前狀態退出，不意外切台或離開 APP)
     */
    private fun handleBackPressHierarchically(): Boolean {
        // 1. 數字跳台輸入中 -> 取消跳台
        if (binding.layoutNumberJump.isVisible) {
            cancelNumberJump()
            return true
        }

        // 2. 設定面板開啟 -> 關閉設定面板
        if (binding.layoutSettings.isVisible) {
            hideSettingsPanel()
            return true
        }

        // 3. 線路選擇面板開啟 -> 關閉線路選擇面板 (保留原線路)
        if (binding.layoutSourceSelection.isVisible) {
            hideSourceSelectionPanel()
            return true
        }

        // 4. 頻道清單抽屜開啟 -> 關閉抽屜
        if (binding.layoutDrawer.isVisible) {
            hideDrawer()
            return true
        }

        // 5. 錯誤提示浮層開啟 -> 隱藏浮層並恢復 TV_APP 控制焦點
        if (binding.layoutYoutubeError.isVisible || binding.layoutError.isVisible) {
            binding.layoutYoutubeError.isVisible = false
            binding.layoutError.isVisible = false
            updateControlModeAndFocusIsolationAfterPanelClose()
            return true
        }

        // 6. OSD 資訊列開啟 -> 關閉 OSD
        if (binding.layoutOsd.isVisible) {
            binding.layoutOsd.isVisible = false
            return true
        }

        // 7. 官方網頁互動模式中 -> 退出互動模式，返回電視 APP 控制
        if (currentControlMode == TvControlMode.WEB_INTERACTION || binding.layoutWebInteractionBanner.isVisible) {
            exitWebInteraction()
            return true
        }

        // 8. 播放器全螢幕或自訂 View -> 退出全螢幕
        if (binding.youtubePlayerView.isVisible && binding.youtubePlayerView.handleBackPress()) {
            return true
        }

        // 9. 一般播放收視中 -> 連按兩次返回退出應用程式
        val now = System.currentTimeMillis()
        if (now - lastBackPressTime < 2000L) {
            finish()
        } else {
            lastBackPressTime = now
            Toast.makeText(this, getString(R.string.press_again_to_exit), Toast.LENGTH_SHORT).show()
        }
        return true
    }

    /**
     * 處理 TV APP 收視模式下的按鍵操作
     */
    private fun handleTvAppKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        resetDrawerDismissTimer()

        // 數字鍵跳台 (0-9)
        val digit = getDigitFromKeyCode(keyCode)
        if (digit != null) {
            handleNumberInput(digit)
            return true
        }

        when (keyCode) {
            // 切換上一台
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                playPreviousChannel()
                return true
            }

            // 切換下一台
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                playNextChannel()
                return true
            }

            // 確認鍵：開啟頻道清單抽屜
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                showDrawer()
                return true
            }

            // 左方向鍵：開啟頻道清單抽屜
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                showDrawer()
                return true
            }

            // 右方向鍵：開啟線路選擇面板 (取代循環切換，由使用者自主選擇)
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                showSourceSelectionPanel()
                return true
            }

            // 快進/快退鍵：開啟線路選擇面板
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                showSourceSelectionPanel()
                return true
            }

            // 資訊鍵：顯示 OSD
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> {
                currentPlayingChannel?.let { showOsd(it) }
                return true
            }
        }
        return false
    }

    private fun handleNumberInput(digit: Int) {
        if (numberInputBuffer.length >= 3) {
            numberInputBuffer.clear()
        }
        numberInputBuffer.append(digit)

        binding.tvNumberInput.text = numberInputBuffer.toString()
        binding.layoutNumberJump.isVisible = true

        numberKeyDebounceJob?.cancel()
        numberKeyDebounceJob = lifecycleScope.launch {
            delay(AppConfig.NUMBER_KEY_DEBOUNCE_MS)
            confirmNumberJump()
        }
    }

    private fun confirmNumberJump() {
        numberKeyDebounceJob?.cancel()
        val inputStr = numberInputBuffer.toString()
        numberInputBuffer.clear()
        binding.layoutNumberJump.isVisible = false

        val targetNum = inputStr.toIntOrNull() ?: return
        val targetIndex = currentChannelList.indexOfFirst { it.number == targetNum }

        if (targetIndex != -1) {
            playChannel(targetIndex)
        } else {
            Toast.makeText(this, "未找到第 $targetNum 台", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cancelNumberJump() {
        numberKeyDebounceJob?.cancel()
        numberInputBuffer.clear()
        binding.layoutNumberJump.isVisible = false
    }

    private fun getDigitFromKeyCode(keyCode: Int): Int? {
        return when (keyCode) {
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> 0
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> 1
            KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> 2
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> 3
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> 4
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> 5
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> 6
            KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> 7
            KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> 8
            KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> 9
            else -> null
        }
    }

    private fun initGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (binding.layoutSettings.isVisible) {
                    hideSettingsPanel()
                    return true
                }
                if (binding.layoutDrawer.isVisible) {
                    hideDrawer()
                } else {
                    showDrawer()
                }
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null || binding.layoutSettings.isVisible) return false
                val deltaX = e2.x - e1.x
                val deltaY = e2.y - e1.y

                if (Math.abs(deltaY) > Math.abs(deltaX) && Math.abs(deltaY) > 80) {
                    if (deltaY < 0) {
                        playNextChannel()
                    } else {
                        playPreviousChannel()
                    }
                    return true
                }

                if (Math.abs(deltaX) > Math.abs(deltaY) && Math.abs(deltaX) > 80) {
                    if (deltaX > 0) {
                        showDrawer()
                    } else {
                        hideDrawer()
                    }
                    return true
                }

                return false
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event != null && gestureDetector.onTouchEvent(event)) {
            return true
        }
        return super.onTouchEvent(event)
    }
}
