package moe.ouom.neriplayer.desktop.net

import moe.ouom.neriplayer.desktop.core.LyricFormat
import moe.ouom.neriplayer.desktop.core.MediaSource
import moe.ouom.neriplayer.desktop.core.OnlineArtist
import moe.ouom.neriplayer.desktop.core.OnlineCollection
import moe.ouom.neriplayer.desktop.core.Song
import moe.ouom.neriplayer.desktop.core.YrcParser
import moe.ouom.neriplayer.desktop.core.biliSongKey
import moe.ouom.neriplayer.desktop.core.lyricsToLrc
import moe.ouom.neriplayer.desktop.core.neteaseSongKey
import java.security.MessageDigest

private const val NETEASE_BASE = "https://music.163.com"

private fun md5Hex(text: String): String =
    MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

private fun cleanBiliText(raw: String?): String =
    raw.orEmpty().replace(Regex("<[^>]+>"), "").replace("&quot;", "\"").replace("&amp;", "&").trim()

private fun biliDurationMs(duration: String?): Long {
    if (duration.isNullOrBlank()) return 0L
    val parts = duration.trim().split(':')
    if (parts.size == 1) {
        val seconds = parts[0].toLongOrNull() ?: return 0L
        return seconds * 1000L
    }
    var totalSeconds = 0L
    parts.forEach { part -> totalSeconds = totalSeconds * 60 + (part.toLongOrNull() ?: 0L) }
    return totalSeconds * 1000L
}

internal fun normalizeImageUrl(url: String?): String? {
    val value = url?.trim().orEmpty()
    if (value.isEmpty()) return null
    return when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("http://") -> "https://" + value.removePrefix("http://")
        else -> value
    }
}

/** 网易云音乐公开接口客户端（无需登录即可搜索、取歌词与试听地址）。 */
class NeteaseApi(private val http: HttpService) {

    private val baseHeaders = mapOf(
        "Referer" to "https://music.163.com/",
        "Cookie" to "appver=2.0.2; os=pc",
    )

    private fun getJson(path: String): JsonObjectSelf? {
        val text = http.get("$NETEASE_BASE$path", baseHeaders) ?: return null
        return NeriJsonParser.parse(text).asObject()?.let { JsonObjectSelf(it) }
    }

    private fun postJson(path: String, form: Map<String, String> = emptyMap()): JsonObjectSelf? {
        val text = http.postForm("$NETEASE_BASE$path", form, baseHeaders) ?: return null
        return NeriJsonParser.parse(text).asObject()?.let { JsonObjectSelf(it) }
    }

    private fun songFromJson(obj: JsonObjectSelf): Song? {
        val id = obj.long("id") ?: return null
        val artists = obj.array("artists")?.objects()?.mapNotNull { it.str("name") }.orEmpty()
            .ifEmpty { obj.array("ar")?.objects()?.mapNotNull { it.str("name") }.orEmpty() }
        val albumObj = obj.obj("album") ?: obj.obj("al")
        val albumName = albumObj?.str("name").orEmpty()
        val cover = normalizeImageUrl(albumObj?.str("picUrl") ?: obj.str("picUrl"))
        val duration = obj.long("duration") ?: obj.long("dt") ?: 0L
        val albumId = (albumObj?.long("id"))?.toString()
        return Song(
            key = neteaseSongKey(id),
            source = MediaSource.NETEASE,
            title = obj.str("name").orEmpty().ifBlank { "未知歌曲" },
            artist = artists.joinToString(" / "),
            album = albumName,
            durationMs = duration,
            remoteId = id.toString(),
            artworkUrl = cover,
            albumId = albumId,
            artistId = obj.array("artists")?.objects()?.firstOrNull()?.long("id")?.toString(),
        )
    }

    fun searchSongs(query: String, limit: Int = 30, offset: Int = 0): List<Song> {
        val path = "/api/search/get?s=${urlEncode(query)}&type=1&limit=$limit&offset=$offset"
        val root = getJson(path) ?: return emptyList()
        val songs = root.obj("result")?.array("songs") ?: return emptyList()
        val parsed = songs.objects().mapNotNull { songFromJson(JsonObjectSelf(it)) }
        // 搜索接口不返回封面，缺少封面的歌曲用详情接口补齐
        val missingCoverIds = parsed.filter { it.artworkUrl.isNullOrBlank() }
            .mapNotNull { it.remoteId?.toLongOrNull() }
        if (missingCoverIds.isEmpty()) return parsed
        val covers = songCovers(missingCoverIds.take(50))
        return parsed.map { song ->
            if (song.artworkUrl.isNullOrBlank()) {
                val cover = covers[song.remoteId?.toLongOrNull()]
                if (cover != null) song.copy(artworkUrl = cover) else song
            } else {
                song
            }
        }
    }

