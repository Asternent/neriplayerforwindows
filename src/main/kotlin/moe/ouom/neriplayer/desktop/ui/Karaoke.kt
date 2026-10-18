package moe.ouom.neriplayer.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import moe.ouom.neriplayer.desktop.core.LyricLine
import moe.ouom.neriplayer.desktop.core.LyricWord
import moe.ouom.neriplayer.desktop.core.lyricProgressAt
import kotlin.math.floor

/** 扫光的重绘间隔：30fps。按字符插值后已经足够顺滑，比 60fps 省一半绘制开销。 */
private const val FRAME_INTERVAL_MS = 33L

/**
 * 逐字高亮（卡拉OK）所需的运行时数据。
 *
 * 时间由 [positionMs] 以 lambda 形式给出：只在**绘制阶段**读它，
 * 位置每 200ms 变一次也不会触发重组，只重绘这一行。
 */
@Stable
data class KaraokeSpec(
    val words: List<LyricWord>,
    val startMs: Long,
    val endMs: Long,
    val positionMs: () -> Long,
)

/** 从某一行歌词构造 karaoke 数据；这行没有逐字时间轴时返回 null。 */
fun LyricLine.karaokeSpec(positionMs: () -> Long): KaraokeSpec? {
    if (words.isEmpty()) return null
    return KaraokeSpec(
        words = words,
        startMs = timeMs,
        endMs = effectiveEndMs,
        positionMs = positionMs,
    )
}

/**
 * 卡拉OK 逐字扫光。
 *
 * 底色层整行绘制，高亮层按当前进度裁到「已唱到的那个字」的中间，
 * 于是能从左到右连续扫过去，而不是整行/整词跳变。
 *
 * 这里用 [TextMeasurer] 自己排版一次、在 Canvas 里画两遍：
 * 相比「两个 Text 叠起来 + onTextLayout 回写」不会出现「排版 → 重组 → 再排版」的抖动。
 */
@Composable
fun KaraokeLyricText(
    text: String,
    style: TextStyle,
    activeColor: Color,
    karaoke: KaraokeSpec?,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
) {
    if (karaoke == null || karaoke.words.isEmpty()) {
        BasicText(
            text = text,
            modifier = modifier,
            style = style,
            maxLines = maxLines,
            overflow = overflow,
            softWrap = softWrap,
        )
        return
    }

    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    // 只在「进度真的变了」时才重绘：两句之间、间奏、暂停时扫光不动，
    // 就完全不产生绘制，避免每帧把整个窗口重画一遍（软件渲染下这很贵）
    val progress = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(karaoke?.startMs, karaoke?.endMs, text) {
        if (karaoke == null || karaoke.words.isEmpty()) return@LaunchedEffect
        progress.floatValue = lyricProgressAt(
            words = karaoke.words,
            lineStartMs = karaoke.startMs,
            lineEndMs = karaoke.endMs,
            textLength = text.length,
            positionMs = karaoke.positionMs(),
        )
        while (true) {
            // 用 delay 而不是 withFrameNanos：后者会持续向渲染器要帧，
            // 即使这一行没开始唱／已经唱完也会每帧把窗口重画一遍。
            // 这里只在进度真的变了的时候写 state，静止时完全不重绘。
            delay(FRAME_INTERVAL_MS)
            val value = lyricProgressAt(
                words = karaoke.words,
                lineStartMs = karaoke.startMs,
                lineEndMs = karaoke.endMs,
                textLength = text.length,
                positionMs = karaoke.positionMs(),
            )
            if (value != progress.floatValue) progress.floatValue = value
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val widthPx = constraints.maxWidth
        if (widthPx <= 0 || text.isEmpty()) return@BoxWithConstraints
        val layout = remember(text, style, widthPx, maxLines, overflow, softWrap, density, layoutDirection) {
            measure(measurer, text, style, widthPx, maxLines, overflow, softWrap)
        }
        val heightDp = with(density) { layout.size.height.toDp() }
        if (heightDp <= 0.dp) return@BoxWithConstraints
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(heightDp)
                .clipToBounds(),
        ) {
            drawKaraokeLayout(layout, style.color, activeColor, progress.floatValue)
        }
    }
}

private fun measure(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    widthPx: Int,
    maxLines: Int,
    overflow: TextOverflow,
    softWrap: Boolean,
): TextLayoutResult = measurer.measure(
    text = AnnotatedString(text),
    style = style,
    overflow = overflow,
    softWrap = softWrap,
    maxLines = maxLines,
    // 撑满可用宽度，让 textAlign 与外面那层 Text 的表现一致
    constraints = Constraints(minWidth = widthPx, maxWidth = widthPx),
)

