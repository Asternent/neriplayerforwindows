package moe.ouom.neriplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.CurationCoverMode
import moe.ouom.neriplayer.desktop.core.CurationItem
import moe.ouom.neriplayer.desktop.core.CurationLyricMode
import moe.ouom.neriplayer.desktop.core.CurationOptions
import moe.ouom.neriplayer.desktop.core.CurationPhase
import moe.ouom.neriplayer.desktop.core.LocalCurationService
import moe.ouom.neriplayer.desktop.core.Song

private enum class CurationStage { OPTIONS, ANALYZING, REVIEW, APPLYING, DONE }

/**
 * 本地歌曲批量整理对话框：在线匹配歌词与封面，确认后写回音频标签。
 *
 * 与手机端一致的流程（分析 → 复核 → 写入），但桌面端不需要存储授权，
 * 写完后重新扫描曲库即可在界面上看到新的歌词与封面。
 */
@Composable
fun LocalCurationDialog(
    container: AppContainer,
    songs: List<Song>,
    onDismiss: () -> Unit,
    showMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val service = remember { LocalCurationService(container.online) }
    var stage by remember { mutableStateOf(CurationStage.OPTIONS) }
    var options by remember { mutableStateOf(CurationOptions()) }
    var items by remember { mutableStateOf(songs.map { CurationItem(it) }) }
    var doneCount by remember { mutableStateOf(0) }
    val actionable = items.filter { it.hasProposal }

    fun startAnalyze() {
        stage = CurationStage.ANALYZING
        doneCount = 0
        items = songs.map { CurationItem(it) }
        scope.launch {
            val result = service.analyze(songs, options) { updated ->
                items = items.map { if (it.song.key == updated.song.key) updated else it }
                doneCount += 1
            }
            items = result
            stage = CurationStage.REVIEW
        }
    }

    fun startApply() {
        val targets = items.filter { it.hasProposal }
        if (targets.isEmpty()) return
        stage = CurationStage.APPLYING
        doneCount = 0
        scope.launch {
            for (target in targets) {
                val updated = service.apply(target, options)
                items = items.map { if (it.song.key == updated.song.key) updated else it }
                doneCount += 1
            }
            stage = CurationStage.DONE
            // 标签改完要重新扫描，界面上的歌词/封面才会跟着更新
            runCatching { container.library.scan() }
            showMessage("批量整理完成，已重新扫描曲库")
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.width(640.dp).height(560.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Text(
                    text = "批量整理本地歌曲",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "在线匹配歌词与封面并写回文件，同一张歌单里的 ${songs.size} 首本地歌曲一起处理",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Box(Modifier.weight(1f)) {
                    when (stage) {
                        CurationStage.OPTIONS -> CurationOptionsContent(options) { options = it }
                        CurationStage.ANALYZING -> ProgressContent(
                            title = "正在匹配歌词与封面…",
                            progress = if (songs.isEmpty()) 0f else doneCount.toFloat() / songs.size,
                            detail = "$doneCount / ${songs.size}",
                        )
                        CurationStage.REVIEW -> CurationReviewContent(items)
                        CurationStage.APPLYING -> ProgressContent(
                            title = "正在写入标签…",
                            progress = if (actionable.isEmpty()) 0f else doneCount.toFloat() / actionable.size,
                            detail = "$doneCount / ${actionable.size}",
                        )
                        CurationStage.DONE -> CurationDoneContent(items)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    when (stage) {
                        CurationStage.OPTIONS -> {
                            TextButton(onClick = onDismiss) { Text("取消") }
                            TextButton(
                                onClick = { startAnalyze() },
                                enabled = options.hasWork && songs.isNotEmpty(),
                            ) { Text("开始分析") }
                        }
                        CurationStage.ANALYZING -> Text("分析中可以继续用其它页面，完成后再回来确认")
                        CurationStage.REVIEW -> {
                            TextButton(onClick = { stage = CurationStage.OPTIONS }) { Text("返回修改") }
                            TextButton(
                                onClick = { startApply() },
                                enabled = actionable.isNotEmpty(),
                            ) { Text("写入 ${actionable.size} 首") }
                        }
                        CurationStage.APPLYING -> Text("正在写入，请不要关闭应用")
                        CurationStage.DONE -> {
                            TextButton(onClick = onDismiss) { Text("完成") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CurationOptionsContent(
    options: CurationOptions,
    onChange: (CurationOptions) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OptionRow("歌词") {
            ModeChip("不处理", options.lyricMode == CurationLyricMode.SKIP) {
                onChange(options.copy(lyricMode = CurationLyricMode.SKIP))
            }
            ModeChip("只补缺失", options.lyricMode == CurationLyricMode.FILL_MISSING) {
                onChange(options.copy(lyricMode = CurationLyricMode.FILL_MISSING))
            }
            ModeChip("全部重新匹配", options.lyricMode == CurationLyricMode.REMATCH_ALL) {
                onChange(options.copy(lyricMode = CurationLyricMode.REMATCH_ALL))
            }
        }
        OptionRow("封面") {
            ModeChip("不处理", options.coverMode == CurationCoverMode.SKIP) {
                onChange(options.copy(coverMode = CurationCoverMode.SKIP))
            }
            ModeChip("只补缺失", options.coverMode == CurationCoverMode.FILL_MISSING) {
                onChange(options.copy(coverMode = CurationCoverMode.FILL_MISSING))
            }
            ModeChip("全部替换", options.coverMode == CurationCoverMode.REPLACE_ALL) {
                onChange(options.copy(coverMode = CurationCoverMode.REPLACE_ALL))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("同时生成 .lrc 文件", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "在歌曲旁边写一份同名歌词，方便其它播放器读取",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = options.writeSidecarLyric,
                onCheckedChange = { onChange(options.copy(writeSidecarLyric = it)) },
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "匹配顺序：网易云 → LRCLIB → 酷狗 → AMLL；只写入确定的同一首歌，" +
                "标题或歌手对不上就跳过，不会覆盖成别的歌。仅支持 mp3 / m4a / flac / ogg 等可写标签的格式。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OptionRow(label: String, content: @Composable () -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun ModeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(text) })
}

@Composable
private fun ProgressContent(title: String, progress: Float, detail: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(0.7f),
        )
        Spacer(Modifier.height(6.dp))
        Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CurationReviewContent(items: List<CurationItem>) {
    val ready = items.count { it.hasProposal }
    Column(Modifier.fillMaxSize()) {
        Text(
            text = "可写入 $ready 首，跳过 ${items.size - ready} 首",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(items, key = { it.song.key }) { item ->
                val accent = when (item.phase) {
                    CurationPhase.READY, CurationPhase.DONE -> MaterialTheme.colorScheme.primary
                    CurationPhase.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = item.song.displayName(),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = item.song.artistText(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = item.statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                    )
                }
            }
        }
    }
}

@Composable
private fun CurationDoneContent(items: List<CurationItem>) {
    val done = items.count { it.phase == CurationPhase.DONE }
    val failed = items.count { it.phase == CurationPhase.FAILED }
    val skipped = items.size - done - failed
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("处理完成", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "已写入 $done 首，失败 $failed 首，跳过 $skipped 首",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(items.filter { it.phase == CurationPhase.DONE || it.phase == CurationPhase.FAILED }, key = { it.song.key }) { item ->
                Text(
                    text = "${item.song.displayName()} · ${item.statusText}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.phase == CurationPhase.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
