package moe.ouom.neriplayer.desktop.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import moe.ouom.neriplayer.desktop.core.AppDirs
import moe.ouom.neriplayer.desktop.core.JsonFileStore
import moe.ouom.neriplayer.desktop.core.Playlist
import moe.ouom.neriplayer.desktop.core.Song

/** 本地删除过的歌单（用于同步时告诉其它设备「这个歌单已经删了」）。 */
@Serializable
data class PlaylistTombstone(
    val id: Long = 0L,
    val deletedAt: Long = 0L,
)

/**
 * 删除墓碑。
 *
 * 同步策略是「歌曲取并集」，如果只在本地把歌从歌单里删掉、不记录墓碑，
 * 下一次合并会把它从远端又并回来 —— 表现为「删掉的歌自己长回来」。
 * 这里把三类删除记下来并随同步上传：
 * - 歌单内歌曲删除（[SyncPlaylistSongDeletion]）
 * - 最近播放删除（[SyncRecentPlayDeletion]）
 * - 歌单整体删除（[PlaylistTombstone]）
 *
 * [withRecordingSuppressed] 用于「应用远端数据」时关闭记录，避免把同步下来的删除又当成新删除上传。
 */
class SyncTombstoneStore(
    file: java.io.File = AppDirs.syncTombstoneFile,
) {
    private val store = JsonFileStore(file, ListSerializer(TombstoneRecord.serializer())) { emptyList() }

    private val lock = Any()
    private var records: List<TombstoneRecord> = store.load()
    private var recordingSuppressed = false

    val songDeletions: List<SyncPlaylistSongDeletion>
        get() = synchronized(lock) { records.filterIsInstance<TombstoneRecord.SongRemoved>().map { it.entry } }

    val recentDeletions: List<SyncRecentPlayDeletion>
        get() = synchronized(lock) { records.filterIsInstance<TombstoneRecord.RecentRemoved>().map { it.entry } }

    val playlistDeletions: List<PlaylistTombstone>
        get() = synchronized(lock) { records.filterIsInstance<TombstoneRecord.PlaylistRemoved>().map { it.entry } }

    /** 一次性取快照，交给同步层拼装 SyncData。 */
    fun snapshot(): TombstoneSnapshot = synchronized(lock) {
        TombstoneSnapshot(
            songDeletions = songDeletions,
            recentDeletions = recentDeletions,
            playlistDeletions = playlistDeletions,
        )
    }

    /** 应用同步结果时不要记录删除（否则会把远端删除当成新的本地删除再传回去）。 */
    fun <T> withRecordingSuppressed(block: () -> T): T = synchronized(lock) {
        recordingSuppressed = true
        try {
            block()
        } finally {
            recordingSuppressed = false
        }
    }

    fun recordSongRemoved(playlist: Playlist, song: Song, now: Long = System.currentTimeMillis()) {
        if (!shouldRecord()) return
        val playlistId = if (playlist.system) {
            SyncMapping.favoriteSyncId(song.source)
        } else {
            SyncMapping.playlistSyncId(playlist)
        }
        val entry = SyncPlaylistSongDeletion(
            playlistId = playlistId,
            songId = SyncMapping.songSyncId(song),
            album = song.album,
            mediaUri = SyncMapping.songMediaUri(song),
            deletedAt = now,
        )
        add(TombstoneRecord.SongRemoved(entry), now)
    }

    fun recordRecentRemoved(song: Song, now: Long = System.currentTimeMillis()) {
        if (!shouldRecord()) return
        val entry = SyncRecentPlayDeletion(
            songId = SyncMapping.songSyncId(song),
            album = song.album,
            mediaUri = SyncMapping.songMediaUri(song),
            deletedAt = now,
        )
        add(TombstoneRecord.RecentRemoved(entry), now)
    }

    fun recordPlaylistDeleted(playlist: Playlist, now: Long = System.currentTimeMillis()) {
        if (!shouldRecord() || playlist.system) return
        val entry = PlaylistTombstone(id = SyncMapping.playlistSyncId(playlist), deletedAt = now)
        add(TombstoneRecord.PlaylistRemoved(entry), now)
    }

    private fun shouldRecord(): Boolean = synchronized(lock) { !recordingSuppressed }

    private fun add(record: TombstoneRecord, now: Long) {
        synchronized(lock) {
            val cutoff = now - TOMBSTONE_TTL_MS
            val merged = (records + record).filter { it.deletedAt >= cutoff }
            // 同一身份只保留最新一条，避免文件无限增长
            val deduped = when (record) {
                is TombstoneRecord.SongRemoved ->
                    merged.filterIsInstance<TombstoneRecord.SongRemoved>()
                        .groupBy { it.key }.map { (_, list) -> list.maxBy { it.deletedAt } } +
                        merged.filterNot { it is TombstoneRecord.SongRemoved }

                is TombstoneRecord.RecentRemoved ->
                    merged.filterIsInstance<TombstoneRecord.RecentRemoved>()
                        .groupBy { it.key }.map { (_, list) -> list.maxBy { it.deletedAt } } +
                        merged.filterNot { it is TombstoneRecord.RecentRemoved }

                is TombstoneRecord.PlaylistRemoved ->
                    merged.filterIsInstance<TombstoneRecord.PlaylistRemoved>()
                        .groupBy { it.key }.map { (_, list) -> list.maxBy { it.deletedAt } } +
                        merged.filterNot { it is TombstoneRecord.PlaylistRemoved }
            }
            records = deduped.sortedByDescending { it.deletedAt }.take(MAX_RECORDS)
            store.save(records)
        }
    }

    private companion object {
        /** 墓碑保留 60 天：足够覆盖「另一台设备很久没同步」的情况，又不会无限增长。 */
        const val TOMBSTONE_TTL_MS = 60L * 24 * 60 * 60 * 1000
        const val MAX_RECORDS = 1200
    }
}

/** 墓碑快照，供同步层一次性拼装 SyncData。 */
data class TombstoneSnapshot(
    val songDeletions: List<SyncPlaylistSongDeletion> = emptyList(),
    val recentDeletions: List<SyncRecentPlayDeletion> = emptyList(),
    val playlistDeletions: List<PlaylistTombstone> = emptyList(),
)

/** 墓碑的持久化形态（JSON 数组，便于人眼排查）。 */@Serializable
sealed interface TombstoneRecord {
    val deletedAt: Long

    @Serializable
    data class SongRemoved(val entry: SyncPlaylistSongDeletion) : TombstoneRecord {
        override val deletedAt: Long get() = entry.deletedAt
        val key: String get() = "${entry.playlistId}|${SyncMapping.identityKey(entry.songId, entry.album, entry.mediaUri)}"
    }

    @Serializable
    data class RecentRemoved(val entry: SyncRecentPlayDeletion) : TombstoneRecord {
        override val deletedAt: Long get() = entry.deletedAt
        val key: String get() = SyncMapping.identityKey(entry.songId, entry.album, entry.mediaUri)
    }

    @Serializable
    data class PlaylistRemoved(val entry: PlaylistTombstone) : TombstoneRecord {
        override val deletedAt: Long get() = entry.deletedAt
        val key: String get() = entry.id.toString()
    }
}