/**
 * 按字符进度绘制逐字扫光。
 *
 * 与上游 Android 版 `multilineGradientReveal` 相同的思路：按**排版后的每一行**分别裁剪，
 * 行内再用「当前字符的左边界 + 下一个字符的左边界」插值出扫光的 x 坐标，
 * 这样多行歌词也是「上一行唱完才轮到下一行」。
 */
private fun DrawScope.drawKaraokeLayout(
    layout: TextLayoutResult,
    baseColor: Color,
    activeColor: Color,
    progress: Float,
) {
    drawText(layout, color = baseColor)
    if (progress <= 0f) return
    if (progress >= 1f) {
        drawText(layout, color = activeColor)
        return
    }
    val reveal = progress * layout.layoutInput.text.length
    for (lineIndex in 0 until layout.lineCount) {
        val lineStart = layout.getLineStart(lineIndex)
        val lineEnd = layout.getLineEnd(lineIndex, visibleEnd = true)
        if (lineEnd <= lineStart) continue
        val top = layout.getLineTop(lineIndex)
        val bottom = layout.getLineBottom(lineIndex)
        val left = layout.getLineLeft(lineIndex)
        val right = layout.getLineRight(lineIndex)
        when {
            reveal >= lineEnd -> clipRect(left, top, right, bottom) {
                drawText(layout, color = activeColor)
            }

            reveal > lineStart -> {
                val within = reveal - lineStart
                val whole = floor(within).toInt().coerceIn(0, (lineEnd - lineStart) - 1)
                val charIndex = lineStart + whole
                val fraction = (within - whole).coerceIn(0f, 1f)
                val startX = charLeft(layout, charIndex, left)
                val endX = if (charIndex + 1 < lineEnd) charLeft(layout, charIndex + 1, right) else right
                val x = (startX + (endX - startX) * fraction).coerceIn(left, right)
                if (x > left) {
                    clipRect(left, top, x, bottom) {
                        drawText(layout, color = activeColor)
                    }
                }
            }
            // 还没唱到这一行：不画
        }
    }
}

private fun charLeft(layout: TextLayoutResult, index: Int, fallback: Float): Float = runCatching {
    layout.getBoundingBox(index).left
}.recoverCatching {
    layout.getHorizontalPosition(index, usePrimaryDirection = true)
}.getOrDefault(fallback)

/**
 * 播放位置平滑器。
 *
 * 引擎的进度是每 200ms 一次的快照，直接拿来扫光会「一顿一顿」；
 * 这里在两次快照之间按墙钟插值，最多补 260ms（比一个快照周期略长），
 * 快照一到就重新对齐，既顺滑又不会越走越偏。
 *
 * 注意这里**不跑逐帧循环**：取位置的一方（[KaraokeLyricText]）自己按 30fps 轮询，
 * 否则即使歌词没在唱，也会一直向渲染器要帧、白白重绘整个窗口。
 */
@Composable
fun rememberSmoothLyricPosition(
    positionMs: () -> Long,
    playing: Boolean,
): () -> Long {
    val clock = remember { LyricPositionClock() }
    LaunchedEffect(Unit) {
        snapshotFlow { positionMs() }.collect { value -> clock.onTick(value) }
    }
    LaunchedEffect(playing) {
        clock.updatePlaying(playing)
    }
    return remember { { clock.read() } }
}

/** 带单调时钟锚点的播放位置，见 [rememberSmoothLyricPosition]。 */
@Stable
private class LyricPositionClock {
    private var position by mutableLongStateOf(0L)
    private var playing by mutableStateOf(false)
    private var positionAtNanos = System.nanoTime()

    fun onTick(value: Long) {
        position = value
        positionAtNanos = System.nanoTime()
    }

    fun updatePlaying(value: Boolean) {
        if (playing == value) return
        playing = value
        positionAtNanos = System.nanoTime()
    }

    fun read(): Long {
        val base = position
        if (!playing) return base
        val elapsedMs = (System.nanoTime() - positionAtNanos) / 1_000_000L
        return base + elapsedMs.coerceIn(0L, MAX_EXTRAPOLATION_MS)
    }

    private companion object {
        const val MAX_EXTRAPOLATION_MS = 260L
    }
}
