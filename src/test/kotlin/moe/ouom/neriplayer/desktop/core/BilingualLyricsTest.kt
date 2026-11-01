package moe.ouom.neriplayer.desktop.core

import org.junit.Test
import kotlin.test.*

class BilingualLyricsTest {
    @Test fun closeEnglishAndChineseLinesHighlightTogether() {
        val lines = prepareBilingualLyricsForDisplay(listOf(LyricLine(1000, "Hello"), LyricLine(1020, "你好")))
        assertEquals(1, lines.size)
        assertEquals("Hello\n你好", lines.single().text)
        assertEquals(1000L, lines.single().timeMs)
    }
    @Test fun inlineTranslationKeepsOpeningQuotesOnItsOwnLine() {
        assertEquals("Hello\n「你好」", prepareBilingualLyricsForDisplay(listOf(LyricLine(0, "Hello 「你好」"))).single().text)
    }
    @Test fun sameLanguageOrDistantLinesAreNotMerged() {
        assertEquals(2, prepareBilingualLyricsForDisplay(listOf(LyricLine(1000, "Hello"), LyricLine(1020, "World"))).size)
        assertEquals(2, prepareBilingualLyricsForDisplay(listOf(LyricLine(1000, "Hello"), LyricLine(1051, "你好"))).size)
    }
    @Test fun karaokeAndCreditLinesRemainIntact() {
        val karaoke = LyricLine(0, "Hello你好", words = listOf(LyricWord("Hello你好", 0, 1000)))
        val credit = LyricLine(0, "作词: Somebody")
        assertEquals(listOf(karaoke, credit), prepareBilingualLyricsForDisplay(listOf(karaoke, credit)))
    }
    @Test fun translationsRomanizationAndEndTimesSurviveGrouping() {
        val result = prepareBilingualLyricsForDisplay(listOf(
            LyricLine(0, "Hello", translation = "译一", romanization = "roma", endMs = 1000),
            LyricLine(20, "你好", translation = "译二", endMs = 2000),
        )).single()
        assertEquals("译一\n译二", result.translation)
        assertEquals("roma", result.romanization)
        assertEquals(2000L, result.endMs)
    }
    @Test fun sourceIndexMappingIsStable() {
        val result = prepareBilingualLyricDisplay(listOf(LyricLine(0, "Hi"), LyricLine(20, "你好"), LyricLine(1000, "Next")))
        assertEquals(listOf(0, 2), result.sourceIndices)
    }
}
