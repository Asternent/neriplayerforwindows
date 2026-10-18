package moe.ouom.neriplayer.desktop.core

/**
 * 逐字歌词的解析与进度计算。
 *
 * 支持的格式：
 * 1. **网易云 yrc**：`[行开始,行时长](字开始,字时长,0)字…`；
 * 2. **增强型 LRC**（A2 扩展）：`[mm:ss.xx]<mm:ss.xx>字<mm:ss.xx>字`；
 * 3. **TTML**（AMLL TTML DB / Apple Music 逐字歌词）：`<p begin=…><span begin=…>`。
 *
 * 普通 LRC 没有逐字信息，仍然按整行线性推进（[lyricProgressAt] 的兜底分支）。
 * 与上游 Android 版（`SyncedLyricsView.kt` 的 `WordTiming` / `calculateLineProgress`）
 * 保持同一套语义：进度以**字符数**为权重，逐字歌词里每个片段按自己的时长线性推进。
 */

/** 原始歌词文本的格式。 */
enum class LyricFormat { LRC, YRC, TTML }

private val YRC_HEADER_REGEX = Regex("""\[\d{1,19},\s*\d{1,19}]""")
private val TTML_ROOT_REGEX = Regex("""<\s*tt(?:\s|>)""", RegexOption.IGNORE_CASE)

/** 按内容判断歌词格式；离线歌词源（本地文件、内嵌标签）也走这一套。 */
fun detectLyricFormat(raw: String): LyricFormat {
    if (raw.isBlank()) return LyricFormat.LRC
    if (TTML_ROOT_REGEX.containsMatchIn(raw)) return LyricFormat.TTML
    if (raw.lineSequence().any { YRC_HEADER_REGEX.containsMatchIn(it) }) return LyricFormat.YRC
    return LyricFormat.LRC
}

/**
 * 网易云 yrc 解析。
 *
 * 除了逐字行，还要兼容两种情况：
 * - `[开始,时长]纯文本`（没有 `<…>` 片段，逐字翻译 `ytlrc` 常见这种）；
 * - 新版接口夹带的 `{"t":0,"c":[…]}` 富文本行（制作信息），这里直接跳过。
 */
object YrcParser {

    private val headerRegex = Regex("""^\s*\[(\d{1,19}),\s*(\d{1,19})]""")
    private val segmentRegex = Regex("""\((\d{1,19}),\s*(\d{1,19}),\s*[-\d]{1,20}\)([^()\n\r]*)""")

    fun parse(raw: String, extraOffsetMs: Long = 0L): List<LyricLine> {
        if (raw.isBlank()) return emptyList()
        val result = ArrayList<LyricLine>()
        raw.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (!line.startsWith("[")) return@forEach
            val header = headerRegex.find(line) ?: return@forEach
            val start = header.groupValues[1].toLongOrNull() ?: return@forEach
            val duration = header.groupValues[2].toLongOrNull() ?: 0L
            val body = line.substring(header.range.last + 1)
            val segments = segmentRegex.findAll(body).toList()
            if (segments.isEmpty()) {
                val text = body.trim()
                if (text.isEmpty()) return@forEach
                val shiftedStart = shift(start, extraOffsetMs)
                result += LyricLine(
                    timeMs = shiftedStart,
                    text = text,
                    endMs = shift(start + duration, extraOffsetMs).coerceAtLeast(shiftedStart),
                )
                return@forEach
            }

            val words = ArrayList<LyricWord>(segments.size)
            val builder = StringBuilder()
            segments.forEach { segment ->
                val wordStart = segment.groupValues[1].toLongOrNull() ?: return@forEach
                val wordDuration = segment.groupValues[2].toLongOrNull() ?: return@forEach
                val text = segment.groupValues[3]
                if (text.isEmpty()) return@forEach
                val shiftedStart = shift(wordStart, extraOffsetMs)
                words += LyricWord(
                    text = text,
                    startMs = shiftedStart,
                    endMs = shift(wordStart + wordDuration, extraOffsetMs).coerceAtLeast(shiftedStart),
                )
                builder.append(text)
            }
            if (words.isEmpty()) return@forEach
            val text = builder.toString()
            if (text.isBlank()) return@forEach
            result += LyricLine(
                timeMs = shift(start, extraOffsetMs),
                text = text,
                words = words,
                endMs = words.last().endMs,
            )
        }
        // yrc 本身是有序的，但翻译行偶尔乱序；排序是稳定排序，不会打乱同时间戳的先后
        return result.sortedBy { it.timeMs }
    }

    private fun shift(value: Long, offsetMs: Long): Long = (value - offsetMs).coerceAtLeast(0L)
}