    /** 按 id 取单曲详情（自动化脚本 / 直链播放用）。 */
    fun songDetail(id: String): Song? {
        val root = getJson("/api/song/detail?ids=%5B$id%5D") ?: return null
        val item = root.array("songs")?.objects()?.firstOrNull() ?: return null
        return songFromJson(JsonObjectSelf(item))
    }

    /** 批量补齐歌曲封面。 */
    private fun songCovers(ids: List<Long>): Map<Long, String> {        if (ids.isEmpty()) return emptyMap()
        val root = getJson("/api/song/detail?ids=%5B${ids.joinToString(",")}%5D") ?: return emptyMap()
        val list = root.array("songs") ?: return emptyMap()
        return list.objects().mapNotNull { item ->
            val id = item.long("id") ?: return@mapNotNull null
            val cover = normalizeImageUrl(item.obj("album")?.str("picUrl")) ?: return@mapNotNull null
            id to cover
        }.toMap()
    }

    fun searchPlaylists(query: String, limit: Int = 30, offset: Int = 0): List<OnlineCollection> {
        val path = "/api/search/get?s=${urlEncode(query)}&type=1000&limit=$limit&offset=$offset"
        val root = getJson(path) ?: return emptyList()
        val list = root.obj("result")?.array("playlists") ?: return emptyList()
        return list.objects().mapNotNull { item ->
            val id = item.long("id") ?: return@mapNotNull null
            OnlineCollection(
                id = id.toString(),
                name = cleanBiliText(item.str("name")),
                creator = item.obj("creator")?.str("nickname").orEmpty(),
                coverUrl = normalizeImageUrl(item.str("coverImgUrl")),
                trackCount = item.int("trackCount") ?: 0,
                playCount = item.long("playCount") ?: 0L,
                source = MediaSource.NETEASE,
                description = item.str("description").orEmpty(),
            )
        }
    }

    fun searchArtists(query: String, limit: Int = 30, offset: Int = 0): List<OnlineArtist> {
        val path = "/api/search/get?s=${urlEncode(query)}&type=100&limit=$limit&offset=$offset"
        val root = getJson(path) ?: return emptyList()
        val list = root.obj("result")?.array("artists") ?: return emptyList()
        return list.objects().mapNotNull { item ->
            val id = item.long("id") ?: return@mapNotNull null
            OnlineArtist(
                id = id.toString(),
                name = cleanBiliText(item.str("name")),
                avatarUrl = normalizeImageUrl(item.str("picUrl")),
                songCount = item.int("musicSize") ?: item.int("albumSize") ?: 0,
                source = MediaSource.NETEASE,
            )
        }
    }

    fun searchAlbums(query: String, limit: Int = 30, offset: Int = 0): List<OnlineCollection> {
        val path = "/api/search/get?s=${urlEncode(query)}&type=10&limit=$limit&offset=$offset"
        val root = getJson(path) ?: return emptyList()
        val list = root.obj("result")?.array("albums") ?: return emptyList()
        return list.objects().mapNotNull { item ->
            val id = item.long("id") ?: return@mapNotNull null
            OnlineCollection(
                id = id.toString(),
                name = cleanBiliText(item.str("name")),
                creator = item.obj("artist")?.str("name").orEmpty(),
                coverUrl = normalizeImageUrl(item.str("picUrl")),
                trackCount = item.int("size") ?: 0,
                source = MediaSource.NETEASE,
            )
        }
    }

    fun hotSearchKeywords(): List<String> {
        val root = getJson("/api/search/hot") ?: return emptyList()
        val hots = root.obj("result")?.array("hots") ?: return emptyList()
        return hots.objects().mapNotNull { it.obj("first")?.str("keyword") ?: it.str("keyword") }.distinct()
    }

    /** 返回 (播放地址, 音质标签, 文件大小)。 */
    fun songUrl(id: String, level: String = "exhigh"): SongUrl? {
        val bitrate = when (level) {
            "standard" -> 128000
            "higher" -> 192000
            "exhigh", "lossless", "hires" -> 320000
            else -> 320000
        }
        val root = getJson("/api/song/enhance/player/url?ids=%5B$id%5D&br=$bitrate") ?: return null
        val data = root.array("data")?.objects()?.firstOrNull() ?: return null
        val url = data.str("url") ?: return null
        return SongUrl(
            url = url,
            level = data.str("level") ?: level,
            type = data.str("type").orEmpty(),
            size = data.long("size") ?: 0L,
        )
    }

