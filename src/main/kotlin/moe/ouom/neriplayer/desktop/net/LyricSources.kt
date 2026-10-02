package moe.ouom.neriplayer.desktop.net

import moe.ouom.neriplayer.desktop.core.MediaSource
import moe.ouom.neriplayer.desktop.core.Song
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 可选的歌词来源。
 *
 * [AUTO] 表示不额外指定 —— 走「本地 .lrc → 内嵌标签 → 歌曲所属平台的在线接口」。
 * 选其它值时，会**先**去该来源查一次（只对在线歌曲生效，本地歌曲仍以自己的 .lrc 为准）。
 *
 * 上游 Android 版还有「QQ 音乐」一项，桌面版没有移植：它的歌词接口要把请求参数
 * 包成一层签名过的 JSON（`u.y.qq.com/cgi-bin/musicu.fcg`），收益不抵复杂度，
 * 而网易云 / 酷狗 / LRCLIB / AMLL 已经覆盖了绝大多数曲目。
 */
enum class LyricSource(val id: String, val displayName: String) {
    AUTO("AUTO", "自动（跟随歌曲来源）"),
    NETEASE("NETEASE", "网易云"),
    KUGOU("KUGOU", "酷狗"),
    LRCLIB("LRCLIB", "LRCLIB"),
    AMLL("AMLL", "AMLL TTML DB");

    companion object {
        val selectable: List<LyricSource> = listOf(AUTO, NETEASE, KUGOU, LRCLIB, AMLL)

        /** 兼容上游 Android 版的 storageValue 写法。 */
        private val aliases: Map<String, LyricSource> = mapOf(
            "automatic" to AUTO,
            "cloud_music" to NETEASE,
            "cloudmusic" to NETEASE,
            "kugou_music" to KUGOU,
            "lrclib_net" to LRCLIB,
            "amll_ttml" to AMLL,
            "amll_ttml_client" to AMLL,
        )

        fun of(id: String?): LyricSource {
            val key = id?.trim()?.lowercase() ?: return AUTO
            aliases[key]?.let { return it }
            return selectable.firstOrNull { it.id.lowercase() == key } ?: AUTO
        }
    }
}

/** 一次歌词查询的结果：原文 + 翻译 + 音译。 */
data class LyricQueryResult(
    val raw: String,
    val translated: String? = null,
    val romanized: String? = null,
    val source: String,
)

/**
 * 时长匹配容差，与上游 Android 版一致：
 * `max(7000ms, 期望时长 × 6%)`，上限 15 秒。
 *
 * 平台给的时长常有几秒出入（尤其是 B 站搬运转载），卡太死会把正确结果筛掉。
 */
private fun durationToleranceMs(expectedMs: Long): Long =
    min(15_000L, max(7_000L, expectedMs * 6L / 100L))

/** 拿不到任一侧时长时不作为否决条件，否则会把「时长缺失」的候选全部丢掉。 */
private fun durationMatches(expectedMs: Long, candidateMs: Long): Boolean {
    if (expectedMs <= 0L || candidateMs <= 0L) return true
    return abs(expectedMs - candidateMs) <= durationToleranceMs(expectedMs)
}

