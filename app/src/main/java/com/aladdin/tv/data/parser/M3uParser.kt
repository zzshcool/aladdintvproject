package com.aladdin.tv.data.parser

import com.aladdin.tv.data.model.Channel
import com.aladdin.tv.data.model.StreamSource
import com.aladdin.tv.data.model.StreamType
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringReader
import java.util.regex.Pattern

/**
 * 高容錯 M3U / M3U8 直播源解析器
 * 針對各類不規範的 IPTV 列表進行極致容錯處理，自動補齊台號、提取分組與圖示
 * 支援識別 YouTube 官方直播位址與畫質標籤
 */
object M3uParser {

    private val EXTINF_TAG_PATTERN = Pattern.compile("^#EXTINF:?(-?\\d*)?(.*)", Pattern.CASE_INSENSITIVE)
    private val ATTRIBUTE_PATTERN = Pattern.compile("([a-zA-Z0-9_-]+)=\"([^\"]*)\"")
    private val YOUTUBE_VIDEO_ID_PATTERN = Pattern.compile("(?:v=|/live/|youtu\\.be/)([a-zA-Z0-9_-]{11})")
    private val QUALITY_LABEL_PATTERN = Pattern.compile("\\((1080p|720p|480p|2160p|4k)\\)", Pattern.CASE_INSENSITIVE)

    fun parse(content: String): List<Channel> {
        return parse(StringReader(content))
    }

    fun parse(inputStream: InputStream): List<Channel> {
        return parse(InputStreamReader(inputStream, Charsets.UTF_8))
    }

    private fun parse(reader: java.io.Reader): List<Channel> {
        val channels = mutableListOf<Channel>()
        val bufferedReader = BufferedReader(reader)

        var currentChno: Int? = null
        var currentName: String? = null
        var currentGroup: String? = null
        var currentLogo: String? = null
        var currentTvgId: String? = null
        var autoIncrementIndex = 1

        try {
            bufferedReader.forEachLine { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty()) return@forEachLine

                if (line.startsWith("#EXTINF", ignoreCase = true)) {
                    val extInfResult = parseExtInfLine(line)
                    currentChno = extInfResult.chno
                    currentName = extInfResult.name
                    currentGroup = extInfResult.group
                    currentLogo = extInfResult.logo
                    currentTvgId = extInfResult.tvgId
                } else if (!line.startsWith("#")) {
                    if (isValidStreamUrl(line)) {
                        val finalNumber = currentChno ?: autoIncrementIndex
                        val rawTitle = currentName ?: "頻道 $finalNumber"
                        val isGeoBlocked = rawTitle.contains("[Geo-blocked]", ignoreCase = true)
                        val isPartTime = rawTitle.contains("[Not 24/7]", ignoreCase = true) ||
                                rawTitle.contains("Not 24/7", ignoreCase = true)
                        val qualityLabel = extractQualityLabel(rawTitle)
                        val cleanedName = cleanChannelName(rawTitle)
                        val finalGroup = if (currentGroup.isNullOrBlank()) "台灣綜合" else currentGroup!!

                        val (streamType, youtubeId) = detectStreamType(line)
                        val stableId = generateStableId(currentTvgId, finalNumber, cleanedName)

                        val streamSource = StreamSource(
                            url = line,
                            type = streamType,
                            youtubeVideoId = youtubeId,
                            liveChannelHandle = youtubeHandle(line),
                            label = qualityLabel,
                            tvgId = currentTvgId,
                            isGeoBlocked = isGeoBlocked,
                            isPartTime = isPartTime
                        )

                        channels.add(
                            Channel(
                                id = stableId,
                                canonicalId = currentTvgId ?: "",
                                number = finalNumber,
                                name = cleanedName,
                                group = finalGroup,
                                streamSources = listOf(streamSource),
                                currentSourceIndex = 0,
                                logoUrl = currentLogo,
                                tvgId = currentTvgId,
                                isGeoBlocked = isGeoBlocked,
                                isPartTime = isPartTime
                            )
                        )
                        autoIncrementIndex++
                    }
                    currentChno = null
                    currentName = null
                    currentGroup = null
                    currentLogo = null
                    currentTvgId = null
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                bufferedReader.close()
            } catch (_: Exception) {}
        }

        return channels
    }

