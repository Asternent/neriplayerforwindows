package moe.ouom.neriplayer.desktop.core

import moe.ouom.neriplayer.desktop.net.LyricQueryResult
import moe.ouom.neriplayer.desktop.net.LyricSource
import java.io.File

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
     * 不要求时间戳完全相等：不同平台导出的翻译经常比原文早十几毫秒，
     * 严格相等会让整首歌的翻译全部丢失。这里按「最近的仍在容差内的行」匹配，
     * 规则与手机端一致（见 [matchSecondaryLines]）。
     */
    fun merge(
        base: List<LyricLine>,
        translated: List<LyricLine>,
        romanized: List<LyricLine> = emptyList(),
    ): List<LyricLine> {
        if (translated.isEmpty() && romanized.isEmpty()) return base
        val translationMatches = matchSecondaryLines(base, translated)
        val romanizationMatches = matchSecondaryLines(base, romanized)
        return base.mapIndexed { index, line ->
            val translation = translationMatches[index]
                ?.takeIf { it.isNotBlank() && it != line.text }
            val romanization = romanizationMatches[index]
                ?.takeIf { it.isNotBlank() && it != line.text && it != translation }
            line.copy(translation = translation, romanization = romanization)
        }
    }

    /**
     * 把翻译/音译行按时间对到主歌词的行下标上。
     *
     * 关键点（对齐手机端 LyricTranslationMatcher）：
     * 1. 容差匹配：译文时间戳落在原行区间内，或与行首相差不超过
     *    [LYRIC_ALIGN_TOLERANCE_MS]，且比下一行更近，就算命中；
     * 2. 提前量兜底：译文比原行早，但不早于 [LYRIC_LOOSE_TOLERANCE_MS]，同样接受；
     * 3. 向下对齐：一行时间戳上常常同时挂着「作词: xxx」「OP: xxx」等制作信息行，
     *    译文要落到这一组里靠后的正文行上，否则整段翻译会错位一行；
     * 4. 制作信息行本身不是翻译，「/」这种未翻译占位也不算。
     */
    internal fun matchSecondaryLines(
        base: List<LyricLine>,
        secondary: List<LyricLine>,
    ): Map<Int, String> {
        if (base.isEmpty() || secondary.isEmpty()) return emptyMap()
        val candidates = secondary.filter {
            it.text.isNotBlank() &&
                !isLyricCreditMetadataLine(it.text) &&
                !isUntranslatedPlaceholderText(it.text)
        }
        if (candidates.isEmpty()) return emptyMap()

        val result = LinkedHashMap<Int, String>()
        var candidateIndex = 0
        var lineIndex = 0

        while (lineIndex < base.size) {
            val groupStart = base[lineIndex].timeMs
            var groupEnd = lineIndex
            while (groupEnd < base.size && base[groupEnd].timeMs == groupStart) groupEnd++
            val groupSize = groupEnd - lineIndex
            val representative = base[groupEnd - 1]
            val nextStart = base.getOrNull(groupEnd)?.timeMs

            val assigned = ArrayList<String>(groupSize)
            while (candidateIndex < candidates.size && assigned.size < groupSize) {
                val candidate = candidates[candidateIndex]
                // 远早于当前行、且已经错过容纳窗口的译文，直接丢掉
                if (candidate.timeMs < groupStart - LYRIC_LOOSE_TOLERANCE_MS) {
                    candidateIndex++
                    continue
                }
                val distance = distanceToLine(candidate.timeMs, representative.timeMs, nextStart)
                val nextDistance = nextStart?.let { distanceToLine(candidate.timeMs, it, null) } ?: Long.MAX_VALUE
                val withinTolerance = distance <= LYRIC_ALIGN_TOLERANCE_MS && distance <= nextDistance
                val slightlyAhead = candidate.timeMs < representative.timeMs &&
                    distance <= LYRIC_LOOSE_TOLERANCE_MS &&
                    distance <= nextDistance
                if (withinTolerance || slightlyAhead) {
                    assigned += candidate.text
                    candidateIndex++
                } else {
                    break
                }
            }

            // 向下对齐：译文放到这一组靠后的行，跳过制作信息行
            val firstTarget = groupEnd - assigned.size
            assigned.forEachIndexed { offset, text ->
                val target = firstTarget + offset
                if (target in base.indices && !isLyricCreditMetadataLine(base[target].text)) {
                    result[target] = text
                }
            }
            lineIndex = groupEnd
        }
        return result
    }

    /** 译文时间戳到某行播放区间的距离：落在区间内记 0。 */
    private fun distanceToLine(timestampMs: Long, lineStartMs: Long, nextStartMs: Long?): Long {
        val endExclusive = if (nextStartMs != null && nextStartMs > lineStartMs) nextStartMs else lineStartMs + 1
        return when {
            timestampMs < lineStartMs -> lineStartMs - timestampMs
            timestampMs >= endExclusive -> timestampMs - endExclusive + 1
            else -> 0L
        }
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

/** 译文与原文行首的对齐容差：超过这个距离就不再认为它们属于同一句。 */
internal const val LYRIC_ALIGN_TOLERANCE_MS = 450L

/** 译文比原文更早时仍可接受的最大提前量。 */
internal const val LYRIC_LOOSE_TOLERANCE_MS = 2_000L

private val lyricCreditRegex = Regex("""^\s*([\p{L}·]{1,12})\s*[:：]\s*\S""")

/**
 * 常见的「制作信息」角色词。这些行挂在和正文相同的时间戳上，
 * 但它们不是翻译，参与对齐会让整段歌词错位。
 */
private val lyricCreditRoles = setOf(
    "作词", "作詞", "作曲", "编曲", "編曲", "谱曲", "譜曲", "制作人", "製作人", "制作", "製作",
    "出品", "出品人", "联合出品", "聯合出品", "营销", "營銷", "企划", "企劃", "策划", "策劃",
    "监制", "監製", "统筹", "統籌", "发行", "發行", "混音", "母带", "母帶", "录音", "錄音",
    "和声", "和聲", "和音", "配唱", "演唱", "原唱", "词", "詞", "曲", "吉他", "贝斯", "貝斯",
    "鼓", "键盘", "鍵盤", "弦乐", "弦樂", "录音师", "錄音師", "混音师", "混音師",
    "制作公司", "製作公司", "版权", "版權", "鸣谢", "鳴謝", "特别鸣谢", "特別鳴謝",
    "op", "sp", "lyricist", "composer", "arranger", "producer", "mixing",
    "mastering", "recording", "vocal", "guitar", "bass", "drums", "keyboard",
    "strings",
)

/** 这一行是不是「作词: xxx」这类制作信息。 */
internal fun isLyricCreditMetadataLine(text: String): Boolean {
    val role = lyricCreditRegex.find(text)?.groupValues?.getOrNull(1)?.trim()?.lowercase() ?: return false
    return role in lyricCreditRoles
}

/** 这一行是不是「/」「//」这种未翻译占位。 */
internal fun isUntranslatedPlaceholderText(text: String): Boolean {
    val normalized = text.replace('／', '/').filterNot(Char::isWhitespace)
    return normalized.length >= 2 && normalized.all { it == '/' }
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

    fun cached(song: Song): Lyrics? = synchronized(cacheLock) { cache[song.key] }

    fun put(song: Song, lyrics: Lyrics) {
        synchronized(cacheLock) { cache[song.key] = lyrics }
    }

    fun invalidate(song: Song) {
        synchronized(cacheLock) { cache.remove(song.key) }
    }

    /** 清掉全部缓存：改过歌词来源或偏移之后要重新取一次。 */
    fun invalidateAll() {
        synchronized(cacheLock) { cache.clear() }
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

        // 空结果也会进 LRU 缓存（下次直接命中），所以不需要额外的「查不到」集合：
        // 那个集合既不淘汰也不会重试，登录后/新放了 .lrc 也永远拿不到歌词。
        val remote = runCatching { remoteProvider(song) }.getOrNull()
        if (remote != null && remote.raw.isNotBlank()) {
            val lyrics = fromResult(remote, offsetMs(LyricSource.AUTO))
            synchronized(cacheLock) { cache[song.key] = lyrics }
            return lyrics
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