    /**
     * 歌词。
     *
     * 优先走新版 eapi 接口 `/eapi/song/lyric/v1`：它比旧接口多返回 `yrc`（逐字时间轴）
     * 与 `ytlrc` / `yromalrc`（逐字翻译 / 音译）。取不到时退回旧的公开接口，
     * 至少还有行级 LRC，不会因为接口变更就没歌词。
     */
    fun lyric(id: String): LyricQueryResult? {
        eapiLyric(id)?.let { payload ->
            val lrc = payload.obj("lrc")?.str("lyric")?.takeIf { it.isNotBlank() }
            val yrc = payload.obj("yrc")?.str("lyric")?.takeIf { it.isNotBlank() }
            if (lrc != null || yrc != null) {
                val raw = lrc?.let(::normalizeNeteaseLyricText)
                    ?: yrc?.let { lyricsToLrc(YrcParser.parse(it)) }
                if (!raw.isNullOrBlank()) {
                    return LyricQueryResult(
                        raw = raw,
                        translated = payload.obj("ytlrc")?.str("lyric")?.takeIf { it.isNotBlank() }
                            ?: payload.obj("tlyric")?.str("lyric")?.takeIf { it.isNotBlank() },
                        romanized = payload.obj("yromalrc")?.str("lyric")?.takeIf { it.isNotBlank() }
                            ?: payload.obj("romalrc")?.str("lyric")?.takeIf { it.isNotBlank() },
                        source = MediaSource.NETEASE.displayName,
                        wordRaw = yrc,
                        wordFormat = LyricFormat.YRC,
                    )
                }
            }
        }

        val root = getJson("/api/song/lyric?id=$id&lv=1&kv=1&tv=-1&rv=1") ?: return null
        val lyric = root.obj("lrc")?.str("lyric") ?: return null
        if (lyric.isBlank()) return null
        val translated = root.obj("tlyric")?.str("lyric")
        val romanized = root.obj("romalrc")?.str("lyric")
        return LyricQueryResult(
            raw = lyric,
            translated = translated?.takeIf { it.isNotBlank() },
            romanized = romanized?.takeIf { it.isNotBlank() },
            source = MediaSource.NETEASE.displayName,
        )
    }

    /** 新版接口的歌词参数（eapi 加密后作为 `params` 表单提交）。 */
    private fun eapiLyric(id: String): JsonObjectSelf? {
        val path = "/api/song/lyric/v1"
        val json = """{"id":"$id","cp":false,"lv":0,"kv":0,"tv":0,"rv":0,"yv":0,"ytv":0,"yrv":0}"""
        val params = NeteaseCrypto.eapiParams(path, json)
        val text = http.postForm(
            "https://interface.music.163.com/eapi/song/lyric/v1",
            mapOf("params" to params),
            headers = mapOf(
                "Referer" to "https://music.163.com/",
                "Origin" to "https://music.163.com",
                "Cookie" to "os=pc; appver=9.0.50; channel=netease; deviceId=NeriPlayer",
            ),
            timeoutSeconds = 20,
        ) ?: return null
        return NeriJsonParser.parse(text).asObject()?.let { JsonObjectSelf(it) }
    }