    private fun parseExtInfLine(line: String): ExtInfResult {
        var chno: Int? = null
        var group: String? = null
        var logo: String? = null
        var name: String? = null
        var tvgId: String? = null

        val matcher = EXTINF_TAG_PATTERN.matcher(line)
        if (matcher.find()) {
            val content = matcher.group(2) ?: ""
            var quoted = false
            val commaIndex = content.indexOfFirst { char ->
                if (char == '"') quoted = !quoted
                char == ',' && !quoted
            }

            val attributesPart = if (commaIndex != -1) content.substring(0, commaIndex) else content
            val titlePart = if (commaIndex != -1) content.substring(commaIndex + 1).trim() else ""

            val attrMatcher = ATTRIBUTE_PATTERN.matcher(attributesPart)
            while (attrMatcher.find()) {
                val key = attrMatcher.group(1)?.lowercase() ?: continue
                val value = attrMatcher.group(2) ?: continue

                when (key) {
                    "tvg-id" -> {
                        if (value.isNotBlank()) tvgId = value
                    }
                    "tvg-chno", "channel-id" -> {
                        chno = value.toIntOrNull()
                    }
                    "group-title" -> {
                        group = value
                    }
                    "tvg-logo" -> {
                        logo = value
                    }
                    "tvg-name" -> {
                        if (name == null) name = value
                    }
                }
            }

            if (titlePart.isNotBlank()) {
                name = titlePart
            }

            if (chno == null && !name.isNullOrBlank()) {
                val prefixDigits = name.takeWhile { it.isDigit() }
                if (prefixDigits.isNotEmpty()) {
                    chno = prefixDigits.toIntOrNull()
                }
            }
        }

        return ExtInfResult(chno, name, group, logo, tvgId)
    }

    private fun youtubeUri(url: String): java.net.URI? {
        val uri = try { java.net.URI(url) } catch (_: java.net.URISyntaxException) { return null }
        val host = uri.host?.lowercase() ?: return null
        return uri.takeIf {
            it.scheme in listOf("https", "http") &&
                (host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be")
        }
    }

    private fun youtubeHandle(url: String): String? {
        val uri = youtubeUri(url) ?: return null
        val segments = uri.path.trim('/').split('/')
        return segments.firstOrNull()?.takeIf {
            it.startsWith("@") && it.length > 1 && segments.getOrNull(1) == "live"
        }
    }

    fun detectStreamType(url: String): Pair<StreamType, String?> {
        val uri = youtubeUri(url) ?: return StreamType.HLS to null
        val segments = uri.path.trim('/').split('/')
        val id = when {
            uri.host.equals("youtu.be", true) -> segments.firstOrNull()
            segments.firstOrNull() in listOf("live", "embed", "shorts") -> segments.getOrNull(1)
            else -> uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("v=") }?.substringAfter('=')
        }?.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) }
        return StreamType.YOUTUBE to id
    }

    private fun extractQualityLabel(name: String): String? {
        val matcher = QUALITY_LABEL_PATTERN.matcher(name)
        return if (matcher.find()) matcher.group(1)?.uppercase() else null
    }

    private fun cleanChannelName(rawName: String): String {
        val cleaned = rawName
            .replace("[Geo-blocked]", "")
            .replace("[Not 24/7]", "")
            .replace(QUALITY_LABEL_PATTERN.toRegex(), "")
            .trim()
        return if (cleaned.isBlank()) rawName.trim() else cleaned
    }

    private fun generateStableId(tvgId: String?, number: Int, name: String): String {
        if (!tvgId.isNullOrBlank()) {
            return "ch_tvg_${tvgId.trim().lowercase().replace(".", "_")}"
        }
        val norm = name.replace(" ", "")
            .replace("-", "")
            .replace("_", "")
            .lowercase()
        return "ch_${number}_${norm}"
    }

    private val BLOCKED_STREAM_DOMAINS = listOf(
        "sason.top",
        "ioapk.com",
        "stream.nexttv.com.tw"
    )

    private fun isValidStreamUrl(url: String): Boolean {
        val lower = url.lowercase().trim()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        if (BLOCKED_STREAM_DOMAINS.any { lower.contains(it) }) return false
        return true
    }

    private data class ExtInfResult(
        val chno: Int?,
        val name: String?,
        val group: String?,
        val logo: String?,
        val tvgId: String?
    )
}
