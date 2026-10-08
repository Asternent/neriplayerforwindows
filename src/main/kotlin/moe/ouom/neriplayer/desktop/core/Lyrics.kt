package moe.ouom.neriplayer.desktop.core

import moe.ouom.neriplayer.desktop.net.LyricQueryResult
import moe.ouom.neriplayer.desktop.net.LyricSource
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 内存中保留的歌词上限（超出后按最近最少使用淘汰）。 */
private const val MAX_LYRICS_CACHE = 120

/** LRC 歌词解析（支持一行多时间标签、翻译行合并与 offset 偏移）。 */
object LrcParser {

    private val timeTagRegex = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val metaRegex = Regex("^\\[([a-zA-Z#]+):(.*)]$")

    private fun fractionToMs(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return when (raw.length) {
            1 -> raw.toLong() * 100
            2 -> raw.toLong() * 10
            else -> raw.take(3).toLong()
        }
    }

    /**
     * 解析 LRC。
     *
     * [extraOffsetMs] 是用户在设置里给该歌词来源配的默认偏移，会叠加在文件自带的
     * `[offset:]` 之上；两者方向一致 —— 正值表示歌词提前出现。
     */
    fun parse(raw: String, extraOffsetMs: Long = 0L): List<LyricLine> {
        if (raw.isBlank()) return emptyList()
        var offset = 0L
        val collected = ArrayList<Pair<Long, String>>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            metaRegex.find(trimmed)?.let { meta ->
                if (meta.groupValues[1].lowercase() == "offset") {
                    offset = meta.groupValues[2].trim().toLongOrNull() ?: 0L
                }
                return@forEach
            }
            val matches = timeTagRegex.findAll(trimmed).toList()
            if (matches.isEmpty()) return@forEach
            val text = trimmed.substring(matches.last().range.last + 1).trim()
            matches.forEach { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                val ms = minutes * 60_000L + seconds * 1_000L + fractionToMs(match.groupValues[3])
                collected += ms to text
            }
        }
        val shift = offset + extraOffsetMs
        return collected
            .sortedBy { it.first }
            .map { (time, text) -> LyricLine((time - shift).coerceAtLeast(0L), text) }
    }

    /**
     * 把翻译与音译按时间戳合并进主歌词。
     *
     * 翻译与音译都允许为空；同一时间戳上只取第一条，避免重复行互相覆盖。
     */
    fun merge(
        base: List<LyricLine>,
        translated: List<LyricLine>,
        romanized: List<LyricLine> = emptyList(),
    ): List<LyricLine> {
        if (translated.isEmpty() && romanized.isEmpty()) return base
        val translationByTime = indexByTime(translated)
        val romanizationByTime = indexByTime(romanized)
        return base.map { line ->
            val translation = translationByTime[line.timeMs]
                ?.takeIf { it.isNotBlank() && it != line.text }
            val romanization = romanizationByTime[line.timeMs]
                ?.takeIf { it.isNotBlank() && it != line.text && it != translation }
            line.copy(translation = translation, romanization = romanization)
        }
    }

    private fun indexByTime(lines: List<LyricLine>): Map<Long, String> {
        if (lines.isEmpty()) return emptyMap()
        val result = LinkedHashMap<Long, String>()
        lines.forEach { line ->
            if (line.text.isNotBlank()) result.putIfAbsent(line.timeMs, line.text)
        }
        return result
    }

    /** 同一文件内同时包含原文与翻译（相同时间戳连写两行）时拆分为翻译。 */
    fun splitDuplicatedTimestamps(
        raw: String,
        extraOffsetMs: Long = 0L,
    ): Pair<List<LyricLine>, List<LyricLine>> {
        val lines = parse(raw, extraOffsetMs)
        val grouped = LinkedHashMap<Long, MutableList<String>>()
        lines.forEach { line -> grouped.getOrPut(line.timeMs) { mutableListOf() }.add(line.text) }
        val main = ArrayList<LyricLine>()
        val translation = ArrayList<LyricLine>()
        grouped.forEach { (time, texts) ->
            val distinct = texts.filter { it.isNotBlank() }.distinct()
            if (distinct.isEmpty()) return@forEach
            main += LyricLine(time, distinct.first())
            distinct.drop(1).forEach { extra -> translation += LyricLine(time, extra) }
        }
        return main.sortedBy { it.timeMs } to translation.sortedBy { it.timeMs }
    }
}

/**
 * 歌词仓库，查找顺序：
 *
 * 1. **用户指定的优先来源**（[preferredSource] 不是 AUTO 时）—— 只对在线歌曲生效，
 *    本地歌曲始终以自己的 `.lrc` 为准，否则用户精心配好的歌词会被在线源盖掉；
 * 2. 本地 `.lrc` → 内嵌标签；
 * 3. 歌曲所属平台的在线接口（网易云 / 哔哩哔哩）。
 *
 * 命中哪一条，就用那一条对应的默认偏移去解析时间戳。
 */
