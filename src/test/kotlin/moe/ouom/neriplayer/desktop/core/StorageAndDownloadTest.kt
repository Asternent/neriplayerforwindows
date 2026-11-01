package moe.ouom.neriplayer.desktop.core

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.desktop.net.OnlineRepository
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class StorageAndDownloadTest {
    @Test fun offsetNormalizationHandlesCorruptedAndExtremeValues() {
        assertEquals(5000L, normalizeLyricOffsetMs(Long.MAX_VALUE))
        assertEquals(-5000L, normalizeLyricOffsetMs(Long.MIN_VALUE))
        assertEquals(100L, normalizeLyricOffsetMs(76))
        assertEquals(-100L, normalizeLyricOffsetMs(-76))
    }
    @Test fun settingsReplaceAndReloadNormalizeOffsetsAndFlushLatestChanges() {
        val dir = Files.createTempDirectory("neri-settings").toFile()
        try {
            val file = dir.resolve("settings.json")
            val repository = SettingsRepository(file)
            repository.replace(AppSettings(lyricOffsetBiliCcMs = Long.MAX_VALUE))
            repository.update { it.copy(lyricOffsetKugouMs = -76) }
            repository.flush()
            assertEquals(5000L, SettingsRepository(file).current.lyricOffsetBiliCcMs)
            assertEquals(-100L, SettingsRepository(file).current.lyricOffsetKugouMs)
        } finally { dir.deleteRecursively() }
    }
    @Test fun concurrentCatalogUpdatesDoNotLoseRecordsOnDisk() {
        val dir = Files.createTempDirectory("neri-catalog").toFile()
        val pool = Executors.newFixedThreadPool(6)
        try {
            val audio = dir.resolve("audio.wav").apply { writeBytes(byteArrayOf(1)) }
            val file = dir.resolve("downloads.json")
            val catalog = DownloadCatalog(file)
            val jobs = (0 until 80).map { id -> pool.submit { catalog.record(DownloadedSong("s$id", audio.path)) } }
            jobs.forEach { it.get(30, TimeUnit.SECONDS) }
            val reloaded = DownloadCatalog(file).apply { load() }
            assertEquals(80, catalog.items.value.size)
            assertEquals(catalog.items.value, reloaded.items.value)
        } finally { pool.shutdownNow(); dir.deleteRecursively() }
    }
    @Test fun duplicateEnqueueAndCancelRetryUseDistinctAttemptIdentities() = runTest {
        val dir = Files.createTempDirectory("neri-download").toFile()
        try {
            val manager = DownloadManager(OnlineRepository(), SettingsRepository(dir.resolve("settings.json")),
                DownloadCatalog(dir.resolve("downloads.json")), backgroundScope, dir)
            val song = Song("n:1", MediaSource.NETEASE, "title")
            assertEquals(1, manager.enqueue(listOf(song, song)))
            val first = manager.tasks.value.single().id
            manager.cancel(first)
            manager.retry(first)
            assertNotEquals(first, manager.tasks.value.single().id)
            assertEquals(DownloadState.QUEUED, manager.tasks.value.single().state)
            manager.clearAll()
            assertTrue(manager.tasks.value.isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun equalTitlesFromDifferentSourcesCannotOverwriteEachOther() = runTest {
        val dir = Files.createTempDirectory("neri-name").toFile()
        try {
            val manager = DownloadManager(OnlineRepository(), SettingsRepository(dir.resolve("s.json")),
                DownloadCatalog(dir.resolve("d.json")), backgroundScope, dir)
            val first = Song("netease:1", MediaSource.NETEASE, "title", "artist")
            assertNotEquals(manager.buildTargetFile(first, "https://example.test/a.mp3"),
                manager.buildTargetFile(first.copy(key = "bili:1"), "https://example.test/a.mp3"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun windowsReservedNamesAndTrailingDotsAreSafe() {
        assertEquals("_CON", sanitizeFileName("CON"))
        assertEquals("_nul.txt", sanitizeFileName("nul.txt"))
        assertFalse(sanitizeFileName("a... ").endsWith('.'))
        assertFalse(sanitizeFileName("bad/name:*?").any { it in "/:*?" })
    }
    @Test fun downloadedCoverIsPreferredButOnlyForTheCorrectSong() {
        val dir = Files.createTempDirectory("neri-cover").toFile()
        try {
            val cover = dir.resolve("cover.jpg").apply { writeBytes(byteArrayOf(1)) }
            val song = Song("n:1", artworkUrl = "https://example.test/cover")
            val entry = DownloadedSong("n:1", "audio.mp3", artworkPath = cover.path)
            assertEquals(cover.path, song.withDownloadedArtwork(entry).artworkPath)
            assertEquals(song, song.withDownloadedArtwork(entry.copy(songKey = "n:2")))
            cover.delete()
            assertEquals(song, song.withDownloadedArtwork(entry))
        } finally { dir.deleteRecursively() }
    }
    @Test fun invalidAudioTaggingNeverDamagesOriginalFile() {
        val dir = Files.createTempDirectory("neri-tags").toFile()
        try {
            val bytes = byteArrayOf(1, 2, 3, 4)
            val file = dir.resolve("invalid.mp3").apply { writeBytes(bytes) }
            assertFalse(DownloadMetadataWriter.write(file, "new", "artist", "album", null, null, null).propertyWritten)
            assertContentEquals(bytes, file.readBytes())
            assertEquals(listOf("invalid.mp3"), dir.listFiles()!!.map { it.name })
        } finally { dir.deleteRecursively() }
    }
}