/** TTML 解析结果：原文行（带逐字）+ 翻译行 + 音译行。 */
data class TtmlLyrics(
    val lines: List<LyricLine> = emptyList(),
    val translated: List<LyricLine> = emptyList(),
    val romanized: List<LyricLine> = emptyList(),
)

/**
 * TTML 逐字歌词解析（AMLL TTML DB 与 Apple Music 的格式）。
 *
 * 一个 `<p>` 是一行：`begin` / `end` 给行时间，内部 `<span begin end>` 给逐字时间；
 * `ttm:role="x-translation"` / `x-romanization` 的 span 是翻译与音译，不能拼进正文。
 * `ttm:role="x-bg"` 是伴唱，保留在正文里（和现在的行为一致）。
 */
object TtmlParser {

    private val paragraphRegex = Regex(
        "<p\\b([^>]*)>(.*?)</p>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val beginRegex = Regex("""begin\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
    private val endRegex = Regex("""end\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
    private val roleSpanRegex = Regex(
        """<span\b[^>]*ttm:role\s*=\s*"(x-(?:translation|romanization))"[^>]*>(.*?)</span>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val tokenRegex = Regex(
        """<span\b([^>]*)>|</span\s*>|<[^>]+>|[^<]+""",
        RegexOption.IGNORE_CASE,
    )
    private val tagRegex = Regex("<[^>]+>")
    private val layoutWhitespaceRegex = Regex("""[\r\n]\s*""")

    /** 逐字片段：文本 + `<span>` 的属性串（裸文本没有属性）。 */
    private data class RawSegment(val text: String, val attrs: String?)

    fun parse(ttml: String, extraOffsetMs: Long = 0L): TtmlLyrics {
        if (ttml.isBlank()) return TtmlLyrics()
        val rawLines = ArrayList<RawLine>()
        paragraphRegex.findAll(ttml).forEach { match ->
            val attrs = match.groupValues[1]
            val start = attributeTime(attrs, beginRegex) ?: return@forEach
            val end = attributeTime(attrs, endRegex)
            val translations = ArrayList<String>()
            val romanizations = ArrayList<String>()
            val withoutRoles = roleSpanRegex.replace(match.groupValues[2]) { span ->
                val role = span.groupValues[1].lowercase()
                val text = decodeEntities(span.groupValues[2].replace(tagRegex, ""))
                    .replace(layoutWhitespaceRegex, "")
                    .trim()
                when {
                    text.isEmpty() -> ""
                    role.endsWith("translation") -> { translations += text; "" }
                    role.endsWith("romanization") -> { romanizations += text; "" }
                    else -> ""
                }
            }
            val words = buildWords(splitSegments(withoutRoles), start, end)
            if (words.isEmpty() && translations.isEmpty() && romanizations.isEmpty()) return@forEach
            rawLines += RawLine(
                startMs = start,
                endMs = end,
                words = words,
                translation = translations.joinToString(" ").takeIf { it.isNotBlank() },
                romanization = romanizations.joinToString(" ").takeIf { it.isNotBlank() },
            )
        }
        if (rawLines.isEmpty()) return TtmlLyrics()

        rawLines.sortBy { it.startMs }
        val lines = ArrayList<LyricLine>(rawLines.size)
        val translated = ArrayList<LyricLine>()
        val romanized = ArrayList<LyricLine>()
        rawLines.forEachIndexed { index, raw ->
            val nextStart = rawLines.getOrNull(index + 1)?.startMs
            val measuredEnd = raw.words.lastOrNull()?.endMs
            val lineEnd = raw.endMs ?: measuredEnd ?: nextStart ?: (raw.startMs + 5_000L)
            val startMs = shift(raw.startMs, extraOffsetMs)
            val endMs = shift(lineEnd, extraOffsetMs).coerceAtLeast(startMs)
            val words = raw.words.mapNotNull { word ->
                val wordStart = shift(word.startMs, extraOffsetMs)
                // 行首尾被 clamp 之后，逐字时间不能超出本行，否则进度会跳
                val wordEnd = shift(word.endMs, extraOffsetMs).coerceIn(wordStart, maxOf(endMs, wordStart))
                LyricWord(text = word.text, startMs = wordStart, endMs = wordEnd)
            }.filter { it.text.isNotEmpty() }
            val text = words.joinToString("") { it.text }
            if (text.isNotBlank()) {
                lines += LyricLine(
                    timeMs = startMs,
                    text = text,
                    words = words.takeIf { it.isNotEmpty() }.orEmpty(),
                    endMs = endMs,
                    // 直接把翻译挂到行上；同时保留在 translated 列表里，供界面判断「有没有翻译」
                    translation = raw.translation,
                    romanization = raw.romanization,
                )
            }
            raw.translation?.let { translated += LyricLine(startMs, it) }
            raw.romanization?.let { romanized += LyricLine(startMs, it) }
        }
        return TtmlLyrics(
            lines = lines.sortedBy { it.timeMs },
            translated = translated.sortedBy { it.timeMs },
            romanized = romanized.sortedBy { it.timeMs },
        )
    }

    /** 把解析结果转回 LRC 文本：`Lyrics.raw` 仍然是「可内嵌 / 可下载」的 LRC。 */
    fun toLrc(parsed: TtmlLyrics): String {
        if (parsed.lines.isEmpty()) return ""
        val extras = HashMap<Long, MutableList<String>>()
        parsed.translated.forEach { extras.getOrPut(it.timeMs) { mutableListOf() } += it.text }
        parsed.romanized.forEach { extras.getOrPut(it.timeMs) { mutableListOf() } += it.text }
        return buildString {
            parsed.lines.forEach { line ->
                append(formatLrcStamp(line.timeMs)).append(line.text).append('\n')
                extras[line.timeMs]?.forEach { extra ->
                    if (extra.isNotBlank() && extra != line.text) {
                        append(formatLrcStamp(line.timeMs)).append(extra).append('\n')
                    }
                }
            }
        }
    }

    private fun formatLrcStamp(timeMs: Long): String =
        "[%02d:%02d.%03d]".format(timeMs / 60_000L, timeMs / 1_000L % 60L, timeMs % 1_000L)

    private fun shift(value: Long, offsetMs: Long): Long = (value - offsetMs).coerceAtLeast(0L)

    private data class RawLine(
        val startMs: Long,
        val endMs: Long?,
        val words: List<LyricWord>,
        val translation: String? = null,
        val romanization: String? = null,
    )

    private fun buildWords(segments: List<RawSegment>, lineStart: Long, lineEnd: Long?): List<LyricWord> {
        if (segments.isEmpty()) return emptyList()
        val timed = segments.map { segment ->
            val begin = segment.attrs?.let { attributeTime(it, beginRegex) }
            val end = segment.attrs?.let { attributeTime(it, endRegex) }
            Triple(segment.text, begin, end)
        }.filter { (text, _, _) -> text.isNotEmpty() }
        if (timed.isEmpty()) return emptyList()
        val fallbackEnd = lineEnd ?: (lineStart + 5_000L)
        val words = ArrayList<LyricWord>(timed.size)
        timed.forEachIndexed { index, (text, begin, end) ->
            val wordStart = begin ?: words.lastOrNull()?.endMs ?: lineStart
            val wordEnd = end
                ?: timed.drop(index + 1).firstNotNullOfOrNull { it.second }
                ?: fallbackEnd
            words += LyricWord(text = text, startMs = wordStart, endMs = wordEnd.coerceAtLeast(wordStart))
        }
        // 去掉整行首尾空白：否则卡拉OK 扫光的字符进度会和可见文字对不上
        val cleaned = words.toMutableList()
        if (cleaned.isNotEmpty()) {
            cleaned[0] = cleaned[0].copy(text = cleaned[0].text.trimStart())
            cleaned[cleaned.lastIndex] = cleaned.last().copy(text = cleaned.last().text.trimEnd())
        }
        return cleaned.filter { it.text.isNotEmpty() }
    }

    /** 把 `<p>` 内部拆成「逐字片段 + 裸文本」，按 `<span>` 嵌套深度归属到最外层 span。 */
    private fun splitSegments(html: String): List<RawSegment> {
        val segments = ArrayList<RawSegment>()
        val bare = StringBuilder()
        var spanText = StringBuilder()
        var spanAttrs: String? = null
        var depth = 0

        fun flushBare() {
            val value = decodeEntities(bare.toString()).replace(layoutWhitespaceRegex, "")
            if (value.isNotEmpty()) segments += RawSegment(value, null)
            bare.clear()
        }

        fun flushSpan() {
            val attrs = spanAttrs
            val value = decodeEntities(spanText.toString()).replace(layoutWhitespaceRegex, "")
            if (attrs != null && value.isNotEmpty()) segments += RawSegment(value, attrs)
            spanText = StringBuilder()
            spanAttrs = null
        }

        tokenRegex.findAll(html).forEach { match ->
            val token = match.value
            when {
                token.startsWith("</") -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0) flushSpan()
                    }
                }

                token.startsWith("<span", ignoreCase = true) -> {
                    if (depth == 0) {
                        flushBare()
                        spanAttrs = match.groupValues[1]
                    }
                    depth++
                }

                token.startsWith("<") -> Unit // <br/> 之类的标签直接丢掉

                else -> if (depth == 0) bare.append(token) else spanText.append(token)
            }
        }
        if (depth == 0) flushBare() else flushSpan()
        return segments
    }

    private fun attributeTime(attrs: String, regex: Regex): Long? =
        regex.find(attrs)?.groupValues?.getOrNull(1)?.let(::parseTimeMs)

    /** 支持 `hh:mm:ss.mmm`、`mm:ss.mmm`、`ss.mmm`、`12.3s`、`450ms` 几种写法。 */
    internal fun parseTimeMs(raw: String): Long? {
        val value = raw.trim().replace(',', '.')
        if (value.isEmpty()) return null
        if (value.endsWith("ms", ignoreCase = true)) {
            return value.dropLast(2).trim().toDoubleOrNull()?.toLong()
        }
        if (value.endsWith("s", ignoreCase = true) && !value.endsWith("ms", ignoreCase = true)) {
            return value.dropLast(1).trim().toDoubleOrNull()?.let { (it * 1000.0).toLong() }
        }
        val parts = value.split(':')
        return runCatching {
            when (parts.size) {
                1 -> (parts[0].toDouble() * 1000.0).toLong()
                2 -> {
                    val seconds = parts[1].toDouble()
                    (parts[0].toLong() * 60_000L + (seconds * 1000.0).toLong())
                }

                3 -> {
                    val seconds = parts[2].toDouble()
                    parts[0].toLong() * 3_600_000L + parts[1].toLong() * 60_000L + (seconds * 1000.0).toLong()
                }

                else -> return null
            }
        }.getOrNull()
    }

    internal fun decodeEntities(value: String): String {
        val named = value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
        return Regex("""&#(x?[0-9a-fA-F]+);""").replace(named) { match ->
            val raw = match.groupValues[1]
            val code = runCatching {
                if (raw.startsWith("x", ignoreCase = true)) raw.drop(1).toInt(16) else raw.toInt()
            }.getOrNull()
            code?.takeIf { it in 0..0x10FFFF }?.let { String(Character.toChars(it)) } ?: match.value
        }
    }
}