    /**
     * 新版接口把「作词: 周杰伦」这类制作信息写成了 JSON 行：
     * `{"t":0,"c":[{"tx":"作词: "},{"tx":"周杰伦"}]}`。
     * 转回普通 LRC 行，否则这些信息会被 LrcParser 当噪声丢掉。
     */
    private fun normalizeNeteaseLyricText(raw: String): String {
        if (raw.lineSequence().none { it.trimStart().startsWith("{") }) return raw
        return raw.lineSequence().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            if (!trimmed.startsWith("{")) return@mapNotNull line
            val obj = NeriJsonParser.parse(trimmed).asObject() ?: return@mapNotNull null
            val time = obj.long("t") ?: 0L
            val text = obj.array("c")?.objects()
                ?.joinToString("") { it.str("tx").orEmpty() }
                ?.trim()
                .orEmpty()
            if (text.isEmpty()) {
                null
            } else {
                "[%02d:%02d.%03d]%s".format(
                    time / 60_000L,
                    time / 1_000L % 60L,
                    time % 1_000L,
                    text,
                )
            }
        }.joinToString("\n")
    }

    /**
     * 歌曲评论。
     *
     * @param offset 已经取到的条数，接口用它做分页。
     * @return (热门评论, 最新评论, 总数, 是否还有更多)
     */
    fun comments(id: String, limit: Int, offset: Int): CommentPage? {
        val root = getJson("/api/v1/resource/comments/R_SO_4_$id?limit=$limit&offset=$offset") ?: return null
        // 业务码不是 200 时别当成「没有评论」：评论已关闭、需要登录都要说清楚
        val code = root.int("code") ?: 200
        if (code != 200) return CommentPage(error = neteaseCommentErrorText(code))
        val hot = root.array("hotComments")?.objects().orEmpty().mapNotNull { neteaseCommentFromJson(JsonObjectSelf(it)) }
        val latest = root.array("comments")?.objects().orEmpty().mapNotNull { neteaseCommentFromJson(JsonObjectSelf(it)) }
        val total = root.long("total")?.toInt() ?: (offset + latest.size)
        val more = root.bool("more") ?: (offset + latest.size < total)
        // 第一页才有热门评论，且热门往往与最新重复，这里去重后只保留一次
        val hotOnly = if (offset == 0) hot.filter { item -> latest.none { it.id == item.id } } else emptyList()
        return CommentPage(hot = hotOnly, latest = latest, total = total, hasMore = more)
    }

    /** 网易云评论业务码 → 给用户看的提示。 */
    private fun neteaseCommentErrorText(code: Int): String = when (code) {
        301, 315, 401, -460 -> "需要登录网易云才能查看这首歌的评论"
        403 -> "这首歌的评论区已关闭，或当前账号没有查看权限"
        404 -> "歌曲不存在，可能已下架"
        in 500..599 -> "网易云评论服务暂时不可用（HTTP $code）"
        else -> "网易云评论接口返回异常（code $code）"
    }

    /**
     * 歌单详情。
     * @param limit 最多返回多少首（首页只展示前几首时传小值以省流量），默认返回完整歌单。
     */
    fun playlistDetail(id: String, limit: Int = Int.MAX_VALUE): Pair<OnlineCollection, List<Song>>? {
        val root = getJson("/api/v6/playlist/detail?id=$id") ?: return null
        val playlist = root.obj("playlist") ?: return null
        val collection = OnlineCollection(
            id = id,
            name = cleanBiliText(playlist.str("name")),
            creator = playlist.obj("creator")?.str("nickname").orEmpty(),
            coverUrl = normalizeImageUrl(playlist.str("coverImgUrl")),
            trackCount = playlist.int("trackCount") ?: 0,
            playCount = playlist.long("playCount") ?: 0L,
            source = MediaSource.NETEASE,
            description = playlist.str("description").orEmpty(),
        )
        val tracks = playlist.array("tracks")?.objects().orEmpty().mapNotNull { songFromJson(JsonObjectSelf(it)) }
        // 详情接口只返回前若干首（热歌榜 200 首只回 10 首），完整列表在 trackIds 里，
        // 缺的部分按 trackIds 顺序用歌曲详情接口补齐，否则歌单会「只显示 10 首」。
        val trackIds = playlist.array("trackIds")?.objects().orEmpty().mapNotNull { item ->
            item.long("id")?.takeIf { it != 0L }
        }
        val wantedIds = trackIds.take(limit)
        val songs = when {
            wantedIds.isEmpty() -> tracks.take(limit)
            wantedIds.size > tracks.size -> fetchSongsByIds(wantedIds)
            else -> tracks.take(limit)
        }
        return collection to songs
    }

    /** 按 id 批量拉取歌曲详情，保持传入顺序；单次请求最多 200 首以控制 URL 长度。 */
    private fun fetchSongsByIds(ids: List<Long>): List<Song> {
        if (ids.isEmpty()) return emptyList()
        val byId = LinkedHashMap<Long, Song>()
        for (chunk in ids.chunked(200)) {
            val path = "/api/song/detail?ids=%5B${chunk.joinToString(",")}%5D"
            val root = getJson(path) ?: continue
            for (item in root.array("songs")?.objects().orEmpty()) {
                val songId = item.long("id") ?: continue
                songFromJson(JsonObjectSelf(item))?.let { song -> byId[songId] = song }
            }
        }
        return ids.mapNotNull { byId[it] }
    }

    fun albumDetail(id: String): Pair<OnlineCollection, List<Song>>? {
        val root = getJson("/api/v1/album/$id") ?: return null
        val album = root.obj("album") ?: return null
        val collection = OnlineCollection(
            id = id,
            name = cleanBiliText(album.str("name")),
            creator = album.obj("artist")?.str("name").orEmpty(),
            coverUrl = normalizeImageUrl(album.str("picUrl")),
            trackCount = album.int("size") ?: 0,
            source = MediaSource.NETEASE,
            description = album.str("description").orEmpty(),
        )
        val songs = root.array("songs")?.objects().orEmpty().mapNotNull { songFromJson(JsonObjectSelf(it)) }
        return collection to songs
    }

    fun artistTopSongs(id: String): List<Song> {
        val root = getJson("/api/v1/artist/$id") ?: return emptyList()
        val hot = root.array("hotSongs") ?: return emptyList()
        return hot.objects().mapNotNull { songFromJson(JsonObjectSelf(it)) }
    }

    fun recommendedNewSongs(limit: Int = 12): List<Song> {
        val root = getJson("/api/personalized/newsong?limit=$limit") ?: return emptyList()
        val list = root.array("result") ?: return emptyList()
        return list.objects().mapNotNull { item ->
            val songObj = item.obj("song") ?: item
            songFromJson(JsonObjectSelf(songObj))?.let { song ->
                if (song.artworkUrl.isNullOrBlank()) song.copy(artworkUrl = normalizeImageUrl(item.str("picUrl"))) else song
            }
        }
    }

    fun recommendedPlaylists(limit: Int = 12): List<OnlineCollection> {
        val root = getJson("/api/personalized/playlist?limit=$limit") ?: return emptyList()
        val list = root.array("result") ?: return emptyList()
        return list.objects().mapNotNull { item ->
            val id = item.long("id") ?: return@mapNotNull null
            OnlineCollection(
                id = id.toString(),
                name = cleanBiliText(item.str("name")),
                creator = item.obj("creator")?.str("nickname").orEmpty(),
                coverUrl = normalizeImageUrl(item.str("picUrl")),
                trackCount = item.int("trackCount") ?: 0,
                playCount = item.long("playCount") ?: 0L,
                source = MediaSource.NETEASE,
            )
        }
    }

    fun toplists(): List<OnlineCollection> {
        val root = getJson("/api/toplist/detail") ?: return emptyList()
        val list = root.array("list") ?: return emptyList()
        return list.objects().mapNotNull { item ->
            val id = item.long("id") ?: return@mapNotNull null
            OnlineCollection(
                id = id.toString(),
                name = cleanBiliText(item.str("name")),
                coverUrl = normalizeImageUrl(item.str("coverImgUrl")),
                trackCount = item.int("trackCount") ?: 0,
                source = MediaSource.NETEASE,
                description = item.str("updateFrequency").orEmpty(),
            )
        }
    }

    /** 登录后读取用户创建与收藏的歌单。 */
    fun userPlaylists(uid: String): List<OnlineCollection> {
        if (uid.isBlank()) return emptyList()
        val root = getJson("/api/user/playlist?uid=$uid&limit=100&offset=0") ?: return emptyList()
        val list = root.array("playlist") ?: return emptyList()
        return list.objects().mapNotNull { item ->
            val id = item.long("id") ?: return@mapNotNull null
            OnlineCollection(
                id = id.toString(),
                name = cleanBiliText(item.str("name")),
                creator = item.obj("creator")?.str("nickname").orEmpty(),
                coverUrl = normalizeImageUrl(item.str("coverImgUrl")),
                trackCount = item.int("trackCount") ?: 0,
                playCount = item.long("playCount") ?: 0L,
                source = MediaSource.NETEASE,
                description = item.str("description").orEmpty(),
            )
        }
    }

    /** 获取用户收藏的歌手。 */
    fun followedArtists(): List<OnlineArtist> {
        val root = getJson("/api/artist/sublist?limit=100&offset=0&total=true")
            ?: postJson("/api/artist/sublist", mapOf("limit" to "100", "offset" to "0", "total" to "true"))
            ?: return emptyList()
        val data = root.array("data")?.objects().orEmpty()
        return data.mapNotNull { item ->
            val id = item.long("id") ?: return@mapNotNull null
            OnlineArtist(
                id = id.toString(),
                name = cleanBiliText(item.str("name")).ifBlank { "未知歌手" },
                avatarUrl = normalizeImageUrl(item.str("picUrl")),
                songCount = item.int("musicSize") ?: 0,
                source = MediaSource.NETEASE,
            )
        }
    }
}

