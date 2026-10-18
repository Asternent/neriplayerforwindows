package moe.ouom.neriplayer.desktop.net

/** 评论正文里的一条内联表情（网易云 `[大笑]`、哔哩哔哩 `[doge]`）。 */
data class CommentEmote(
    /** 正文中真实出现的标记文本，例如 `[doge]`。 */
    val placeholder: String,
    /** 平台官方 CDN 上的表情图。 */
    val url: String,
)

/** 评论里的一张配图（哔哩哔哩 `content.pictures`）。 */
data class CommentImage(
    val url: String,
    val width: Int = 0,
    val height: Int = 0,
) {
    /** 宽高比；平台没给尺寸时回退 1:1，避免图片加载完成前高度跳动。 */
    val aspectRatio: Float
        get() = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 1f
}

/** 被引用的评论（网易云 `beReplied`）；[content] 为 null 表示原评论已删除。 */
data class CommentQuote(
    val author: String,
    val content: String?,
)

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
    val emotes: List<CommentEmote> = emptyList(),
    val images: List<CommentImage> = emptyList(),
    val quote: CommentQuote? = null,
    val userLevel: Int? = null,
)

/** 一页评论：[hot] 只在第一页有值，且已经与 [latest] 去重。 */
data class CommentPage(
    val hot: List<Comment> = emptyList(),
    val latest: List<Comment> = emptyList(),
    val total: Int = 0,
    val hasMore: Boolean = false,
    /** 非空表示这一页其实是失败的，UI 要按错误展示（例如评论已关闭、需要登录）。 */
    val error: String? = null,
) {
    val isEmpty: Boolean get() = hot.isEmpty() && latest.isEmpty()
}

/** 网易云官方表情 CDN 模板，`%s` 换成表情 id。 */
private const val NETEASE_EMOTE_URL_TEMPLATE = "https://s1.music.126.net/style/web2/emt/emoji_%s.png"

/**
 * 网易云 web 端内置表情的「名称 → 官方 CDN 图片 id」映射（取自 core.js 的 i5n 表）。
 * 只内置映射、不上架图片：表情图仍由官方 CDN 按需加载。
 */
private val neteaseEmoteIds: Map<String, String> = mapOf(
    "大笑" to "86", "可爱" to "85", "憨笑" to "359", "色" to "95", "亲亲" to "363",
    "惊恐" to "96", "流泪" to "356", "亲" to "362", "呆" to "352", "哀伤" to "342",
    "呲牙" to "343", "吐舌" to "348", "撇嘴" to "353", "怒" to "361", "奸笑" to "341",
    "汗" to "97", "痛苦" to "346", "惶恐" to "354", "生病" to "350", "口罩" to "351",
    "大哭" to "357", "晕" to "355", "发怒" to "115", "开心" to "360", "鬼脸" to "94",
    "皱眉" to "87", "流感" to "358", "爱心" to "33", "心碎" to "34", "钟情" to "303",
    "星星" to "309", "生气" to "314", "便便" to "89", "强" to "13", "弱" to "372",
    "拜" to "14", "牵手" to "379", "跳舞" to "380", "禁止" to "374", "这边" to "262",
    "爱意" to "106", "示爱" to "376", "嘴唇" to "367", "狗" to "81", "猫" to "78",
    "猪" to "100", "兔子" to "459", "小鸡" to "450", "公鸡" to "461", "幽灵" to "116",
    "圣诞" to "411", "外星" to "101", "钻石" to "52", "礼物" to "107", "男孩" to "0",
    "女孩" to "1", "蛋糕" to "337", "圈" to "312", "叉" to "313",
)

/** 「正文标记 → 表情图 URL」表（惰性构建一次）。 */
val neteaseEmoteUrls: Map<String, String> by lazy {
    neteaseEmoteIds.mapValues { (_, id) -> NETEASE_EMOTE_URL_TEMPLATE.replace("%s", id) }
}

/**
 * 从正文里找出所有命中官方表情表的 `[名称]` 标记。
 *
 * 未知的 `[xxx]` 不产生表情，照旧当纯文本渲染；同一个标记只保留一条。
 */
fun neteaseEmotesIn(content: String): List<CommentEmote> {
    if (content.isEmpty()) return emptyList()
    val catalog = neteaseEmoteUrls
    val result = ArrayList<CommentEmote>()
    var cursor = 0
    while (cursor < content.length) {
        val start = content.indexOf('[', cursor)
        if (start < 0) break
        val end = content.indexOf(']', start + 1)
        if (end < 0) break
        val inner = content.substring(start + 1, end)
        if (inner.isEmpty() || '[' in inner) {
            // 与 `\[[^\[\]]+]` 等价：该位置不成立时从下一个字符继续找
            cursor = start + 1
            continue
        }
        val url = catalog[inner]
        if (url != null) {
            val token = content.substring(start, end + 1)
            if (result.none { it.placeholder == token }) {
                result += CommentEmote(placeholder = token, url = url)
            }
        }
        cursor = end + 1
    }
    return result
}

/** 评论正文按表情标记切分后的最小单元。 */
sealed interface CommentSegment {
    data class Text(val text: String) : CommentSegment
    data class Emote(val emote: CommentEmote) : CommentSegment
}

/**
 * 把评论正文按 [emotes] 切分成「文本 / 表情」序列。
 *
 * 规则与手机端一致：长标记优先匹配（避免 `[do]` 抢走 `[doge]` 的前缀），
 * 未命中的原文原样保留，不产生空的或相邻的文本段。
 */
