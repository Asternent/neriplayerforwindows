package moe.ouom.neriplayer.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Comment
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.MediaSource
import moe.ouom.neriplayer.desktop.core.Song
import moe.ouom.neriplayer.desktop.net.Comment
import moe.ouom.neriplayer.desktop.net.CommentEmote
import moe.ouom.neriplayer.desktop.net.CommentImage
import moe.ouom.neriplayer.desktop.net.CommentSegment
import moe.ouom.neriplayer.desktop.net.formatCommentTime
import moe.ouom.neriplayer.desktop.net.splitCommentContent

enum class CommentPanelState { COLLAPSED, HALF, FULL }

/** 内联表情占位符与图片尺寸（与手机端一致，避免含表情的行高忽高忽低）。 */
private val EmotePlaceholderSize = 20.sp
private val EmoteImageSize = 20.dp

/** 单图预览最大高度与网格列数。 */
private val CommentImageSingleMaxHeight = 220.dp
private const val CommentImageGridColumns = 3
private const val CommentImageGridMaxVisible = 9

/**
 * 歌曲评论面板（网易云 / 哔哩哔哩）。
 *
 * 复用播放队列那套右侧抽屉（[OverlayPanel]），第一页会同时拿到「热门评论」与
 * 「最新评论」（网易云接口一次就返回两组），后续按已取条数翻页。
 * 本地歌曲没有可查的评论，调用方不会打开这个面板。
 */
