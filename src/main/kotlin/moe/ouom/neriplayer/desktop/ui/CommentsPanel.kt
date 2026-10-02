package moe.ouom.neriplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Comment
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.MediaSource
import moe.ouom.neriplayer.desktop.core.Song
import moe.ouom.neriplayer.desktop.net.Comment
import moe.ouom.neriplayer.desktop.net.formatCommentTime

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

    suspend fun load(reset: Boolean) {
        if (reset) {
            loading = true
            error = null
        } else {
            loadingMore = true
        }
        val offset = if (reset) 0 else latest.size
        val page = runCatching { container.online.comments(song, offset, pageSize) }.getOrNull()
        if (page == null) {
            if (reset) error = "暂时拿不到评论（可能是网络问题，或这首歌没有开放评论）"
        } else {
            if (reset) {
                hot = page.hot
                latest = page.latest
                total = page.total
            } else {
                // 追加时按评论 id 去重：接口在翻页边界上偶尔会重复返回同一条
                val seen = latest.mapTo(HashSet()) { it.id }
                latest = latest + page.latest.filter { seen.add(it.id) }
            }
            hasMore = page.hasMore
        }
        loading = false
        loadingMore = false
    }

    LaunchedEffect(song.key) { if (!isLocal) load(reset = true) }

    OverlayPanel(title = "评论", onClose = onClose) {
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

            error != null -> EmptyState(
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
                        CommentRow(comment, isHot = true)
                    }
                }
                if (latest.isNotEmpty()) {
                    item(key = "latest-header") { CommentSectionHeader("最新评论") }
                    items(latest, key = { "latest:${it.id}" }) { comment ->
                        CommentRow(comment, isHot = false)
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
private fun CommentRow(comment: Comment, isHot: Boolean) {
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
            Text(
                text = comment.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
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
                if (comment.replyCount > 0) {
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
