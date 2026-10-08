package moe.ouom.neriplayer.desktop.net

/** 一条歌曲评论（网易云与哔哩哔哩共用同一套结构）。 */
data class Comment(
    val id: String,
    val author: String,
    val avatarUrl: String? = null,
    val content: String,
    val likedCount: Int = 0,
    val timeMs: Long = 0L,
    val replyCount: Int = 0,
    val location: String? = null,
    val isHot: Boolean = false,
    val replies: List<Comment> = emptyList(),
)

/** 一页评论：[hot] 只在第一页有值，且已经与 [latest] 去重。 */
data class CommentPage(
    val hot: List<Comment> = emptyList(),
    val latest: List<Comment> = emptyList(),
    val total: Int = 0,
    val hasMore: Boolean = false,
) {
    val isEmpty: Boolean get() = hot.isEmpty() && latest.isEmpty()
}

/** 网易云评论对象 → [Comment]。 */
fun neteaseCommentFromJson(item: JsonObjectSelf): Comment? {
    val id = item.long("commentId") ?: item.long("beRepliedCommentId") ?: return null
    val content = item.str("content")?.trim().orEmpty()
    if (content.isEmpty()) return null
    val user = item.obj("user")
    return Comment(
        id = id.toString(),
        author = user?.str("nickname")?.takeIf { it.isNotBlank() } ?: "网易云用户",
        avatarUrl = normalizeImageUrl(user?.str("avatarUrl")),
        content = content,
        likedCount = item.long("likedCount")?.toInt() ?: 0,
        // 网易云的 time 已经是毫秒
        timeMs = item.long("time") ?: 0L,
        replyCount = (item.long("replyCount") ?: item.long("repliedCount") ?: 0L).toInt(),
        location = item.str("ipLocation")?.takeIf { it.isNotBlank() },
        replies = item.array("beReplied")?.objects().orEmpty().mapNotNull { neteaseCommentFromJson(JsonObjectSelf(it)) },
    )
}

/** 哔哩哔哩评论对象 → [Comment]。 */
fun biliCommentFromJson(item: JsonObjectSelf): Comment? {
    val id = item.long("rpid") ?: return null
    val content = item.obj("content")?.str("message")?.trim().orEmpty()
    if (content.isEmpty()) return null
    val member = item.obj("member")
    return Comment(
        id = id.toString(),
        author = member?.str("uname")?.takeIf { it.isNotBlank() } ?: "哔哩哔哩用户",
        // B 站头像常以 // 开头，补上协议
        avatarUrl = normalizeImageUrl(member?.str("avatar")),
        content = content,
        likedCount = item.long("like")?.toInt() ?: 0,
        // B 站的 ctime 是秒
        timeMs = (item.long("ctime") ?: 0L) * 1000L,
        replyCount = item.long("rcount")?.toInt() ?: 0,
        replies = item.array("replies")?.objects().orEmpty().mapNotNull { biliCommentFromJson(JsonObjectSelf(it)) },
    )
}

/** 把评论时间显示成「刚刚 / 5 分钟前 / 3 天前 / 2024-05-01」。 */
fun formatCommentTime(timeMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (timeMs <= 0L) return ""
    val delta = nowMs - timeMs
    return when {
        delta < 60_000L -> "刚刚"
        delta < 3_600_000L -> "${delta / 60_000L} 分钟前"
        delta < 86_400_000L -> "${delta / 3_600_000L} 小时前"
        delta < 30L * 86_400_000L -> "${delta / 86_400_000L} 天前"
        else -> java.time.Instant.ofEpochMilli(timeMs)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .toString()
    }
}
