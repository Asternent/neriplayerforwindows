package moe.ouom.neriplayer.desktop.net

import moe.ouom.neriplayer.desktop.core.LyricLine
import java.net.URI
import kotlin.math.roundToLong

// Desktop adaptation of cwuom/NeriPlayer #575 (6051379f), GPL-3.0-only.
data class BiliSubtitleSettings(
    val enabled: Boolean = true,
    val preferredLanguage: String = "",
    val aiFallback: Boolean = true,
)

data class BiliSubtitleTrack(
    val id: String,
    val language: String,
    val label: String,
    val url: String,
    val isAi: Boolean = false,
)

/** API-supplied URLs must remain on Bilibili's HTTPS subtitle hosts. */
internal fun normalizeBiliSubtitleUrl(raw: String?): String? = runCatching {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val normalized = when {
        text.startsWith("//") -> "https:$text"
        text.startsWith("http://", true) -> "https://${text.substring(7)}"
        else -> text
    }
    val uri = URI(normalized)
    val host = uri.host?.lowercase() ?: return null
    if (uri.scheme != "https" || uri.userInfo != null || uri.port !in setOf(-1, 443)) return null
    if (host != "hdslb.com" && !host.endsWith(".hdslb.com") &&
        host != "bilibili.com" && !host.endsWith(".bilibili.com")) return null
    uri.toASCIIString()
}.getOrNull()

internal fun parseBiliSubtitleTracks(payload: String): List<BiliSubtitleTrack> =
    NeriJsonParser.parse(payload).asObject()?.array("subtitles")?.objects().orEmpty().mapNotNull { item ->
        val url = normalizeBiliSubtitleUrl(item.str("subtitle_url")) ?: return@mapNotNull null
        val language = item.str("lan").orEmpty().trim()
        BiliSubtitleTrack(
            id = item.str("id_str") ?: item.str("id").orEmpty(),
            language = language,
            label = item.str("lan_doc")?.takeIf { it.isNotBlank() } ?: language,
            url = url,
            isAi = item.int("type") == 1 || language.startsWith("ai-", true),
        )
    }.distinctBy { it.id to it.language }

private fun languageBase(value: String): String =
    value.lowercase().removePrefix("ai-").replace('_', '-').substringBefore('-')

internal fun matchesSubtitleLanguage(language: String, preferred: String): Boolean {
    val actual = language.trim().lowercase().replace('_', '-')
    val target = preferred.trim().lowercase().replace('_', '-')
    if (actual.isEmpty() || target.isEmpty()) return false
    if (actual == target) return true
    if (actual.startsWith("ai-") || target.startsWith("ai-")) return false
    if (languageBase(actual) != languageBase(target)) return false
    fun script(value: String): Int = when (value.substringAfter('-', "")) {
        "hant", "tw", "hk", "mo" -> 2
        "hans", "cn", "sg" -> 1
        else -> 0
    }
    return script(actual) == 0 || script(target) == 0 || script(actual) == script(target)
}

internal fun subtitleCandidates(tracks: List<BiliSubtitleTrack>, settings: BiliSubtitleSettings): List<BiliSubtitleTrack> {
    val explicit = tracks.filter { matchesSubtitleLanguage(it.language, settings.preferredLanguage) }
        .sortedBy { !it.language.equals(settings.preferredLanguage, true) }
    val fallback = tracks.filter { it !in explicit && (settings.aiFallback || !it.isAi) }
        .sortedWith(compareBy<BiliSubtitleTrack> { languageBase(it.language) != "zh" }
            .thenBy { it.isAi }.thenBy { it.language })
    return explicit + fallback
}

internal fun translatedSubtitleTrack(
    primary: BiliSubtitleTrack,
    tracks: List<BiliSubtitleTrack>,
    aiAllowed: Boolean,
): BiliSubtitleTrack? = tracks.filter {
    languageBase(it.language) != languageBase(primary.language) && (aiAllowed || !it.isAi)
}.sortedWith(compareBy<BiliSubtitleTrack> { it.isAi }.thenBy { it.language }).firstOrNull()

internal fun parseBiliSubtitleLines(payload: String): List<LyricLine> =
    NeriJsonParser.parse(payload).asObject()?.array("body")?.objects().orEmpty().mapNotNull { item ->
        val text = item.str("content")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val from = item.double("from")?.takeIf { it.isFinite() && it >= 0 && it < 1e10 } ?: return@mapNotNull null
        val to = item.double("to")?.takeIf { it.isFinite() && it >= 0 && it < 1e10 } ?: from
        val start = (from * 1000).roundToLong()
        LyricLine(timeMs = start, text = text, endMs = (to * 1000).roundToLong().coerceAtLeast(start + 1))
    }.distinctBy { it.timeMs to it.text }.sortedBy { it.timeMs }

/** Subtitle tracks can have different cue boundaries; align by overlap, not exact timestamps. */
internal fun mergeSubtitleTranslation(base: List<LyricLine>, translated: List<LyricLine>): List<LyricLine> =
    base.map { line ->
        val matches = translated.filter { it.timeMs < line.endMs && it.endMs > line.timeMs }
            .map { it.text }.filter { it != line.text }.distinct()
        line.copy(translation = matches.takeIf { it.isNotEmpty() }?.joinToString("\n"))
    }