/** 简单的 JSON 对象包装，方便统一调用解析扩展。 */
@JvmInline
value class JsonObjectSelf(private val raw: kotlinx.serialization.json.JsonObject) {
    fun str(key: String) = raw.str(key)
    fun long(key: String) = raw.long(key)
    fun int(key: String) = raw.int(key)
    fun bool(key: String) = raw.bool(key)
    fun obj(key: String) = raw.obj(key)?.let(::JsonObjectSelf)
    fun array(key: String) = raw.array(key)

    /** 对象的全部键（B 站表情表以标记本身作为键）。 */
    fun keys(): Set<String> = raw.keys
}

data class SongUrl(
    val url: String,
    val level: String,
    val type: String,
    val size: Long,
)

/** 哔哩哔哩客户端：WBI 签名搜索 + 音频流解析。 */
class BiliApi(private val http: HttpService) {

    private val headers = mapOf("Referer" to "https://www.bilibili.com/")

    private var wbiKeys: Pair<String, String>? = null
    private var wbiFetchedAt = 0L
    private var cookieReady = false
    private val cookieLock = Any()

    private fun ensureCookies() {
        if (cookieReady) return
        synchronized(cookieLock) {
            if (cookieReady) return
            // 只有真的拿到响应才算就绪：以前先把标志置 true，一次失败就再也不补指纹 Cookie
            val home = http.get(
                "https://www.bilibili.com/",
                mapOf("Referer" to "https://www.bilibili.com/"),
                timeoutSeconds = 12,
            )
            val spi = http.get("https://api.bilibili.com/x/frontend/finger/spi", headers)
            NeriJsonParser.parse(spi.orEmpty()).asObject()?.obj("data")?.let { data ->
                data.str("b_3")?.let { http.setCookie("api.bilibili.com", "buvid3", it) }
                data.str("b_4")?.let { http.setCookie("api.bilibili.com", "buvid4", it) }
            }
            cookieReady = home != null || spi != null
        }
    }

