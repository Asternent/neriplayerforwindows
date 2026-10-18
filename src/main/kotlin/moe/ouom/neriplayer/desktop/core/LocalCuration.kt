package moe.ouom.neriplayer.desktop.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.desktop.net.LyricSource
import moe.ouom.neriplayer.desktop.net.OnlineRepository
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** 歌词处理方式。 */
enum class CurationLyricMode { SKIP, FILL_MISSING, REMATCH_ALL }

/** 封面处理方式。 */
enum class CurationCoverMode { SKIP, FILL_MISSING, REPLACE_ALL }

/** 单首歌在批处理里的状态。 */
enum class CurationPhase { QUEUED, ANALYZING, READY, NO_MATCH, UNSUPPORTED, APPLYING, DONE, FAILED }

data class CurationOptions(
    val lyricMode: CurationLyricMode = CurationLyricMode.FILL_MISSING,
    val coverMode: CurationCoverMode = CurationCoverMode.FILL_MISSING,
    /** 额外把歌词写成同名 `.lrc`，方便其它播放器读取。 */
    val writeSidecarLyric: Boolean = true,
) {
    val hasWork: Boolean get() = lyricMode != CurationLyricMode.SKIP || coverMode != CurationCoverMode.SKIP
}

data class CurationItem(
    val song: Song,
    val phase: CurationPhase = CurationPhase.QUEUED,
    val hadLyrics: Boolean = false,
    val hadCover: Boolean = false,
    val lyric: String? = null,
    val lyricSource: String = "",
    val matchedTitle: String = "",
    val matchedArtist: String = "",
    val coverUrl: String? = null,
    val message: String = "",
) {
    val hasProposal: Boolean get() = !lyric.isNullOrBlank() || !coverUrl.isNullOrBlank()

    val statusText: String
        get() = when (phase) {
            CurationPhase.QUEUED -> "等待分析"
            CurationPhase.ANALYZING -> "正在匹配"
            CurationPhase.READY -> buildString {
                if (!lyric.isNullOrBlank()) append("歌词 ✓")
                if (!coverUrl.isNullOrBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append("封面 ✓")
                }
                if (message.isNotBlank()) append(" · ").append(message)
            }
            CurationPhase.NO_MATCH -> message.ifBlank { "没有匹配到结果" }
            CurationPhase.UNSUPPORTED -> message.ifBlank { "这个格式不支持写入标签" }
            CurationPhase.APPLYING -> "正在写入"
            CurationPhase.DONE -> message.ifBlank { "已写入" }
            CurationPhase.FAILED -> message.ifBlank { "写入失败" }
        }
}

/**
 * 本地歌曲批量整理：按「标题 + 歌手」在线匹配歌词与封面，再写回音频标签。
 *
 * 与手机端的差异：桌面端直接在文件系统上读写，不需要 MediaStore 授权，
 * 因此去掉了 SAF/授权那几层；匹配顺序、缺失判断与写入字段保持一致。
 */
class LocalCurationService(private val online: OnlineRepository) {

    /** 能否写入内嵌标签（与下载元数据写入支持的容器一致）。 */
    fun supports(song: Song): Boolean {
        val path = song.filePath ?: return false
        val file = File(path)
        return file.isFile && DownloadMetadataWriter.supports(file)
    }

    /** 歌曲当前是否已经有内嵌歌词 / 封面。 */
    fun inspect(song: Song): Pair<Boolean, Boolean> {
        val path = song.filePath ?: return false to false
        val file = File(path)
        val sidecar = File(file.parentFile, "${file.nameWithoutExtension}.lrc")
        return runCatching {
            val audio = AudioFileIO.read(file)
            val tag = audio.tag
            val hasLyrics = !tag?.getFirst(FieldKey.LYRICS).isNullOrBlank() || sidecar.isFile
            val hasCover = tag?.firstArtwork != null || !song.artworkPath.isNullOrBlank()
            hasLyrics to hasCover
        }.getOrElse { false to false }
    }

    /** 并发分析（匹配线上歌词与封面），每完成一首就回调一次。 */
    suspend fun analyze(
        songs: List<Song>,
        options: CurationOptions,
        onItem: (CurationItem) -> Unit,
    ): List<CurationItem> = withContext(Dispatchers.IO) {
        val semaphore = Semaphore(4)
        val results = coroutineScope {
            songs.map { song ->
                async {
                    semaphore.withPermit {
                        val item = runCatching { analyzeOne(song, options) }
                            .getOrElse { error ->
                                CurationItem(song = song, phase = CurationPhase.FAILED, message = error.message ?: "分析失败")
                            }
                        onItem(item)
                        item
                    }
                }
            }.awaitAll()
        }
        results
    }

