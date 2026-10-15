package com.aladdin.tv.data.repository

import android.content.Context
import com.aladdin.tv.config.AppConfig
import com.aladdin.tv.data.model.Channel
import com.aladdin.tv.data.model.ChannelHealth
import com.aladdin.tv.data.model.ChannelHealthStatus
import com.aladdin.tv.data.model.StreamSource
import com.aladdin.tv.data.model.StreamType
import com.aladdin.tv.data.parser.M3uParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * 頻道資料儲存庫
 * 負責來源容錯、同名頻道多來源合併備援、來源狀態統計與獨立健康狀態管理
 */
class ChannelRepository(private val context: Context) {

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(AppConfig.HTTP_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(AppConfig.HTTP_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val cacheFileV2: File
        get() = File(context.filesDir, AppConfig.CACHE_FILE_NAME_V2)

    private val legacyCacheFile: File
        get() = File(context.filesDir, AppConfig.CACHE_FILE_NAME)

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _healthMap = MutableStateFlow<Map<String, ChannelHealth>>(emptyMap())
    val healthMap: StateFlow<Map<String, ChannelHealth>> = _healthMap.asStateFlow()

    private val prefs = context.getSharedPreferences(AppConfig.PREFS_NAME, Context.MODE_PRIVATE)
    private val checkSemaphore = Semaphore(AppConfig.HEALTH_CHECK_CONCURRENCY)
    private val sourceFailureCount = mutableMapOf<String, Int>()

    /**
     * 本次實機全線路確認失效之頻道排除集合
     * 確保快取重構、本機開機載入、內建種子與遠端更新時均穩定排除，不重新出現
     */
    private val EXCLUDED_DEFUNCT_CHANNELS = setOf(
        "ctivariety.tw@sd",
        "daai2.tw@sd",
        "ftv_gen",
        "good2.tw@sd",
        "goodtv.tw@sd",
        "cts_main",
        "pts_main",
        "pts_taiwan",
        "smart_knowledge",
        "longhua_drama",
        "longhua_japan_korea",
        "ofiii_focus_drama",
        "global_taiwan",
        "ofiii_finance",
        "ctv_main",
        "longhua_classic",
        "global_finance",
        "longhua_movie",
        "longhua_foreign",
        "ofiii_stephen_chow",
        "ofiii_movies",
        "ofiii_doraemon",
        "ofiii_golden_fans",
        "ofiii_ebc_entertainment",
        "asia_travel",
        "goodtv_1",
        "goodtv_2",
        "ofiii_studio_classroom",
        "ofiii_focus_knowledge",
        "ofiii_concert",
        "ofiii_freeman",
        "ofiii_food"
    )

    private fun isDefunctExcludedChannel(channel: Channel): Boolean {
        if (prefs.getStringSet("manually_hidden_channels", emptySet())!!.contains(channel.canonicalId)) return true
        if (prefs.getStringSet("restored_channels", emptySet())!!.contains(channel.canonicalId)) return false
        val canon = channel.canonicalId.trim().lowercase().removePrefix("canon_").removePrefix("tvg_")
        val rawId = channel.id.trim().lowercase().removePrefix("ch_").removePrefix("seed_")
        val tvg = channel.tvgId?.trim()?.lowercase() ?: ""
        val name = channel.name.trim().lowercase()

        // 排除 GOOD TV 專題短片頻道群
        val isGoodTvSeries = canon.startsWith("name_goodtvch") ||
                rawId.startsWith("name_goodtvch") ||
                name.contains("good tv ch") ||
                name.startsWith("good tv") ||
                channel.streamSources.any { it.url.contains("live.streamingfast.net/osmflivech") }

        return isGoodTvSeries ||
               EXCLUDED_DEFUNCT_CHANNELS.contains(canon) ||
               EXCLUDED_DEFUNCT_CHANNELS.contains(rawId) ||
               (tvg.isNotEmpty() && EXCLUDED_DEFUNCT_CHANNELS.contains(tvg))
    }

    private val ytLiveCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    private val ytResolveLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val YT_CACHE_DURATION_MS = 60 * 60 * 1000L // 1 小時快取

    /**
     * 動態 YouTube 直播解析：透過頻道 handle (例如 @newsebc) 追蹤 /live 取得即時 Video ID
     */
    suspend fun resolveYouTubeLiveVideoId(handle: String): String? = withContext(Dispatchers.IO) {
        val cleanHandle = handle.trim()
        if (cleanHandle.isBlank()) return@withContext null
        ytResolveLocks.getOrPut(cleanHandle) { Mutex() }.withLock {
            val now = System.currentTimeMillis()
            val cached = getYouTubeResolution(cleanHandle)
            if (cached != null && now - cached.second in 0 until YT_CACHE_DURATION_MS) {
                return@withLock cached.first
            }
            try {
                val encoded = if (cleanHandle.startsWith("@")) {
                    "@" + java.net.URLEncoder.encode(cleanHandle.substring(1), "UTF-8")
                } else {
                    java.net.URLEncoder.encode(cleanHandle, "UTF-8")
                }
                val targetUrl = "https://www.youtube.com/$encoded/live"
                val request = Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept-Language", "zh-TW,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                    .build()
                val call = okHttpClient.newCall(request)
                call.timeout().timeout(AppConfig.YOUTUBE_LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                val response = suspendCancellableCoroutine<Triple<Int, okhttp3.HttpUrl, String>> { continuation ->
                    continuation.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: java.io.IOException) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                        override fun onResponse(call: Call, response: Response) {
                            try {
                                val result = response.use { Triple(it.code, it.request.url, it.body?.string().orEmpty()) }
                                if (continuation.isActive) continuation.resume(result)
                            } catch (e: java.io.IOException) {
                                if (continuation.isActive) continuation.resumeWithException(e)
                            }
                        }
                    })
                }
                run {
                    if (response.first !in 200..299 || response.second.host !in setOf("www.youtube.com", "youtube.com")) return@withLock null
                    val body = response.third
                    val id = parseYouTubeLiveResponse(body, response.second.queryParameter("v"), cleanHandle)
                        ?: return@withLock null
                    val resolvedAt = System.currentTimeMillis()
                    ytLiveCache[cleanHandle] = id to resolvedAt
                    prefs.edit().putString("yt_live_$cleanHandle", JSONObject().put("id", id).put("time", resolvedAt).toString()).apply()
                    id
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    fun getYouTubeResolution(handle: String): Pair<String, Long>? {
        val key = handle.trim()
        ytLiveCache[key]?.let { return it }
        return try {
            val saved = JSONObject(prefs.getString("yt_live_$key", null) ?: return null)
            val id = saved.getString("id")
            if (!Regex("[A-Za-z0-9_-]{11}").matches(id)) return null
            (id to saved.getLong("time")).also { ytLiveCache[key] = it }
        } catch (_: Exception) { null }
    }

    fun invalidateYouTubeResolution(handle: String) {
        ytLiveCache.remove(handle.trim())
        prefs.edit().remove("yt_live_${handle.trim()}").apply()
    }

    fun isFavorite(channel: Channel) = prefs.getStringSet("favorite_channels", emptySet())!!.contains(channel.canonicalId)

    fun toggleFavorite(channel: Channel) {
        val ids = prefs.getStringSet("favorite_channels", emptySet())!!.toMutableSet()
        if (!ids.add(channel.canonicalId)) ids.remove(channel.canonicalId)
        prefs.edit().putStringSet("favorite_channels", ids).apply()
    }

    fun rememberWatchedChannel(channel: Channel) {
        val ids = getRecentChannelIds().filterNot { it == channel.canonicalId }
        prefs.edit().putString("recent_channels", JSONArray(listOf(channel.canonicalId) + ids.take(19)).toString()).apply()
    }

    fun getRecentChannelIds(): List<String> = try {
        val array = JSONArray(prefs.getString("recent_channels", "[]"))
        (0 until array.length()).map { array.getString(it) }
    } catch (_: Exception) { emptyList() }

    fun getHiddenChannels(): List<Channel> {
        val archived = try {
            val array = JSONArray(prefs.getString("hidden_channel_archive", "[]"))
            (0 until array.length()).map { Channel.fromJson(array.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
        return (getBuiltInSeedChannels() + archived).distinctBy { it.canonicalId }
            .filter { isDefunctExcludedChannel(it) }
    }

    fun restoreHiddenChannel(channel: Channel) {
        prefs.edit()
            .putStringSet("restored_channels", prefs.getStringSet("restored_channels", emptySet())!! + channel.canonicalId)
            .putStringSet("manually_hidden_channels", prefs.getStringSet("manually_hidden_channels", emptySet())!! - channel.canonicalId)
            .apply()
        val merged = applyUserSelectedSources(mergeAndDeduplicate(_channels.value, listOf(channel)))
        _channels.value = merged
        initHealthStatuses(merged)
        saveToCache(merged)
    }

    fun hideChannel(channel: Channel) {
        val archived = (getHiddenChannels() + channel).distinctBy { it.canonicalId }
        prefs.edit().putString("hidden_channel_archive", JSONArray(archived.map { it.toJson() }).toString())
            .putStringSet("manually_hidden_channels", prefs.getStringSet("manually_hidden_channels", emptySet())!! + channel.canonicalId)
            .apply()
        _channels.value = _channels.value.filterNot { it.canonicalId == channel.canonicalId }
        saveToCache(_channels.value)
    }


    private data class CanonicalChannelRule(
        val canonicalId: String,
        val defaultNumber: Int,
        val canonicalName: String,
        val group: String,
        val tvgIds: List<String>,
        val nameAliases: List<String>
    )

    private val CANONICAL_RULES = listOf(
        CanonicalChannelRule(
            canonicalId = "ftv_gen",
            defaultNumber = 6,
            canonicalName = "民視綜合台",
            group = "無線綜合",
            tvgIds = listOf("FTV.tw", "ftv.tw"),
            nameAliases = listOf("民視", "民視綜合台", "ftv")
        ),
        CanonicalChannelRule(
            canonicalId = "daai_1",
            defaultNumber = 9,
            canonicalName = "大愛電視1台",
            group = "宗教文化",
            tvgIds = listOf("DaAi1.tw", "daai1.tw"),
            nameAliases = listOf("大愛電視", "大愛一臺", "大愛一台", "大愛1台", "daai1")
        ),
        CanonicalChannelRule(
            canonicalId = "ctv_news",
            defaultNumber = 10,
            canonicalName = "中視新聞台",
            group = "新聞時事",
            tvgIds = listOf("CTVNews.tw", "ctvnews.tw"),
            nameAliases = listOf("中視新聞", "中視新聞台", "ctv news", "ctvnews")
        ),
        CanonicalChannelRule(
            canonicalId = "cts_main",
            defaultNumber = 12,
            canonicalName = "華視主頻",
            group = "無線綜合",
            tvgIds = listOf("CTS.tw", "cts.tw"),
            nameAliases = listOf("華視", "華視主頻", "cts")
        ),
        CanonicalChannelRule(
            canonicalId = "pts_main",
            defaultNumber = 13,
            canonicalName = "公視主頻",
            group = "公共電視",
            tvgIds = listOf("PTS.tw", "pts.tw"),
            nameAliases = listOf("公視", "公視主頻", "pts")
        ),
        CanonicalChannelRule(
            canonicalId = "pts_taiwan",
            defaultNumber = 14,
            canonicalName = "公視台語台",
            group = "公共電視",
            tvgIds = listOf("PTSTaigi.tw", "ptstaigi.tw"),
            nameAliases = listOf("公視台語台", "公視台語", "pts taigi", "ptstaigi")
        ),
        CanonicalChannelRule(
            canonicalId = "ttv_news",
            defaultNumber = 15,
            canonicalName = "台視新聞台",
            group = "新聞時事",
            tvgIds = listOf("TTVNews.tw", "ttvnews.tw"),
            nameAliases = listOf("台視新聞", "台視新聞台", "ttv news", "ttvnews")
        ),
        CanonicalChannelRule(
            canonicalId = "titv",
            defaultNumber = 16,
            canonicalName = "原住民族電視台",
            group = "文化族群",
            tvgIds = listOf("IndigenousTV.tw", "TaiwanIndigenousTV.tw", "indigenoustv.tw"),
            nameAliases = listOf("原住民族電視台", "原民台", "原視", "titv")
        ),
        CanonicalChannelRule(
            canonicalId = "ebc_news",
            defaultNumber = 51,
            canonicalName = "東森新聞台",
            group = "新聞時事",
            tvgIds = listOf("EBCNews.tw", "EBCNewsHD.tw", "ebcnews.tw"),
            nameAliases = listOf("東森新聞", "東森新聞台", "ebc news", "ebcnews", "ebc新聞")
        ),
        CanonicalChannelRule(
            canonicalId = "cts_news",
            defaultNumber = 52,
            canonicalName = "華視新聞資訊台",
            group = "新聞時事",
            tvgIds = listOf("CTSNews.tw", "ctsnews.tw"),
            nameAliases = listOf("華視新聞", "華視新聞台", "華視新聞資訊台", "華視新聞資訊", "cts news", "ctsnews")
        ),
        CanonicalChannelRule(
            canonicalId = "ftv_news",
            defaultNumber = 53,
            canonicalName = "民視新聞台",
            group = "新聞時事",
            tvgIds = listOf("FTVNews.tw", "FTVNewsHD.tw", "ftvnews.tw"),
            nameAliases = listOf("民視新聞", "民視新聞台", "ftv news", "ftvnews", "ftv新聞")
        ),
        CanonicalChannelRule(
            canonicalId = "set_news",
            defaultNumber = 54,
            canonicalName = "三立新聞台",
            group = "新聞時事",
            tvgIds = listOf("SETNews.tw", "setnews.tw"),
            nameAliases = listOf("三立新聞", "三立新聞台", "set news", "setnews")
        ),
        CanonicalChannelRule(
            canonicalId = "tvbs_news",
            defaultNumber = 55,
            canonicalName = "TVBS新聞台",
            group = "新聞時事",
            tvgIds = listOf("TVBSNews.tw", "TVBSNewsHD.tw", "tvbsnews.tw"),
            nameAliases = listOf("tvbs新聞", "tvbs新聞台", "tvbs news", "tvbsnews")
        ),
        CanonicalChannelRule(
            canonicalId = "cti_news",
            defaultNumber = 56,
            canonicalName = "中天新聞台",
            group = "新聞時事",
            tvgIds = listOf("CTiNews.tw", "ctinews.tw"),
            nameAliases = listOf("中天新聞", "中天新聞台", "中天電視", "cti news", "ctinews")
        ),
        CanonicalChannelRule(
            canonicalId = "ebc_fin_news",
            defaultNumber = 57,
            canonicalName = "東森財經新聞台",
            group = "財經新聞",
            tvgIds = listOf("EBCFinancialNews.tw", "ebcfinancialnews.tw"),
            nameAliases = listOf("東森財經新聞", "東森財經新聞台", "57東森財經新聞", "東森財經", "ebc financial news")
        ),
        CanonicalChannelRule(
            canonicalId = "mirror_news",
            defaultNumber = 86,
            canonicalName = "鏡電視新聞台",
            group = "新聞時事",
            tvgIds = listOf("MirrorNews.tw", "mirrornews.tw"),
            nameAliases = listOf("鏡電視", "鏡電視新聞台", "鏡電視新聞", "鏡新聞", "mirror news", "mnews")
        ),
        CanonicalChannelRule(
            canonicalId = "set_inews",
            defaultNumber = 88,
            canonicalName = "三立財經iNEWS",
            group = "財經新聞",
            tvgIds = listOf("SETiNews.tw", "setinews.tw"),
            nameAliases = listOf("三立財經inews", "三立財經", "set inews", "setinews", "三立新聞財經", "三立財經台")
        ),
        CanonicalChannelRule(
            canonicalId = "ntd_news",
            defaultNumber = 12,
            canonicalName = "新唐人亞太台",
            group = "新聞時事",
            tvgIds = listOf("NTDAPTV.tw", "ntdaptv.tw"),
            nameAliases = listOf("新唐人", "新唐人亞太", "新唐人亞太台", "新唐人電視台")
        ),
        CanonicalChannelRule(
            canonicalId = "pts_news",
            defaultNumber = 13,
            canonicalName = "公視新聞網",
            group = "公共電視",
            tvgIds = listOf("PNNPTS.tw", "pnntvs.tw"),
            nameAliases = listOf("公視新聞", "公視新聞網", "pnn", "公視網路直播")
        ),
        CanonicalChannelRule(
            canonicalId = "momo_shop2",
            defaultNumber = 35,
            canonicalName = "momo購物2台",
            group = "生活購物",
            tvgIds = listOf("Momo2.tw", "momo2.tw"),
            nameAliases = listOf("momo2", "momo購物2台", "momo購物二台", "momo 2")
        ),
        CanonicalChannelRule(
            canonicalId = "taiwan_plus",
            defaultNumber = 38,
            canonicalName = "TaiwanPlus",
            group = "國際新聞",
            tvgIds = listOf("TaiwanPlus.tw", "taiwanplus.tw"),
            nameAliases = listOf("taiwanplus", "taiwan plus")
        ),
        CanonicalChannelRule(
            canonicalId = "ebc_shop",
            defaultNumber = 47,
            canonicalName = "東森購物台",
            group = "生活購物",
            tvgIds = listOf("EBCShop.tw", "ebcshop.tw"),
            nameAliases = listOf("東森購物", "東森購物台", "東森購物1台", "東森購物一台")
        ),
        CanonicalChannelRule(
            canonicalId = "muse_anime",
            defaultNumber = 70,
            canonicalName = "Muse木棉花動漫",
            group = "卡通動漫",
            tvgIds = listOf("MuseAnime.tw", "museanime.tw"),
            nameAliases = listOf("木棉花", "木棉花動漫", "muse動漫", "muse木棉花")
        ),
        CanonicalChannelRule(
            canonicalId = "rock_music",
            defaultNumber = 90,
            canonicalName = "滾石音樂24H",
            group = "音樂娛樂",
            tvgIds = listOf("RockMusic.tw", "rockmusic.tw"),
            nameAliases = listOf("滾石唱片", "滾石音樂", "滾石24h", "rock records")
        )
    )

    /**
     * 開機啟動初始化載入流程
     */
    suspend fun loadChannels() = withContext(Dispatchers.IO) {
        val cachedChannels = readFromCache()
        if (cachedChannels.isNotEmpty()) {
            val merged = applyUserSelectedSources(mergeAndDeduplicate(getBuiltInSeedChannels(), cachedChannels))
            _channels.value = merged
            initHealthStatuses(merged)
        } else {
            val initialChannels = applyUserSelectedSources(mergeAndDeduplicate(getBuiltInSeedChannels()))
            _channels.value = initialChannels
            initHealthStatuses(initialChannels)
            saveToCache(initialChannels)
        }

        // 背景非同步抓取最新清單
        fetchAndRefreshFromRemote()
    }

    /**
     * 從網路抓取最新 M3U 內容並驗證有效性
     */
    suspend fun fetchAndRefreshFromRemote(): Boolean = withContext(Dispatchers.IO) {
        var defaultSuccess = false
        var backupSuccess = false

        val defaultContent = downloadM3u(AppConfig.DEFAULT_M3U_URL)
        val nowBatch = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())

        val defaultChannels = if (isValidM3uContent(defaultContent)) {
            val parsed = M3uParser.parse(defaultContent!!)
            if (parsed.isNotEmpty()) {
                defaultSuccess = true
                parsed.map { ch ->
                    ch.copy(streamSources = ch.streamSources.map { s ->
                        s.copy(
                            provider = "remote_default",
                            updateBatch = nowBatch,
                            providers = setOf("remote_default")
                        )
                    })
                }
            } else emptyList()
        } else emptyList()

        val backupContent = downloadM3u(AppConfig.BACKUP_M3U_URL)
        val backupChannels = if (isValidM3uContent(backupContent)) {
            val parsed = M3uParser.parse(backupContent!!)
            if (parsed.isNotEmpty()) {
                backupSuccess = true
                parsed.map { ch ->
                    ch.copy(streamSources = ch.streamSources.map { s ->
                        s.copy(
                            provider = "remote_backup",
                            updateBatch = nowBatch,
                            providers = setOf("remote_backup")
                        )
                    })
                }
            } else emptyList()
        } else emptyList()

        val isAnyRemoteSuccess = defaultSuccess || backupSuccess
        val nowStr = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date())

        if (isAnyRemoteSuccess) {
            val current = _channels.value

            // 分別管理 remote_default 與 remote_backup 的過期淘汰：
            // 只有對應提供者完整成功更新時，才從來源中移除該提供者標籤；若來源不再被任何提供者供應且非種子，才予以淘汰
            val prunedCurrent = current.map { ch ->
                val updatedSources = ch.streamSources.mapNotNull { s ->
                    val currentProviders = s.effectiveProviders.toMutableSet()
                    if (defaultSuccess && s.updateBatch != nowBatch) {
                        currentProviders.remove("remote_default")
                    }
                    if (backupSuccess && s.updateBatch != nowBatch) {
                        currentProviders.remove("remote_backup")
                    }
                    // 保留具備有效提供者，或為內建種子/ofiii 的來源
                    if (currentProviders.isNotEmpty() || s.provider == "seed" || s.provider == "ofiii" || s.provider == "built_in") {
                        s.copy(providers = currentProviders)
                    } else {
                        null
                    }
                }
                ch.copy(streamSources = updatedSources)
            }.filter { it.streamSources.isNotEmpty() }

            val deduplicated = mergeAndDeduplicate(defaultChannels, backupChannels, getBuiltInSeedChannels(), prunedCurrent)
            val mergedList = applyUserSelectedSources(deduplicated)

            if (mergedList.isNotEmpty()) {
                _channels.value = mergedList
                initHealthStatuses(mergedList)
                saveToCache(mergedList)

                val statusDesc = when {
                    defaultSuccess && backupSuccess -> "主要與備用來源皆更新成功"
                    defaultSuccess -> "主要來源更新成功 (備用來源失效)"
                    else -> "主要來源失效，備用來源更新成功"
                }
                val totalSources = mergedList.sumOf { it.streamSources.size }
                saveSourceUpdateInfo(nowStr, statusDesc, mergedList.size, totalSources)
                return@withContext true
            }
        }

        // 遠端更新失敗，不覆蓋最後成功更新時間，完整保留既有快取清單
        val cached = _channels.value
        val totalSources = cached.sumOf { it.streamSources.size }
        prefs.edit()
            .putString(AppConfig.PREF_SOURCE_STATUS, "遠端下載失敗，沿用既有快取清單 (嘗試時間 $nowStr)")
            .putInt(AppConfig.PREF_CHANNELS_COUNT, cached.size)
            .putInt(AppConfig.PREF_TOTAL_SOURCES_COUNT, totalSources)
            .apply()
        return@withContext false
    }

    private fun isValidM3uContent(content: String?): Boolean {
        if (content.isNullOrBlank()) return false
        return content.contains("#EXTM3U") || content.contains("#EXTINF")
    }

    /**
     * 多來源智慧合併與備援整合策略：
     * 1. 頻道 ID 完全解耦台號，依標準 Canonical ID 或 tvg-id 聚合同頻道不同線路進備援池
     * 2. 明確分開「最新來源資訊」與「既有播放統計」，新種子或遠端 metadata 不得覆蓋已儲存的 failureCount 與 lastSuccessTime
     * 3. 保留個別線路的地區與時段限制，不因單一來源受限而鎖死整個頻道
     */
    private fun mergeAndDeduplicate(vararg channelLists: List<Channel>): List<Channel> {
        val uniqueMap = linkedMapOf<String, Channel>()
        val nameToKeyMap = mutableMapOf<String, String>()

        // 提取歷史播放經驗 ( failureCount 與 lastSuccessTime )，確保新來源 metadata 不覆蓋歷史經驗
        val historyStats = mutableMapOf<String, Pair<Int, Long>>()
        for (list in channelLists) {
            for (ch in list) {
                for (src in ch.streamSources) {
                    if (src.failureCount > 0 || src.lastSuccessTime > 0L) {
                        val prev = historyStats[src.url]
                        val maxFail = maxOf(prev?.first ?: 0, src.failureCount)
                        val maxSuccess = maxOf(prev?.second ?: 0L, src.lastSuccessTime)
                        historyStats[src.url] = Pair(maxFail, maxSuccess)
                    }
                }
            }
        }

        for (list in channelLists) {
            for (ch in list) {
                val (primaryKey, canonicalRule) = resolveChannelIdentity(ch)
                val cleanName = normalizeChannelKey(ch.name)

                // 智慧別名映射：若無 tvgId 且非 Canonical，嘗試藉由同名 cleanName 對齊現有已登記之頻道 Key
                val effectiveKey = if (canonicalRule != null) {
                    primaryKey
                } else if (nameToKeyMap.containsKey(cleanName)) {
                    nameToKeyMap[cleanName] ?: primaryKey
                } else {
                    primaryKey
                }

                val existing = uniqueMap[effectiveKey]

                if (existing == null) {
                    val finalNumber = canonicalRule?.defaultNumber ?: ch.number
                    val finalName = canonicalRule?.canonicalName ?: ch.name
                    val finalGroup = canonicalRule?.group ?: ch.group
                    val finalCanonicalId = canonicalRule?.canonicalId ?: ch.canonicalId.ifBlank { effectiveKey }

                    // 頻道 ID 徹底獨立於台號，台號僅為排序與顯示資料
                    val stableChannelId = "ch_$finalCanonicalId"

                    // 還原歷史成敗統計
                    val sourcesWithHistory = ch.streamSources.map { s ->
                        val stats = historyStats[s.url]
                        if (stats != null) {
                            s.copy(failureCount = stats.first, lastSuccessTime = stats.second)
                        } else s
                    }

                    val newChannel = ch.copy(
                        id = stableChannelId,
                        canonicalId = finalCanonicalId,
                        number = finalNumber,
                        name = finalName,
                        group = finalGroup,
                        streamSources = sortSourcesByHealth(sourcesWithHistory)
                    )
                    uniqueMap[effectiveKey] = newChannel
                    if (cleanName.isNotBlank() && !nameToKeyMap.containsKey(cleanName)) {
                        nameToKeyMap[cleanName] = effectiveKey
                    }
                } else {
                    // 合併備援來源池，避免相同 URL 重複，分開「最新來源資訊」與「既有播放統計」
                    val existingUrls = existing.streamSources.associateBy { it.url }
                    val mergedSources = existing.streamSources.toMutableList()

                    for (src in ch.streamSources) {
                        val existingSrc = existingUrls[src.url]
                        val stats = historyStats[src.url]
                        val finalFailCount = stats?.first ?: maxOf(existingSrc?.failureCount ?: 0, src.failureCount)
                        val finalSuccessTime = stats?.second ?: maxOf(existingSrc?.lastSuccessTime ?: 0L, src.lastSuccessTime)

                        if (existingSrc == null) {
                            mergedSources.add(src.copy(
                                failureCount = finalFailCount,
                                lastSuccessTime = finalSuccessTime
                            ))
                        } else {
                            // 保持既有累計之成敗統計，並合併提供者集合
                            val srcIdx = mergedSources.indexOfFirst { it.url == src.url }
                            if (srcIdx != -1) {
                                val combinedProviders = existingSrc.effectiveProviders + src.effectiveProviders
                                mergedSources[srcIdx] = src.copy(
                                    failureCount = finalFailCount,
                                    lastSuccessTime = finalSuccessTime,
                                    providers = combinedProviders,
                                    label = if (src.label.isNullOrBlank()) existingSrc.label else src.label,
                                    originLabel = if (src.originLabel.isNullOrBlank()) existingSrc.originLabel else src.originLabel
                                )
                            }
                        }
                    }

                    val sortedSources = sortSourcesByHealth(mergedSources)
                    val updatedTvgId = existing.tvgId ?: ch.tvgId

                    // 保留來源個別的地區與時段限制，只有所有來源皆受限時，頻道才標記為受限
                    val allGeoBlocked = sortedSources.isNotEmpty() && sortedSources.all { it.isGeoBlocked }
                    val allPartTime = sortedSources.isNotEmpty() && sortedSources.all { it.isPartTime }

                    val mergedChannel = existing.copy(
                        streamSources = sortedSources,
                        logoUrl = existing.logoUrl ?: ch.logoUrl,
                        tvgId = updatedTvgId,
                        isGeoBlocked = allGeoBlocked,
                        isPartTime = allPartTime
                    )
                    uniqueMap[effectiveKey] = mergedChannel
                }
            }
        }

        // 重新分配嚴格唯一、確定性之頻道台號，不更動頻道 ID，並排除所有線路確認失效之頻道
        val hidden = uniqueMap.values.filter { isDefunctExcludedChannel(it) }
        if (hidden.isNotEmpty()) {
            val archived = (getHiddenChannels() + hidden).distinctBy { it.canonicalId }
            prefs.edit().putString("hidden_channel_archive", JSONArray(archived.map { it.toJson() }).toString()).apply()
        }
        val filtered = uniqueMap.values.filterNot { isDefunctExcludedChannel(it) }
        return assignUniqueChannelNumbers(filtered)
    }

    /**
     * 為所有合併後之獨立頻道分配嚴格唯一、確定性的台號：
     * 1. 規範頻道保留專屬台號 (06, 09, 10, 12, 13, 14, 15, 16, 51, 52, 53, 54, 55, 56, 57, 86, 88)
     * 2. 非規範頻道若自帶有效無衝突之台號則予以保留
     * 3. 衝突或未指定之頻道由小至大依序分配剩餘可用整數，徹底杜絕 01, 02, 03 重複
     * 注意：台號變更嚴禁更動頻道原本的穩定 ID
     */
    private fun assignUniqueChannelNumbers(channels: List<Channel>): List<Channel> {
        val usedNumbers = mutableSetOf<Int>()
        val result = mutableListOf<Channel>()
        val unassigned = mutableListOf<Channel>()

        // 1. 先固定所有規範頻道專屬台號
        for (ch in channels) {
            val rule = CANONICAL_RULES.firstOrNull { it.canonicalId == ch.canonicalId }
            if (rule != null) {
                usedNumbers.add(rule.defaultNumber)
                result.add(ch.copy(number = rule.defaultNumber))
            } else {
                unassigned.add(ch)
            }
        }

        // 2. 處理非規範頻道：保留非衝突且大於 0 之台號
        val pendingReassign = mutableListOf<Channel>()
        for (ch in unassigned) {
            if (ch.number > 0 && !usedNumbers.contains(ch.number)) {
                usedNumbers.add(ch.number)
                result.add(ch.copy(number = ch.number))
            } else {
                pendingReassign.add(ch)
            }
        }

        // 3. 對衝突或無台號者，從 1 開始尋找最小未使用整數依序分配
        var nextAvailableNum = 1
        for (ch in pendingReassign) {
            while (usedNumbers.contains(nextAvailableNum)) {
                nextAvailableNum++
            }
            usedNumbers.add(nextAvailableNum)
            result.add(ch.copy(number = nextAvailableNum))
            nextAvailableNum++
        }

        // 依台號由小至大排序
        return result.sortedBy { it.number }
    }

    private fun sortSourcesByHealth(sources: List<StreamSource>): List<StreamSource> {
        return sources.sortedBy { src ->
            val memFail = sourceFailureCount.getOrDefault(src.url, 0)
            val srcFail = src.failureCount
            maxOf(memFail, srcFail)
        }
    }

    fun recordSourceFailure(url: String, reason: String? = null) {
        if (url.isBlank()) return
        if (reason != null) {
            prefs.edit()
                .putString("source_failure_reason_${android.net.Uri.encode(url)}", reason)
                .putLong("source_failure_time_${android.net.Uri.encode(url)}", System.currentTimeMillis())
                .apply()
        }
        val count = sourceFailureCount.getOrDefault(url, 0)
        sourceFailureCount[url] = count + 1
        updateSourceStats(url, isSuccess = false)
    }

    fun recordSourceSuccess(url: String) {
        if (url.isBlank()) return
        sourceFailureCount[url] = 0
        updateSourceStats(url, isSuccess = true)
    }

    fun getSourceLastFailure(url: String): String? =
        prefs.getString("source_failure_reason_${android.net.Uri.encode(url)}", null)

    private fun updateSourceStats(url: String, isSuccess: Boolean) {
        val currentChannels = _channels.value
        var changed = false
        val now = System.currentTimeMillis()

        val updatedChannels = currentChannels.map { ch ->
            val srcIdx = ch.streamSources.indexOfFirst { it.url == url }
            if (srcIdx != -1) {
                changed = true
                val oldSrc = ch.streamSources[srcIdx]
                val newSrc = if (isSuccess) {
                    oldSrc.copy(failureCount = 0, lastSuccessTime = now)
                } else {
                    oldSrc.copy(failureCount = oldSrc.failureCount + 1)
                }
                val newSources = ch.streamSources.toMutableList().apply { set(srcIdx, newSrc) }
                ch.copy(streamSources = newSources)
            } else {
                ch
            }
        }

        if (changed) {
            _channels.value = updatedChannels
            saveToCache(updatedChannels)
        }
    }

    private fun resolveChannelIdentity(ch: Channel): Pair<String, CanonicalChannelRule?> {
        // 1. 優先嘗試透過 canonicalId 匹配規範規則 (保留副頻明確區別)
        if (ch.canonicalId.isNotBlank()) {
            val byCanonical = CANONICAL_RULES.firstOrNull { it.canonicalId.equals(ch.canonicalId, ignoreCase = true) }
            if (byCanonical != null) {
                return Pair(byCanonical.canonicalId, byCanonical)
            }
        }

        // 2. 透過 tvgId 或頻道名稱比對規範規則
        val rule = findCanonicalRule(ch.tvgId, ch.name)
        if (rule != null) {
            return Pair(rule.canonicalId, rule)
        }

        // 3. 非規範頻道：統一身份鍵命名空間，修復 tvg_xxx 與 canon_xxx 導致同頻道分裂的問題
        val cleanTvgId = ch.tvgId?.trim()?.lowercase()
        val cleanCanonical = ch.canonicalId.trim().lowercase().removePrefix("canon_").removePrefix("tvg_")
        val cleanName = normalizeChannelKey(ch.name)

        val key = when {
            cleanCanonical.isNotBlank() -> cleanCanonical
            !cleanTvgId.isNullOrBlank() -> cleanTvgId
            else -> "name_$cleanName"
        }
        return Pair(key, null)
    }

    private fun findCanonicalRule(tvgId: String?, name: String): CanonicalChannelRule? {
        val cleanTvgId = tvgId?.trim()?.lowercase()
        if (!cleanTvgId.isNullOrBlank()) {
            val byTvg = CANONICAL_RULES.firstOrNull { rule ->
                rule.tvgIds.any { it.equals(cleanTvgId, ignoreCase = true) }
            }
            if (byTvg != null) return byTvg
        }

        val cleanName = normalizeChannelKey(name)
        if (cleanName.isBlank()) return null

        return CANONICAL_RULES.firstOrNull { rule ->
            rule.nameAliases.any { alias ->
                normalizeChannelKey(alias) == cleanName
            }
        }
    }

    private fun normalizeChannelKey(name: String): String {
        return name
            .replace(Regex("[\\s\\-_()\\[\\]（）]"), "")
            .replace("頻道", "")
            .replace("台", "")
            .lowercase()
    }

    private fun initHealthStatuses(channelList: List<Channel>) {
        val currentMap = _healthMap.value.toMutableMap()
        val now = System.currentTimeMillis()

        for (ch in channelList) {
            if (!currentMap.containsKey(ch.id)) {
                currentMap[ch.id] = ChannelHealth(
                    status = ChannelHealthStatus.UNKNOWN,
                    lastUpdatedTime = now,
                    message = "未檢查",
                    currentSourceIndex = ch.currentSourceIndex,
                    totalSources = ch.streamSources.size
                )
            }
        }
        _healthMap.value = currentMap
    }

    fun updateChannelHealth(channelId: String, status: ChannelHealthStatus, message: String? = null, sourceIndex: Int = 0, totalSources: Int = 1) {
        val currentMap = _healthMap.value.toMutableMap()
        currentMap[channelId] = ChannelHealth(
            status = status,
            lastUpdatedTime = System.currentTimeMillis(),
            message = message,
            currentSourceIndex = sourceIndex,
            totalSources = totalSources
        )
        _healthMap.value = currentMap
    }

    fun getChannelHealth(channel: Channel): ChannelHealth {
        val record = _healthMap.value[channel.id]
        if (record == null) {
            return ChannelHealth(ChannelHealthStatus.UNKNOWN, System.currentTimeMillis(), "未檢查", 0, channel.streamSources.size)
        }

        val now = System.currentTimeMillis()
        if (now - record.lastUpdatedTime > AppConfig.HEALTH_STATUS_EXPIRATION_MS) {
            return ChannelHealth(ChannelHealthStatus.UNKNOWN, now, "狀態過期", record.currentSourceIndex, record.totalSources)
        }

        return record
    }

    /**
     * 定期掃描過期狀態，將逾 15 分鐘之記錄轉為灰燈
     */
    fun scanExpiredHealthStatuses() {
        val now = System.currentTimeMillis()
        val currentMap = _healthMap.value.toMutableMap()
        var hasChanged = false

        for ((id, health) in currentMap) {
            if (health.status != ChannelHealthStatus.UNKNOWN &&
                now - health.lastUpdatedTime > AppConfig.HEALTH_STATUS_EXPIRATION_MS) {
                currentMap[id] = ChannelHealth(ChannelHealthStatus.UNKNOWN, now, "狀態過期", health.currentSourceIndex, health.totalSources)
                hasChanged = true
            }
        }

        if (hasChanged) {
            _healthMap.value = currentMap
        }
    }

    private fun downloadM3u(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", AppConfig.USER_AGENT)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readFromCache(): List<Channel> {
        // 1. 優先嘗試讀取 V2 JSON 快取
        val listV2 = parseCacheFile(cacheFileV2).filterNot { isDefunctExcludedChannel(it) }
        if (listV2.isNotEmpty()) return listV2

        // 2. 若主快取異常，嘗試讀取 .bak 備份快取
        val bakFile = File(context.filesDir, "${AppConfig.CACHE_FILE_NAME_V2}.bak")
        val listBak = parseCacheFile(bakFile).filterNot { isDefunctExcludedChannel(it) }
        if (listBak.isNotEmpty()) return listBak

        // 3. 舊版 M3U 快取遷移相容
        try {
            if (legacyCacheFile.exists() && legacyCacheFile.length() > 0) {
                val legacyChannels = legacyCacheFile.inputStream().use { stream ->
                    M3uParser.parse(stream)
                }
                if (legacyChannels.isNotEmpty()) {
                    val migrated = mergeAndDeduplicate(getBuiltInSeedChannels(), legacyChannels)
                    saveToCache(migrated)
                    try { legacyCacheFile.delete() } catch (_: Exception) {}
                    return migrated
                }
            }
        } catch (_: Exception) {}

        return emptyList()
    }

    private fun parseCacheFile(file: File): List<Channel> {
        try {
            if (file.exists() && file.length() > 0) {
                val content = file.readText(Charsets.UTF_8)
                val jsonArray = JSONArray(content)
                val list = mutableListOf<Channel>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.optJSONObject(i)
                    if (obj != null) {
                        list.add(Channel.fromJson(obj))
                    }
                }
                if (list.isNotEmpty()) return list
            }
        } catch (_: Exception) {}
        return emptyList()
    }

    /**
     * 快取採可恢復的替換流程：寫入臨時檔 -> 備份現有快取至 .bak -> 替換 -> 失敗時自動還原
     */
    private fun saveToCache(channels: List<Channel>) {
        if (channels.isEmpty()) return
        try {
            val jsonArray = JSONArray()
            for (ch in channels) {
                jsonArray.put(ch.toJson())
            }
            val tmpFile = File(context.filesDir, "${AppConfig.CACHE_FILE_NAME_V2}.tmp")
            val bakFile = File(context.filesDir, "${AppConfig.CACHE_FILE_NAME_V2}.bak")

            FileOutputStream(tmpFile).use { fos ->
                fos.write(jsonArray.toString(2).toByteArray(Charsets.UTF_8))
                fos.flush()
                try { fos.fd.sync() } catch (_: Exception) {}
            }

            if (tmpFile.exists() && tmpFile.length() > 0) {
                if (cacheFileV2.exists()) {
                    if (bakFile.exists()) bakFile.delete()
                    cacheFileV2.renameTo(bakFile)
                }
                val renamed = tmpFile.renameTo(cacheFileV2)
                if (renamed) {
                    if (bakFile.exists()) bakFile.delete()
                } else {
                    // rename 失敗時從 .bak 還原
                    if (bakFile.exists()) {
                        bakFile.renameTo(cacheFileV2)
                    }
                    tmpFile.delete()
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * 最近觀看恢復採用逐級查找：
     * 級別 1: 依已儲存的頻道 ID 查找
     * 級別 2: 依 canonicalId 查找
     * 級別 3: 依已儲存的串流 URL 查找對應頻道與來源
     * 若皆找不到才回傳 null，由呼叫端安全 fallback
     */
    fun findChannelHierarchically(channels: List<Channel>, savedId: String?, savedUrl: String?): Pair<Int, Int>? {
        if (channels.isEmpty()) return null

        // 1. 依精確頻道 ID 查找
        if (!savedId.isNullOrBlank()) {
            val idx = channels.indexOfFirst { it.id == savedId }
            if (idx != -1) {
                val ch = channels[idx]
                val srcIdx = if (!savedUrl.isNullOrBlank()) {
                    ch.streamSources.indexOfFirst { it.url == savedUrl }.let { if (it != -1) it else 0 }
                } else 0
                return Pair(idx, srcIdx)
            }

            // 2. 舊版或遷移 ID：提取可能包含的 canonicalId
            val candidateCanon = savedId.removePrefix("seed_").removePrefix("ch_")
                .substringAfterLast("_")
            if (candidateCanon.isNotBlank()) {
                val canonIdx = channels.indexOfFirst {
                    it.canonicalId.equals(candidateCanon, ignoreCase = true) || it.id.contains(candidateCanon)
                }
                if (canonIdx != -1) {
                    val ch = channels[canonIdx]
                    val srcIdx = if (!savedUrl.isNullOrBlank()) {
                        ch.streamSources.indexOfFirst { it.url == savedUrl }.let { if (it != -1) it else 0 }
                    } else 0
                    return Pair(canonIdx, srcIdx)
                }
            }
        }

        // 3. 依已儲存之 URL 反查擁有此來源的頻道與來源位置
        if (!savedUrl.isNullOrBlank()) {
            for ((chIdx, ch) in channels.withIndex()) {
                val srcIdx = ch.streamSources.indexOfFirst { it.url == savedUrl }
                if (srcIdx != -1) {
                    return Pair(chIdx, srcIdx)
                }
            }
        }

        return null
    }

    fun saveLastChannel(id: String, url: String) {
        prefs.edit()
            .putString(AppConfig.PREF_LAST_CHANNEL_ID, id)
            .putString(AppConfig.PREF_LAST_CHANNEL_URL, url)
            .apply()
    }

    fun getLastChannelId(): String? = prefs.getString(AppConfig.PREF_LAST_CHANNEL_ID, null)
    fun getLastChannelUrl(): String? = prefs.getString(AppConfig.PREF_LAST_CHANNEL_URL, null)

    fun saveTheme(themeMode: Int) = prefs.edit().putInt(AppConfig.PREF_APP_THEME, themeMode).apply()
    fun getSavedTheme(): Int = prefs.getInt(AppConfig.PREF_APP_THEME, AppConfig.THEME_SYSTEM)

    fun saveQualityPref(pref: Int) = prefs.edit().putInt(AppConfig.PREF_QUALITY_PREF, pref).apply()
    fun getSavedQualityPref(): Int = prefs.getInt(AppConfig.PREF_QUALITY_PREF, AppConfig.QUALITY_AUTO)

    private fun saveSourceUpdateInfo(time: String, status: String, count: Int, totalSources: Int) {
        prefs.edit()
            .putString(AppConfig.PREF_LAST_UPDATE_TIME, time)
            .putString(AppConfig.PREF_SOURCE_STATUS, status)
            .putInt(AppConfig.PREF_CHANNELS_COUNT, count)
            .putInt(AppConfig.PREF_TOTAL_SOURCES_COUNT, totalSources)
            .apply()
    }

    fun isAutoFallbackEnabled(): Boolean = prefs.getBoolean(AppConfig.PREF_AUTO_FALLBACK_ENABLED, false)
    fun setAutoFallbackEnabled(enabled: Boolean) = prefs.edit().putBoolean(AppConfig.PREF_AUTO_FALLBACK_ENABLED, enabled).apply()

    fun getUserSelectedSource(channelKey: String): String? {
        return prefs.getString("${AppConfig.PREF_USER_SELECTED_SOURCE_PREFIX}$channelKey", null)
    }

    fun saveUserSelectedSource(channelKey: String, sourceId: String) {
        prefs.edit().putString("${AppConfig.PREF_USER_SELECTED_SOURCE_PREFIX}$channelKey", sourceId).apply()
    }

    /**
     * 套用使用者手動選定之線路 (手動主導模式：不因排序或清單刷新而自動擅改線路)
     */
    fun applyUserSelectedSources(channels: List<Channel>): List<Channel> {
        return channels.map { ch ->
            val savedSourceId = getUserSelectedSource(ch.id) ?: getUserSelectedSource(ch.canonicalId)
            if (savedSourceId != null) {
                val idx = ch.streamSources.indexOfFirst { it.sourceId == savedSourceId }
                if (idx != -1) {
                    ch.withSourceIndex(idx)
                } else {
                    ch
                }
            } else {
                ch
            }
        }
    }

    fun getLastUpdateTime(): String = prefs.getString(AppConfig.PREF_LAST_UPDATE_TIME, "尚未更新") ?: "尚未更新"
    fun getSourceStatus(): String = prefs.getString(AppConfig.PREF_SOURCE_STATUS, "使用預置種子清單") ?: "使用預置種子清單"
    fun getChannelsCount(): Int = prefs.getInt(AppConfig.PREF_CHANNELS_COUNT, _channels.value.size)
    fun getTotalSourcesCount(): Int = prefs.getInt(
        AppConfig.PREF_TOTAL_SOURCES_COUNT,
        _channels.value.sumOf { it.streamSources.size }
    )

    /**
     * 內建預置種子頻道 (台灣各大電視台穩定串流 + 官方 YouTube 24h 直播源)
     * 東森新聞 (CH 51)、民視新聞 (CH 53)、TVBS新聞 (CH 55) 等均具備雙來源備援
     */
    private fun getBuiltInSeedChannels(): List<Channel> {
        val seeds = listOf(
            Channel(
                id = "seed_daai_1",
                canonicalId = "daai_1",
                number = 9,
                name = "大愛電視1台",
                group = "宗教文化",
                streamSources = listOf(
                    StreamSource(
                        url = "https://www.youtube.com/@DaAiWorldNews/live",
                        type = StreamType.YOUTUBE,
                        youtubeVideoId = "S-IRquSID3g",
                        liveChannelHandle = "@DaAiWorldNews",
                        label = "YouTube官方 24H",
                        tvgId = "DaAi1.tw",
                        originLabel = "YouTube官方直播"
                    )
                ),
                tvgId = "DaAi1.tw"
            ),
            Channel(
                id = "seed_ctv_news",
                canonicalId = "ctv_news",
                number = 10,
                name = "中視新聞台",
                group = "新聞時事",
                streamSources = listOf(
                    StreamSource(
                        url = "https://www.youtube.com/@chinatv_news/live",
                        type = StreamType.YOUTUBE,
                        youtubeVideoId = "5n0y6b0Q25o",
                        liveChannelHandle = "@chinatv_news",
                        label = "YouTube官方 24H",
                        tvgId = "CTVNews.tw",
                        originLabel = "YouTube官方直播"
                    )
                ),
                tvgId = "CTVNews.tw"
            ),
            Channel(
                id = "seed_ntd_news",
                canonicalId = "ntd_news",
                number = 12,
                name = "新唐人亞太台",
                group = "新聞時事",
                streamSources = listOf(
                    StreamSource(
                        url = "https://www.youtube.com/@NTDAPTV/live",
                        type = StreamType.YOUTUBE,
                        youtubeVideoId = "Bhp_V7H5QoY",
                        liveChannelHandle = "@NTDAPTV",
                        label = "YouTube官方 24H",
                        tvgId = "NTDAPTV.tw",
                        originLabel = "YouTube官方直播"
                    )
                ),
                tvgId = "NTDAPTV.tw"
            ),
            Channel(
                id = "seed_pts_news",
                canonicalId = "pts_news",
                number = 13,
                name = "公視新聞網",
                group = "公共電視",
                streamSources = listOf(
                    StreamSource(
                        url = "https://www.youtube.com/@PNNPTS/live",
                        type = StreamType.YOUTUBE,
                        youtubeVideoId = "quwqlazU-c8",
                        liveChannelHandle = "@PNNPTS",
                        label = "YouTube官方 24H",
                        tvgId = "PNNPTS.tw",
                        originLabel = "YouTube官方直播"
                    )
                ),
                tvgId = "PNNPTS.tw"
            ),
            Channel(
                id = "seed_ttv_news",
                canonicalId = "ttv_news",
                number = 15,
                name = "台視新聞台",
                group = "新聞時事",
                streamSources = listOf(
                    StreamSource(
                        url = "https://www.youtube.com/@ttvnews_taiwan/live",
                        type = StreamType.YOUTUBE,
                        youtubeVideoId = "pF507BLtbqU",
                        liveChannelHandle = "@ttvnews_taiwan",
                        label = "YouTube官方 24H",
                        tvgId = "TTVNews.tw",
                        originLabel = "YouTube官方直播"
                    )
                ),
                tvgId = "TTVNews.tw"
            ),
            Channel(
                id = "seed_titv",
                canonicalId = "titv",
                number = 16,
                name = "原住民族電視台",
                group = "文化族群",
                streamSources = listOf(
                    StreamSource(
                        url = "http://streamipcf.akamaized.net/live/_definst_/smil:liveabr.smil/playlist.m3u8",
                        type = StreamType.HLS,
                        label = "1080P",
                        tvgId = "IndigenousTV.tw",
                        originLabel = "Akamai CDN"
                    )
                ),
                tvgId = "IndigenousTV.tw"
            ),
            Channel(
                id = "seed_momo_shop2",
                canonicalId = "momo_shop2",
                number = 35,
                name = "momo購物2台",
                group = "生活購物",
                streamSources = listOf(
                    StreamSource(
                        url = "https://www.youtube.com/@momoch3571/live",
                        type = StreamType.YOUTUBE,
                        liveChannelHandle = "@momoch3571",
                        label = "YouTube官方 24H",
                        tvgId = "Momo2.tw",
                        originLabel = "YouTube官方直播"
                    )
                ),
                tvgId = "Momo2.tw"
            ),
            Channel(
                id = "seed_taiwan_plus",
                canonicalId = "taiwan_plus",
                number = 38,
                name = "TaiwanPlus",
                group = "國際新聞",
                streamSources = listOf(
                    StreamSource(
                        url = "https://www.youtube.com/@TaiwanPlusNews/live",
                        type = StreamType.YOUTUBE,
                        youtubeVideoId = "nT__fkHXsvE",
                        liveChannelHandle = "@TaiwanPlusNews",
                        label = "YouTube官方 24H",
                        tvgId = "TaiwanPlus.tw",
                        originLabel = "YouTube官方直播"
                    )
                ),
                tvgId = "TaiwanPlus.tw"
            ),
            Channel(
                id = "seed_ebc_shop",
                canonicalId = "ebc_shop",
                number = 47,
                name = "東森購物台",
                group = "生活購物",
                streamSources = listOf(
                    StreamSource(
                        url = "https://www.youtube.com/@HotsaleTV/live",
                        type = StreamType.YOUTUBE,
                        liveChannelHandle = "@HotsaleTV",
                        label = "YouTube官方 24H",
                        tvgId = "EBCShop.tw",
                        originLabel = "YouTube官方直播"
                    )
                ),
                tvgId = "EBCShop.tw"
            ),
            Channel(
                id = "seed_ebc_news",
                canonicalId = "ebc_news",
                number = 51,
                name = "東森新聞台",
                group = "新聞時事",
                streamSources = listOf(
                    StreamSource(
                        url = "https://www.youtube.com/@newsebc/live",
                        type = StreamType.YOUTUBE,
                        youtubeVideoId = "V1p33hqPrUk",
                        liveChannelHandle = "@newsebc",
                        label = "官方YouTube 24H",
                        tvgId = "EBCNews.tw",
                        originLabel = "YouTube官方直播"
                    )
                ),
                tvgId = "EBCNews.tw"
            ),
            Channel(
                id = "seed_cts_news",
                canonicalId = "cts_news",
                number = 52,
                name = "華視新聞資訊台",
                group = "新聞時事",
                streamSources = listOf(
                    StreamSource(
                        url = "https://www.youtube.com/@CtsTw/live",
                        type = StreamType.YOUTUBE,
                        youtubeVideoId = "KMMHw8rllic",
                        liveChannelHandle = "@CtsTw",
                        label = "官方YouTube 24H",
                        tvgId = "CTSNews.tw",
                        originLabel = "YouTube官方直播"
                    )
                ),
                tvgId = "CTSNews.tw"
            ),
            Channel(
                id = "seed_ftv_news",
                canonicalId = "ftv_news",
                number = 53,
                name = "民視新聞台",
                group = "新聞時事",
                streamSources = listOf(
                    StreamSource(
                        url = "https://www.youtube.com/@FTV_News/live",
                        type = StreamType.YOUTUBE,
                        youtubeVideoId = "iGMj2iyL--s",
                        liveChannelHandle = "@FTV_News",
                        label = "官方YouTube 24H",
                        tvgId = "FTVNews.tw",
                        originLabel = "YouTube官方直播"
                    )
                ),
                tvgId = "FTVNews.tw"
            ),
            Channel(
                id = "seed_set_news",
                canonicalId = "set_news",
                number = 54,
                name = "三立新聞台",
                group = "新聞時事",
                streamSources = listOf(
                    StreamSource(
                        url = "https://www.youtube.com/@setn/live",
                        type = StreamType.YOUTUBE,
                        youtubeVideoId = "qGySalbSJfU",
                        liveChannelHandle = "@setn",
                        label = "官方YouTube 24H",
                        provider = "seed",
                        tvgId = "SETNews.tw",
                        originLabel = "YouTube官方直播"
                    )
                ),
                tvgId = "SETNews.tw"
            ),
            Channel(
                id = "seed_tvbs_news",
                canonicalId = "tvbs_news",
                number = 55,
                name = "TVBS新聞台",
                group = "新聞時事",
                streamSources = listOf(
                    StreamSource(
                        url = "https://www.youtube.com/@TVBSNEWS01/live",
                        type = StreamType.YOUTUBE,
                        youtubeVideoId = "oM3M7yuwNn8",
                        liveChannelHandle = "@TVBSNEWS01",
                        label = "YouTube官方 24H",
                        tvgId = "TVBSNews.tw",
                        originLabel = "YouTube官方直播"
                    )
                ),
                tvgId = "TVBSNews.tw"
            ),
            Channel(
                id = "seed_cti_news",
                canonicalId = "cti_news",
                number = 56,
                name = "中天新聞台",
                group = "新聞時事",
                streamSources = listOf(
                    StreamSource(
                        url = "https://www.youtube.com/@ctitvnews/live",
                        type = StreamType.YOUTUBE,
                        youtubeVideoId = "-rP6YXm-Xb8",
                        liveChannelHandle = "@ctitvnews",
                        label = "官方YouTube 24H",
                        tvgId = "CTiNews.tw",
                        originLabel = "YouTube官方直播"
                    )
                ),
                tvgId = "CTiNews.tw"
            ),
            Channel(
                id = "seed_ebc_fin_news",
                canonicalId = "ebc_fin_news",
                number = 57,
                name = "東森財經新聞台",
                group = "財經新聞",
                streamSources = listOf(
                    StreamSource(
                        url = "https://www.youtube.com/@57ETFN/live",
                        type = StreamType.YOUTUBE,
                        youtubeVideoId = "1I2iq41Akmo",
                        liveChannelHandle = "@57ETFN",
                        label = "官方YouTube 24H",
                        tvgId = "EBCFinancialNews.tw",
                        originLabel = "YouTube官方直播"
                    )
                ),
                tvgId = "EBCFinancialNews.tw"
            ),
            Channel(
                id = "seed_muse_anime",
                canonicalId = "muse_anime",
                number = 70,
                name = "Muse木棉花動漫",
                group = "卡通動漫",
                streamSources = listOf(
                    StreamSource(
                        url = "https://www.youtube.com/@MuseTW/live",
                        type = StreamType.YOUTUBE,
                        youtubeVideoId = "JjtDSypFLDk",
                        liveChannelHandle = "@MuseTW",
                        label = "官方YouTube 24H",
                        tvgId = "MuseAnime.tw",
                        originLabel = "YouTube官方直播"
                    )
                ),
                tvgId = "MuseAnime.tw"
            ),
            Channel(
                id = "seed_mirror_news",
                canonicalId = "mirror_news",
                number = 86,
                name = "鏡電視新聞台",
                group = "新聞時事",
                streamSources = listOf(
                    StreamSource(
                        url = "https://www.youtube.com/@mlive-tw/live",
                        type = StreamType.YOUTUBE,
                        youtubeVideoId = "vylit7WPgeA",
                        liveChannelHandle = "@mlive-tw",
                        label = "官方YouTube 24H",
                        tvgId = "MirrorNews.tw",
                        originLabel = "YouTube官方直播"
                    )
                ),
                tvgId = "MirrorNews.tw"
            ),
            Channel(
                id = "seed_set_inews",
                canonicalId = "set_inews",
                number = 88,
                name = "三立財經iNEWS",
                group = "財經新聞",
                streamSources = listOf(
                    StreamSource(
                        url = "https://www.youtube.com/@setinews/live",
                        type = StreamType.YOUTUBE,
                        youtubeVideoId = "CKjS4477pnU",
                        liveChannelHandle = "@setinews",
                        label = "官方YouTube 24H",
                        tvgId = "SETiNews.tw",
                        originLabel = "YouTube官方直播"
                    )
                ),
                tvgId = "SETiNews.tw"
            ),
            Channel(
                id = "seed_rock_music",
                canonicalId = "rock_music",
                number = 90,
                name = "滾石音樂24H",
                group = "音樂娛樂",
                streamSources = listOf(
                    StreamSource(
                        url = "https://www.youtube.com/@rockrecords/live",
                        type = StreamType.YOUTUBE,
                        youtubeVideoId = "JtrfTWBfrvQ",
                        liveChannelHandle = "@rockrecords",
                        label = "官方YouTube 24H",
                        tvgId = "RockMusic.tw",
                        originLabel = "YouTube官方直播"
                    )
                ),
                tvgId = "RockMusic.tw"
            )
        )
        val ofiiiPages = mapOf(
            "ctv_news" to "4gtv-4gtv074",
            "cts_main" to "4gtv-4gtv041",
            "cts_news" to "4gtv-4gtv052",
            "mirror_news" to "mnews",
            "set_inews" to "iNEWS",
            "cti_news" to "4gtv-4gtv009"
        )
        val augmented = seeds.map { channel ->
            val page = ofiiiPages[channel.canonicalId]
            if (page == null) channel else channel.copy(
                streamSources = channel.streamSources + StreamSource(
                    url = "https://www.ofiii.com/channel/watch/$page",
                    type = StreamType.WEB_VIEW,
                    label = "ofiii 官方網頁",
                    provider = "ofiii",
                    originLabel = "ofiii 官方觀看頁"
                )
            )
        }
        fun ofiiiChannel(key: String, number: Int, name: String, group: String, page: String) = Channel(
            id = "seed_$key", canonicalId = key, number = number, name = name, group = group,
            streamSources = listOf(StreamSource(
                url = "https://www.ofiii.com/channel/watch/$page",
                type = StreamType.WEB_VIEW, label = "ofiii 官方網頁",
                provider = "ofiii", originLabel = "ofiii 官方觀看頁"
            ))
        )
        val allSeeds = augmented + listOf(
            Channel(
                id = "seed_ctv_main", canonicalId = "ctv_main", number = 11,
                name = "中視主頻", group = "無線綜合",
                streamSources = listOf(StreamSource(
                    url = "https://www.ofiii.com/channel/watch/4gtv-4gtv040",
                    type = StreamType.WEB_VIEW, label = "ofiii 官方網頁",
                    provider = "ofiii", originLabel = "ofiii 官方觀看頁"
                )), tvgId = "CTV.tw"
            ),
            Channel(
                id = "seed_global_taiwan", canonicalId = "global_taiwan", number = 62,
                name = "寰宇新聞台灣台", group = "新聞時事",
                streamSources = listOf(StreamSource(
                    url = "https://www.ofiii.com/channel/watch/4gtv-4gtv156",
                    type = StreamType.WEB_VIEW, label = "ofiii 官方網頁",
                    provider = "ofiii", originLabel = "ofiii 官方觀看頁"
                ))
            ),
            ofiiiChannel("global_finance", 57, "寰宇財經台", "新聞時事", "4gtv-4gtv158"),
            ofiiiChannel("ofiii_finance", 64, "第1財經", "新聞時事", "ofiii64"),
            ofiiiChannel("longhua_movie", 71, "龍華電影台", "電影", "litv-xinchuang03"),
            ofiiiChannel("longhua_classic", 69, "龍華經典台", "電影", "litv-xinchuang21"),
            ofiiiChannel("longhua_foreign", 72, "龍華洋片台", "電影", "litv-xinchuang02"),
            ofiiiChannel("ofiii_stephen_chow", 73, "周星馳台", "電影", "ofiii73"),
            ofiiiChannel("ofiii_movies", 74, "歐飛電影台", "電影", "ofiii74"),
            ofiiiChannel("ofiii_golden_fans", 76, "Golden影迷台", "電影", "ofiii76"),
            ofiiiChannel("longhua_drama", 40, "龍華戲劇台", "戲劇", "litv-xinchuang18"),
            ofiiiChannel("longhua_japan_korea", 46, "龍華日韓台", "戲劇", "litv-xinchuang11"),
            ofiiiChannel("ofiii_focus_drama", 48, "Focus 風采戲劇台", "戲劇", "ofiii1048"),
            ofiiiChannel("ofiii_doraemon", 22, "哆啦Ａ夢台", "卡通兒童", "ofiii22"),
            ofiiiChannel("ofiii_ebc_entertainment", 32, "東森娛樂台", "綜藝娛樂", "ofiii32"),
            ofiiiChannel("ofiii_concert", 91, "演唱會直擊", "綜藝娛樂", "ofiii91"),
            ofiiiChannel("ofiii_freeman", 95, "Freeman @台灣", "生活旅遊", "ofiii95"),
            ofiiiChannel("ofiii_food", 96, "饕客揪愛吃", "生活旅遊", "ofiii96"),
            ofiiiChannel("asia_travel", 34, "亞洲旅遊台", "生活旅遊", "4gtv-4gtv076"),
            ofiiiChannel("goodtv_1", 15, "好消息", "宗教人文", "litv-ftv16"),
            ofiiiChannel("goodtv_2", 14, "好消息2台", "宗教人文", "litv-ftv17"),
            ofiiiChannel("ofiii_studio_classroom", 16, "空中英語教室HD頻道", "生活學習", "ofiii16"),
            ofiiiChannel("smart_knowledge", 37, "Smart知識台", "生活學習", "litv-xinchuang19"),
            ofiiiChannel("ofiii_focus_knowledge", 82, "Focus探索新知台", "生活學習", "ofiii82")
        )
        return allSeeds
    }

    private fun channelsToM3u(channels: List<Channel>): String {
        val sb = StringBuilder("#EXTM3U\n")
        for (ch in channels) {
            for (src in ch.streamSources) {
                sb.append("#EXTINF:-1 tvg-chno=\"${ch.number}\" group-title=\"${ch.group}\",${ch.name}\n")
                sb.append("${src.url}\n")
            }
        }
        return sb.toString()
    }
}

internal fun parseYouTubeLiveResponse(body: String, redirectedId: String?, handle: String): String? {
    return try {
        val marker = Regex("""(?:var\s+)?ytInitialPlayerResponse\s*=\s*\{""").find(body)
            ?: return null
        val start = body.indexOf('{', marker.range.first)
        var depth = 0
        var quoted = false
        var escaped = false
        var end = -1
        for (i in start until body.length) {
            val c = body[i]
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { end = i + 1; break } }
            }
        }
        if (end == -1) return null
        val player = JSONObject(body.substring(start, end))
        val details = player.optJSONObject("videoDetails") ?: return null
        val microformat = player.optJSONObject("microformat")?.optJSONObject("playerMicroformatRenderer")
        val live = microformat?.optJSONObject("liveBroadcastDetails")
        val id = details.optString("videoId")
        val ownerHandle = microformat?.optString("ownerProfileUrl")
            ?.removePrefix("https://www.youtube.com/")?.trimEnd('/')
        val requestedHandle = java.net.URLDecoder.decode(handle, "UTF-8")
        val ownerMatches = ownerHandle != null && java.net.URLDecoder.decode(ownerHandle, "UTF-8")
            .equals(requestedHandle, ignoreCase = true)
        if (!Regex("[A-Za-z0-9_-]{11}").matches(id) || live?.optBoolean("isLiveNow") != true ||
            player.optJSONObject("playabilityStatus")?.optString("status") != "OK" ||
            !(redirectedId == id || ownerMatches)) return null
        id
    } catch (_: Exception) {
        null
    }
}