@Composable
fun CommentsPanel(
    container: AppContainer,
    song: Song,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var panelState by remember { mutableStateOf(CommentPanelState.HALF) }
    val pageSize = remember { container.settings.current.commentPageSize.coerceIn(5, 50) }
    // 本地歌曲没有可查的评论：直接给一句明确的话，别去请求再报「加载失败」
    val isLocal = song.source == MediaSource.LOCAL

    var hot by remember(song.key) { mutableStateOf(emptyList<Comment>()) }
    var latest by remember(song.key) { mutableStateOf(emptyList<Comment>()) }
    var total by remember(song.key) { mutableStateOf(0) }
    var hasMore by remember(song.key) { mutableStateOf(false) }
    var loading by remember(song.key) { mutableStateOf(!isLocal) }
    var loadingMore by remember(song.key) { mutableStateOf(false) }
    var error by remember(song.key) { mutableStateOf<String?>(null) }
    // B 站按页翻页：页码必须自己记住，不能用「已取条数 / 每页条数」反推
    var page by remember(song.key) { mutableStateOf(1) }
    /** 正在放大预览的图片集合与下标。 */
    var preview by remember(song.key) { mutableStateOf<Pair<List<CommentImage>, Int>?>(null) }

    suspend fun load(reset: Boolean) {
        if (reset) {
            loading = true
            error = null
        } else {
            loadingMore = true
        }
        val offset = if (reset) 0 else latest.size
        val nextPage = if (reset) 1 else page + 1
        val result = runCatching { container.online.comments(song, offset, pageSize, nextPage) }.getOrNull()
        when {
            result == null -> if (reset) error = "暂时拿不到评论（可能是网络问题，或这首歌没有开放评论）"
            result.error != null -> error = result.error
            reset -> {
                hot = result.hot
                latest = result.latest
                total = result.total
                hasMore = result.hasMore
                page = nextPage
            }
            else -> {
                // 追加时按评论 id 去重：接口在翻页边界上偶尔会重复返回同一条
                val seen = latest.mapTo(HashSet()) { it.id }
                latest = latest + result.latest.filter { seen.add(it.id) }
                total = result.total
                hasMore = result.hasMore
                page = nextPage
            }
        }
        loading = false
        loadingMore = false
    }

    LaunchedEffect(song.key) { if (!isLocal) load(reset = true) }

    val panelWidth = when (panelState) {
        CommentPanelState.COLLAPSED -> 0.dp // 随后会关闭面板
        CommentPanelState.HALF -> 420.dp
        CommentPanelState.FULL -> 800.dp
    }

    LaunchedEffect(panelState) {
        if (panelState == CommentPanelState.COLLAPSED) {
            onClose()
        }
    }

    OverlayPanel(
        title = "评论",
        onClose = onClose,
        width = panelWidth,
        headerActions = {
            TextButton(
                onClick = {
                    panelState = if (panelState == CommentPanelState.HALF) {
                        CommentPanelState.FULL
                    } else {
                        CommentPanelState.HALF
                    }
                },
            ) {
                Text(if (panelState == CommentPanelState.HALF) "全屏" else "缩小")
            }
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    isLocal -> "本地歌曲"
                    loading -> "正在加载…"
                    total > 0 -> "${song.source.displayName} · 共 $total 条"
                    else -> song.source.displayName
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (!isLocal) {
                TextButton(onClick = { scope.launch { load(reset = true) } }) { Text("刷新") }
            }
        }

        when {
            isLocal -> EmptyState(
                title = "本地歌曲没有在线评论",
                hint = "评论来自网易云 / 哔哩哔哩，只有在线歌曲才能查看",
                icon = Icons.Outlined.Comment,
                modifier = Modifier.weight(1f),
            )

            loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }

            // 有内容时即使翻页失败也继续显示，只在完全没内容时占满整块
            error != null && hot.isEmpty() && latest.isEmpty() -> EmptyState(
                title = "评论加载失败",
                hint = error.orEmpty(),
                icon = Icons.Outlined.Comment,
                modifier = Modifier.weight(1f),
            )

            hot.isEmpty() && latest.isEmpty() -> EmptyState(
                title = "还没有评论",
                hint = "这首歌暂时没人留言",
                icon = Icons.Outlined.Comment,
                modifier = Modifier.weight(1f),
            )

            else -> LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (hot.isNotEmpty()) {
                    item(key = "hot-header") { CommentSectionHeader("热门评论") }
                    items(hot, key = { "hot:${it.id}" }) { comment ->
                        CommentRow(comment, isHot = true, onPreviewImage = { images, index ->
                            preview = images to index
                        })
                    }
                }
                if (latest.isNotEmpty()) {
                    item(key = "latest-header") { CommentSectionHeader("最新评论") }
                    items(latest, key = { "latest:${it.id}" }) { comment ->
                        CommentRow(comment, isHot = false, onPreviewImage = { images, index ->
                            preview = images to index
                        })
                    }
                }
                if (error != null) {
                    item(key = "load-more-error") {
                        Text(
                            text = error.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        )
                    }
                }
                if (hasMore) {
                    item(key = "load-more") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            if (loadingMore) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp))
                            } else {
                                TextButton(onClick = { scope.launch { load(reset = false) } }) {
                                    Text("加载更多")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    preview?.let { (images, index) ->
        CommentImagePreview(
            images = images,
            initialIndex = index,
            onDismiss = { preview = null },
        )
    }
}

@Composable
private fun CommentSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun CommentRow(
    comment: Comment,
    isHot: Boolean,
    onPreviewImage: (List<CommentImage>, Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        RemoteArtwork(
            url = comment.avatarUrl,
            size = 34.dp,
            shape = RoundedCornerShape(17.dp),
            fallback = Icons.Outlined.Comment,
            // 34dp 的头像按 96px 解码就够了，别占封面缓存的额度
            maxPx = 96,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = comment.author,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                comment.userLevel?.let { level ->
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Lv.$level",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isHot) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "热",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(3.dp))

            // 被引用的楼层：原评论删掉时只有一句「原评论已删除」
            comment.quote?.let { quote ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = "回复 @${quote.author}：",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = quote.content ?: "原评论已删除",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            CommentRichText(
                content = comment.content,
                emotes = comment.emotes,
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
            )

            if (comment.images.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                CommentImageGrid(images = comment.images, onPreview = onPreviewImage)
            }

            // 楼中楼预览（网易云 showFloorComment / B 站 replies 的前几条）
            if (comment.replies.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    comment.replies.forEach { reply ->
                        Row {
                            Text(
                                text = "${reply.author}：",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = reply.content,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (comment.replyCount > comment.replies.size) {
                        Text(
                            text = "共 ${comment.replyCount} 条回复，这里只显示前 ${comment.replies.size} 条",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatCommentTime(comment.timeMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                comment.location?.let { location ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = location,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (comment.likedCount > 0) {
                    Text(
                        text = "♥ ${comment.likedCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (comment.replyCount > 0 && comment.replies.isEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "💬 ${comment.replyCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 评论正文：把 `[大笑]` 这类标记渲染成内联表情图。
 *
 * 没有命中任何表情时直接走纯文本，省掉 AnnotatedString 的构建。
 */
@Composable
private fun CommentRichText(
    content: String,
    emotes: List<CommentEmote>,
    style: TextStyle,
) {
    val segments = remember(content, emotes) { splitCommentContent(content, emotes) }
    val emoteSegments = segments.filterIsInstance<CommentSegment.Emote>()
    if (emoteSegments.isEmpty()) {
        Text(text = content, style = style)
        return
    }

    // 每段表情各自取图；列表长度由正文决定，重建时也保持同样的顺序
    val bitmaps = ArrayList<androidx.compose.ui.graphics.ImageBitmap?>(emoteSegments.size)
    for (segment in emoteSegments) {
        bitmaps += rememberRemoteArtwork(segment.emote.url, maxPx = 64)
    }
    val inlineContent = LinkedHashMap<String, InlineTextContent>()
    var emoteIndex = 0
    val annotated = buildAnnotatedString {
        segments.forEach { segment ->
            when (segment) {
                is CommentSegment.Text -> append(segment.text)
                is CommentSegment.Emote -> {
                    val id = "emote_$emoteIndex"
                    appendInlineContent(id, segment.emote.placeholder)
                    val bitmap = bitmaps.getOrNull(emoteIndex)
                    inlineContent[id] = InlineTextContent(
                        placeholder = Placeholder(
                            width = EmotePlaceholderSize,
                            height = EmotePlaceholderSize,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                        ),
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = segment.emote.placeholder,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.size(EmoteImageSize),
                            )
                        }
                    }
                    emoteIndex++
                }
            }
        }
    }
    Text(text = annotated, style = style, inlineContent = inlineContent)
}

/** 评论配图：单图按原始比例、多图 3 列方格，都不使用可滚动容器以免嵌套滚动。 */
@Composable
private fun CommentImageGrid(
    images: List<CommentImage>,
    onPreview: (List<CommentImage>, Int) -> Unit,
) {
    if (images.isEmpty()) return
    if (images.size == 1) {
        val image = images.first()
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .clip(RoundedCornerShape(10.dp))
                .clickable { onPreview(images, 0) },
        ) {
            val height = minOf(
                maxWidth / image.aspectRatio.coerceAtLeast(0.01f),
                CommentImageSingleMaxHeight,
            )
            CommentThumb(image, Modifier.fillMaxWidth().height(height))
        }
        return
    }

    val visible = images.take(CommentImageGridMaxVisible)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        visible.chunked(CommentImageGridColumns).forEach { rowImages ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rowImages.forEach { image ->
                    val index = visible.indexOf(image)
                    CommentThumb(
                        image = image,
                        modifier = Modifier
                            .weight(1f)
                            .height(88.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPreview(images, index) },
                    )
                }
                // 最后一行不足 3 张时补占位，保证方格宽度一致
                repeat(CommentImageGridColumns - rowImages.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CommentThumb(image: CommentImage, modifier: Modifier = Modifier) {
    val bitmap = rememberRemoteArtwork(image.url, maxPx = 640)
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "评论配图",
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
}

/** 全屏图片预览：左右翻页 + Esc / 点击背景关闭。 */
@Composable
private fun CommentImagePreview(
    images: List<CommentImage>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    if (images.isEmpty()) return
    var index by remember(images) { mutableStateOf(initialIndex.coerceIn(0, images.lastIndex)) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF101012))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = rememberRemoteArtwork(images[index].url, maxPx = 1600)
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "评论配图 ${index + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(48.dp),
                )
            } else {
                CircularProgressIndicator()
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
            }

            if (images.size > 1) {
                if (index > 0) {
                    IconButton(
                        onClick = { index -= 1 },
                        modifier = Modifier.align(Alignment.CenterStart).padding(8.dp),
                    ) {
                        Icon(Icons.Outlined.KeyboardArrowLeft, contentDescription = "上一张", tint = Color.White)
                    }
                }
                if (index < images.lastIndex) {
                    IconButton(
                        onClick = { index += 1 },
                        modifier = Modifier.align(Alignment.CenterEnd).padding(8.dp),
                    ) {
                        Icon(Icons.Outlined.KeyboardArrowRight, contentDescription = "下一张", tint = Color.White)
                    }
                }
                Text(
                    text = "${index + 1} / ${images.size}",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                )
            }
        }
    }
}