fun splitCommentContent(content: String, emotes: List<CommentEmote>): List<CommentSegment> {
    if (content.isEmpty()) return emptyList()
    val candidates = emotes
        .filter { it.placeholder.isNotEmpty() }
        .distinctBy { it.placeholder }
        .sortedByDescending { it.placeholder.length }
    if (candidates.isEmpty()) return listOf(CommentSegment.Text(content))

    val segments = ArrayList<CommentSegment>()
    val pending = StringBuilder()
    var index = 0
    while (index < content.length) {
        val matched = candidates.firstOrNull { content.startsWith(it.placeholder, index) }
        if (matched == null) {
            pending.append(content[index])
            index++
            continue
        }
        if (pending.isNotEmpty()) {
            segments += CommentSegment.Text(pending.toString())
            pending.clear()
        }
        segments += CommentSegment.Emote(matched)
        index += matched.placeholder.length
    }
    if (pending.isNotEmpty()) segments += CommentSegment.Text(pending.toString())
    return segments
}

/** 网易云评论对象 → [Comment]。 */
fun neteaseCommentFromJson(item: JsonObjectSelf): Comment? {
    val id = item.long("commentId") ?: item.long("beRepliedCommentId") ?: return null
    val content = item.str("content")?.trim().orEmpty()
    if (content.isEmpty()) return null
    val user = item.obj("user")
    val floor = item.obj("showFloorComment")
    val replyCount = (item.long("replyCount") ?: floor?.long("replyCount") ?: 0L).toInt()
    return Comment(
        id = id.toString(),
        author = user?.str("nickname")?.takeIf { it.isNotBlank() } ?: "网易云用户",
        avatarUrl = normalizeImageUrl(user?.str("avatarUrl")),
        content = content,
        likedCount = item.long("likedCount")?.toInt() ?: 0,
        // 网易云的 time 已经是毫秒
        timeMs = item.long("time") ?: 0L,
        replyCount = replyCount,
        location = item.str("ipLocation")?.takeIf { it.isNotBlank() },
        // 楼中楼预览：只有前几条，完整楼层要点开再拉
        replies = floor?.array("comments")?.objects().orEmpty()
            .mapNotNull { neteaseCommentFromJson(JsonObjectSelf(it)) },
        emotes = neteaseEmotesIn(content),
        quote = item.array("beReplied")?.objects().orEmpty().firstOrNull()?.let { quoted ->
            val quotedItem = JsonObjectSelf(quoted)
            CommentQuote(
                author = quotedItem.obj("user")?.str("nickname")?.takeIf { it.isNotBlank() } ?: "网易云用户",
                // status = -5 表示原评论已删除
                content = if ((quotedItem.long("status") ?: 0L) == -5L) {
                    null
                } else {
                    quotedItem.str("content")
                },
            )
        },
        userLevel = user?.long("level")?.toInt()?.takeIf { it > 0 },
    )
}

/** 哔哩哔哩评论对象 → [Comment]。 */
fun biliCommentFromJson(item: JsonObjectSelf): Comment? {
    val id = item.long("rpid") ?: return null
    val contentObject = item.obj("content")
    val content = contentObject?.str("message")?.trim().orEmpty()
    if (content.isEmpty()) return null
    val member = item.obj("member")
    return Comment(
        id = id.toString(),
        author = member?.str("uname")?.takeIf { it.isNotBlank() } ?: "哔哩哔哩用户",
        // B 站头像/图片常以 // 开头，补上协议
        avatarUrl = normalizeImageUrl(member?.str("avatar")),
        content = content,
        likedCount = item.long("like")?.toInt() ?: 0,
        // B 站的 ctime 是秒
        timeMs = (item.long("ctime") ?: 0L) * 1000L,
        // rcount 是楼中楼数量，缺失时回退到 count
        replyCount = (item.long("rcount") ?: item.long("count") ?: 0L).toInt(),
        replies = item.array("replies")?.objects().orEmpty()
            .mapNotNull { biliCommentFromJson(JsonObjectSelf(it)) },
        emotes = biliEmotesIn(contentObject),
        images = biliImagesIn(contentObject),
        userLevel = member?.obj("level_info")?.long("current_level")?.toInt()?.takeIf { it > 0 },
    )
}

/** 哔哩哔哩 `content.emote` → 表情列表。 */
private fun biliEmotesIn(content: JsonObjectSelf?): List<CommentEmote> {
    val emoteObject = content?.obj("emote") ?: return emptyList()
    return emoteObject.keys().mapNotNull { placeholder ->
        val url = normalizeImageUrl(emoteObject.obj(placeholder)?.str("url")) ?: return@mapNotNull null
        CommentEmote(placeholder = placeholder, url = url)
    }
}

/** 哔哩哔哩 `content.pictures` → 配图列表。 */
private fun biliImagesIn(content: JsonObjectSelf?): List<CommentImage> {
    return content?.array("pictures")?.objects().orEmpty().mapNotNull { picture ->
        val pictureObject = JsonObjectSelf(picture)
        val url = normalizeImageUrl(pictureObject.str("img_src")) ?: return@mapNotNull null
        CommentImage(
            url = url,
            width = pictureObject.long("img_width")?.toInt() ?: 0,
            height = pictureObject.long("img_height")?.toInt() ?: 0,
        )
    }
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