class LyricsRepository(
    /** 歌曲所属平台的在线歌词（网易云 / 哔哩哔哩）。 */
    private val remoteProvider: suspend (Song) -> LyricQueryResult? = { null },
    /** 用户指定的优先来源；返回 null 表示这次没查到，交回默认链路。 */
    private val preferredProvider: suspend (Song, LyricSource) -> LyricQueryResult? = { _, _ -> null },
    private val preferredSource: () -> LyricSource = { LyricSource.AUTO },
    /** 各来源的默认时间偏移（毫秒）。 */
    private val offsetMs: (LyricSource) -> Long = { 0L },
) {
    // 歌词缓存按 LRU 限长：听歌久了缓存会一直涨，而歌词只在当前/最近几首之间来回用
    private val cache = object : LinkedHashMap<String, Lyrics>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics>): Boolean =
            size > MAX_LYRICS_CACHE
    }
    private val cacheLock = Any()
    private val missCache = ConcurrentHashMap.newKeySet<String>()

    fun cached(song: Song): Lyrics? = synchronized(cacheLock) { cache[song.key] }

    fun put(song: Song, lyrics: Lyrics) {
        synchronized(cacheLock) { cache[song.key] = lyrics }
    }

    fun invalidate(song: Song) {
        synchronized(cacheLock) { cache.remove(song.key) }
        missCache.remove(song.key)
    }

    /** 清掉全部缓存：改过歌词来源或偏移之后要重新取一次。 */
    fun invalidateAll() {
        synchronized(cacheLock) { cache.clear() }
        missCache.clear()
    }

    suspend fun load(song: Song): Lyrics {
        synchronized(cacheLock) { cache[song.key] }?.let { return it }

        val preferred = preferredSource()
        if (preferred != LyricSource.AUTO && song.source != MediaSource.LOCAL) {
            var hit = runCatching { preferredProvider(song, preferred) }.getOrNull()
            
            // 歌词音译回退：如果指定了第三方来源且没拿到音译，回退到网易云补全音译
            if (hit != null && hit.raw.isNotBlank() && hit.romanized.isNullOrBlank() && preferred != LyricSource.NETEASE) {
                val fallback = runCatching { preferredProvider(song, LyricSource.NETEASE) }.getOrNull()
                if (fallback?.romanized?.isNotBlank() == true) {
                    hit = hit.copy(romanized = fallback.romanized)
                }
            }
            
            if (hit != null && hit.raw.isNotBlank()) {
                val lyrics = fromResult(hit, offsetMs(preferred))
                synchronized(cacheLock) { cache[song.key] = lyrics }
                return lyrics
            }
        }

        localLyrics(song)?.let { lyrics ->
            synchronized(cacheLock) { cache[song.key] = lyrics }
            return lyrics
        }

        if (song.key !in missCache) {
            val remote = runCatching { remoteProvider(song) }.getOrNull()
            if (remote != null && remote.raw.isNotBlank()) {
                val lyrics = fromResult(remote, offsetMs(LyricSource.AUTO))
                synchronized(cacheLock) { cache[song.key] = lyrics }
                return lyrics
            }
            missCache += song.key
        }
        val empty = Lyrics()
        synchronized(cacheLock) { cache[song.key] = empty }
        return empty
    }

    private fun localLyrics(song: Song): Lyrics? {
        val path = song.filePath
        if (!path.isNullOrBlank()) {
            val audio = File(path)
            val baseName = audio.nameWithoutExtension
            val candidates = listOf(
                File(audio.parentFile, "$baseName.lrc"),
                File(audio.parentFile, "$baseName.LRC"),
                File(audio.parentFile, "$baseName.zh.lrc"),
                File(audio.parentFile, "$baseName.trans.lrc"),
            )
            val lrc = candidates.firstOrNull { it.isFile }
            if (lrc != null) {
                val raw = runCatching { lrc.readText() }.getOrNull()
                if (!raw.isNullOrBlank()) {
                    val (main, translation) = LrcParser.splitDuplicatedTimestamps(raw)
                    return Lyrics(
                        raw = raw,
                        lines = LrcParser.merge(main, translation),
                        translated = translation,
                        source = "本地歌词",
                    )
                }
            }
        }
        val embedded = song.lyrics
        if (!embedded.isNullOrBlank()) {
            val (main, translation) = LrcParser.splitDuplicatedTimestamps(embedded)
            return Lyrics(
                raw = embedded,
                lines = LrcParser.merge(main, translation),
                translated = translation,
                source = "内嵌歌词",
            )
        }
        return null
    }

    private fun fromResult(result: LyricQueryResult, offsetMs: Long): Lyrics {
        val (main, inlineTranslation) = LrcParser.splitDuplicatedTimestamps(result.raw, offsetMs)
        val translationLines = if (!result.translated.isNullOrBlank()) {
            LrcParser.parse(result.translated, offsetMs)
        } else {
            inlineTranslation
        }
        val romanizedLines = result.romanized
            ?.takeIf { it.isNotBlank() }
            ?.let { LrcParser.parse(it, offsetMs) }
            .orEmpty()
        return Lyrics(
            raw = result.raw,
            lines = LrcParser.merge(main, translationLines, romanizedLines),
            translated = translationLines,
            romanized = romanizedLines,
            source = result.source,
        )
    }
}

/** 找出当前播放位置对应的歌词行下标。 */
fun currentLyricIndex(lines: List<LyricLine>, positionMs: Long): Int {
    if (lines.isEmpty()) return -1
    var low = 0
    var high = lines.size - 1
    var result = -1
    while (low <= high) {
        val mid = (low + high) / 2
        if (lines[mid].timeMs <= positionMs) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
}
