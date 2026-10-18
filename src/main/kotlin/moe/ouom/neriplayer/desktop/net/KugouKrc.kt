package moe.ouom.neriplayer.desktop.net

import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.InflaterInputStream

/** 酷狗 KRC 解码结果：逐字时间轴（yrc 文本）+ 翻译（LRC 文本）。 */
internal data class KugouKrcPayload(
    val yrc: String,
    val translatedLrc: String?,
)

/**
 * 酷狗 KRC 歌词解码。
 *
 * 下载接口返回的是 `Base64(前 4 字节头 + XOR + zlib)`，解密后每行形如
 * `[行开始,行时长]<字偏移,字时长,0>字…`，正好可以转成网易云那套 yrc，
 * 复用 [moe.ouom.neriplayer.desktop.core.YrcParser]。
 *
 * 算法与上游 Android 版 `KugouLyricsClient` 一致。
 */
internal object KugouKrc {

    private const val HEADER_BYTES = 4
    private val KEY = byteArrayOf(
        0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
        0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69,
    )
    private val tagRegex = Regex("""^\[(\w+):([^\]]*)]$""")
    private val lineRegex = Regex("""^\[(\d+),\s*(\d+)](.*)$""")
    private val segmentRegex = Regex("""<(\d+),\s*(\d+),\s*[-\d]+>([^<\n\r]*)""")

    fun decode(base64: String): KugouKrcPayload? = runCatching {
        val krc = decrypt(Base64.getDecoder().decode(base64)) ?: return@runCatching null
        val yrc = convert(krc).takeIf { it.isNotBlank() } ?: return@runCatching null
        KugouKrcPayload(yrc = yrc, translatedLrc = extractTranslation(krc))
    }.getOrNull()

    private fun decrypt(encrypted: ByteArray): String? {
        if (encrypted.size <= HEADER_BYTES) return null
        val payload = encrypted.copyOfRange(HEADER_BYTES, encrypted.size)
        val plain = ByteArray(payload.size) { index ->
            (payload[index].toInt() xor KEY[index % KEY.size].toInt()).toByte()
        }
        return runCatching {
            InflaterInputStream(ByteArrayInputStream(plain)).use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** `[行开始,行时长]<字偏移,字时长,0>字…` → `[行开始,行时长](字绝对开始,字时长,0)字…` */
    private fun convert(krc: String): String = krc.lineSequence().mapNotNull { rawLine ->
        val line = rawLine.trim()
        val header = lineRegex.find(line) ?: return@mapNotNull null
        val lineStart = header.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val segments = segmentRegex.findAll(header.groupValues[3]).toList()
        if (segments.isEmpty()) return@mapNotNull null
        buildString {
            append('[').append(lineStart).append(',').append(header.groupValues[2]).append(']')
            segments.forEach { segment ->
                val offset = segment.groupValues[1].toLongOrNull() ?: return@forEach
                val duration = segment.groupValues[2].toLongOrNull() ?: return@forEach
                append('(').append(lineStart + offset).append(',').append(duration).append(",0)")
                append(segment.groupValues[3])
            }
        }
    }.joinToString("\n")

    /**
     * KRC 的翻译在 `[language:base64(json)]` 标签里，按行下标与正文一一对应；
     * 只有 `type == 1` 的那一组是翻译。
     */
    private fun extractTranslation(krc: String): String? {
        val timedLines = krc.lineSequence().mapNotNull { rawLine ->
            lineRegex.find(rawLine.trim())?.let { match ->
                (match.groupValues[1].toLongOrNull() ?: return@mapNotNull null) to
                    (match.groupValues[2].toLongOrNull() ?: return@mapNotNull null)
            }
        }.toList()
        if (timedLines.isEmpty()) return null
        val languageTag = krc.lineSequence().mapNotNull { rawLine ->
            tagRegex.matchEntire(rawLine.trim())?.let { match ->
                if (match.groupValues[1] == "language") match.groupValues[2] else null
            }
        }.firstOrNull() ?: return null
        val json = runCatching {
            String(Base64.getDecoder().decode(languageTag), Charsets.UTF_8)
        }.recoverCatching {
            String(Base64.getMimeDecoder().decode(languageTag), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val content = NeriJsonParser.parse(json).asObject()?.array("content")?.objects().orEmpty()
        val translation = content.firstOrNull { it.int("type") == 1 } ?: return null
        val rows = translation.array("lyricContent") ?: return null
        val builder = StringBuilder()
        timedLines.forEachIndexed { index, (start, _) ->
            val cell = rows.getOrNull(index) ?: return@forEachIndexed
            val text = when (cell) {
                is kotlinx.serialization.json.JsonArray -> cell.joinToString(" ") { item ->
                    item.toString().trim().trim('"')
                }.trim()
                is kotlinx.serialization.json.JsonPrimitive -> cell.content.trim()
                else -> ""
            }
            if (text.isNotBlank()) {
                builder.append(formatStamp(start)).append(text).append('\n')
            }
        }
        return builder.toString().takeIf { it.isNotBlank() }
    }

    private fun formatStamp(startMs: Long): String =
        "[%02d:%02d.%02d]".format(startMs / 60_000L, startMs % 60_000L / 1_000L, startMs % 1_000L / 10L)
}
