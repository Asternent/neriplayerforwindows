package moe.ouom.neriplayer.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.net.LyricSource
import moe.ouom.neriplayer.desktop.net.BiliSubtitleTrack
import moe.ouom.neriplayer.desktop.core.MediaSource
import moe.ouom.neriplayer.desktop.core.normalizeLyricOffsetMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 「歌词来源」设置分组。
 *
 * 对应上游 Android 版的「歌词音源偏好」：选一个优先来源，并给每个来源配一个
 * 默认时间偏移（有些库的歌词整体早/晚几百毫秒，逐首调太麻烦）。
 * 偏移叠加在 LRC 自带的 `[offset:]` 之上，正值表示歌词提前出现。
 */
@Composable
fun LyricsSourceSettingsSection(
    container: AppContainer,
    showMessage: (String) -> Unit,
) {
    val settings by container.settings.state.collectAsState()
    val current = LyricSource.of(settings.lyricSourcePreference)

    SettingsSection(
        title = "歌词来源",
        description = "优先歌词来源与各来源的默认偏移",
        icon = Icons.Outlined.LibraryMusic,
    ) {
        Text(
            text = "在线歌曲会先去下面选中的来源找歌词；找不到再按「本地 .lrc → 内嵌标签 → 歌曲所属平台」" +
                "的顺序回退。本地歌曲始终优先使用同目录的 .lrc 文件。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SettingLabel("优先来源")
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LyricSource.selectable.take(3).forEach { source ->
                LyricSourceChip(source, current, container, showMessage)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LyricSource.selectable.drop(3).forEach { source ->
                LyricSourceChip(source, current, container, showMessage)
            }
        }

        Spacer(Modifier.height(10.dp))
        SettingLabel("各来源默认偏移（毫秒，正值 = 歌词提前）")
        LyricOffsetSlider(
            label = "内置（网易云 / 哔哩哔哩）",
            value = settings.lyricOffsetBuiltinMs,
            onChange = { value -> container.settings.update { it.copy(lyricOffsetBuiltinMs = value) } },
            onCommit = { container.reloadLyricsAfterSourceChange() },
        )
        LyricOffsetSlider(
            label = "LRCLIB",
            value = settings.lyricOffsetLrclibMs,
            onChange = { value -> container.settings.update { it.copy(lyricOffsetLrclibMs = value) } },
            onCommit = { container.reloadLyricsAfterSourceChange() },
        )
        LyricOffsetSlider(
            label = "酷狗",
            value = settings.lyricOffsetKugouMs,
            onChange = { value -> container.settings.update { it.copy(lyricOffsetKugouMs = value) } },
            onCommit = { container.reloadLyricsAfterSourceChange() },
        )
        LyricOffsetSlider(
            label = "AMLL TTML DB",
            value = settings.lyricOffsetAmllMs,
            onChange = { value -> container.settings.update { it.copy(lyricOffsetAmllMs = value) } },
            onCommit = { container.reloadLyricsAfterSourceChange() },
        )
        LyricOffsetSlider(
            label = "哔哩哔哩 CC 字幕",
            value = settings.lyricOffsetBiliCcMs,
            onChange = { value -> container.settings.update { it.copy(lyricOffsetBiliCcMs = value) } },
            onCommit = { container.reloadLyricsAfterSourceChange() },
        )
        BiliSubtitleControls(container, showMessage)

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                container.settings.update {
                    it.copy(
                        lyricOffsetBuiltinMs = 0L,
                        lyricOffsetLrclibMs = 0L,
                        lyricOffsetKugouMs = 0L,
                        lyricOffsetAmllMs = 0L,
                        lyricOffsetBiliCcMs = 0L,
                    )
                }
                container.reloadLyricsAfterSourceChange()
                showMessage("已把各来源偏移重置为 0")
            }) { Text("重置全部偏移") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                container.reloadLyricsAfterSourceChange()
                showMessage("正在按新设置重新获取歌词…")
            }) { Text("重新获取当前歌词") }
        }
    }
}

@Composable
private fun LyricSourceChip(
    source: LyricSource,
    current: LyricSource,
    container: AppContainer,
    showMessage: (String) -> Unit,
) {
    FilterChip(
        selected = source == current,
        onClick = {
            container.settings.update { it.copy(lyricSourcePreference = source.id) }
            container.reloadLyricsAfterSourceChange()
            showMessage("优先歌词来源：${source.displayName}")
        },
        label = { Text(source.displayName) },
    )
}