/** 去掉常见后缀与括号内容，留下可用于比对的核心标题。 */
private fun normalizeTitle(value: String): String =
    value.lowercase()
        .replace(Regex("\\([^)]*\\)|（[^）]*）|\\[[^]]*]|【[^】]*】"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

/**
 * LRCLIB：开放歌词库（<https://lrclib.net>）。
 *
 * 先 `api/get` 做「标题 + 歌手 + 时长」精确匹配；没命中再 `api/search` 模糊搜索，
 * 按时长挑最接近的一条。只提供原文与同步时间轴，没有翻译 / 音译。
 */
class LrclibProvider(private val http: HttpService) {

    private val headers = mapOf(
        "User-Agent" to "NeriPlayer/1.4.7 (https://github.com/Asternent/neriplayerforwindows)",
    )

    suspend fun fetch(song: Song): LyricQueryResult? {
        val title = song.title.ifBlank { return null }
        val artist = primaryArtist(song.artist)

        if (song.durationMs > 0L) {
            exact(title, artist, song.durationMs)?.let { return it }
        }
        return search(title, artist, song.durationMs)
    }

    private fun exact(title: String, artist: String, durationMs: Long): LyricQueryResult? {
        val url = "https://lrclib.net/api/get?track_name=${urlEncode(title)}" +
            "&artist_name=${urlEncode(artist)}&duration=${durationMs / 1000L}"
        val root = NeriJsonParser.parse(http.get(url, headers) ?: return null).asObject() ?: return null
        val hours = ((root.str("duration")?.toDoubleOrNull() ?: 0.0) * 1000).toLong()
        if (!durationMatches(durationMs, hours)) return null
        if (!titleMatches(title, root.str("trackName").orEmpty())) return null
        // 只要带时间轴的：纯文本歌词在逐行高亮的歌词页里没法用，
        // 这种情况交给 search 再找一个同步版本，找不到就回落到内置来源。
        val raw = root.str("syncedLyrics")?.takeIf { it.isNotBlank() } ?: return null
        return LyricQueryResult(raw = raw, source = "LRCLIB")
    }

    private fun search(title: String, artist: String, durationMs: Long): LyricQueryResult? {
        val items = NeriJsonParser.parse(http.get("https://lrclib.net/api/search?q=${urlEncode("$title $artist")}", headers) ?: return null)
            .let { element -> (element as? kotlinx.serialization.json.JsonArray)?.objects().orEmpty() }
        if (items.isEmpty()) return null
        val best = items
            .filter { it.str("syncedLyrics")?.isNotBlank() == true }
            .filter { titleMatches(title, it.str("trackName").orEmpty()) }
            .filter {
                durationMatches(durationMs, ((it.str("duration")?.toDoubleOrNull() ?: 0.0) * 1000).toLong())
            }
            .minByOrNull { candidate ->
                val candidateMs = ((candidate.str("duration")?.toDoubleOrNull() ?: 0.0) * 1000).toLong()
                if (durationMs > 0L && candidateMs > 0L) abs(durationMs - candidateMs) else Long.MAX_VALUE / 2
            } ?: return null
        val raw = best.str("syncedLyrics")?.takeIf { it.isNotBlank() } ?: return null
        return LyricQueryResult(raw = raw, source = "LRCLIB")
    }

    private fun titleMatches(expected: String, candidate: String): Boolean {
        val a = normalizeTitle(expected)
        val b = normalizeTitle(candidate)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.contains(b) || b.contains(a)
    }
}

/**
 * 酷狗歌词：`lyrics.kugou.com/search` 搜候选 → `lyrics.kugou.com/download` 取 LRC。
 *
 * 上游 Android 版会先经过 `mobilecdn.kugou.com` 拿 hash 再搜，这里直接用
 * 「标题 + 歌手 + 时长」查询歌词库，少一次请求（已实测可用）。
 * 下载接口返回 base64 编码的 LRC 文本，需要解码一次。
 */
class KugouProvider(private val http: HttpService) {

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) NeriPlayer",
        "Referer" to "https://www.kugou.com/",
    )

    suspend fun fetch(song: Song): LyricQueryResult? {
        val title = song.title.ifBlank { return null }
        val artist = primaryArtist(song.artist)
        val keyword = if (artist.isBlank()) title else "$title $artist"
        val searchUrl = "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc" +
            "&keyword=${urlEncode(keyword)}" +
            (if (song.durationMs > 0L) "&duration=${song.durationMs}" else "")
        val root = NeriJsonParser.parse(http.get(searchUrl, headers) ?: return null).asObject() ?: return null
        val candidates = root.array("candidates")?.objects().orEmpty()
            .filter { it.str("id") != null && it.str("accesskey") != null }
            .filter { durationMatches(song.durationMs, it.str("duration")?.toLongOrNull() ?: 0L) }
        if (candidates.isEmpty()) return null

        // 候选里挑时长最接近、再按接口给的 score 兜底
        val best = candidates.minWithOrNull(
            compareBy(
                { candidate ->
                    val candidateMs = candidate.str("duration")?.toLongOrNull() ?: 0L
                    if (song.durationMs > 0L && candidateMs > 0L) abs(song.durationMs - candidateMs)
                    else Long.MAX_VALUE / 2
                },
                { candidate -> -(candidate.str("score")?.toLongOrNull() ?: 0L) },
            ),
        ) ?: return null

        val id = best.str("id") ?: return null
        val accessKey = best.str("accesskey") ?: return null
        val downloadUrl = "https://lyrics.kugou.com/download?ver=1&client=pc" +
            "&id=$id&accesskey=$accessKey&fmt=lrc&charset=utf8"
        val payload = NeriJsonParser.parse(http.get(downloadUrl, headers) ?: return null).asObject() ?: return null
        val content = payload.str("content") ?: return null
        val raw = runCatching { String(java.util.Base64.getDecoder().decode(content), Charsets.UTF_8) }
            .getOrNull()
            ?.removePrefix("\uFEFF")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return LyricQueryResult(raw = raw, source = "酷狗")
    }
}

/**
 * AMLL TTML DB：社区维护的逐字歌词库。
 *
 * 走它的公开接口：`POST /api/search-lyrics` 按标题搜，再 `GET /raw-lyrics/<file>`
 * 取 TTML。TTML 是逐字时间轴，这里只取到行级 —— 每个 `<p>` 的 `begin` 当行时间，
 * 行文本由内部所有 `<span>` 的文本拼起来。
 */
