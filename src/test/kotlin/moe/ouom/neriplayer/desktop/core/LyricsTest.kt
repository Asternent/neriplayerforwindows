package moe.ouom.neriplayer.desktop.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.desktop.net.LyricQueryResult
import moe.ouom.neriplayer.desktop.net.LyricSource
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class LyricsTest {
    private val song = Song("netease:1", source = MediaSource.NETEASE)
    private fun result(text: String = "hello") = LyricQueryResult("[00:01.00]$text", source = "fixture")

    @Test fun offsetAtEndAppliesToEveryLine() {
        val lines = LrcParser.parse("[00:01.00]a\n[00:03.00]b\n[offset:500]", 100)
        assertEquals(listOf(400L, 2400L), lines.map { it.timeMs })
    }

    @Test fun bomAndFractionFormatsAreSupported() {
        assertEquals(listOf(1100L, 1230L, 1234L),
            LrcParser.parse("\uFEFF[00:01.1]a\n[00:01.23]b\n[00:01.234]c").map { it.timeMs })
    }

    @Test fun invalidOffsetsCannotOverflowTimeline() {
        assertTrue(LrcParser.parse("[00:01]a\n[offset:${Long.MAX_VALUE}]", Long.MIN_VALUE).all { it.timeMs >= 0 })
    }

    @Test fun creditLinesDoNotReplaceTheActualLyrics() {
        val (main, translation) = LrcParser.splitDuplicatedTimestamps(
            "[00:01]作词: 作者\n[00:01]Hello\n[00:01]你好",
        )
        assertEquals(listOf("作词: 作者", "Hello"), main.map { it.text })
        assertEquals("你好", LrcParser.merge(main, translation).last().translation)
        assertTrue(isUntranslatedPlaceholderText(" / "))
    }

    @Test fun enhancedLrcPreservesWordTiming() {
        val line = LrcParser.parse("[00:01]<00:01.00>你<00:01.50>好<00:02.00>\n[00:03]尾声").first()
        assertEquals("你好", line.text)
        assertEquals(listOf(1000L, 1500L), line.words.map { it.startMs })
        assertTrue(line.words.all { it.endMs >= it.startMs })
    }
    @Test fun repeatedEnhancedLrcRebasesEachWordTimeline() {
        val lines = LrcParser.parse("[00:01][00:05]<00:01>你<00:02>好")
        assertEquals(listOf(1000L, 5000L), lines.map { it.timeMs })
        assertEquals(listOf(5000L, 6000L), lines[1].words.map { it.startMs })
    }
    @Test fun mixedYrcAndOrdinaryLrcDoNotLoseEitherTimeline() {
        val lines = YrcParser.parse("[1000,1000](1000,1000,0)卡拉\n[00:03]普通\n[00:05]<00:05>增强")
        assertEquals(listOf("卡拉", "普通", "增强"), lines.map { it.text })
        assertTrue(lines.first().hasWordTiming)
        assertTrue(lines.last().hasWordTiming)
    }

    @Test fun emptyResponsesAreRetriedAfterLoginOrNetworkRecovery() = runTest {
        var calls = 0
        val repository = LyricsRepository(remoteProvider = { if (++calls == 1) null else result() })
        assertTrue(repository.load(song).isEmpty)
        assertEquals("hello", repository.load(song).lines.single().text)
        repository.load(song)
        assertEquals(2, calls)
    }

    @Test fun invalidPreferredLyricsFallBackToBuiltInSource() = runTest {
        val repository = LyricsRepository(
            remoteProvider = { result("fallback") },
            preferredProvider = { _, _ -> LyricQueryResult("not timed lyrics", source = "bad") },
            preferredSource = { LyricSource.LRCLIB },
        )
        assertEquals("fallback", repository.load(song).lines.single().text)
    }

    @Test fun invalidationRejectsInflightCacheWrites() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = LyricsRepository(remoteProvider = { started.complete(Unit); release.await(); result("old") })
        val loading = async { repository.load(song) }
        started.await()
        repository.invalidateAll()
        release.complete(Unit)
        loading.await()
        assertNull(repository.cached(song))
    }

    @Test fun cancellationIsNotConvertedIntoAnEmptyResult() = runTest {
        val repository = LyricsRepository(remoteProvider = { throw CancellationException("cancel") })
        assertFailsWith<CancellationException> { repository.load(song) }
        assertNull(repository.cached(song))
    }

    @Test fun ccOffsetsAreIndependentAndPreserveCueEndTimes() = runTest {
        val repository = LyricsRepository(
            remoteProvider = { LyricQueryResult("[00:01]字幕", source = "CC", isBiliCc = true,
                timedLines = listOf(LyricLine(1000, "字幕", translation = "subtitle", endMs = 2500))) },
            offsetMs = { 900 }, biliCcOffsetMs = { 100 },
        )
        val line = repository.load(song).lines.single()
        assertEquals(900L, line.timeMs)
        assertEquals(2400L, line.endMs)
        assertEquals("subtitle", line.translation)
    }

    @Test fun localSidecarWinsOverOnlinePreference() = runTest {
        val dir = Files.createTempDirectory("neri-lyrics").toFile()
        try {
            val audio = dir.resolve("song.wav").apply { writeBytes(byteArrayOf(1)) }
            dir.resolve("song.lrc").writeText("[00:01]local")
            val repository = LyricsRepository(preferredSource = { LyricSource.NETEASE },
                preferredProvider = { _, _ -> fail("Local sidecar should win") })
            assertEquals("local", repository.load(Song("local", filePath = audio.path)).lines.single().text)
        } finally { dir.deleteRecursively() }
    }
}
