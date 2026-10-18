package moe.ouom.neriplayer.desktop.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ouom.neriplayer.desktop.core.LyricLine
import moe.ouom.neriplayer.desktop.core.Song
import moe.ouom.neriplayer.desktop.ui.AppLyricsMaxWidth
import moe.ouom.neriplayer.desktop.ui.KaraokeSpec
import moe.ouom.neriplayer.desktop.ui.KaraokeLyricText
import moe.ouom.neriplayer.desktop.ui.LyricsSecondaryLineMode
import moe.ouom.neriplayer.desktop.ui.karaokeSpec
import moe.ouom.neriplayer.desktop.ui.rememberArtwork
import moe.ouom.neriplayer.desktop.ui.rememberSmoothLyricPosition
import moe.ouom.neriplayer.desktop.ui.secondaryLyricText

/** 背景封面按这个尺寸解码后拉伸，等价于一层很重的柔焦，但不需要运行时高斯模糊。 */
private const val BACKDROP_ARTWORK_PX = 48

/** 当前行停在可视区从上往下 36% 的位置（Apple Music 大致也是这个比例）。 */
private const val ACTIVE_LINE_FRACTION = 0.36f

/**
 * 某一行的整体透明度：当前行最亮，越远越暗。
 *
 * 抽成纯函数是为了让自检能直接验证「距离越远越暗」这条规则。
 */
internal fun appleLyricAlpha(active: Boolean, distance: Int): Float = when {
    active -> 1f
    distance <= 1 -> 0.66f
    distance == 2 -> 0.46f
    else -> 0.30f
}

/**
 * Apple Music 风格的歌词页。
 *
 * 三个特征：
 * 1. 背景是**放大压暗的封面**（小尺寸解码后拉伸，天然柔焦）；
 * 2. 大字、白色、加粗，当前行放大并带柔光，其余行按距离淡出；
 * 3. 逐字扫光 + 弹性滚动，当前行始终停在偏上三分之一的位置。
 */
@Composable
fun AppleLyricsScreen(
    song: Song?,
    lines: List<LyricLine>,
    currentIndex: Int,
    loading: Boolean,
    secondaryLine: LyricsSecondaryLineMode,
    fontScale: Float,
    positionMs: () -> Long,
    playing: Boolean,
    wordByWord: Boolean,
    onSeekLine: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
    footer: String? = null,
) {
    val artwork = rememberArtwork(song, maxPx = BACKDROP_ARTWORK_PX)
    val smoothPosition = rememberSmoothLyricPosition(positionMs, playing && wordByWord)
    // 封面稍微降饱和：Apple Music 的背景是「记得住但抢不了戏」的版本
    val mutedColorFilter = remember {
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.55f) })
    }
    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (artwork != null) {
            Image(
                painter = BitmapPainter(artwork),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = mutedColorFilter,
                modifier = Modifier.fillMaxSize().alpha(0.9f),
            )
        }
        // 压暗 + 纵向渐变：保证白色歌词在任何封面上都有足够对比度
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Black.copy(alpha = 0.62f),
                        0.45f to Color.Black.copy(alpha = 0.76f),
                        1f to Color.Black.copy(alpha = 0.93f),
                    ),
                ),
            ),
        )
        Column(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                AppleLyricsHeader(song = song, onBack = onBack, trailing = trailing)
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val viewportHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = AppLyricsMaxWidth)
                        .fillMaxWidth()
                        .fillMaxHeight(),
                ) {
                    when {
                        loading -> CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = Color.White,
                        )

                        lines.isEmpty() -> Text(
                            text = "暂无歌词",
                            modifier = Modifier.align(Alignment.Center),
                            color = Color.White.copy(alpha = 0.65f),
                        )

                        else -> AppleLyricList(
                            lines = lines,
                            currentIndex = currentIndex,
                            viewportHeightPx = viewportHeightPx,
                            fontScale = fontScale,
                            secondaryLine = secondaryLine,
                            wordByWord = wordByWord,
                            smoothPosition = smoothPosition,
                            onSeekLine = onSeekLine,
                        )
                    }
                }
            }
            if (!footer.isNullOrBlank()) {
                Text(
                    text = footer,
                    color = Color.White.copy(alpha = 0.38f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun AppleLyricsHeader(
    song: Song?,
    onBack: () -> Unit,
    trailing: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = song?.displayName().orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song?.artistText().orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.62f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

@Composable
private fun AppleLyricList(
    lines: List<LyricLine>,
    currentIndex: Int,
    viewportHeightPx: Float,
    fontScale: Float,
    secondaryLine: LyricsSecondaryLineMode,
    wordByWord: Boolean,
    smoothPosition: () -> Long,
    onSeekLine: (Long) -> Unit,
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (currentIndex - 2).coerceAtLeast(0),
    )
    LaunchedEffect(currentIndex, lines.size, viewportHeightPx) {
        if (currentIndex !in lines.indices) return@LaunchedEffect
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == currentIndex }) {
            listState.animateScrollToItem(currentIndex)
        }
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == currentIndex }
            ?: return@LaunchedEffect
        // 让当前行停在偏上三分之一：动画用弹簧，切句时有一点「跟手」的阻尼感
        val target = viewportHeightPx * ACTIVE_LINE_FRACTION - info.size / 2f
        listState.animateScrollBy(
            value = info.offset - target,
            animationSpec = spring(dampingRatio = 0.9f, stiffness = 240f),
        )
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(lines) { index, line ->
            val active = index == currentIndex
            AppleLyricLine(
                line = line,
                active = active,
                distance = if (currentIndex < 0) 3 else kotlin.math.abs(index - currentIndex),
                fontScale = fontScale,
                secondaryLine = secondaryLine,
                karaoke = if (active && wordByWord) line.karaokeSpec(smoothPosition) else null,
                onClick = { onSeekLine(line.timeMs) },
            )
        }
    }
}

@Composable
private fun AppleLyricLine(
    line: LyricLine,
    active: Boolean,
    distance: Int,
    fontScale: Float,
    secondaryLine: LyricsSecondaryLineMode,
    karaoke: KaraokeSpec?,
    onClick: () -> Unit,
) {
    val alpha by animateFloatAsState(
        targetValue = appleLyricAlpha(active, distance),
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 500f),
        label = "apple-lyric-alpha",
    )
    val scale by animateFloatAsState(
        targetValue = if (active) 1f else 0.975f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
        label = "apple-lyric-scale",
    )
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
                this.alpha = alpha
            }
            .clip(RoundedCornerShape(10.dp))
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(vertical = 6.dp),
    ) {
        KaraokeLyricText(
            text = line.text.ifBlank { "♪" },
            style = TextStyle(
                fontSize = (26f * fontScale).sp,
                lineHeight = (34f * fontScale).sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                // 未唱到的部分：白色但压暗，扫过之后才亮起来
                color = Color.White.copy(alpha = 0.40f),
                textAlign = TextAlign.Start,
                shadow = if (active) {
                    Shadow(
                        color = Color.White.copy(alpha = 0.45f),
                        offset = Offset.Zero,
                        blurRadius = 18f,
                    )
                } else {
                    null
                },
            ),
            activeColor = Color.White,
            karaoke = karaoke,
            glowPadding = 8.dp,
        )
        secondaryLyricText(line, secondaryLine)?.let { secondary ->
            Text(
                text = secondary,
                fontSize = (16f * fontScale).sp,
                lineHeight = (22f * fontScale).sp,
                color = Color.White.copy(alpha = if (active) 0.72f else 0.38f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
