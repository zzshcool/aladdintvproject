package com.aladdin.tv.ui.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.aladdin.tv.config.AppConfig

/**
 * 官方 YouTube 與網頁串流播放器組件
 * 專為 Android 電視盒與手機設計，支援雙向 Attempt 隔離、精確網域導航白名單、防記憶體洩漏與全螢幕操作
 */
@SuppressLint("SetJavaScriptEnabled")
class YouTubeIframePlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    enum class PlayMode {
        IFRAME,     // 官方 IFrame Player API
        WEB_WATCH   // 官方網頁觀看模式 (YouTube watch 頁或 ofiii 官方觀看頁)
    }

    data class PlaybackAttempt(
        val attemptId: Long,
        val channelId: String,
        val sourceId: String,
        val playMode: PlayMode,
        val targetUrlOrId: String = ""
    )

    interface YouTubePlayerListener {
        fun onReady(attemptId: Long, channelId: String, sourceId: String, playMode: PlayMode)
        fun onPlaying(attemptId: Long, channelId: String, sourceId: String, playMode: PlayMode)
        fun onPlayingConfirmed(
            attemptId: Long,
            channelId: String,
            sourceId: String,
            playMode: PlayMode,
            currentTimeSeconds: Double,
            isLiveConfirmed: Boolean = true
        )
        fun onBuffering(attemptId: Long, channelId: String, sourceId: String, playMode: PlayMode)
        fun onEnded(attemptId: Long, channelId: String, sourceId: String, playMode: PlayMode)
        fun onError(attemptId: Long, channelId: String, sourceId: String, playMode: PlayMode, errorCode: Int, rawReason: String, description: String)
        fun onAutoplayBlocked(attemptId: Long, channelId: String, sourceId: String, playMode: PlayMode)
        fun onAdStateChanged(attemptId: Long, channelId: String, sourceId: String, isAdPlaying: Boolean) {}
        fun onLoginRequired(attemptId: Long, channelId: String, sourceId: String) {}
        fun onGeoBlocked(attemptId: Long, channelId: String, sourceId: String, rawReason: String) {}
    }

    var listener: YouTubePlayerListener? = null

    private var webView: WebView? = null
    private var activeAttempt: PlaybackAttempt? = null
    private var currentNavAttemptId: Long = 0L
    private var injectedAttemptId: Long = 0L

    // 全螢幕自訂視圖支援 (HTML5 Fullscreen)
    private var customFullscreenView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    companion object {
        private const val TAG = "AladdinYouTubePlayer"
        private const val APP_ORIGIN = AppConfig.YOUTUBE_APP_ORIGIN
        // 標準合規的 User-Agent，包含標準 Android WebView 標識及 App 身分，禁止偽裝成 youtube.com
        private const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 14; AladdinTV/1.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /**
         * 導航白名單：使用精確 host 或合法子網域比對，禁止 contains 模糊匹配
         */
        fun isAllowedHost(host: String?): Boolean {
            if (host.isNullOrBlank()) return false
            val h = host.lowercase()
            return h == "youtube.com" || h.endsWith(".youtube.com") ||
                   h == "youtu.be" || h.endsWith(".youtu.be") ||
                   h == "youtube-nocookie.com" || h.endsWith(".youtube-nocookie.com") ||
                   h == "ofiii.com" || h.endsWith(".ofiii.com") ||
                   h == "litv.tv" || h.endsWith(".litv.tv")
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        setupWebView()
    }

    private var isWebInteractionEnabled: Boolean = false

    fun setWebInteractionEnabled(enabled: Boolean) {
        this.isWebInteractionEnabled = enabled
        descendantFocusability = if (enabled) {
            android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        } else {
            android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        webView?.isFocusable = enabled
        webView?.isFocusableInTouchMode = enabled
        if (!enabled) {
            webView?.clearFocus()
        } else {
            webView?.requestFocus()
        }
    }

    fun isWebInteractionEnabled(): Boolean = isWebInteractionEnabled

    fun isCustomFullscreenActive(): Boolean = customFullscreenView != null

    /**
     * 設定面板開啟時的焦點隔離保護
     * 阻止背景 WebView、HTML5 全螢幕 View 及其子 View 搶奪電視盒遙控器焦點
     */
    fun setFocusIsolationEnabled(isolate: Boolean) {
        if (isolate) {
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            isFocusable = false
            isFocusableInTouchMode = false
            clearFocus()
            webView?.isFocusable = false
            webView?.isFocusableInTouchMode = false
            webView?.clearFocus()
            customFullscreenView?.isFocusable = false
            customFullscreenView?.isFocusableInTouchMode = false
            customFullscreenView?.clearFocus()
        } else {
            descendantFocusability = if (isWebInteractionEnabled) {
                android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
            } else {
                android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
            if (isWebInteractionEnabled) {
                webView?.isFocusable = true
                webView?.isFocusableInTouchMode = true
            }
        }
    }

    fun clearSiteData() {
        webView?.let { wv ->
            wv.clearCache(true)
            wv.clearFormData()
            wv.clearHistory()
        }
        val cookieManager = android.webkit.CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
        android.webkit.WebStorage.getInstance().deleteAllData()
    }

    private fun setupWebView() {
        val wv = WebView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.BLACK)
            // 預設為電視 APP 控制模式，禁止 WebView 攔截電視盒實體遙控器按鍵焦點
            isFocusable = false
            isFocusableInTouchMode = false

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
                userAgentString = DEFAULT_UA
            }

            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (customFullscreenView != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    customFullscreenView = view
                    customViewCallback = callback
                    addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                }

                override fun onHideCustomView() {
                    customFullscreenView?.let { cv ->
                        removeView(cv)
                        customFullscreenView = null
                        customViewCallback?.onCustomViewHidden()
                        customViewCallback = null
                    }
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return true
                    val host = request.url?.host
                    if (isAllowedHost(host)) {
                        return false // 允許在內部載入
                    }
                    Log.w(TAG, "安全攔截非白名單外部導航跳轉: $url")
                    return true // 阻止跳出
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    injectedAttemptId = 0L
                    Log.d(TAG, "WebView onPageStarted: url=$url attemptId=${activeAttempt?.attemptId}")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val attempt = activeAttempt ?: return
                    if (url == null || url == "about:blank" || url != view?.url) return
                    // 導航事件歸屬驗證：確認此完成事件屬於當前 attempt，且未曾被舊導航消耗
                    if (attempt.attemptId != currentNavAttemptId) {
                        Log.d(TAG, "忽略舊導航之 onPageFinished: attemptId=${attempt.attemptId} navAttempt=$currentNavAttemptId")
                        return
                    }

                    if (attempt.playMode == PlayMode.WEB_WATCH && injectedAttemptId != attempt.attemptId) {
                        injectedAttemptId = attempt.attemptId
                        injectWebWatchScript(attempt)
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    val attempt = activeAttempt ?: return
                    if (attempt.attemptId != currentNavAttemptId) return
                    post {
                        if (activeAttempt?.attemptId == attempt.attemptId) {
                            listener?.onError(
                                attempt.attemptId,
                                attempt.channelId,
                                attempt.sourceId,
                                attempt.playMode,
                                errorCode,
                                description ?: "網路連線異常",
                                "網頁載入異常 ($errorCode)"
                            )
                        }
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    val attempt = activeAttempt ?: return
                    if (attempt.attemptId != currentNavAttemptId) return
                    if (request?.isForMainFrame == true) {
                        val desc = error?.description?.toString() ?: "網頁載入失敗"
                        post {
                            if (activeAttempt?.attemptId == attempt.attemptId) {
                                listener?.onError(
                                    attempt.attemptId,
                                    attempt.channelId,
                                    attempt.sourceId,
                                    attempt.playMode,
                                    -1,
                                    desc,
                                    "主頁面載入失敗: $desc"
                                )
                            }
                        }
                    }
                }
            }

            addJavascriptInterface(YouTubeJsBridge(), "AndroidBridge")
        }

        addView(wv)
        this.webView = wv
    }

    /**
     * 載入官方 YouTube IFrame 內嵌播放器 (優先模式)
     */
    fun loadIFrame(channelId: String, sourceId: String, attemptId: Long, videoId: String) {
        if (videoId.isBlank()) return
        val attempt = PlaybackAttempt(attemptId, channelId, sourceId, PlayMode.IFRAME, videoId)
        activeAttempt = attempt
        currentNavAttemptId = attemptId
        injectedAttemptId = 0L

        val html = buildIFrameHtml(attempt, videoId)
        Log.d(TAG, "loadIFrame: attemptId=$attemptId channelId=$channelId sourceId=$sourceId vid=$videoId")
        webView?.loadDataWithBaseURL(APP_ORIGIN, html, "text/html", "UTF-8", null)
    }

    /**
     * 在 APK 內載入官方網頁觀看模式 (YouTube watch 頁面或 ofiii 網頁)
     */
    fun loadWebWatch(channelId: String, sourceId: String, attemptId: Long, urlOrVideoId: String) {
        if (urlOrVideoId.isBlank()) return
        val targetUrl = if (urlOrVideoId.startsWith("http://") || urlOrVideoId.startsWith("https://")) {
            urlOrVideoId
        } else {
            "https://www.youtube.com/watch?v=$urlOrVideoId"
        }
        val attempt = PlaybackAttempt(attemptId, channelId, sourceId, PlayMode.WEB_WATCH, targetUrl)
        activeAttempt = attempt
        currentNavAttemptId = attemptId
        injectedAttemptId = 0L

        Log.d(TAG, "loadWebWatch: attemptId=$attemptId channelId=$channelId sourceId=$sourceId url=$targetUrl")
        webView?.loadUrl(targetUrl)
    }

    /**
     * 注入官方網頁 watch 頁監測腳本 (支援 video 元素替換自動重新綁定、區分廣告與正片直播特徵)
     */
    private fun injectWebWatchScript(attempt: PlaybackAttempt) {
        val js = """
            (function() {
                const ATTEMPT_ID = ${attempt.attemptId};
                if (window.aladdinWatchAttempt === ATTEMPT_ID) return;
                window.aladdinWatchAttempt = ATTEMPT_ID;
                const CHANNEL_ID = '${attempt.channelId}';
                const SOURCE_ID = '${attempt.sourceId}';
                const PLAY_MODE = 'WEB_WATCH';
                
                let currentVideo = null;
                let lastTime = 0;
                let advanceCount = 0;
                let progressTimer = null;
                let domObserver = null;
                let lastAdState = null;
                let contentConfirmed = false;
                let monitoredSrc = '';

                function playbackDocuments() {
                    const docs = [document];
                    document.querySelectorAll('iframe').forEach(function(frame) {
                        try { if (frame.contentDocument) docs.push(frame.contentDocument); } catch(e) {}
                    });
                    return docs;
                }

                function isAdPlaying() {
                    return playbackDocuments().some(function(doc) {
                        return Array.from(doc.querySelectorAll('.ad-showing, .vjs-ad-playing, .vjs-ad-loading, .ima-ad-container, .house_video')).some(function(el) {
                            const rect = el.getBoundingClientRect();
                            return rect.width > 0 && rect.height > 0 && doc.defaultView.getComputedStyle(el).visibility !== 'hidden';
                        });
                    });
                }
                
                function cleanupListeners() {
                    if (progressTimer) {
                        clearInterval(progressTimer);
                        progressTimer = null;
                    }
                    advanceCount = 0;
                    contentConfirmed = false;
                    if (currentVideo) {
                        currentVideo.removeEventListener('playing', onPlaying);
                        currentVideo.removeEventListener('waiting', onWaiting);
                        currentVideo.removeEventListener('pause', onPause);
                        currentVideo.removeEventListener('ended', onEnded);
                        currentVideo.removeEventListener('error', onError);
                        currentVideo = null;
                    }
                }
                
                function isLiveConfirmed(video) {
                    if (!video) return false;
                    if (video.closest('.vjs-ad-playing, .vjs-ad-loading, .ad-showing')) return false;
                    if (video.closest('.vjs-live')) return true;
                    // 直播特徵：duration 為 Infinity 或 NaN，或時長大於 10 小時
                    if (video.duration === Infinity || isNaN(video.duration) || video.duration > 36000) {
                        return true;
                    }
                    // 短片 (小於 3 分鐘) 大多為前置廣告，無法確定為目標直播
                    if (isFinite(video.duration) && video.duration > 0 && video.duration < 180) {
                        return false;
                    }
                    // 檢查常見直播 DOM 標識
                    const hasLiveBadge = video.ownerDocument.querySelector('.ytp-live, .vjs-live-control, [aria-label*="直播"]') !== null;
                    return hasLiveBadge;
                }
                
                function startProgress(video) {
                    if (progressTimer) clearInterval(progressTimer);
                    advanceCount = 0;
                    lastTime = video.currentTime || 0;
                    
                    progressTimer = setInterval(function() {
                        if (!video || video !== currentVideo) {
                            cleanupListeners();
                            return;
                        }
                        if (isAdPlaying()) {
                            contentConfirmed = false;
                            advanceCount = 0;
                            lastTime = video.currentTime || 0;
                            return;
                        }
                        const cur = video.currentTime || 0;
                        // 連續前進檢查：移除只要大於 0 就通過的漏洞，要求實質前進
                        if (cur > lastTime + 0.25) {
                            advanceCount++;
                            if (advanceCount >= 2 && !contentConfirmed) {
                                const liveOk = isLiveConfirmed(video);
                                AndroidBridge.onPlayingConfirmed(ATTEMPT_ID, CHANNEL_ID, SOURCE_ID, PLAY_MODE, cur, liveOk);
                                contentConfirmed = true;
                            }
                        } else {
                            advanceCount = 0;
                        }
                        lastTime = cur;
                    }, 1000);
                }
                
                function onPlaying() {
                    AndroidBridge.onStateChange(ATTEMPT_ID, CHANNEL_ID, SOURCE_ID, PLAY_MODE, 1, currentVideo ? currentVideo.currentTime || 0 : 0);
                    if (currentVideo) startProgress(currentVideo);
                }
                function onWaiting() {
                    if (isAdPlaying()) return;
                    if (progressTimer) clearInterval(progressTimer);
                    progressTimer = null;
                    contentConfirmed = false;
                    advanceCount = 0;
                    AndroidBridge.onStateChange(ATTEMPT_ID, CHANNEL_ID, SOURCE_ID, PLAY_MODE, 3, currentVideo ? currentVideo.currentTime || 0 : 0);
                }
                function onPause() {
                    if (progressTimer) clearInterval(progressTimer);
                    progressTimer = null;
                    contentConfirmed = false;
                    advanceCount = 0;
                }
                function onEnded() {
                    if (progressTimer) clearInterval(progressTimer);
                    advanceCount = 0;
                    if (location.hostname.endsWith('ofiii.com')) {
                        const adTransition = isAdPlaying() || !isLiveConfirmed(currentVideo);
                        cleanupListeners();
                        if (!adTransition) AndroidBridge.onStateChange(ATTEMPT_ID, CHANNEL_ID, SOURCE_ID, PLAY_MODE, 3, 0);
                        return;
                    }
                    AndroidBridge.onStateChange(ATTEMPT_ID, CHANNEL_ID, SOURCE_ID, PLAY_MODE, 0, currentVideo ? currentVideo.currentTime || 0 : 0);
                }
                function onError() {
                    cleanupListeners();
                    AndroidBridge.onError(ATTEMPT_ID, CHANNEL_ID, SOURCE_ID, PLAY_MODE, -1002, 'HTML5 Video Playback Error');
                }
                
                function bindVideoElement(video) {
                    if (!video || video === currentVideo) return;
                    cleanupListeners();
                    currentVideo = video;
                    window.aladdinPlaybackVideo = video;
                    monitoredSrc = video.currentSrc;
                    
                    video.addEventListener('playing', onPlaying);
                    video.addEventListener('waiting', onWaiting);
                    video.addEventListener('pause', onPause);
                    video.addEventListener('ended', onEnded);
                    video.addEventListener('error', onError);
                    
                    if (!video.paused && (video.currentTime || 0) > 0) {
                        onPlaying();
                    } else {
                        try {
                            video.play().catch(function() {
                                AndroidBridge.onAutoplayBlocked(ATTEMPT_ID, CHANNEL_ID, SOURCE_ID, PLAY_MODE);
                            });
                        } catch(e) {
                            AndroidBridge.onAutoplayBlocked(ATTEMPT_ID, CHANNEL_ID, SOURCE_ID, PLAY_MODE);
                        }
                    }
                }
                
                function applyWatchLayout() {
                    const host = location.hostname;
                    const ofiii = host === 'ofiii.com' || host.endsWith('.ofiii.com');
                    const youtube = host === 'youtube.com' || host.endsWith('.youtube.com');
                    if (!ofiii && !youtube) return;
                    const root = ofiii
                        ? document.querySelector('.player_section, .player_fixed_section iframe[src*="/player"]')
                        : document.querySelector('video.html5-main-video')?.closest('#player-container-outer, #player-container-id, ytm-player, ytd-player, #movie_player') ||
                          document.querySelector('#player-container-outer, #player-container-id, ytm-player, ytd-player, #movie_player');
                    if (!root) return;
                    root.setAttribute('data-aladdin-watch-player', '');
                    for (let parent = root.parentElement; parent; parent = parent.parentElement) {
                        parent.style.setProperty('transform', 'none', 'important');
                        parent.style.setProperty('contain', 'none', 'important');
                        parent.style.setProperty('overflow', 'visible', 'important');
                    }
                    if (!document.getElementById('aladdin-watch-layout')) {
                        const style = document.createElement('style');
                        style.id = 'aladdin-watch-layout';
                        const p = '[data-aladdin-watch-player]';
                        style.textContent = 'html,body{margin:0!important;overflow:hidden!important;background:#000!important;}' +
                            p + '{position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;min-height:0!important;max-height:none!important;max-width:none!important;margin:0!important;padding:0!important;aspect-ratio:auto!important;background:#000!important;z-index:1000!important;}' +
                            p + ' iframe{position:absolute!important;inset:0!important;width:100%!important;height:100%!important;border:0!important;}' +
                            p + ' video{object-fit:contain!important;}' +
                            (ofiii
                                ? 'header.header,.header_section,.header_container,footer{display:none!important;}' + p + ' video.thumbnail{width:100%!important;height:100%!important;}'
                                : 'ytm-mobile-topbar-renderer,header.mobile-topbar-header,ytm-pivot-bar-renderer,ytd-masthead,#masthead-container{display:none!important;}' +
                                  p + ' #player-container-inner,' + p + ' #player,' + p + ' #movie_player,' + p + ' .html5-video-container{position:absolute!important;inset:0!important;width:100%!important;height:100%!important;min-height:0!important;margin:0!important;padding:0!important;box-sizing:border-box!important;}' +
                                  p + ' .html5-main-video{position:absolute!important;top:0!important;left:0!important;width:100%!important;height:100%!important;}');
                        (document.head || document.documentElement).appendChild(style);
                    }
                    if (ofiii) root.querySelectorAll('iframe').forEach(function(frame) {
                        try {
                            const doc = frame.contentDocument;
                            if (!doc || doc.getElementById('aladdin-frame-layout')) return;
                            const style = doc.createElement('style');
                            style.id = 'aladdin-frame-layout';
                            style.textContent = 'html,body{margin:0!important;width:100%!important;height:100%!important;overflow:hidden!important;background:#000!important;}' +
                                '.video-js{position:absolute!important;inset:0!important;width:100%!important;height:100%!important;padding-top:0!important;}' +
                                'video{object-fit:contain!important;}';
                            (doc.head || doc.documentElement).appendChild(style);
                        } catch(e) {}
                    });
                }

                function scanAndBind() {
                    applyWatchLayout();
                    const docs = playbackDocuments();
                    const videos = docs.flatMap(function(doc) {
                        return Array.from(doc.querySelectorAll('video')).filter(function(v) {
                            return !v.classList.contains('thumbnail') &&
                                (v.currentSrc || v.getAttribute('src') || v.querySelector('source'));
                        });
                    });
                    if (videos.length > 0) {
                        const visible = videos.filter(function(v) { return v.getBoundingClientRect().width > 0 && v.getBoundingClientRect().height > 0; });
                        const candidate = visible.find(function(v) { return !v.paused && v.readyState >= 2; }) || visible[0];
                        if (!candidate) return false;
                        bindVideoElement(candidate);
                        if (!candidate.paused && candidate.readyState >= 2 &&
                            (!progressTimer || monitoredSrc !== candidate.currentSrc)) {
                            monitoredSrc = candidate.currentSrc;
                            contentConfirmed = false;
                            startProgress(candidate);
                        }
                        return true;
                    }
                    return false;
                }
                function checkAdAndLogin() {
                    try {
                        const isAd = isAdPlaying();
                        if (isAd !== lastAdState) {
                            lastAdState = isAd;
                            if (isAd) contentConfirmed = false;
                            AndroidBridge.onAdState(ATTEMPT_ID, CHANNEL_ID, SOURCE_ID, isAd);
                        }

                        const bodyTxt = document.body ? document.body.innerText : '';
                        if (bodyTxt.includes('Sign in to confirm you’re not a bot') || bodyTxt.includes('Sign in to confirm your age') || bodyTxt.includes('請登入以確認年齡')) {
                            AndroidBridge.onLoginRequired(ATTEMPT_ID, CHANNEL_ID, SOURCE_ID);
                        } else if (bodyTxt.includes('上傳者並未允許這部影片在你的國家') || 
                                   bodyTxt.includes('not made this video available in your country') ||
                                   bodyTxt.includes('此影片在您的國家/地區無法播放') ||
                                   bodyTxt.includes('This video is not available in your country') ||
                                   bodyTxt.includes('未在您的國家/地區開放此影片')) {
                            AndroidBridge.onGeoBlocked(ATTEMPT_ID, CHANNEL_ID, SOURCE_ID, '上傳者並未允許這部影片在你的國家／地區播放');
                        }
                    } catch(e) {}
                }

                scanAndBind();
                checkAdAndLogin();
                setInterval(function() { scanAndBind(); checkAdAndLogin(); }, 1000);
                
                // MutationObserver 監測 video 元素替換 (如廣告切換至正片直播)
                try {
                    domObserver = new MutationObserver(function() {
                        scanAndBind();
                        checkAdAndLogin();
                    });
                    domObserver.observe(document.body || document.documentElement, {
                        childList: true,
                        subtree: true
                    });
                } catch(e) {}
            })();
        """.trimIndent()

        webView?.evaluateJavascript(js, null)
    }

    /**
     * 遙控器 OK 或觸控手動起播
     */
    fun triggerManualPlay() {
        val attempt = activeAttempt ?: return
        if (attempt.playMode == PlayMode.IFRAME) {
            webView?.evaluateJavascript(
                "if (window.player && player.playVideo) { player.playVideo(); }",
                null
            )
        } else {
            webView?.evaluateJavascript(
                """
                (function() {
                    const entry = document.querySelector('.player_section .play_icon');
                    if (entry && !window.aladdinPlaybackVideo) { entry.parentElement.click(); return; }
                    const v = window.aladdinPlaybackVideo || document.querySelector('video:not(.thumbnail)');
                    if (v) { v.play().catch(function() {}); }
                    const btn = document.querySelector('.ytp-play-button') || 
                                document.querySelector('button[aria-label*="Play"]') || 
                                document.querySelector('button[aria-label*="播放"]');
                    if (btn) { btn.click(); }
                })();
                """.trimIndent(),
                null
            )
        }
    }

    /**
     * 停止播放並清空當前頁面與背景音訊，避免雙重聲音
     */
    fun stopAndClear() {
        activeAttempt = null
        injectedAttemptId = 0L
        webView?.let { wv ->
            try {
                wv.evaluateJavascript(
                    "if (window.player && player.stopVideo) { player.stopVideo(); } const v = window.aladdinPlaybackVideo || document.querySelector('video:not(.thumbnail)'); if (v) { v.pause(); }",
                    null
                )
            } catch (_: Exception) {}
            wv.stopLoading()
            wv.loadUrl("about:blank")
        }
    }

    fun pauseVideo() {
        val attempt = activeAttempt ?: return
        if (attempt.playMode == PlayMode.IFRAME) {
            webView?.evaluateJavascript("if (window.player && player.pauseVideo) { player.pauseVideo(); }", null)
        } else {
            webView?.evaluateJavascript("const v = window.aladdinPlaybackVideo || document.querySelector('video:not(.thumbnail)'); if (v) { v.pause(); }", null)
        }
    }

    fun resumeVideo() {
        val attempt = activeAttempt ?: return
        if (attempt.playMode == PlayMode.IFRAME) {
            webView?.evaluateJavascript("if (window.player && player.playVideo) { player.playVideo(); }", null)
        } else {
            webView?.evaluateJavascript("const v = window.aladdinPlaybackVideo || document.querySelector('video:not(.thumbnail)'); if (v) { v.play().catch(function() {}); }", null)
        }
    }

    fun canGoBack(): Boolean = webView?.canGoBack() == true

    fun goBack() {
        webView?.goBack()
    }

    fun handleBackPress(): Boolean {
        if (customFullscreenView != null) {
            (webView?.webChromeClient as? WebChromeClient)?.onHideCustomView()
            return true
        }
        if (canGoBack()) {
            goBack()
            return true
        }
        return false
    }

    fun release() {
        stopAndClear()
        webView?.let { wv ->
            removeView(wv)
            wv.destroy()
        }
        webView = null
    }

    private fun buildIFrameHtml(attempt: PlaybackAttempt, videoId: String): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                <meta name="referrer" content="strict-origin-when-cross-origin">
                <style>
                    body, html { margin: 0; padding: 0; width: 100%; height: 100%; background-color: #000000; overflow: hidden; }
                    #player { width: 100%; height: 100%; }
                </style>
            </head>
            <body>
                <div id="player"></div>
                <script src="https://www.youtube.com/iframe_api"></script>
                <script>
                    const ATTEMPT_ID = ${attempt.attemptId};
                    const CHANNEL_ID = '${attempt.channelId}';
                    const SOURCE_ID = '${attempt.sourceId}';
                    const PLAY_MODE = 'IFRAME';
                    const VIDEO_ID = '$videoId';
                    let player = null;
                    let lastTime = 0;
                    let advanceCount = 0;
                    let progressTimer = null;

                    function clearProgressTimer() {
                        if (progressTimer) {
                            clearInterval(progressTimer);
                            progressTimer = null;
                        }
                        advanceCount = 0;
                    }

                    function startProgressCheck() {
                        clearProgressTimer();
                        lastTime = (player && player.getCurrentTime) ? player.getCurrentTime() : 0;
                        progressTimer = setInterval(function() {
                            const cur = (player && player.getCurrentTime) ? player.getCurrentTime() : 0;
                            if (cur > lastTime + 0.3) {
                                advanceCount++;
                                if (advanceCount >= 2) {
                                    AndroidBridge.onPlayingConfirmed(ATTEMPT_ID, CHANNEL_ID, SOURCE_ID, PLAY_MODE, cur);
                                    clearProgressTimer();
                                }
                            }
                            lastTime = cur;
                        }, 1000);
                    }

                    function onYouTubeIframeAPIReady() {
                        player = new YT.Player('player', {
                            height: '100%',
                            width: '100%',
                            videoId: VIDEO_ID,
                            playerVars: {
                                'autoplay': 1,
                                'controls': 1,
                                'rel': 0,
                                'playsinline': 1,
                                'enablejsapi': 1,
                                'origin': '$APP_ORIGIN'
                            },
                            events: {
                                'onReady': onPlayerReady,
                                'onStateChange': onPlayerStateChange,
                                'onError': onPlayerError
                            }
                        });
                    }

                    function onPlayerReady(event) {
                        AndroidBridge.onReady(ATTEMPT_ID, CHANNEL_ID, SOURCE_ID, PLAY_MODE);
                        try {
                            const p = event.target.playVideo();
                            if (p && p.catch) {
                                p.catch(function(e) {
                                    AndroidBridge.onAutoplayBlocked(ATTEMPT_ID, CHANNEL_ID, SOURCE_ID, PLAY_MODE);
                                });
                            }
                        } catch (e) {
                            AndroidBridge.onAutoplayBlocked(ATTEMPT_ID, CHANNEL_ID, SOURCE_ID, PLAY_MODE);
                        }
                    }

                    function onPlayerStateChange(event) {
                        const state = event.data;
                        let curTime = 0;
                        try {
                            if (player && player.getCurrentTime) {
                                curTime = player.getCurrentTime() || 0;
                            }
                        } catch (e) {}

                        AndroidBridge.onStateChange(ATTEMPT_ID, CHANNEL_ID, SOURCE_ID, PLAY_MODE, state, curTime);

                        if (state === 1) { // PLAYING (重新進入播放狀態重新啟動進度檢查，不被舊狀態阻擋)
                            startProgressCheck();
                        } else {
                            clearProgressTimer();
                        }
                    }

                    function onPlayerError(event) {
                        clearProgressTimer();
                        const errCode = event.data;
                        AndroidBridge.onError(ATTEMPT_ID, CHANNEL_ID, SOURCE_ID, PLAY_MODE, errCode, 'IFrame Error ' + errCode);
                    }
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    private inner class YouTubeJsBridge {
        @JavascriptInterface
        fun onReady(attemptId: Long, channelId: String, sourceId: String, modeStr: String) {
            post {
                val current = activeAttempt ?: return@post
                if (current.attemptId == attemptId && current.channelId == channelId && current.sourceId == sourceId) {
                    val mode = if (modeStr == "WEB_WATCH") PlayMode.WEB_WATCH else PlayMode.IFRAME
                    listener?.onReady(attemptId, channelId, sourceId, mode)
                }
            }
        }

        @JavascriptInterface
        fun onStateChange(
            attemptId: Long,
            channelId: String,
            sourceId: String,
            modeStr: String,
            state: Int,
            currentTime: Double
        ) {
            post {
                val current = activeAttempt ?: return@post
                if (current.attemptId != attemptId || current.channelId != channelId || current.sourceId != sourceId) {
                    Log.d(TAG, "丟棄非當前 attempt 事件: attemptId=$attemptId current=${current.attemptId}")
                    return@post
                }
                val mode = if (modeStr == "WEB_WATCH") PlayMode.WEB_WATCH else PlayMode.IFRAME
                when (state) {
                    1 -> listener?.onPlaying(attemptId, channelId, sourceId, mode)
                    3 -> listener?.onBuffering(attemptId, channelId, sourceId, mode)
                    0 -> listener?.onEnded(attemptId, channelId, sourceId, mode)
                }
            }
        }

        @JavascriptInterface
        fun onPlayingConfirmed(
            attemptId: Long,
            channelId: String,
            sourceId: String,
            modeStr: String,
            currentTime: Double,
            isLiveConfirmed: Boolean
        ) {
            post {
                val current = activeAttempt ?: return@post
                if (current.attemptId == attemptId && current.channelId == channelId && current.sourceId == sourceId) {
                    val mode = if (modeStr == "WEB_WATCH") PlayMode.WEB_WATCH else PlayMode.IFRAME
                    listener?.onPlayingConfirmed(attemptId, channelId, sourceId, mode, currentTime, isLiveConfirmed)
                }
            }
        }

        @JavascriptInterface
        fun onPlayingConfirmed(
            attemptId: Long,
            channelId: String,
            sourceId: String,
            modeStr: String,
            currentTime: Double
        ) {
            onPlayingConfirmed(attemptId, channelId, sourceId, modeStr, currentTime, true)
        }

        @JavascriptInterface
        fun onError(
            attemptId: Long,
            channelId: String,
            sourceId: String,
            modeStr: String,
            errorCode: Int,
            rawReason: String
        ) {
            post {
                val current = activeAttempt ?: return@post
                if (current.attemptId != attemptId || current.channelId != channelId || current.sourceId != sourceId) {
                    Log.d(TAG, "丟棄非當前 attempt 錯誤回呼: attemptId=$attemptId current=${current.attemptId}")
                    return@post
                }
                val mode = if (modeStr == "WEB_WATCH") PlayMode.WEB_WATCH else PlayMode.IFRAME
                val desc = when (errorCode) {
                    2 -> "YouTube 影片參數無效 (2)"
                    5 -> "HTML5 播放核心異常 (5)"
                    100 -> "官方直播已結束或已設為私人影片 (100)"
                    101, 150 -> "頻道官方禁止第三方嵌入 (150)"
                    153 -> "缺少 Referer 或安全政策限制 (153)"
                    -1001 -> "YouTube 載入逾時 (-1001)"
                    -1002 -> "官方網頁觀看模式無法播放 (-1002)"
                    else -> "YouTube 播放器錯誤 ($errorCode)"
                }
                listener?.onError(attemptId, channelId, sourceId, mode, errorCode, rawReason, desc)
            }
        }

        @JavascriptInterface
        fun onAutoplayBlocked(attemptId: Long, channelId: String, sourceId: String, modeStr: String) {
            post {
                val current = activeAttempt ?: return@post
                if (current.attemptId == attemptId && current.channelId == channelId && current.sourceId == sourceId) {
                    val mode = if (modeStr == "WEB_WATCH") PlayMode.WEB_WATCH else PlayMode.IFRAME
                    listener?.onAutoplayBlocked(attemptId, channelId, sourceId, mode)
                }
            }
        }

        @JavascriptInterface
        fun onAdState(attemptId: Long, channelId: String, sourceId: String, isAd: Boolean) {
            post {
                val current = activeAttempt ?: return@post
                if (current.attemptId == attemptId && current.channelId == channelId && current.sourceId == sourceId) {
                    listener?.onAdStateChanged(attemptId, channelId, sourceId, isAd)
                }
            }
        }

        @JavascriptInterface
        fun onLoginRequired(attemptId: Long, channelId: String, sourceId: String) {
            post {
                val current = activeAttempt ?: return@post
                if (current.attemptId == attemptId && current.channelId == channelId && current.sourceId == sourceId) {
                    listener?.onLoginRequired(attemptId, channelId, sourceId)
                }
            }
        }

        @JavascriptInterface
        fun onGeoBlocked(attemptId: Long, channelId: String, sourceId: String, rawReason: String) {
            post {
                val current = activeAttempt ?: return@post
                if (current.attemptId == attemptId && current.channelId == channelId && current.sourceId == sourceId) {
                    listener?.onGeoBlocked(attemptId, channelId, sourceId, rawReason)
                }
            }
        }
    }
}
