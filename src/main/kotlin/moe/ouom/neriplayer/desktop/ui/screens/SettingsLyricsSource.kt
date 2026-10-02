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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.net.LyricSource
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

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                container.settings.update {
                    it.copy(
                        lyricOffsetBuiltinMs = 0L,
                        lyricOffsetLrclibMs = 0L,
                        lyricOffsetKugouMs = 0L,
                        lyricOffsetAmllMs = 0L,
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
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                text = if (value > 0) "+${value}ms" else "${value}ms",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (value == 0L) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { raw -> onChange((raw / 50f).roundToInt() * 50L) },
            onValueChangeFinished = onCommit,
            // 与上游一致的量程：±5 秒、50ms 一档
            valueRange = -5000f..5000f,
        )
    }
}