    private suspend fun analyzeOne(song: Song, options: CurationOptions): CurationItem {
        if (!supports(song)) {
            return CurationItem(
                song = song,
                phase = CurationPhase.UNSUPPORTED,
                message = "只支持 mp3 / m4a / flac / ogg 等能写标签的格式",
            )
        }
        val (hadLyrics, hadCover) = inspect(song)
        val needsLyric = when (options.lyricMode) {
            CurationLyricMode.SKIP -> false
            CurationLyricMode.FILL_MISSING -> !hadLyrics
            CurationLyricMode.REMATCH_ALL -> true
        }
        val needsCover = when (options.coverMode) {
            CurationCoverMode.SKIP -> false
            CurationCoverMode.FILL_MISSING -> !hadCover
            CurationCoverMode.REPLACE_ALL -> true
        }
        if (!needsLyric && !needsCover) {
            return CurationItem(
                song = song,
                phase = CurationPhase.NO_MATCH,
                hadLyrics = hadLyrics,
                hadCover = hadCover,
                message = if (options.lyricMode == CurationLyricMode.SKIP || options.coverMode == CurationCoverMode.SKIP) {
                    "需要的项目已齐全"
                } else {
                    "歌词与封面都已存在"
                },
            )
        }

        val query = listOf(song.title, song.artist).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) {
            return CurationItem(song = song, phase = CurationPhase.NO_MATCH, message = "文件名里没有可用的标题")
        }
        val candidate = online.netease.searchSongs(query, limit = 5)
            .firstOrNull { matchesSong(song, it) }

        var lyric: String? = null
        var lyricSource = ""
        if (needsLyric) {
            val byId = candidate?.remoteId?.let { id -> online.netease.lyric(id) }
            if (!byId?.raw.isNullOrBlank()) {
                lyric = byId?.raw
                lyricSource = byId?.source.orEmpty()
            } else if (candidate != null) {
                // 网易云没给歌词时退回第三方歌词库（用匹配到的标题/歌手查询更准）
                val matched = song.copy(title = candidate.title, artist = candidate.artist)
                val fallback = listOf(LyricSource.LRCLIB, LyricSource.KUGOU, LyricSource.AMLL)
                    .firstNotNullOfOrNull { runCatching { online.preferredLyrics(matched, it) }.getOrNull() }
                if (!fallback?.raw.isNullOrBlank()) {
                    lyric = fallback?.raw
                    lyricSource = fallback?.source.orEmpty()
                }
            }
        }

        val coverUrl = if (needsCover) candidate?.artworkUrl?.takeIf { it.isNotBlank() } else null
        val ready = !lyric.isNullOrBlank() || coverUrl != null
        return CurationItem(
            song = song,
            phase = if (ready) CurationPhase.READY else CurationPhase.NO_MATCH,
            hadLyrics = hadLyrics,
            hadCover = hadCover,
            lyric = lyric,
            lyricSource = lyricSource,
            matchedTitle = candidate?.title.orEmpty(),
            matchedArtist = candidate?.artist.orEmpty(),
            coverUrl = coverUrl,
            message = if (ready) "" else "在线曲库里没找到这首歌",
        )
    }

    /** 写入一首歌：先取封面字节，再一次性写回标签（失败时保留原文件）。 */
    suspend fun apply(item: CurationItem, options: CurationOptions): CurationItem = withContext(Dispatchers.IO) {
        val path = item.song.filePath
        if (path.isNullOrBlank()) {
            return@withContext item.copy(phase = CurationPhase.FAILED, message = "缺少文件路径")
        }
        val file = File(path)
        if (!file.isFile) {
            return@withContext item.copy(phase = CurationPhase.FAILED, message = "文件已不存在")
        }
        val coverBytes = item.coverUrl?.let { url -> online.httpService.download(url) }
        val result = DownloadMetadataWriter.write(
            file = file,
            title = "",
            artist = "",
            album = "",
            lyrics = item.lyric,
            coverBytes = coverBytes,
            coverMimeType = coverBytes?.let(::imageMimeType),
        )
        var sidecarWritten = false
        if (options.writeSidecarLyric && !item.lyric.isNullOrBlank()) {
            sidecarWritten = runCatching {
                File(file.parentFile, "${file.nameWithoutExtension}.lrc")
                    .writeText(item.lyric.orEmpty(), Charsets.UTF_8)
                true
            }.getOrDefault(false)
        }
        val wroteSomething = result.coverWritten || result.lyricWritten
        if (!wroteSomething && !sidecarWritten) {
            item.copy(phase = CurationPhase.FAILED, message = "标签写入失败（文件可能只读或被占用）")
        } else {
            item.copy(
                phase = CurationPhase.DONE,
                message = buildString {
                    if (result.lyricWritten) append("歌词已写入")
                    if (result.coverWritten) {
                        if (isNotEmpty()) append("，")
                        append("封面已写入")
                    }
                    if (sidecarWritten) {
                        if (isNotEmpty()) append("，")
                        append("已生成 .lrc")
                    }
                },
            )
        }
    }

    /** 标题/歌手是否算同一首歌（宽松匹配，避免错配到同名翻唱）。 */
    private fun matchesSong(target: Song, candidate: Song): Boolean {
        val title = normalizeText(target.title)
        val candidateTitle = normalizeText(candidate.title)
        if (title.isBlank() || candidateTitle.isBlank()) return false
        if (!candidateTitle.contains(title) && !title.contains(candidateTitle)) return false
        val artist = normalizeText(target.artist)
        if (artist.isBlank() || candidate.artist.isBlank()) return true
        return normalizeText(candidate.artist).contains(artist) || artist.contains(normalizeText(candidate.artist))
    }

    private fun normalizeText(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }

    companion object {
        /** 时长容差：与在线歌词匹配同一套规则（6%，夹在 7~15 秒之间）。 */
        fun durationToleranceMs(expectedMs: Long): Long =
            min(15_000L, max(7_000L, expectedMs * 6 / 100))
    }
}