/**
 * 当前播放位置在这行的进度（0f..1f）。
 *
 * 与上游一致：逐字歌词按字符数加权，行内进度 = 已完成字符数 / 总字符数；
 * 没有逐字信息时退化成「行时长线性推进」。
 */
fun lyricProgressAt(
    words: List<LyricWord>,
    lineStartMs: Long,
    lineEndMs: Long,
    textLength: Int,
    positionMs: Long,
): Float {
    val start = lineStartMs
    val end = lineEndMs.coerceAtLeast(start)
    if (positionMs <= start) return 0f
    if (end > start && positionMs >= end) return 1f
    if (words.isEmpty() || textLength <= 0) {
        return if (end <= start) 1f else ((positionMs - start).toFloat() / (end - start)).coerceIn(0f, 1f)
    }
    var completed = 0
    words.forEach { word ->
        if (positionMs < word.startMs) {
            return (completed.toFloat() / textLength).coerceIn(0f, 1f)
        }
        if (positionMs < word.endMs) {
            val duration = (word.endMs - word.startMs).coerceAtLeast(1L)
            val fraction = ((positionMs - word.startMs).toFloat() / duration).coerceIn(0f, 1f)
            return ((completed + fraction * word.text.length) / textLength).coerceIn(0f, 1f)
        }
        completed += word.text.length
    }
    return 1f
}

/** 单行歌词在 [positionMs] 时的进度。 */
fun LyricLine.progressAt(positionMs: Long): Float =
    lyricProgressAt(words, timeMs, effectiveEndMs, text.length, positionMs)

/**
 * 把带逐字时间轴的歌词转回行级 LRC 文本。
 *
 * 逐字歌词的原始格式（yrc）不能直接写进 `.lrc` 或音频标签，下载 / 内嵌时用这个转换，
 * 播放时仍然按逐字时间轴走。
 */
fun lyricsToLrc(lines: List<LyricLine>): String = buildString {
    lines.filter { it.text.isNotBlank() }.forEach { line ->
        append(
            "[%02d:%02d.%03d]".format(
                line.timeMs / 60_000L,
                line.timeMs / 1_000L % 60L,
                line.timeMs % 1_000L,
            ),
        )
        append(line.text).append('\n')
    }
}
