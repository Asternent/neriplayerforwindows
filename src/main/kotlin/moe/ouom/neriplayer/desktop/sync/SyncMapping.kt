package moe.ouom.neriplayer.desktop.sync

import moe.ouom.neriplayer.desktop.core.MediaSource
import moe.ouom.neriplayer.desktop.core.Playlist
import moe.ouom.neriplayer.desktop.core.Song
import moe.ouom.neriplayer.desktop.core.biliSongKey
import moe.ouom.neriplayer.desktop.core.localSongKey
import moe.ouom.neriplayer.desktop.core.neteaseSongKey
import java.security.MessageDigest

/**
 * 本地数据与同步数据的互相映射。
 *
 * 身份规则与手机端 SongIdentity.stableKey() 一致：`id|album|mediaUri`，
 * 其中本地文件用 `file://` 形式表达 mediaUri，便于两端识别同一首歌。
 */
object SyncMapping {

    fun songSyncId(song: Song): Long = song.remoteId?.toLongOrNull() ?: 0L

    /**
     * 歌曲的「媒体地址」标识。
     *
     * 本地文件用 `file://`；哔哩哔哩的 remoteId 是 bvid（非数字），
     * 直接用它算同步 ID 会得到 0，导致同一歌单里所有 B 站歌曲身份相同、被合并成一首。
     * 这里把 bvid 编码进 mediaUri（`bili:BV...`），身份键仍然保持 `id|album|mediaUri` 的格式。
     */
    fun songMediaUri(song: Song): String? = when (song.source) {
        MediaSource.LOCAL -> song.filePath?.let { path ->
            if (path.startsWith("file://")) path else "file://$path"
        }

        MediaSource.BILIBILI -> song.remoteId?.takeIf { it.isNotBlank() }?.let { "bili:$it" }
        else -> null
    }

    fun identityKey(song: Song): String = identityKey(songSyncId(song), song.album, songMediaUri(song))

    fun identityKey(id: Long, album: String, mediaUri: String?): String = "$id|${album}|${mediaUri ?: ""}"

    /** 由本地字符串 ID 推导出稳定的正数同步 ID（同一账号下多次同步结果一致）。 */
    fun stableSyncId(localId: String): Long {
        localId.removePrefix("sync:").toLongOrNull()?.let { return it }
        val digest = MessageDigest.getInstance("SHA-256").digest(localId.toByteArray())
        var value = 0L
        for (index in 0 until 8) {
            value = (value shl 8) or (digest[index].toLong() and 0xFF)
        }
        return value and 0x7FFFFFFFFFFFFFFFL
    }

    fun playlistSyncId(playlist: Playlist): Long = stableSyncId(playlist.id)

    fun syncPlaylistId(syncId: Long): String = "sync:$syncId"

    /** 收藏歌单按平台拆分（与手机端一致，每个来源一个收藏夹）。 */
    fun favoriteSyncId(source: MediaSource): Long = stableSyncId("favorites:${source.name.lowercase()}")

    fun toSyncSong(song: Song): SyncSong = SyncSong(
        id = songSyncId(song),
        name = song.displayName(),
        artist = song.artist,
        album = song.album,
        albumId = song.albumId?.toLongOrNull() ?: 0L,
        durationMs = song.durationMs,
        coverUrl = song.artworkUrl,
        mediaUri = songMediaUri(song),
        addedAt = song.dateAdded,
        matchedLyric = song.lyrics,
        // B 站歌曲没有数字 ID：把 bvid 放进 channelId，另一台设备才能还原成可播放的曲目
        channelId = song.remoteId?.takeIf { it.isNotBlank() && song.source == MediaSource.BILIBILI },
        syncMetadataVersion = CURRENT_SYNC_METADATA_VERSION,
    )

    fun fromSyncSong(sync: SyncSong): Song {
        val mediaUri = sync.mediaUri
        val filePath = mediaUri
            ?.takeIf { it.startsWith("file://") }
            ?.removePrefix("file://")
        val biliHint = sync.channelId ?: sync.audioId ?: sync.subAudioId
            ?: mediaUri?.takeIf { it.startsWith("bili:") }?.removePrefix("bili:")
        val source = when {
            filePath != null -> MediaSource.LOCAL
            biliHint != null -> MediaSource.BILIBILI
            sync.id != 0L -> MediaSource.NETEASE
            else -> MediaSource.LOCAL
        }
        val remoteId = when (source) {
            MediaSource.NETEASE -> sync.id.takeIf { it != 0L }?.toString()
            MediaSource.BILIBILI -> biliHint
            else -> null
        }
        val key = when {
            filePath != null -> localSongKey(filePath)
            source == MediaSource.NETEASE && remoteId != null -> neteaseSongKey(remoteId.toLong())
            source == MediaSource.BILIBILI && remoteId != null -> biliSongKey(remoteId)
            else -> "sync:${sync.id}|${sync.album}|${sync.name}"
        }
        return Song(
            key = key,
            source = source,
            title = sync.name,
            artist = sync.artist,
            album = sync.album,
            durationMs = sync.durationMs,
            filePath = filePath,
            remoteId = remoteId,
            artworkUrl = sync.coverUrl ?: sync.customCoverUrl,
            lyrics = sync.matchedLyric,
            dateAdded = sync.addedAt,
            albumId = sync.albumId.takeIf { it != 0L }?.toString(),
        )
    }

    fun toSyncPlaylist(playlist: Playlist): SyncPlaylist = SyncPlaylist(
        id = playlistSyncId(playlist),
        name = playlist.name,
        songs = playlist.songs.map(::toSyncSong),
        createdAt = playlist.createdAt,
        modifiedAt = playlist.updatedAt,
        isDeleted = false,
        songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION,
    )
}
