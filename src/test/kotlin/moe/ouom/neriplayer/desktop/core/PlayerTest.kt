@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package moe.ouom.neriplayer.desktop.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import moe.ouom.neriplayer.desktop.net.LyricQueryResult
import moe.ouom.neriplayer.desktop.net.OnlineRepository
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class PlayerTest {
    private class FakeEngine : AudioEngine {
        override val snapshot = MutableStateFlow(EngineSnapshot())
        override val supportsEffects = false
        override var onCompleted: (() -> Unit)? = null
        override var onError: ((String) -> Unit)? = null
        var plays = 0
        var opens = 0
        override fun open(input: AudioInput, startMs: Long, durationMs: Long) { opens++; snapshot.value = EngineSnapshot(durationMs = durationMs) }
        override fun prepare(durationMs: Long) { snapshot.value = EngineSnapshot(durationMs = durationMs) }
        override fun play() { plays++; snapshot.value = snapshot.value.copy(playing = true) }
        override fun pause() { snapshot.value = snapshot.value.copy(playing = false) }
        override fun release() { snapshot.value = EngineSnapshot() }
        override fun seekTo(positionMs: Long) { snapshot.value = snapshot.value.copy(positionMs = positionMs) }
        override fun setVolume(volume: Float) {}
        override fun setSpeed(speed: Float) {}
        override fun setPitch(semitones: Float) {}
        override fun setLoudness(enabled: Boolean) {}
        override fun setEqualizer(bands: List<Float>?) {}
    }

    private class Fixture(scope: CoroutineScope, lyrics: LyricsRepository = LyricsRepository()) : AutoCloseable {
        val dir = Files.createTempDirectory("neri-player").toFile()
        val audio = dir.resolve("audio.wav").apply { writeBytes(byteArrayOf(1)) }
        val settings = SettingsRepository(dir.resolve("settings.json"))
        val engine = FakeEngine()
        val history = HistoryRepository()
        val stats = StatsRepository(history)
        val downloads = DownloadCatalog(dir.resolve("downloads.json"))
        val player = PlayerManager(settings, history, stats, lyrics, OnlineRepository(), scope, downloads, engine)
        fun song(key: String) = Song(key, durationMs = 2000, filePath = audio.path).also {
            downloads.record(DownloadedSong(key, audio.path))
        }
        override fun close() { settings.flush(); dir.deleteRecursively() }
    }

    @Test fun clearingQueueCancelsPendingPlaybackAndPersistsTheEmptyQueue() = runTest {
        Fixture(backgroundScope).use { f ->
            f.player.setQueue(listOf(f.song("one")), 0)
            f.player.clearQueue()
            runCurrent()
            assertEquals(0, f.engine.plays)
            assertTrue(f.player.queue.value.isEmpty())
            assertTrue(f.settings.current.lastQueue.isEmpty())
            assertFalse(f.player.lyricsLoading.value)
        }
    }
    @Test fun pausingWhilePreparingDoesNotStartPlayingBehindTheUser() = runTest {
        Fixture(backgroundScope).use { f ->
            f.player.setQueue(listOf(f.song("one")), 0)
            f.player.pause()
            runCurrent()
            assertEquals(0, f.engine.plays)
            assertEquals(PlaybackState.PAUSED, f.player.state.value)
            f.player.play()
            runCurrent()
            assertEquals(1, f.engine.plays)
        }
    }
    @Test fun reorderingDuplicateSongsKeepsTheCorrectQueueOccurrence() = runTest {
        Fixture(backgroundScope).use { f ->
            val repeated = f.song("same")
            f.player.setQueue(listOf(repeated, f.song("other"), repeated), 2, autoPlay = false)
            f.player.moveInQueue(1, 0)
            assertEquals(2, f.player.currentIndex.value)
            f.player.moveInQueue(2, 1)
            assertEquals(1, f.player.currentIndex.value)
            assertEquals(1, f.settings.current.lastQueueIndex)
        }
    }
    @Test fun playSongNowActuallyPlaysRequestedSongEvenIfMissingFromQueue() = runTest {
        Fixture(backgroundScope).use { f ->
            val requested = f.song("requested")
            f.player.playSongNow(requested, listOf(f.song("other")))
            runCurrent()
            assertEquals("requested", f.player.currentSong.value?.key)
        }
    }
    @Test fun switchingTracksFlushesListeningTimeToPreviousTrack() = runTest {
        Fixture(backgroundScope).use { f ->
            f.player.setQueue(listOf(f.song("one"), f.song("two")), 0)
            runCurrent()
            Thread.sleep(20)
            f.player.next()
            runCurrent()
            assertTrue(f.stats.stats.value.filter { it.songKey == "one" }.sumOf { it.listenMs } > 0)
            assertEquals(0L, f.stats.stats.value.filter { it.songKey == "two" }.sumOf { it.listenMs })
        }
    }
    @Test fun olderLyricRequestCannotReplaceRefreshedLyricsForTheSameSong() = runTest {
        var requests = 0
        val release = CompletableDeferred<Unit>()
        val lyrics = LyricsRepository(remoteProvider = {
            val sequence = ++requests
            if (sequence == 1) withContext(NonCancellable) { release.await() }
            LyricQueryResult("[00:01]result-$sequence", source = "fixture")
        })
        Fixture(backgroundScope, lyrics).use { f ->
            try {
                f.player.setQueue(listOf(f.song("one")), 0)
                runCurrent()
                f.player.reloadLyrics()
                runCurrent()
                assertEquals("result-2", f.player.lyrics.value.lines.single().text)
                release.complete(Unit)
                runCurrent()
                assertEquals("result-2", f.player.lyrics.value.lines.single().text)
                assertFalse(f.player.lyricsLoading.value)
            } finally { release.complete(Unit) }
        }
    }
}
