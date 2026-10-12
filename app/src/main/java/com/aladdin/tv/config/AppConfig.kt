package com.aladdin.tv.config

/**
 * 阿拉丁TV 系統全域配置中心
 * 集中管理直播來源、本地快取、網路超時、電視盒緩衝策略與防抖時間
 */
object AppConfig {
    // 預設台灣直播源網址 (主要來源為 iptv-org 國家庫，備用來源為 iptv-org 全局串流庫，均為開源社群維護之公開串流)
    const val DEFAULT_M3U_URL = "https://iptv-org.github.io/iptv/countries/tw.m3u"
    const val BACKUP_M3U_URL = "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/tw.m3u"

    // 本地快取檔案名稱
    const val CACHE_FILE_NAME = "channels_cache.m3u"
    const val CACHE_FILE_NAME_V2 = "channels_cache_v2.json"

    // HTTP 網路連線與讀取超時 (毫秒)
    const val HTTP_CONNECT_TIMEOUT_MS = 6000L
    const val HTTP_READ_TIMEOUT_MS = 10000L

    // 晶晨 Amlogic S905X4 5GHz Wi-Fi 低延遲起播緩衝參數 (毫秒)
    const val MIN_BUFFER_MS = 1500
    const val MAX_BUFFER_MS = 5000
    const val BUFFER_FOR_PLAYBACK_MS = 500
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 1000

    // 遙控器數字鍵輸入防抖超時 (毫秒)：停止按鍵後自動換台
    const val NUMBER_KEY_DEBOUNCE_MS = 1200L

    // 底部 OSD 浮層自動隱藏時間 (毫秒)
    const val OSD_AUTO_DISMISS_MS = 4500L

    // 左側選台抽屜閒置自動關閉時間 (毫秒)
    const val DRAWER_AUTO_DISMISS_MS = 6000L

    // 播放錯誤單一來源最大重試次數與間隔
    const val RETRY_INTERVAL_MS = 2000L
    const val MAX_RETRY_COUNT = 3

    // 播放穩定度判定：連續播放滿 2 秒無中斷判定為綠燈正常
    const val STABLE_PLAYBACK_DELAY_MS = 2000L

    // 背景健康巡檢並行限制與超時 (毫秒)
    const val HEALTH_CHECK_CONCURRENCY = 2
    const val HEALTH_CHECK_TIMEOUT_MS = 4000L
    const val HEALTH_STATUS_EXPIRATION_MS = 900_000L // 15 分鐘未更新視為灰燈過期
    const val HEALTH_EXPIRY_SCAN_INTERVAL_MS = 60_000L // 每 60 秒掃描過期狀態

    // 本地持久化 Key
    const val PREFS_NAME = "aladdin_tv_prefs"
    const val PREF_LAST_CHANNEL_ID = "last_channel_id"
    const val PREF_LAST_CHANNEL_URL = "last_channel_url"
    const val PREF_LAST_CHANNEL_INDEX = "last_channel_index"
    const val PREF_APP_THEME = "app_theme"
    const val PREF_QUALITY_PREF = "quality_pref"
    const val PREF_LAST_UPDATE_TIME = "last_update_time"
    const val PREF_SOURCE_STATUS = "source_status"
    const val PREF_CHANNELS_COUNT = "channels_count"
    const val PREF_TOTAL_SOURCES_COUNT = "total_sources_count"
    const val PREF_AUTO_FALLBACK_ENABLED = "auto_fallback_enabled"
    const val PREF_USER_SELECTED_SOURCE_PREFIX = "user_selected_source_"

    // 主題模式定義：跟隨系統 (0)、深色 (1)、淺色 (2)
    const val THEME_SYSTEM = 0
    const val THEME_DARK = 1
    const val THEME_LIGHT = 2

    // 畫質上限偏好：自動 (0)、最高 1080p (1)、允許 4K (2)
    const val QUALITY_AUTO = 0
    const val QUALITY_1080P = 1
    const val QUALITY_4K = 2

    // 播放器 User-Agent (避免被部分直播串流伺服器識別攔截)
    const val USER_AGENT = "AladdinTV/1.0 (Linux; Android TV; ExoPlayer) Mobile/TV"

    // 應用程式版本與建置識別 (用於實機診斷與比對)
    const val VERSION_NAME = "1.0.1"
    const val VERSION_CODE = 2
    const val BUILD_ID = "20261012_TV_STABLE_V2"

    // YouTube 官方嵌入合規 Origin 與超時參數 (禁止偽裝成 youtube.com)
    const val YOUTUBE_APP_ORIGIN = "https://tv.aladdin.app"
    const val YOUTUBE_BUFFERING_TIMEOUT_MS = 10_000L
    const val YOUTUBE_LOAD_TIMEOUT_MS = 12_000L
    const val YOUTUBE_WEB_WATCH_TIMEOUT_MS = 15_000L
}
