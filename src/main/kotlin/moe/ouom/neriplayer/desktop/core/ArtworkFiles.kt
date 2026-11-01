package moe.ouom.neriplayer.desktop.core

import java.io.File
import java.security.MessageDigest

fun artworkCacheFile(url: String): File {
    val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        .joinToString("") { "%02x".format(it) }
    return File(AppDirs.coverDir, "$digest.img")
}

fun Song.withDownloadedArtwork(entry: DownloadedSong?): Song {
    if (entry?.songKey != key) return this
    val path = entry.artworkPath?.takeIf { File(it).isFile } ?: return this
    return copy(artworkPath = path)
}

fun readArtworkFile(file: File): ByteArray? = runCatching {
    if (!file.isFile || file.length() > 8L * 1024 * 1024) return null
    file.inputStream().use { moe.ouom.neriplayer.desktop.net.readLimitedBody(it, 8 * 1024 * 1024) }
}.getOrNull()