    /** 风控/验证响应：data 里只有 v_voucher，或返回码非 0。 */
    private fun isBlockedResponse(root: kotlinx.serialization.json.JsonObject?): Boolean {
        if (root == null) return true
        if (root.int("code") != 0) return true
        val data = root.obj("data") ?: return true
        if (data["v_voucher"] != null) return true
        return data.array("result") == null
    }

    private val mixinKeyEncTab = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
        61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
        36, 20, 34, 44, 52
    )

    private fun loadWbiKeys(): Pair<String, String>? {
        // B 站每天轮换 wbi key：缓存 12 小时后主动重新取，别一直用到失效
        val cached = wbiKeys
        if (cached != null && System.currentTimeMillis() - wbiFetchedAt < WBI_KEY_TTL_MS) return cached
        ensureCookies()
        val text = http.get("https://api.bilibili.com/x/web-interface/nav", headers) ?: return null
        val wbi = NeriJsonParser.parse(text).asObject()?.obj("data")?.obj("wbi_img") ?: return null
        val imgKey = wbi.str("img_url").orEmpty().substringAfterLast('/').substringBefore('.')
        val subKey = wbi.str("sub_url").orEmpty().substringAfterLast('/').substringBefore('.')
        if (imgKey.isEmpty() || subKey.isEmpty()) return null
        val keys = imgKey to subKey
        wbiKeys = keys
        wbiFetchedAt = System.currentTimeMillis()
        return keys
    }

    private fun mixinKey(): String? {
        val (imgKey, subKey) = loadWbiKeys() ?: return null
        val raw = imgKey + subKey
        val builder = StringBuilder()
        mixinKeyEncTab.forEach { index -> if (index < raw.length) builder.append(raw[index]) }
        return builder.toString().take(32)
    }

    private fun refreshWbiKeys() {
        wbiKeys = null
        wbiFetchedAt = 0L
    }

    private fun signedQuery(params: Map<String, String>): String? {
        val mixin = mixinKey() ?: return null
        val withTime = params.toMutableMap()
        withTime["wts"] = (System.currentTimeMillis() / 1000).toString()
        val query = withTime.entries
            .sortedBy { it.key }
            .joinToString("&") { (key, value) ->
                val cleaned = value.filterNot { it in "!'()*" }
                "${urlEncode(key)}=${urlEncode(cleaned)}"
            }
        val wRid = md5Hex(query + mixin)
        return "$query&w_rid=$wRid"
    }

    fun searchVideos(keyword: String, page: Int = 1): List<Song> {
        ensureCookies()
        val params = mapOf(
            "search_type" to "video",
            "keyword" to keyword,
            "page" to page.toString(),
            "page_size" to "30",
        )
        var lastRoot: kotlinx.serialization.json.JsonObject? = null
        repeat(2) { attempt ->
            if (attempt > 0) {
                Thread.sleep(300L)
                refreshWbiKeys()
            }
            lastRoot = wbiRequest("https://api.bilibili.com/x/web-interface/wbi/search/type", params)
            if (isBlockedResponse(lastRoot)) {
                val fallback = "https://api.bilibili.com/x/web-interface/search/type?search_type=video" +
                    "&keyword=${urlEncode(keyword)}&page=$page&page_size=30"
                lastRoot = NeriJsonParser.parse(http.get(fallback, headers).orEmpty()).asObject()
            }
            val results = lastRoot?.obj("data")?.array("result")
            if (results != null && results.isNotEmpty()) {
                return mapSearchResults(results)
            }
        }
        return emptyList()
    }

    private fun mapSearchResults(results: kotlinx.serialization.json.JsonArray): List<Song> =
        results.objects().mapNotNull { item ->
            val bvid = item.str("bvid") ?: item.str("aid") ?: return@mapNotNull null
            Song(
                key = biliSongKey(bvid),
                source = MediaSource.BILIBILI,
                title = cleanBiliText(item.str("title")).ifBlank { "未知视频" },
                artist = cleanBiliText(item.str("author")),
                album = "哔哩哔哩",
                durationMs = biliDurationMs(item.str("duration")),
                remoteId = bvid,
                artworkUrl = normalizeImageUrl(item.str("pic")),
            )
        }

    private fun wbiRequest(baseUrl: String, params: Map<String, String>): kotlinx.serialization.json.JsonObject? {
        val query = signedQuery(params) ?: return null
        val text = http.get("$baseUrl?$query", headers) ?: return null
        return NeriJsonParser.parse(text).asObject()
    }

    /** 调试用：返回搜索接口的原始 code 与 message。 */
    fun debugSearch(keyword: String): String {
        ensureCookies()
        val params = mapOf(
            "search_type" to "video",
            "keyword" to keyword,
            "page" to "1",
            "page_size" to "30",
        )
        val query = signedQuery(params) ?: return "mixin key unavailable"
        val text = http.get("https://api.bilibili.com/x/web-interface/wbi/search/type?$query", headers)
            ?: return "empty response"
        val root = NeriJsonParser.parse(text).asObject()
        val data = root?.obj("data")
        val results = data?.array("result")
        val first = results?.objects()?.firstOrNull()
        return "code=${root?.int("code")} message=${root?.str("message")} length=${text.length} " +
            "dataKeys=${data?.keys} resultSize=${results?.size} firstKeys=${first?.keys} " +
            "firstTitle=${first?.str("title")} firstBvid=${first?.str("bvid")}"
    }

    fun videoPages(bvid: String): List<Pair<String, String>> {
        val text = http.get("https://api.bilibili.com/x/player/pagelist?bvid=$bvid", headers) ?: return emptyList()
        val root = NeriJsonParser.parse(text).asObject() ?: return emptyList()
        val data = root.array("data") ?: return emptyList()
        return data.objects().mapNotNull { item ->
            val cid = item.long("cid")?.toString() ?: return@mapNotNull null
            cid to cleanBiliText(item.str("part"))
        }
    }

    /** bvid → aid：评论接口要的是数字 aid，不是 bvid。 */
    fun aidOf(bvid: String): Long? = runCatching {
        val text = http.get("https://api.bilibili.com/x/web-interface/view?bvid=$bvid", headers) ?: return@runCatching null
        NeriJsonParser.parse(text).asObject()?.obj("data")?.long("aid")
    }.getOrNull()

    /**
     * 视频评论。
     *
     * @param page 页码（从 1 开始）。B 站必须按页取：用「已取条数 ÷ 每页条数」反推页码，
     *   一旦服务端返回的条数少于请求数，就会算出同一个页码、反复请求第一页。
     * @param sort 2 = 按热度，0 = 按时间。
     */
    fun comments(bvid: String, page: Int, limit: Int, sort: Int = 2): CommentPage? {
        ensureCookies()
        val aid = aidOf(bvid) ?: return null
        val pageSize = limit.coerceAtLeast(1)
        val safePage = page.coerceAtLeast(1)
        val text = http.get(
            "https://api.bilibili.com/x/v2/reply?type=1&oid=$aid&sort=$sort&pn=$safePage&ps=$pageSize",
            headers,
        ) ?: return null
        val root = NeriJsonParser.parse(text).asObject() ?: return null
        val code = root.int("code") ?: -1
        if (code != 0) return CommentPage(error = biliCommentErrorText(code))
        val data = root.obj("data")?.let(::JsonObjectSelf)
        val items = data?.array("replies")?.objects().orEmpty()
            .mapNotNull { biliCommentFromJson(JsonObjectSelf(it)) }
        val pageInfo = data?.obj("page")
        val total = pageInfo?.long("count")?.toInt()?.takeIf { it >= 0 }
        val size = pageInfo?.long("size")?.toInt()?.takeIf { it > 0 } ?: pageSize
        // 用「请求的页码」而不是返回的 num 计算进度：匿名访问时接口可能返回 num=0，
        // 用它会把「还有下一页」算成「已经没有更多」，或者反过来一直翻页。
        val loadedThrough = (safePage - 1).toLong() * size + items.size
        // 明明还有评论却给了空页，说明这次响应被截断（未登录/风控），不能当成到底
        if (items.isEmpty() && total != null && loadedThrough < total) {
            return CommentPage(
                total = total,
                hasMore = false,
                error = "哔哩哔哩这次只返回了空页（可能被风控或需要登录），已经加载 $loadedThrough / $total 条",
            )
        }
        val hasMore = total?.let { loadedThrough < it } ?: (items.size >= pageSize)
        return CommentPage(
            latest = items,
            total = total ?: loadedThrough.toInt(),
            hasMore = hasMore,
        )
    }

    /** 哔哩哔哩评论业务码 → 给用户看的提示。 */
    private fun biliCommentErrorText(code: Int): String = when (code) {
        -101, -102, -111, -403, -412, 12004 -> "需要登录哔哩哔哩才能查看这条视频的评论"
        12002, 12061 -> "这条视频的评论区已关闭"
        -404, 12006 -> "视频不存在，可能已删除或仅限特定用户观看"
        in 500..599 -> "哔哩哔哩评论服务暂时不可用（HTTP $code）"
        else -> "哔哩哔哩评论接口返回异常（code $code）"
    }

    fun audioUrl(bvid: String, cid: String): String? {
        ensureCookies()
        val params = mapOf(
            "bvid" to bvid,
            "cid" to cid,
            "fnval" to "16",
            "fourk" to "1",
        )
        var dash: kotlinx.serialization.json.JsonObject? = null
        for (attempt in 0 until 2) {
            if (attempt > 0) {
                Thread.sleep(300L)
                refreshWbiKeys()
            }
            var root = wbiRequest("https://api.bilibili.com/x/player/wbi/playurl", params)
            if (root == null || root.int("code") != 0 || root.obj("data")?.obj("dash") == null) {
                val fallback = "https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&fnval=16&fourk=1"
                root = NeriJsonParser.parse(http.get(fallback, headers).orEmpty()).asObject()
            }
            dash = root?.obj("data")?.obj("dash")
            if (dash != null) break
        }
        val resolvedDash = dash ?: return null
        val audios = resolvedDash.array("audio")?.objects().orEmpty()
        val best = audios.maxByOrNull { it.long("bandwidth") ?: 0L } ?: return null
        val direct = best.str("baseUrl") ?: best.str("base_url") ?: return null
        return normalizeImageUrl(direct)
    }

    fun biliHeaders(): Map<String, String> = mapOf(
        "Referer" to "https://www.bilibili.com/",
        "User-Agent" to DESKTOP_USER_AGENT,
        "Origin" to "https://www.bilibili.com",
    )

    /** 登录后读取用户创建的收藏夹。 */
    fun favoriteFolders(mid: String): List<OnlineCollection> {
        if (mid.isBlank()) return emptyList()
        val text = http.get(
            "https://api.bilibili.com/x/v3/fav/folder/created/list-all?up_mid=$mid",
            headers,
        ) ?: return emptyList()
        val root = NeriJsonParser.parse(text).asObject() ?: return emptyList()
        if (root.int("code") != 0) return emptyList()
        val list = root.obj("data")?.array("list") ?: return emptyList()
        return list.objects().mapNotNull { item ->
            val id = item.long("id") ?: return@mapNotNull null
            OnlineCollection(
                id = id.toString(),
                name = cleanBiliText(item.str("title")),
                creator = "哔哩哔哩收藏夹",
                coverUrl = normalizeImageUrl(item.str("cover")),
                trackCount = item.int("media_count") ?: 0,
                source = MediaSource.BILIBILI,
            )
        }
    }

    /**
     * 读取收藏夹内的视频（作为可播放音频）。
     * 该接口每页最多 20 条，需要按 pn 翻页；onPage 会在每页返回时回调已累计的列表，便于界面边加载边展示。
     */
    fun favoriteFolderSongs(
        mediaId: String,
        maxItems: Int = 2000,
        onPage: (List<Song>) -> Unit = {},
    ): List<Song> {
        if (mediaId.isBlank()) return emptyList()
        ensureCookies()
        val collected = ArrayList<Song>()
        var page = 1
        while (collected.size < maxItems && page <= 100) {
            val url = "https://api.bilibili.com/x/v3/fav/resource/list" +
                "?media_id=$mediaId&pn=$page&ps=20&platform=web"
            val text = http.get(url, headers) ?: break
            val root = NeriJsonParser.parse(text).asObject() ?: break
            if (root.int("code") != 0) {
                println("[bili] 收藏夹内容读取失败：code=${root.int("code")} ${root.str("message")}")
                break
            }
            val data = root.obj("data") ?: break
            val medias = data.array("medias")?.objects().orEmpty()
            if (medias.isEmpty()) break
            medias.forEach { item ->
                favoriteMediaToSong(JsonObjectSelf(item))?.let { collected += it }
            }
            onPage(collected.toList())
            val hasMore = data.bool("has_more") ?: false
            if (!hasMore) break
            page += 1
        }
        return if (collected.size > maxItems) collected.take(maxItems) else collected
    }
}

/** 收藏夹条目 → 可播放歌曲（duration 单位为秒，封面为 http 需转 https）。可离线单测。 */
fun favoriteMediaToSong(item: JsonObjectSelf): Song? {
    val bvid = item.str("bvid")?.takeIf { it.isNotBlank() } ?: return null
    return Song(
        key = biliSongKey(bvid),
        source = MediaSource.BILIBILI,
        title = cleanBiliText(item.str("title")).ifBlank { "未知视频" },
        artist = cleanBiliText(item.obj("upper")?.str("name")),
        album = "哔哩哔哩收藏夹",
        durationMs = (item.long("duration") ?: 0L) * 1000L,
        remoteId = bvid,
        artworkUrl = normalizeImageUrl(item.str("cover")),
    )
}


/** wbi key 的有效期：B 站每天轮换，12 小时足够保守。 */
private const val WBI_KEY_TTL_MS = 12L * 60 * 60 * 1000
