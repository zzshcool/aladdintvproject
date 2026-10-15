package com.aladdin.tv.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 串流類型定義
 */
enum class StreamType {
    HLS,      // 標準 ExoPlayer 本地播放 (m3u8, mp4, ts)
    YOUTUBE,  // 官方 YouTube IFrame 內嵌播放
    WEB_VIEW  // 官方網頁觀看模式 (如 ofiii / 官方觀看頁)
}

/**
 * 頻道健康狀態燈定義
 */
enum class ChannelHealthStatus {
    NORMAL,      // 綠燈：目前播放正常 (持續輸出畫面 > 2秒)
    CONNECTING,  // 黃燈：連線中、緩衝中或切換備援中
    FAILED,      // 紅燈：重試與所有備援耗盡後仍無法播放
    UNKNOWN,     // 灰燈：尚未檢查或狀態已過期
    EXTERNAL     // 藍灰：外部狀態 (保留相容)
}

/**
 * 單一串流來源實體 (用於多來源備援)
 */
data class StreamSource(
    val url: String,
    val type: StreamType = StreamType.HLS,
    val youtubeVideoId: String? = null,
    val liveChannelHandle: String? = null,
    val label: String? = null,
    val tvgId: String? = null,
    val isGeoBlocked: Boolean = false,
    val isPartTime: Boolean = false,
    val originLabel: String? = null,
    val failureCount: Int = 0,
    val lastSuccessTime: Long = 0L,
    val id: String = "",
    val provider: String = "built_in",
    val updateBatch: String = "",
    val providers: Set<String> = emptySet()
) {
    val sourceId: String
        get() = when {
            id.isNotBlank() -> id
            youtubeVideoId != null -> "yt_$youtubeVideoId"
            url.isNotBlank() -> "src_${url.hashCode().toUInt().toString(16)}"
            else -> "src_${System.identityHashCode(this)}"
        }

    val effectiveProviders: Set<String>
        get() = if (providers.isNotEmpty()) providers else setOf(provider)

    /**
     * 取得脫敏後的串流網址 (診斷資訊展示用，移除 Token、Cookie、簽名等敏感查詢參數)
     */
    fun getSanitizedUrl(): String {
        if (url.isBlank()) return ""
        return try {
            val qIdx = url.indexOf('?')
            if (qIdx != -1) url.substring(0, qIdx) else url
        } catch (_: Exception) {
            url
        }
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("url", url)
        json.put("type", type.name)
        if (youtubeVideoId != null) json.put("youtubeVideoId", youtubeVideoId)
        if (liveChannelHandle != null) json.put("liveChannelHandle", liveChannelHandle)
        if (label != null) json.put("label", label)
        if (tvgId != null) json.put("tvgId", tvgId)
        json.put("isGeoBlocked", isGeoBlocked)
        json.put("isPartTime", isPartTime)
        if (originLabel != null) json.put("originLabel", originLabel)
        json.put("failureCount", failureCount)
        json.put("lastSuccessTime", lastSuccessTime)
        json.put("id", sourceId)
        json.put("provider", provider)
        json.put("updateBatch", updateBatch)

        val provArr = org.json.JSONArray()
        for (p in effectiveProviders) {
            provArr.put(p)
        }
        json.put("providers", provArr)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): StreamSource {
            val typeStr = json.optString("type", StreamType.HLS.name)
            val streamType = try {
                StreamType.valueOf(typeStr)
            } catch (_: Exception) {
                StreamType.HLS
            }

            val provSet = mutableSetOf<String>()
            val provArr = json.optJSONArray("providers")
            if (provArr != null) {
                for (i in 0 until provArr.length()) {
                    val p = provArr.optString(i)
                    if (p.isNotBlank()) provSet.add(p)
                }
            }
            val defaultProv = json.optString("provider", "built_in")
            if (provSet.isEmpty() && defaultProv.isNotBlank()) {
                provSet.add(defaultProv)
            }

            return StreamSource(
                url = json.optString("url", ""),
                type = streamType,
                youtubeVideoId = json.optString("youtubeVideoId").takeIf { it.isNotBlank() },
                liveChannelHandle = json.optString("liveChannelHandle").takeIf { it.isNotBlank() },
                label = json.optString("label").takeIf { it.isNotBlank() },
                tvgId = json.optString("tvgId").takeIf { it.isNotBlank() },
                isGeoBlocked = json.optBoolean("isGeoBlocked", false),
                isPartTime = json.optBoolean("isPartTime", false),
                originLabel = json.optString("originLabel").takeIf { it.isNotBlank() },
                failureCount = json.optInt("failureCount", 0),
                lastSuccessTime = json.optLong("lastSuccessTime", 0L),
                id = json.optString("id", ""),
                provider = defaultProv,
                updateBatch = json.optString("updateBatch", ""),
                providers = provSet
            )
        }
    }
}

/**
 * 頻道健康狀態記錄實體
 */
data class ChannelHealth(
    val status: ChannelHealthStatus = ChannelHealthStatus.UNKNOWN,
    val lastUpdatedTime: Long = System.currentTimeMillis(),
    val message: String? = null,
    val currentSourceIndex: Int = 0,
    val totalSources: Int = 1
)

/**
 * 細緻化即時播放狀態 (區分廣告、等待點擊、登入與真正緩衝)
 */