/**
 * 单个来源的偏移滑杆。拖动时只更新设置（实时生效），松手后再清缓存重取，
 * 避免拖动过程中反复发起网络请求。
 */
@Composable
private fun LyricOffsetSlider(
    label: String,
    value: Long,
    onChange: (Long) -> Unit,
    onCommit: () -> Unit,
) {
    var pending by remember(value) { mutableStateOf(normalizeLyricOffsetMs(value)) }
    var editing by remember { mutableStateOf(false) }
    var input by remember(value) { mutableStateOf(value.toString()) }
    fun commit(next: Long) {
        pending = normalizeLyricOffsetMs(next)
        onChange(pending)
        onCommit()
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { input = pending.toString(); editing = true }) { Text(
                text = if (pending > 0) "+${pending}ms" else "${pending}ms",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (pending == 0L) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            ) }
        }
        Slider(
            value = pending.toFloat(),
            onValueChange = { raw -> pending = normalizeLyricOffsetMs(raw.toLong()) },
            onValueChangeFinished = { commit(pending) },
            // 与上游一致的量程：±5 秒、50ms 一档
            valueRange = -5000f..5000f,
        )
        Row {
            TextButton(onClick = { commit(pending - 50) }, enabled = pending > -5000) { Text("−50 ms") }
            TextButton(onClick = { commit(pending + 50) }, enabled = pending < 5000) { Text("+50 ms") }
            TextButton(onClick = { commit(0) }, enabled = pending != 0L) { Text("重置") }
        }
    }
    if (editing) {
        val parsed = input.toLongOrNull()?.takeIf { it in -5000L..5000L }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("$label · 偏移") },
            text = {
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, singleLine = true,
                    label = { Text("−5000 至 +5000 毫秒（50 ms 一档）") },
                    isError = parsed == null,
                )
            },
            confirmButton = {
                TextButton(enabled = parsed != null, onClick = { parsed?.let(::commit); editing = false }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun BiliSubtitleControls(container: AppContainer, showMessage: (String) -> Unit) {
    val settings by container.settings.state.collectAsState()
    val song by container.player.currentSong.collectAsState()
    val scope = rememberCoroutineScope()
    var tracks by remember(song?.key) { mutableStateOf<List<BiliSubtitleTrack>>(emptyList()) }
    var loading by remember(song?.key) { mutableStateOf(false) }
    fun changed() = container.reloadLyricsAfterSourceChange()
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("将哔哩哔哩 CC 字幕作为歌词（需要登录）", modifier = Modifier.weight(1f))
        Switch(checked = settings.biliCcLyricsEnabled, onCheckedChange = { enabled ->
            container.settings.update { it.copy(biliCcLyricsEnabled = enabled) }; changed()
        })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("没有人工字幕时允许 AI 字幕", modifier = Modifier.weight(1f))
        Switch(checked = settings.biliCcAiFallback, enabled = settings.biliCcLyricsEnabled, onCheckedChange = { enabled ->
            container.settings.update { it.copy(biliCcAiFallback = enabled) }; changed()
        })
    }
    Text("默认字幕轨道：${settings.biliCcPreferredLanguage.ifBlank { "自动（优先中文人工字幕）" }}",
        style = MaterialTheme.typography.bodySmall)
    Row {
        TextButton(onClick = {
            container.settings.update { it.copy(biliCcPreferredLanguage = "") }; changed()
        }, enabled = settings.biliCcPreferredLanguage.isNotBlank()) { Text("恢复自动") }
        TextButton(enabled = song?.source == MediaSource.BILIBILI && !loading, onClick = {
            val target = song ?: return@TextButton
            loading = true
            scope.launch {
                try {
                    val loaded = withContext(Dispatchers.IO) { container.online.bilibili.subtitleTracks(target) }
                    if (song?.key == target.key) tracks = loaded
                    if (loaded.isEmpty()) showMessage("没有可用字幕，请确认已登录且当前视频提供 CC 字幕")
                } finally { loading = false }
            }
        }) { Text(if (loading) "读取中…" else "读取当前视频字幕轨道") }
    }
    tracks.forEach { track ->
        FilterChip(
            selected = settings.biliCcPreferredLanguage == track.language,
            onClick = {
                container.settings.update { it.copy(biliCcPreferredLanguage = track.language) }; changed()
            },
            label = { Text(track.label + if (track.isAi) "（AI）" else "") },
        )
    }
}
