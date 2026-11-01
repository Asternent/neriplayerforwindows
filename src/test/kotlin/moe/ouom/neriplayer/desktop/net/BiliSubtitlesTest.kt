package moe.ouom.neriplayer.desktop.net

import moe.ouom.neriplayer.desktop.core.*
import org.junit.Test
import kotlin.test.*

class BiliSubtitlesTest {
    private fun track(language: String, ai: Boolean = language.startsWith("ai-")) =
        BiliSubtitleTrack(language, language, language, "https://i0.hdslb.com/$language.json", ai)

    @Test fun normalizesOnlyTrustedHttpsSubtitleUrls() {
        assertEquals("https://i0.hdslb.com/a", normalizeBiliSubtitleUrl("//i0.hdslb.com/a"))
        assertEquals("https://i0.hdslb.com/a", normalizeBiliSubtitleUrl("http://i0.hdslb.com/a"))
        listOf("https://evilhdslb.com/a", "https://i0.hdslb.com.evil.test/a", "file:///x",
            "https://user:pass@i0.hdslb.com/a", "https://i0.hdslb.com:8080/a").forEach {
            assertNull(normalizeBiliSubtitleUrl(it), it)
        }
    }
    @Test fun candidatesPreferChineseHumanThenChineseAi() {
        val tracks = listOf(track("en"), track("ai-zh"), track("zh-Hans"), track("ai-en"))
        assertEquals(listOf("zh-Hans", "ai-zh", "en", "ai-en"),
            subtitleCandidates(tracks, BiliSubtitleSettings()).map { it.language })
    }
    @Test fun explicitAiSelectionWorksWithAutomaticAiFallbackDisabled() {
        val tracks = listOf(track("zh-Hans"), track("ai-en"), track("ai-zh"))
        assertEquals(listOf("ai-en", "zh-Hans"), subtitleCandidates(tracks,
            BiliSubtitleSettings(preferredLanguage = "ai-en", aiFallback = false)).map { it.language })
    }
    @Test fun languageMatchingRespectsChineseScriptAndAiIdentity() {
        assertTrue(matchesSubtitleLanguage("zh-CN", "zh-Hans"))
        assertTrue(matchesSubtitleLanguage("zh_TW", "zh-Hant"))
        assertFalse(matchesSubtitleLanguage("zh-Hans", "zh-Hant"))
        assertFalse(matchesSubtitleLanguage("ai-zh", "zh"))
    }
    @Test fun translationMustUseAnotherBaseLanguage() {
        val main = track("zh-Hans")
        assertEquals("en", translatedSubtitleTrack(main, listOf(track("ai-zh"), track("zh-Hant"), track("en")), true)?.language)
        assertNull(translatedSubtitleTrack(main, listOf(track("ai-en")), false))
    }
    @Test fun parserFiltersInvalidCuesAndSortsAndDeduplicates() {
        val lines = parseBiliSubtitleLines("""{"body":[
            {"from":2.5,"to":2,"content":"后"}, {"from":1.25,"to":2,"content":"前"},
            {"from":1.25,"to":2,"content":"前"}, {"from":-1,"content":"bad"},
            {"from":"NaN","content":"bad"}, {"from":1,"content":" "}]}""")
        assertEquals(listOf(1250L, 2500L), lines.map { it.timeMs })
        assertEquals(2501L, lines.last().endMs)
    }
    @Test fun parserRetainsOnlyUsableTracks() {
        val tracks = parseBiliSubtitleTracks("""{"subtitles":[
            {"id_str":"1","lan":"ai-zh","lan_doc":"中文","subtitle_url":"//i0.hdslb.com/a"},
            {"id":2,"lan":"en","subtitle_url":"file:///bad"}]}""")
        assertEquals("1", tracks.single().id)
        assertTrue(tracks.single().isAi)
    }
    @Test fun translationsAlignAcrossDifferentCueBoundaries() {
        val lines = mergeSubtitleTranslation(listOf(LyricLine(1000, "原文", endMs = 3000)),
            listOf(LyricLine(1200, "one", endMs = 2000), LyricLine(2000, "two", endMs = 3100)))
        assertEquals("one\ntwo", lines.single().translation)
    }
    @Test fun anonymousSubtitleLookupReturnsImmediatelyWithoutNetworking() {
        assertTrue(BiliApi(HttpService()).subtitleTracks(Song("bili:1", MediaSource.BILIBILI, remoteId = "BV1" )).isEmpty())
    }
}