enum class PlaybackStatus {
    CONNECTING,             // 連線中 (建立連線與初始化)
    BUFFERING,              // 真正串流緩衝中
    WAITING_CLICK,          // 等待手動點擊 (自動播放受限)
    AD_PLAYING,             // 廣告播放中 (請稍候，看門狗暫停)
    INTERACTION_REQUIRED,   // 需要使用者互動操作
    LOGIN_REQUIRED,         // 需要官方登入 (目前不支援)
    STREAM_ENDED,           // 直播串流已結束
    SOURCE_FAILED,          // 當前線路連線失敗
    NORMAL,                 // 正常播放中 (具有前進證據)
    UNKNOWN                 // 狀態未知
}

/**
 * 台灣第四台頻道資料模型 (身分與串流網址分離，支援多來源備援)
 *
 * @property id 頻道穩定識別碼 (不綁定易變的台號)
 * @property canonicalId 標準規範頻道身分 (如 ebc_news, ftv_news, tvbs_news)
 * @property number 頻道台號 (顯示與排序資料，不作為頻道身份依據)
 * @property name 頻道名稱 (例如 民視新聞台、TVBS新聞台、東森新聞台)
 * @property group 頻道分組 (例如 新聞時事、無線綜合、公共電視)
 * @property streamSources 備援串流來源池
 * @property currentSourceIndex 當前嘗試的來源索引
 * @property logoUrl 台標圖示位址 (可選)
 * @property tvgId M3U tvg-id 標籤 (如 EBCNews.tw)
 * @property isGeoBlocked 是否所有來源均具備地區播放限制
 * @property isPartTime 是否所有來源均為非 24 小時直播
 */
data class Channel(
    val id: String,
    val canonicalId: String = "",
    val number: Int,
    val name: String,
    val group: String,
    val streamSources: List<StreamSource>,
    val currentSourceIndex: Int = 0,
    val logoUrl: String? = null,
    val tvgId: String? = null,
    val isGeoBlocked: Boolean = false,
    val isPartTime: Boolean = false
) {
    // 便捷構造函式：支援單一 URL 建立 Channel
    constructor(
        id: String = "",
        canonicalId: String = "",
        number: Int,
        name: String,
        group: String,
        url: String,
        streamType: StreamType = StreamType.HLS,
        youtubeVideoId: String? = null,
        logoUrl: String? = null,
        tvgId: String? = null,
        isGeoBlocked: Boolean = false,
        isPartTime: Boolean = false,
        sourceProvider: String = "built_in"
    ) : this(
        id = id.ifBlank { "ch_${canonicalId.ifBlank { name.hashCode().toUInt().toString(16) }}" },
        canonicalId = canonicalId,
        number = number,
        name = name,
        group = group,
        streamSources = listOf(
            StreamSource(
                url = url,
                type = streamType,
                youtubeVideoId = youtubeVideoId,
                tvgId = tvgId,
                isGeoBlocked = isGeoBlocked,
                isPartTime = isPartTime,
                provider = sourceProvider
            )
        ),
        currentSourceIndex = 0,
        logoUrl = logoUrl,
        tvgId = tvgId,
        isGeoBlocked = isGeoBlocked,
        isPartTime = isPartTime
    )

    val currentSource: StreamSource?
        get() = streamSources.getOrNull(currentSourceIndex) ?: streamSources.firstOrNull()

    val currentSourceId: String
        get() = currentSource?.sourceId ?: ""

    val url: String
        get() = currentSource?.url ?: ""

    val streamType: StreamType
        get() = currentSource?.type ?: StreamType.HLS

    val youtubeVideoId: String?
        get() = currentSource?.youtubeVideoId

    val hasBackupSources: Boolean
        get() = streamSources.size > 1

    /**
     * 切換至指定索引之來源
     */
    fun withSourceIndex(index: Int): Channel {
        val safeIndex = index.coerceIn(0, (streamSources.size - 1).coerceAtLeast(0))
        return copy(currentSourceIndex = safeIndex)
    }

    /**
     * 切換至下一個備援串流，若已達末尾則回傳 null
     */
    fun nextBackup(): Channel? {
        val nextIndex = currentSourceIndex + 1
        return if (nextIndex < streamSources.size) {
            copy(currentSourceIndex = nextIndex)
        } else {
            null
        }
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("canonicalId", canonicalId)
        json.put("number", number)
        json.put("name", name)
        json.put("group", group)
        json.put("currentSourceIndex", currentSourceIndex)
        if (logoUrl != null) json.put("logoUrl", logoUrl)
        if (tvgId != null) json.put("tvgId", tvgId)
        json.put("isGeoBlocked", isGeoBlocked)
        json.put("isPartTime", isPartTime)

        val sourcesArray = JSONArray()
        for (src in streamSources) {
            sourcesArray.put(src.toJson())
        }
        json.put("streamSources", sourcesArray)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): Channel {
            val sources = mutableListOf<StreamSource>()
            val sourcesArray = json.optJSONArray("streamSources")
            if (sourcesArray != null) {
                for (i in 0 until sourcesArray.length()) {
                    val srcObj = sourcesArray.optJSONObject(i)
                    if (srcObj != null) {
                        sources.add(StreamSource.fromJson(srcObj))
                    }
                }
            }
            return Channel(
                id = json.optString("id", ""),
                canonicalId = json.optString("canonicalId", ""),
                number = json.optInt("number", 0),
                name = json.optString("name", ""),
                group = json.optString("group", "台灣頻道"),
                streamSources = sources,
                currentSourceIndex = json.optInt("currentSourceIndex", 0),
                logoUrl = json.optString("logoUrl").takeIf { it.isNotBlank() },
                tvgId = json.optString("tvgId").takeIf { it.isNotBlank() },
                isGeoBlocked = json.optBoolean("isGeoBlocked", false),
                isPartTime = json.optBoolean("isPartTime", false)
            )
        }
    }
}