class AmllProvider(private val http: HttpService) {

    private val headers = mapOf(
        "User-Agent" to "NeriPlayer/1.4.7",
        "Content-Type" to "application/json; charset=utf-8",
    )

    suspend fun fetch(song: Song): LyricQueryResult? {
        val title = song.title.ifBlank { return null }
        val body = """{"query":${jsonString(title)},"type":"title"}"""
        val response = postJson("https://amlldb.bikonoo.com/api/search-lyrics", body) ?: return null
        val items = NeriJsonParser.parse(response)
            .let { element -> (element as? kotlinx.serialization.json.JsonArray)?.objects().orEmpty() }
            .filter { it.str("file")?.endsWith(".ttml") == true }
        if (items.isEmpty()) return null

        val wanted = normalizeTitle(title)
        val artist = normalizeTitle(primaryArtist(song.artist))
        val best = items
            .filter { item ->
                // 接口按标题匹配，这里再确认一次标题，避免拿到翻唱 / 同名曲
                val candidate = normalizeTitle(item.str("title").orEmpty())
                candidate.isNotEmpty() && (candidate == wanted || candidate.contains(wanted) || wanted.contains(candidate))
            }
            .maxByOrNull { item ->
                val artistHit = if (artist.isNotEmpty() &&
                    normalizeTitle(item.str("artist").orEmpty()).contains(artist)
                ) {
                    1
                } else {
                    0
                }
                // 有网易云 ID 的更可信（说明是原曲而非搬运）
                val idHit = if (song.source == MediaSource.NETEASE && song.remoteId != null &&
                    item.array("ncmIds")?.any { it.toString().trim('"') == song.remoteId } == true
                ) {
                    2
                } else {
                    0
                }
                artistHit * 1000 + idHit * 100 + (item.str("score")?.toIntOrNull() ?: 0)
            } ?: return null

        val file = best.str("file")?.takeIf { !it.contains('/') } ?: return null
        val ttml = http.get("https://amlldb.bikonoo.com/raw-lyrics/$file", headers, timeoutSeconds = 20) ?: return null
        val raw = parseTtml(ttml).takeIf { it.isNotBlank() } ?: return null
        return LyricQueryResult(raw = raw, source = "AMLL TTML DB")
    }

    /** 极小范围的 JSON POST：只为 AMLL 的搜索接口服务。 */
    private fun postJson(url: String, body: String): String? = runCatching {
        java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .build()
            .send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(20))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("User-Agent", "NeriPlayer/1.4.7")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString(Charsets.UTF_8),
            )
            .takeIf { it.statusCode() in 200..299 }
            ?.body()
    }.getOrNull()

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    companion object {

        private val paragraphRegex = Regex(
            "<p\\b([^>]*)>(.*?)</p>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val beginRegex = Regex("""begin\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
        private val tagRegex = Regex("<[^>]+>")

        /** 把 TTML 转成 LRC 文本，复用现成的 LrcParser。 */
        fun parseTtml(ttml: String): String {
            val builder = StringBuilder()
            paragraphRegex.findAll(ttml).forEach { match ->
                val begin = beginRegex.find(match.groupValues[1])?.groupValues?.get(1) ?: return@forEach
                val timeMs = parseTimeMs(begin) ?: return@forEach
                val text = decodeEntities(match.groupValues[2].replace(tagRegex, "")).trim()
                if (text.isEmpty()) return@forEach
                builder.append('[')
                    .append(
                        // 保留到毫秒：LRC 支持三位小数，写成两位会白白丢掉最多 9ms 的对齐精度
                        "%02d:%02d.%03d".format(
                            timeMs / 60_000L,
                            timeMs / 1_000L % 60L,
                            timeMs % 1_000L,
                        ),
                    )
                    .append(']')
                    .append(text)
                    .append('\n')
            }
            return builder.toString()
        }

        /** 支持 `hh:mm:ss.mmm`、`mm:ss.mmm` 与 `mm:ss,fff` 三种写法。 */
        private fun parseTimeMs(raw: String): Long? {
            val parts = raw.trim().split(':')
            if (parts.size < 2) return null
            return runCatching {
                val seconds = parts.last().replace(',', '.').toDouble()
                val minutes = parts[parts.size - 2].toLong()
                val hours = if (parts.size >= 3) parts[parts.size - 3].toLong() else 0L
                ((hours * 3600L + minutes * 60L) * 1000L) + (seconds * 1000).toLong()
            }.getOrNull()
        }

        private fun decodeEntities(value: String): String = value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
    }
}

/** 取演唱者里的第一个名字：平台的 artist 字段常是「A / B / C」。 */
internal fun primaryArtist(artist: String): String =
    artist.split('/', '、', '&', ',', '，')
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        .orEmpty()
