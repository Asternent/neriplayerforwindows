package moe.ouom.neriplayer.desktop.tools

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.desktop.core.AppDirs
import moe.ouom.neriplayer.desktop.core.AudioInput
import moe.ouom.neriplayer.desktop.core.FfmpegSupport
import moe.ouom.neriplayer.desktop.core.LrcParser
import moe.ouom.neriplayer.desktop.core.MediaSource
import moe.ouom.neriplayer.desktop.core.Song
import moe.ouom.neriplayer.desktop.core.createAudioEngine
import moe.ouom.neriplayer.desktop.core.displayName
import moe.ouom.neriplayer.desktop.core.progressAt
import moe.ouom.neriplayer.desktop.net.OnlineRepository
import moe.ouom.neriplayer.desktop.net.LyricSource
import moe.ouom.neriplayer.desktop.net.asObject
import moe.ouom.neriplayer.desktop.net.formatCommentTime
import moe.ouom.neriplayer.desktop.ui.LyricsSecondaryLineMode
import moe.ouom.neriplayer.desktop.ui.nextLyricsSecondaryLineMode
import moe.ouom.neriplayer.desktop.ui.resolveLyricsSecondaryLineMode
import kotlinx.coroutines.cancelChildren
import java.io.File

private fun log(message: String) = println("[selftest] $message")

private var checksFailed = 0

private fun check(name: String, condition: Boolean, detail: String = "") {
    if (condition) {
        log("PASS $name${if (detail.isEmpty()) "" else " ($detail)"}")
    } else {
        checksFailed += 1
        log("FAIL $name${if (detail.isEmpty()) "" else " ($detail)"}")
    }
}

/** 校验哔哩哔哩账号解析：nav(isLogin 为布尔) → 成员接口 → Cookie 兜底。 */
private fun checkAccountParsing() {
    val loggedInNav = """
        {"code":0,"message":"0","data":{"isLogin":true,"uname":"测试用户","mid":123456,
        "face":"https://i0.hdslb.com/bfs/face/x.jpg","vipStatus":{"status":1,"type":2}}}
    """.trimIndent()
    val account = moe.ouom.neriplayer.desktop.net.parseBiliAccount(loggedInNav, null, emptyMap())
    check(
        "bili-nav-logged-in",
        account != null && account.nickname == "测试用户" && account.userId == "123456" && account.vip,
        "nickname=${account?.nickname} mid=${account?.userId} vip=${account?.vip}",
    )

    val loggedOutNav = """{"code":-101,"message":"账号未登录","data":{"isLogin":false}}"""
    val numericNav = """{"code":0,"data":{"isLogin":1,"uname":"数字标记用户","mid":42}}"""
    check(
        "bili-nav-isLogin-number",
        moe.ouom.neriplayer.desktop.net.parseBiliAccount(numericNav, null, emptyMap())?.nickname == "数字标记用户",
    )

    val memberJson = """{"code":0,"data":{"mid":999,"uname":"成员接口用户","face":"https://x/y.png"}}"""
    val fromMember = moe.ouom.neriplayer.desktop.net.parseBiliAccount(loggedOutNav, memberJson, emptyMap())
    check(
        "bili-member-fallback",
        fromMember != null && fromMember.nickname == "成员接口用户" && fromMember.userId == "999",
        "nickname=${fromMember?.nickname}",
    )

    val fromCookie = moe.ouom.neriplayer.desktop.net.parseBiliAccount(
        loggedOutNav,
        null,
        mapOf("SESSDATA" to "abc%2Cdef", "DedeUserID" to "777"),
    )
    check(
        "bili-cookie-fallback",
        fromCookie != null && fromCookie.userId == "777" && fromCookie.displayName() == "UID 777",
        "displayName=${fromCookie?.displayName()}",
    )

    check(
        "bili-not-logged-in",
        moe.ouom.neriplayer.desktop.net.parseBiliAccount(loggedOutNav, null, emptyMap()) == null,
    )
    check(
        "netease-cookie-fallback",
        moe.ouom.neriplayer.desktop.net.parseCookieFallbackAccount(
            source = moe.ouom.neriplayer.desktop.core.MediaSource.NETEASE,
            cookies = mapOf("MUSIC_U" to "token"),
            cookieName = "MUSIC_U",
            userId = "",
        )?.displayName() == "已登录",
    )
}

fun main() = runBlocking {
    log("data dir: ${AppDirs.dataDir}")
    log("ffmpeg available: ${FfmpegSupport.available} (${FfmpegSupport.version})")

    // 1. LRC 解析
    val lrc = """
        [ti:测试]
        [offset:0]
        [00:01.00]第一句
        [00:03.50][00:05.50]重复句
        [00:07.25]最后一句
    """.trimIndent()
    val parsed = LrcParser.parse(lrc)
    log("lrc lines=${parsed.size} first=${parsed.firstOrNull()?.timeMs} last=${parsed.lastOrNull()?.timeMs}")

    // 2. 网络接口
    val online = OnlineRepository()
    val songs = online.search(MediaSource.NETEASE, moe.ouom.neriplayer.desktop.core.SearchKind.SONG, "周杰伦", 1)
    log("netease search result=${songs.songs.size} first=${songs.songs.firstOrNull()?.displayName()} / ${songs.songs.firstOrNull()?.artistText()}")
    val first = songs.songs.firstOrNull()
    if (first != null) {
        val urlResult = online.netease.songUrl(first.remoteId ?: "", "exhigh")
        log("netease song url=${urlResult?.url?.take(60)} level=${urlResult?.level}")
        val lyric = online.netease.lyric(first.remoteId ?: "")
        log(
            "netease lyric length=${lyric?.raw?.length} " +
                "translated=${lyric?.translated?.length ?: 0} romanized=${lyric?.romanized?.length ?: 0}",
        )
        val bili = online.search(MediaSource.BILIBILI, moe.ouom.neriplayer.desktop.core.SearchKind.SONG, "周杰伦 晴天", 1)
        log("bili search result=${bili.songs.size} first=${bili.songs.firstOrNull()?.displayName()}")
        log("bili debug: ${online.bilibili.debugSearch("周杰伦 晴天")}")
        val biliSong = bili.songs.firstOrNull()
        if (biliSong != null) {
            val audio = online.resolvePlayback(biliSong, "exhigh")
            log("bili audio url=${audio?.url?.take(60)}")
        }
    }

    // 5. 在线歌单完整性：详情接口只返回前若干首，必须按 trackIds 补齐
    val hotList = online.netease.playlistDetail("3778678")
    val hotCount = hotList?.second?.size ?: 0
    val declared = hotList?.first?.trackCount ?: 0
    check(
        "netease-playlist-full",
        hotCount >= 100,
        "热歌榜声明 $declared 首，实际取回 $hotCount 首",
    )
    check(
        "netease-playlist-limited",
        (online.netease.playlistDetail("3778678", limit = 5)?.second?.size ?: 0) == 5,
    )

    // 6. 必站收藏夹条目映射（时长单位是秒、封面 http 需要转 https、标题要去掉高亮标签）
    val favoriteItem = moe.ouom.neriplayer.desktop.net.NeriJsonParser.parse(
        """{"bvid":"BV1test","title":"【<em class=\"keyword\">音乐</em>】收藏夹测试视频","duration":206,"cover":"http://i1.hdslb.com/bfs/archive/x.jpg","upper":{"name":"测试UP主"}}"""
    ).asObject()
    val mapped = favoriteItem?.let {
        moe.ouom.neriplayer.desktop.net.favoriteMediaToSong(moe.ouom.neriplayer.desktop.net.JsonObjectSelf(it))
    }
    check(
        "bili-favorite-mapping",
        mapped != null &&
            mapped.title == "【音乐】收藏夹测试视频" &&
            mapped.durationMs == 206_000L &&
            mapped.artist == "测试UP主" &&
            mapped.remoteId == "BV1test" &&
            mapped.artworkUrl?.startsWith("https://") == true,
        "title=${mapped?.title} duration=${mapped?.durationMs} cover=${mapped?.artworkUrl}",
    )
    val emptyItem = moe.ouom.neriplayer.desktop.net.NeriJsonParser.parse("{}").asObject()
    check(
        "bili-favorite-missing-bvid",
        emptyItem == null ||
            moe.ouom.neriplayer.desktop.net.favoriteMediaToSong(
                moe.ouom.neriplayer.desktop.net.JsonObjectSelf(emptyItem)
            ) == null,
    )

    // 3. 生成测试音频并播放
    val tone = File(AppDirs.cacheDir, "selftest-tone.wav")
    val ffmpeg = ProcessBuilder(
        "ffmpeg", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=3",
        "-ac", "2", "-ar", "48000", tone.absolutePath
    ).redirectErrorStream(true).start()
    ffmpeg.inputStream.readBytes()
    ffmpeg.waitFor()
    log("test tone generated: ${tone.isFile} size=${tone.length()}")

    val engine = createAudioEngine()
    log("engine=${engine.javaClass.simpleName} supportsEffects=${engine.supportsEffects}")
    var completed = false
    engine.onCompleted = { completed = true; log("engine completed callback") }
    engine.onError = { log("engine error: $it") }
    engine.open(AudioInput(path = tone.absolutePath), 0L, 3000L)
    engine.play()
    repeat(8) {
        kotlinx.coroutines.delay(400)
        val snap = engine.snapshot.value
        log("position=${snap.positionMs}ms playing=${snap.playing} buffering=${snap.buffering} error=${snap.error}")
    }
    engine.setSpeed(1.5f)
    kotlinx.coroutines.delay(800)
    log("after speed change position=${engine.snapshot.value.positionMs}ms")
    engine.seekTo(0)
    engine.play()
    kotlinx.coroutines.delay(1200)
    log("after seek position=${engine.snapshot.value.positionMs}ms playing=${engine.snapshot.value.playing}")
    kotlinx.coroutines.delay(2500)
    log("completed=$completed final=${engine.snapshot.value.positionMs}ms")
    engine.release()
    log("--- 账号解析自检 ---")
    checkAccountParsing()
    log("账号解析失败项：$checksFailed")
    log("--- GitHub 同步自检 ---")
    checkSyncSerializerAndMerge()
    log("--- 悬浮歌词自检 ---")
    checkFloatingLyrics()
    log("--- 定位到正在播放自检 ---")
    checkLocateCurrent()
    log("--- 桌面端同步特性自检 ---")
    checkDesktopSyncFeatures()
    log("--- 下载自检 ---")
    checkDownloads(online)
    log("--- 后台与系统控制自检 ---")
    checkBackground()
    log("--- 歌词来源与评论自检 ---")
    checkLyricSources(online)
    log("累计失败项：$checksFailed")
    log("DONE")
}

/**
 * 歌词来源与评论自检。
 *
 * 前半段是纯逻辑（时间偏移、翻译/音译合并、TTML 转换、第二行模式、评论时间格式），
 * 不依赖网络；后半段会真的去打 LRCLIB / 酷狗 / AMLL / 网易云 / 哔哩哔哩，
 * 需要联网才能通过。
 */
private suspend fun checkLyricSources(online: OnlineRepository) {
    val lrc = LrcParser

    // ---------------------------------------------------------- 默认偏移
    val raw = "[00:01.00]第一行\n[00:03.50]第二行\n"
    check("lrc-offset-zero", lrc.parse(raw).map { it.timeMs } == listOf(1000L, 3500L))
    check(
        "lrc-offset-positive-earlier",
        lrc.parse(raw, extraOffsetMs = 500L).map { it.timeMs } == listOf(500L, 3000L),
        "${lrc.parse(raw, extraOffsetMs = 500L).map { it.timeMs }}",
    )
    check(
        "lrc-offset-negative-later",
        lrc.parse(raw, extraOffsetMs = -500L).map { it.timeMs } == listOf(1500L, 4000L),
        "${lrc.parse(raw, extraOffsetMs = -500L).map { it.timeMs }}",
    )
    // 文件自带的 [offset:] 与设置里的默认偏移要叠加，而不是互相覆盖
    val withFileOffset = lrc.parse("[offset:200]\n[00:01.00]x\n", extraOffsetMs = 300L)
    check("lrc-offset-stacks", withFileOffset.map { it.timeMs } == listOf(500L), "${withFileOffset.map { it.timeMs }}")

    // ---------------------------------------------------------- 翻译 / 音译合并
    val base = lrc.parse(raw)
    val merged = lrc.merge(
        base,
        lrc.parse("[00:01.00]翻译一\n"),
        lrc.parse("[00:01.00]yi\n[00:03.50]er\n"),
    )
    check("lrc-merge-translation", merged[0].translation == "翻译一", "${merged[0].translation}")
    check("lrc-merge-romanization", merged[0].romanization == "yi" && merged[1].romanization == "er")
    check("lrc-merge-no-cross-talk", merged[1].translation == null && merged[0].romanization != merged[0].translation)

    // ---------------------------------------------------------- AMLL TTML → LRC / 逐字
    val ttml = "<tt><body><div>" +
        "<p begin=\"00:01.737\" end=\"00:06.722\"><span begin=\"00:01.737\">沈</span><span>む</span></p>" +
        "<p begin=\"01:02.189\" end=\"01:04.033\"><span>鳴る</span><span>&amp;夜</span></p>" +
        "<p begin=\"00:10.000\"><span>本&#39;文</span>" +
        "<span ttm:role=\"x-translation\">translated &#x2019;line</span></p>" +
        "<p begin=\"00:20.000\" end=\"00:24.000\">" +
        "<span begin=\"00:20.000\" end=\"00:20.400\">逐</span>" +
        "<span begin=\"00:20.400\" end=\"00:21.200\">字</span>" +
        "<span begin=\"00:21.200\" end=\"00:22.000\">歌</span>" +
        "<span begin=\"00:22.000\" end=\"00:24.000\">詞</span>" +
        "<span ttm:role=\"x-translation\">逐字翻译</span></p>" +
        "</div></body></tt>"
    val ttmlParsed = moe.ouom.neriplayer.desktop.core.TtmlParser.parse(ttml)
    val ttmlLines = lrc.parse(moe.ouom.neriplayer.desktop.core.TtmlParser.toLrc(ttmlParsed))
    check("amll-ttml-line-count", ttmlLines.size == 6, "${ttmlLines.map { it.timeMs }}")
    check("amll-ttml-text", ttmlLines.firstOrNull { it.timeMs == 1_737L }?.text == "沈む",
        "${ttmlLines.firstOrNull { it.timeMs == 1_737L }?.text}")
    val ttmlAt62 = ttmlLines.firstOrNull { it.timeMs == 62_189L }
    check("amll-ttml-minutes", ttmlAt62 != null, "${ttmlLines.map { it.timeMs }}")
    check("amll-ttml-entities", ttmlAt62?.text == "鳴る&夜", "${ttmlAt62?.text}")
    // ttm:role 的翻译行不能拼进正文，还要单独成行（同时间戳 → LrcParser 会当成翻译）
    val ttmlAt10 = ttmlLines.filter { it.timeMs == 10_000L }.map { it.text }
    check(
        "amll-ttml-role-split",
        ttmlAt10 == listOf("本'文", "translated ’line"),
        "$ttmlAt10",
    )
    // 逐字：每个 <span> 的 begin/end 都要变成 LyricWord
    val ttmlWordLine = ttmlParsed.lines.firstOrNull { it.timeMs == 20_000L }
    check(
        "amll-ttml-words",
        ttmlWordLine?.words?.map { it.text } == listOf("逐", "字", "歌", "詞"),
        "${ttmlWordLine?.words?.map { it.text }}",
    )
    check(
        "amll-ttml-word-timing",
        ttmlWordLine?.words?.map { it.startMs to it.endMs } ==
            listOf(20_000L to 20_400L, 20_400L to 21_200L, 21_200L to 22_000L, 22_000L to 24_000L),
        "${ttmlWordLine?.words?.map { it.startMs to it.endMs }}",
    )
    check("amll-ttml-word-text", ttmlWordLine?.text == "逐字歌詞", "${ttmlWordLine?.text}")
    check("amll-ttml-word-translation", ttmlWordLine?.translation == "逐字翻译", "${ttmlWordLine?.translation}")
    check(
        "amll-ttml-word-progress",
        ttmlWordLine != null &&
            kotlin.math.abs(ttmlWordLine.progressAt(20_200L) - 0.125f) < 0.01f &&
            kotlin.math.abs(ttmlWordLine.progressAt(21_200L) - 0.5f) < 0.01f &&
            ttmlWordLine.progressAt(24_000L) == 1f,
        "${ttmlWordLine?.progressAt(20_200L)} / ${ttmlWordLine?.progressAt(21_200L)}",
    )
    check(
        "amll-ttml-word-offset",
        moe.ouom.neriplayer.desktop.core.TtmlParser.parse(ttml, extraOffsetMs = 500L)
            .lines.firstOrNull { it.timeMs == 19_500L }?.words?.isNotEmpty() == true,
    )

    // ---------------------------------------------------------- 网易云 yrc 逐字
    val yrcSample = """
        {"t":0,"c":[{"tx":"作词: "},{"tx":"某某"}]}
        [1000,2000](1000,500,0)逐(1500,500,0)字(2000,1000,0)歌詞
        [4000,1000](4000,1000,0)第二句
    """.trimIndent()
    val yrcLines = moe.ouom.neriplayer.desktop.core.YrcParser.parse(yrcSample)
    check("yrc-line-count", yrcLines.size == 2, "${yrcLines.map { it.timeMs }}")
    check("yrc-word-text", yrcLines[0].text == "逐字歌詞", "${yrcLines[0].text}")
    check(
        "yrc-word-timing",
        yrcLines[0].words.map { it.text to (it.startMs to it.endMs) } ==
            listOf("逐" to (1000L to 1500L), "字" to (1500L to 2000L), "歌詞" to (2000L to 3000L)),
        "${yrcLines[0].words.map { it.text to (it.startMs to it.endMs) }}",
    )
    check("yrc-line-end", yrcLines[0].endMs == 3000L, "${yrcLines[0].endMs}")
    check("yrc-json-credit-skipped", yrcLines.none { it.text.contains("作词") })
    check(
        "yrc-offset",
        moe.ouom.neriplayer.desktop.core.YrcParser.parse(yrcSample, extraOffsetMs = 500L)
            .first().timeMs == 500L,
    )
    check(
        "lyric-format-detect",
        moe.ouom.neriplayer.desktop.core.detectLyricFormat(yrcSample) ==
            moe.ouom.neriplayer.desktop.core.LyricFormat.YRC &&
            moe.ouom.neriplayer.desktop.core.detectLyricFormat(ttml) ==
            moe.ouom.neriplayer.desktop.core.LyricFormat.TTML &&
            moe.ouom.neriplayer.desktop.core.detectLyricFormat(raw) ==
            moe.ouom.neriplayer.desktop.core.LyricFormat.LRC,
    )

    // ---------------------------------------------------------- 增强型 LRC 逐字
    val enhancedRaw = "[00:10.00]<00:10.00>逐<00:10.50>字<00:11.20>歌詞\n[00:13.00]下一句\n"
    val enhancedLines = lrc.parse(enhancedRaw)
    check("enhanced-lrc-count", enhancedLines.size == 2, "${enhancedLines.map { it.timeMs }}")
    check("enhanced-lrc-text", enhancedLines[0].text == "逐字歌詞", "${enhancedLines[0].text}")
    check(
        "enhanced-lrc-words",
        enhancedLines[0].words.map { it.text to it.startMs } ==
            listOf("逐" to 10_000L, "字" to 10_500L, "歌詞" to 11_200L),
        "${enhancedLines[0].words.map { it.text to it.startMs }}",
    )
    // 最后一个词的结束时间要由下一行补出来，否则扫光会在行尾卡住
    check("enhanced-lrc-last-end", enhancedLines[0].words.last().endMs == 13_000L,
        "${enhancedLines[0].words.last().endMs}")
    check(
        "enhanced-lrc-merge-keeps-words",
        lrc.merge(enhancedLines, lrc.parse("[00:10.00]翻译\n")).first().let {
            it.words.size == 3 && it.translation == "翻译"
        },
    )
    check(
        "enhanced-lrc-split-keeps-words",
        lrc.splitDuplicatedTimestamps(enhancedRaw).first.first().words.size == 3,
    )

    // ---------------------------------------------------------- 逐字进度（卡拉OK 扫光）
    val wordLine = moe.ouom.neriplayer.desktop.core.LyricLine(
        timeMs = 1_000L,
        text = "逐字",
        words = listOf(
            moe.ouom.neriplayer.desktop.core.LyricWord("逐", 1_000L, 2_000L),
            moe.ouom.neriplayer.desktop.core.LyricWord("字", 2_000L, 3_000L),
        ),
    )
    check("word-progress-before", wordLine.progressAt(500L) == 0f)
    check(
        "word-progress-mid",
        kotlin.math.abs(wordLine.progressAt(1_500L) - 0.25f) < 0.01f,
        "${wordLine.progressAt(1_500L)}",
    )
    check(
        "word-progress-second-char",
        kotlin.math.abs(wordLine.progressAt(2_000L) - 0.5f) < 0.01f,
        "${wordLine.progressAt(2_000L)}",
    )
    check("word-progress-done", wordLine.progressAt(3_000L) == 1f)
    check(
        "word-progress-line-fallback",
        kotlin.math.abs(
            moe.ouom.neriplayer.desktop.core.LyricLine(1_000L, "ab", endMs = 3_000L).progressAt(2_000L) - 0.5f,
        ) < 0.01f,
    )
    check(
        "lyrics-has-word-timing",
        moe.ouom.neriplayer.desktop.core.Lyrics(lines = listOf(wordLine)).hasWordTiming &&
            !moe.ouom.neriplayer.desktop.core.Lyrics(lines = listOf(moe.ouom.neriplayer.desktop.core.LyricLine(1_000L, "x"))).hasWordTiming,
    )

    // ---------------------------------------------------------- Apple Music 风格歌词页
    // 当前行最亮，离当前行越远越暗：距离 1 > 2 > 更远，且当前行是 1.0
    check(
        "apple-lyric-alpha",
        moe.ouom.neriplayer.desktop.ui.screens.appleLyricAlpha(true, 0) == 1f &&
            moe.ouom.neriplayer.desktop.ui.screens.appleLyricAlpha(false, 1) >
            moe.ouom.neriplayer.desktop.ui.screens.appleLyricAlpha(false, 2) &&
            moe.ouom.neriplayer.desktop.ui.screens.appleLyricAlpha(false, 2) >
            moe.ouom.neriplayer.desktop.ui.screens.appleLyricAlpha(false, 9),
        "${moe.ouom.neriplayer.desktop.ui.screens.appleLyricAlpha(false, 1)} / " +
            "${moe.ouom.neriplayer.desktop.ui.screens.appleLyricAlpha(false, 2)} / " +
            "${moe.ouom.neriplayer.desktop.ui.screens.appleLyricAlpha(false, 9)}",
    )

    // ---------------------------------------------------------- 酷狗 KRC 解码
    run {
        val plainKrc = "[1000,2000]<0,500,0>逐<500,500,0>字<1000,1000,0>歌詞\n"
        val deflated = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.DeflaterOutputStream(out).use { it.write(plainKrc.toByteArray()) }
        }.toByteArray()
        val key = byteArrayOf(
            0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
            0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69,
        )
        val encrypted = ByteArray(4 + deflated.size)
        deflated.forEachIndexed { index, byte ->
            encrypted[4 + index] = (byte.toInt() xor key[index % key.size].toInt()).toByte()
        }
        val decoded = moe.ouom.neriplayer.desktop.net.KugouKrc.decode(
            java.util.Base64.getEncoder().encodeToString(encrypted),
        )
        check(
            "kugou-krc-decode",
            decoded != null && decoded.yrc.contains("(1000,500,0)逐"),
            "${decoded?.yrc}",
        )
        check(
            "kugou-krc-lrc",
            decoded != null &&
                moe.ouom.neriplayer.desktop.core.YrcParser.parse(decoded.yrc).firstOrNull()?.words?.size == 3,
            "${decoded?.yrc}",
        )
    }

    // ---------------------------------------------------------- 歌词第二行模式
    val none = LyricsSecondaryLineMode.NONE
    val translationMode = LyricsSecondaryLineMode.TRANSLATION
    val phoneticMode = LyricsSecondaryLineMode.PHONETIC
    check(
        "lyric-mode-both-cycle",
        nextLyricsSecondaryLineMode(translationMode, true, true) == phoneticMode &&
            nextLyricsSecondaryLineMode(phoneticMode, true, true) == none &&
            nextLyricsSecondaryLineMode(none, true, true) == translationMode,
    )
    check(
        "lyric-mode-translation-only",
        nextLyricsSecondaryLineMode(translationMode, true, false) == none &&
            nextLyricsSecondaryLineMode(none, true, false) == translationMode,
    )
    check(
        "lyric-mode-resolve",
        resolveLyricsSecondaryLineMode(false, true, true, true) == none &&
            resolveLyricsSecondaryLineMode(true, true, true, true) == phoneticMode &&
            resolveLyricsSecondaryLineMode(true, true, true, false) == translationMode &&
            resolveLyricsSecondaryLineMode(true, true, false, true) == phoneticMode,
    )
    check(
        "lyric-source-alias",
        LyricSource.of("cloud_music") == LyricSource.NETEASE &&
            LyricSource.of("amll_ttml") == LyricSource.AMLL &&
            LyricSource.of("nonsense") == LyricSource.AUTO &&
            LyricSource.of(null) == LyricSource.AUTO,
    )

    // ---------------------------------------------------------- 评论时间格式
    val now = 1_700_000_000_000L
    check("comment-time-minutes", formatCommentTime(now - 5 * 60_000L, now) == "5 分钟前")
    check("comment-time-hours", formatCommentTime(now - 3 * 3_600_000L, now) == "3 小时前")
    check("comment-time-days", formatCommentTime(now - 4 * 86_400_000L, now) == "4 天前")
    check("comment-time-empty", formatCommentTime(0L, now) == "")

    // ---------------------------------------------------------- 在线：三个歌词源
    val song = moe.ouom.neriplayer.desktop.core.Song(
        key = "netease:1409311773",
        source = moe.ouom.neriplayer.desktop.core.MediaSource.NETEASE,
        title = "夜に駆ける",
        artist = "YOASOBI",
        album = "夜に駆ける",
        durationMs = 261_013L,
        remoteId = "1409311773",
    )
    for (source in listOf(LyricSource.LRCLIB, LyricSource.KUGOU, LyricSource.AMLL)) {
        val hit = runCatching { online.preferredLyrics(song, source) }.getOrNull()
        val lines = hit?.let { lrc.parse(it.raw).size } ?: 0
        log("lyric-source ${source.id}: source=${hit?.source} chars=${hit?.raw?.length ?: 0} lines=$lines")
        check("lyric-source-${source.id.lowercase()}", lines > 0, "${hit?.source} lines=$lines")
    }

    // ---------------------------------------------------------- 在线：网易云音译
    val neteaseLyric = online.netease.lyric("1409311773")
    log(
        "netease lyric: raw=${neteaseLyric?.raw?.length ?: 0} " +
            "translated=${neteaseLyric?.translated?.length ?: 0} romanized=${neteaseLyric?.romanized?.length ?: 0}",
    )
    check("netease-translation-present", !neteaseLyric?.translated.isNullOrBlank())
    check("netease-romanization-present", !neteaseLyric?.romanized.isNullOrBlank())

    // ---------------------------------------------------------- 在线：网易云逐字（eapi 新接口）
    // 新版歌曲才有 yrc；选一首确定有的（孤勇者），接口真的返回了才检查解析
    val yrcOnline = online.netease.lyric("1901371647")
    log(
        "netease yrc: wordRaw=${yrcOnline?.wordRaw?.length ?: 0} " +
            "format=${yrcOnline?.wordFormat} raw=${yrcOnline?.raw?.length ?: 0}",
    )
    check("netease-yrc-available", !yrcOnline?.wordRaw.isNullOrBlank(), "wordRaw 为空，eapi 可能不可用")
    val yrcOnlineLines = moe.ouom.neriplayer.desktop.core.YrcParser.parse(yrcOnline?.wordRaw.orEmpty())
    check(
        "netease-yrc-parse",
        yrcOnlineLines.any { it.hasWordTiming },
        "lines=${yrcOnlineLines.size} withWords=${yrcOnlineLines.count { it.hasWordTiming }}",
    )
    check(
        "netease-yrc-line-text",
        yrcOnlineLines.firstOrNull { it.hasWordTiming }?.let { it.text == it.words.joinToString("") { word -> word.text } } == true,
    )

    // ---------------------------------------------------------- 在线：评论
    val neteaseComments = online.comments(song, 0, 20)
    log(
        "netease comments: total=${neteaseComments?.total} hot=${neteaseComments?.hot?.size ?: 0} " +
            "latest=${neteaseComments?.latest?.size ?: 0} hasMore=${neteaseComments?.hasMore}",
    )
    check("netease-comments", (neteaseComments?.latest?.size ?: 0) > 0)
    check(
        "netease-comment-fields",
        neteaseComments?.latest?.firstOrNull()?.let { it.content.isNotBlank() && it.author.isNotBlank() } == true,
    )

    val biliSong = moe.ouom.neriplayer.desktop.core.Song(
        key = "bili:BV1d4411N7zD",
        source = moe.ouom.neriplayer.desktop.core.MediaSource.BILIBILI,
        title = "晴天 MV",
        artist = "周杰伦",
        durationMs = 269_000L,
        remoteId = "BV1d4411N7zD",
    )
    val biliComments = online.comments(biliSong, 0, 20)
    log("bili comments: total=${biliComments?.total} latest=${biliComments?.latest?.size ?: 0}")
    check("bili-comments", (biliComments?.latest?.size ?: 0) > 0)
}

/** 后台播放信息快照（系统媒体控制 / 托盘 / 通知共用）。 */
private fun checkBackground() {
    val song = moe.ouom.neriplayer.desktop.core.Song(
        key = "netease:123",
        source = moe.ouom.neriplayer.desktop.core.MediaSource.NETEASE,
        title = "测试歌曲",
        artist = "测试歌手",
        album = "测试专辑",
        durationMs = 210_000L,
        artworkUrl = "https://p1.music.126.net/x.jpg",
    )
    val snapshot = moe.ouom.neriplayer.desktop.core.buildNowPlayingSnapshot(
        song = song,
        durationMs = 210_000L,
        positionMs = 12_000L,
        playing = true,
    )
    check(
        "background-snapshot-fields",
        snapshot != null &&
            snapshot.title == "测试歌曲" &&
            snapshot.artist == "测试歌手" &&
            snapshot.album == "测试专辑" &&
            snapshot.durationMs == 210_000L &&
            snapshot.positionMs == 12_000L &&
            snapshot.playing &&
            snapshot.artUrl == "https://p1.music.126.net/x.jpg" &&
            snapshot.source == "netease",
        "snapshot=$snapshot",
    )
    check(
        "background-snapshot-empty",
        moe.ouom.neriplayer.desktop.core.buildNowPlayingSnapshot(null, 0L, 0L, false) == null,
    )
}

/** 下载：文件名清理、记录读写，以及一次真实的在线歌曲下载。 */
private suspend fun checkDownloads(online: moe.ouom.neriplayer.desktop.net.OnlineRepository) {
    check(
        "download-sanitize-illegal",
        moe.ouom.neriplayer.desktop.core.sanitizeFileName("a/b:c*d?e\"f<g>h|i") == "a_b_c_d_e_f_g_h_i",
        moe.ouom.neriplayer.desktop.core.sanitizeFileName("a/b:c*d?e\"f<g>h|i"),
    )
    check(
        "download-sanitize-blank",
        moe.ouom.neriplayer.desktop.core.sanitizeFileName("   ") == "unknown",
    )
    check(
        "download-sanitize-length",
        moe.ouom.neriplayer.desktop.core.sanitizeFileName("x".repeat(300)).length == 120,
    )
    check(
        "download-sanitize-spaces",
        moe.ouom.neriplayer.desktop.core.sanitizeFileName("周杰伦   晴天 ") == "周杰伦 晴天",
    )

    // 真实下载：搜索一首在线歌曲 → 下载到临时目录 → 校验文件与记录
    val tempDir = kotlin.io.path.createTempDirectory("neri-download-test").toFile()
    val catalogFile = java.io.File(tempDir, "downloads.json")
    val catalog = moe.ouom.neriplayer.desktop.core.DownloadCatalog(catalogFile)
    val settingsRepo = moe.ouom.neriplayer.desktop.core.SettingsRepository()
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
    val manager = moe.ouom.neriplayer.desktop.core.DownloadManager(
        online = online,
        settings = settingsRepo,
        catalog = catalog,
        scope = scope,
        directoryOverride = tempDir,
        lyricsProvider = { song -> online.lyrics(song)?.raw },
    )
    val candidates = online.search(
        moe.ouom.neriplayer.desktop.core.MediaSource.NETEASE,
        moe.ouom.neriplayer.desktop.core.SearchKind.SONG,
        "晴天 周杰伦",
    ).songs
    val target = candidates.firstOrNull()
    if (target == null) {
        check("download-real-file", false, "搜索不到可用歌曲")
    } else {
        val queued = manager.enqueue(listOf(target))
        check("download-enqueue", queued == 1, "queued=$queued")
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline && catalog.items.value[target.key] == null) {
            kotlinx.coroutines.delay(500)
        }
        val entry = catalog.items.value[target.key]
        val file = entry?.let { java.io.File(it.filePath) }
        check(
            "download-real-file",
            file != null && file.isFile && file.length() > 0L,
            "file=${file?.absolutePath} size=${file?.length()}",
        )
        check(
            "download-file-name",
            file != null && file.name.contains("晴天") && file.extension == "mp3",
            "name=${file?.name}",
        )
        check(
            "download-catalog-persist",
            runCatching {
                val reloaded = moe.ouom.neriplayer.desktop.core.DownloadCatalog(catalogFile)
                reloaded.load()
                reloaded.contains(target.key)
            }.getOrDefault(false),
        )
        check(
            "download-enqueue-dedupe",
            manager.enqueue(listOf(target)) == 0,
            "重复入队应被跳过",
        )

        // 元数据：内嵌标签、内嵌封面、封面 sidecar、歌词 sidecar
        val downloadedFile = entry?.let { java.io.File(it.filePath) }
        if (downloadedFile != null && downloadedFile.isFile) {
            val audio = runCatching { org.jaudiotagger.audio.AudioFileIO.read(downloadedFile) }.getOrNull()
            check(
                "download-tag-title",
                audio?.tag?.getFirst(org.jaudiotagger.tag.FieldKey.TITLE) == target.displayName(),
                "title=${audio?.tag?.getFirst(org.jaudiotagger.tag.FieldKey.TITLE)}",
            )
            check(
                "download-tag-artist",
                audio?.tag?.getFirst(org.jaudiotagger.tag.FieldKey.ARTIST) == target.artist,
                "artist=${audio?.tag?.getFirst(org.jaudiotagger.tag.FieldKey.ARTIST)}",
            )
            val embedded = runCatching { audio?.tag?.firstArtwork?.binaryData }.getOrNull()
            check(
                "download-embedded-cover",
                embedded != null && embedded.isNotEmpty(),
                "bytes=${embedded?.size ?: 0}",
            )
            val coverFile = entry.artworkPath?.let { java.io.File(it) }
            check(
                "download-cover-sidecar",
                coverFile != null && coverFile.isFile && coverFile.length() > 1024,
                "file=${coverFile?.name} size=${coverFile?.length()}",
            )
            val lyricFile = entry.lyricsPath?.let { java.io.File(it) }
            check(
                "download-lyrics-sidecar",
                lyricFile != null && lyricFile.isFile && lyricFile.readText().contains("["),
                "file=${lyricFile?.name} size=${lyricFile?.length()}",
            )

            // 模拟「旧版本下载的文件」：清空标签与内嵌封面后，用「补齐标签」恢复
            val stripped = runCatching {
                val audio = org.jaudiotagger.audio.AudioFileIO.read(downloadedFile)
                val tag = audio.tag
                tag?.deleteField(org.jaudiotagger.tag.FieldKey.TITLE)
                tag?.deleteField(org.jaudiotagger.tag.FieldKey.ARTIST)
                tag?.deleteArtworkField()
                audio.commit()
                org.jaudiotagger.audio.AudioFileIO.read(downloadedFile)
                    .tag?.getFirst(org.jaudiotagger.tag.FieldKey.TITLE)
                    .isNullOrBlank()
            }.getOrDefault(false)
            check("download-repair-stripped", stripped, "清空标签后应为空")
            val repaired = kotlinx.coroutines.runBlocking { manager.repairMetadata() }
            val afterRepair = runCatching {
                org.jaudiotagger.audio.AudioFileIO.read(downloadedFile)
            }.getOrNull()
            check("download-repair-count", repaired >= 1, "repaired=$repaired")
            check(
                "download-repair-tag",
                afterRepair?.tag?.getFirst(org.jaudiotagger.tag.FieldKey.TITLE) == target.displayName(),
                "title=${afterRepair?.tag?.getFirst(org.jaudiotagger.tag.FieldKey.TITLE)}",
            )
            check(
                "download-repair-cover",
                afterRepair?.tag?.firstArtwork?.binaryData?.isNotEmpty() == true,
                "bytes=${afterRepair?.tag?.firstArtwork?.binaryData?.size ?: 0}",
            )
        }
    }
    scope.coroutineContext.cancelChildren()
    tempDir.deleteRecursively()
}

/** 「定位到正在播放」的下标计算。 */
private fun checkLocateCurrent() {
    val songs = listOf(
        moe.ouom.neriplayer.desktop.core.Song(key = "local:/a.mp3", title = "A"),
        moe.ouom.neriplayer.desktop.core.Song(key = "local:/b.mp3", title = "B"),
        moe.ouom.neriplayer.desktop.core.Song(key = "net:1", title = "C"),
    )
    check(
        "locate-found-middle",
        moe.ouom.neriplayer.desktop.ui.currentSongIndex(songs, songs[1]) == 1,
    )
    check(
        "locate-found-last",
        moe.ouom.neriplayer.desktop.ui.currentSongIndex(songs, songs[2]) == 2,
    )
    check(
        "locate-not-in-list",
        moe.ouom.neriplayer.desktop.ui.currentSongIndex(
            songs,
            moe.ouom.neriplayer.desktop.core.Song(key = "local:/x.mp3"),
        ) == -1,
    )
    check(
        "locate-no-current",
        moe.ouom.neriplayer.desktop.ui.currentSongIndex(songs, null) == -1,
    )
    check(
        "locate-empty-list",
        moe.ouom.neriplayer.desktop.ui.currentSongIndex(emptyList(), songs[0]) == -1,
    )
}

/** 悬浮歌词的颜色解析、位置换算与尺寸计算。 */
private fun checkFloatingLyrics() {
    check(
        "floating-color-parse",
        moe.ouom.neriplayer.desktop.ui.FloatingLyricColor.of("YELLOW") ==
            moe.ouom.neriplayer.desktop.ui.FloatingLyricColor.YELLOW &&
            moe.ouom.neriplayer.desktop.ui.FloatingLyricColor.of("unknown") ==
            moe.ouom.neriplayer.desktop.ui.FloatingLyricColor.WHITE,
    )
    check(
        "floating-style-parse",
        moe.ouom.neriplayer.desktop.ui.FloatingLyricRenderStyle.of("OUTLINE") ==
            moe.ouom.neriplayer.desktop.ui.FloatingLyricRenderStyle.OUTLINE &&
            moe.ouom.neriplayer.desktop.ui.FloatingLyricAlignment.of("LEFT") ==
            moe.ouom.neriplayer.desktop.ui.FloatingLyricAlignment.LEFT,
    )

    val (x, y) = moe.ouom.neriplayer.desktop.ui.resolveFloatingPosition(
        screenWidth = 1920,
        screenHeight = 1080,
        windowWidth = 900,
        windowHeight = 120,
        ratioX = 0.5f,
        ratioY = 0.85f,
    )
    check("floating-position-center-bottom", x == 510 && y == 816, "x=$x y=$y")

    val (clampedX, clampedY) = moe.ouom.neriplayer.desktop.ui.resolveFloatingPosition(
        screenWidth = 1000,
        screenHeight = 800,
        windowWidth = 900,
        windowHeight = 120,
        ratioX = 1.6f,
        ratioY = -0.5f,
    )
    check("floating-position-clamped", clampedX == 100 && clampedY == 0, "x=$clampedX y=$clampedY")

    val (ratioX, ratioY) = moe.ouom.neriplayer.desktop.ui.resolveFloatingRatio(
        screenWidth = 1920,
        screenHeight = 1080,
        windowWidth = 900,
        windowHeight = 120,
        x = 510,
        y = 816,
    )
    check(
        "floating-ratio-roundtrip",
        kotlin.math.abs(ratioX - 0.5f) < 0.001f && kotlin.math.abs(ratioY - 0.85f) < 0.001f,
        "ratioX=$ratioX ratioY=$ratioY",
    )

    val heightWithTranslation = moe.ouom.neriplayer.desktop.ui.floatingWindowHeightDp(
        moe.ouom.neriplayer.desktop.core.AppSettings(
            floatingLyricsFontSize = 30f,
            floatingLyricsShowTranslation = true,
        )
    )
    val heightWithoutTranslation = moe.ouom.neriplayer.desktop.ui.floatingWindowHeightDp(
        moe.ouom.neriplayer.desktop.core.AppSettings(
            floatingLyricsFontSize = 30f,
            floatingLyricsShowTranslation = false,
        )
    )
    check(
        "floating-window-height",
        heightWithTranslation > heightWithoutTranslation && heightWithoutTranslation >= 64f,
        "with=$heightWithTranslation without=$heightWithoutTranslation",
    )

    // 拖动跟随：窗口必须与鼠标保持固定相对位置（旧实现用窗口内增量累加会越拖越偏）
    val grabX = 100f
    val grabY = 40f
    val (sameX, sameY) = moe.ouom.neriplayer.desktop.ui.resolveDragPosition(
        windowX = 300, windowY = 710,
        pointerLocalX = grabX, pointerLocalY = grabY,
        grabOffsetX = grabX, grabOffsetY = grabY,
        screenWidth = 1500, screenHeight = 1000, windowWidth = 900, windowHeight = 134,
    )
    check("floating-drag-no-jump", sameX == 300 && sameY == 710, "x=$sameX y=$sameY")

    // 鼠标屏幕坐标右移 50（窗口尚未移动，本地坐标变为 150）
    val (stepOneX, _) = moe.ouom.neriplayer.desktop.ui.resolveDragPosition(
        windowX = 300, windowY = 710,
        pointerLocalX = grabX + 50f, pointerLocalY = grabY,
        grabOffsetX = grabX, grabOffsetY = grabY,
        screenWidth = 1500, screenHeight = 1000, windowWidth = 900, windowHeight = 134,
    )
    check("floating-drag-follow-1", stepOneX == 350, "x=$stepOneX")

    // 窗口已跟随到 350 后再右移 50：本地坐标重新变成 150（因为窗口跟着动了），
    // 目标位置应为 400 而不是旧实现里 350 + 50 的重复叠加
    val (stepTwoX, _) = moe.ouom.neriplayer.desktop.ui.resolveDragPosition(
        windowX = 350, windowY = 710,
        pointerLocalX = grabX + 50f, pointerLocalY = grabY,
        grabOffsetX = grabX, grabOffsetY = grabY,
        screenWidth = 1500, screenHeight = 1000, windowWidth = 900, windowHeight = 134,
    )
    check("floating-drag-follow-2", stepTwoX == 400, "x=$stepTwoX")

    // 拖到屏幕外会被夹回屏幕内
    val (clampedDragX, clampedDragY) = moe.ouom.neriplayer.desktop.ui.resolveDragPosition(
        windowX = 300, windowY = 710,
        pointerLocalX = 5000f, pointerLocalY = 5000f,
        grabOffsetX = grabX, grabOffsetY = grabY,
        screenWidth = 1500, screenHeight = 1000, windowWidth = 900, windowHeight = 134,
    )
    check("floating-drag-clamped", clampedDragX == 600 && clampedDragY == 866, "x=$clampedDragX y=$clampedDragY")
}

/** 同步通道序列化与合并策略的离线自检。 */
private fun checkSyncSerializerAndMerge() {
    val songA = moe.ouom.neriplayer.desktop.sync.SyncSong(
        id = 123L,
        name = "测试歌曲A",
        artist = "歌手A",
        album = "专辑A",
        durationMs = 210_000L,
        addedAt = 1_000L,
    )
    val songB = songA.copy(id = 456L, name = "测试歌曲B", album = "专辑B", addedAt = 2_000L)
    val payload = moe.ouom.neriplayer.desktop.sync.SyncData(
        deviceId = "desktop-test",
        deviceName = "self-test",
        playlists = listOf(
            moe.ouom.neriplayer.desktop.sync.SyncPlaylist(
                id = 1L,
                name = "歌单A",
                songs = listOf(songA),
                createdAt = 10L,
                modifiedAt = 20L,
            )
        ),
        favoritePlaylists = listOf(
            moe.ouom.neriplayer.desktop.sync.SyncFavoritePlaylist(
                id = 7L,
                name = "网易云收藏",
                source = "netease",
                songs = listOf(songA),
                addedTime = 5L,
                modifiedAt = 6L,
            )
        ),
        recentPlays = listOf(
            moe.ouom.neriplayer.desktop.sync.SyncRecentPlay(songId = 123L, song = songA, playedAt = 999L)
        ),
        playbackStatBuckets = listOf(
            moe.ouom.neriplayer.desktop.sync.SyncPlaybackStatBucket(
                dayStartAt = 1_700_000_000_000L,
                identityKey = "123|专辑A|",
                playCount = 3,
                totalListenMs = 12_000L,
            )
        ),
    )

    val jsonBytes = moe.ouom.neriplayer.desktop.sync.SyncDataSerializer.serialize(payload, useDataSaver = false)
    check("sync-json-text", jsonBytes.decodeToString().trimStart().startsWith("{"))
    check(
        "sync-json-roundtrip",
        moe.ouom.neriplayer.desktop.sync.SyncDataSerializer.deserialize(jsonBytes) == payload,
    )

    val rawBytes = moe.ouom.neriplayer.desktop.sync.SyncDataSerializer.serialize(payload, useDataSaver = true)
    check(
        "sync-raw-zstd-magic",
        rawBytes.size > 4 && rawBytes[0] == 0x28.toByte() && rawBytes[1] == 0xB5.toByte() &&
            rawBytes[2] == 0x2F.toByte() && rawBytes[3] == 0xFD.toByte(),
        "size=${rawBytes.size} json=${jsonBytes.size}",
    )
    check(
        "sync-raw-roundtrip",
        moe.ouom.neriplayer.desktop.sync.SyncDataSerializer.deserialize(rawBytes) == payload,
    )
    check(
        "sync-raw-smaller-than-json",
        rawBytes.size < jsonBytes.size,
        "raw=${rawBytes.size} json=${jsonBytes.size}",
    )
    val legacy = moe.ouom.neriplayer.desktop.sync.SyncDataSerializer.encodeLegacyBase64(payload)
    check(
        "sync-legacy-base64-roundtrip",
        moe.ouom.neriplayer.desktop.sync.SyncDataSerializer.deserialize(legacy) == payload,
        "legacy=${legacy.size}",
    )

    // 合并：歌单按 modifiedAt 取新 + 歌曲取并集
    val local = moe.ouom.neriplayer.desktop.sync.SyncData(
        playlists = listOf(
            moe.ouom.neriplayer.desktop.sync.SyncPlaylist(id = 1L, name = "本地旧名", songs = listOf(songA), modifiedAt = 10L)
        ),
    )
    val remote = moe.ouom.neriplayer.desktop.sync.SyncData(
        deviceId = "phone",
        playlists = listOf(
            moe.ouom.neriplayer.desktop.sync.SyncPlaylist(id = 1L, name = "远端新名", songs = listOf(songB), modifiedAt = 20L)
        ),
    )
    val merged = moe.ouom.neriplayer.desktop.sync.SyncMerger.merge(local, remote)
    check(
        "sync-merge-playlist-newer-wins",
        merged.playlists.size == 1 && merged.playlists.first().name == "远端新名",
        "name=${merged.playlists.firstOrNull()?.name}",
    )
    check(
        "sync-merge-song-union",
        merged.playlists.first().songs.size == 2,
        "songs=${merged.playlists.first().songs.size}",
    )
    check(
        "sync-merge-keeps-remote-fields",
        merged.deviceId == "phone",
        "deviceId=${merged.deviceId}",
    )

    // 统计合并取 max，避免重复累加
    val statLocal = moe.ouom.neriplayer.desktop.sync.SyncTrackStat(
        identityKey = "123|专辑A|",
        totalListenMs = 5_000L,
        playCount = 2,
    )
    val statRemote = statLocal.copy(totalListenMs = 9_000L, playCount = 4)
    val mergedStats = moe.ouom.neriplayer.desktop.sync.SyncMerger.merge(
        moe.ouom.neriplayer.desktop.sync.SyncData(playbackStats = listOf(statLocal)),
        moe.ouom.neriplayer.desktop.sync.SyncData(playbackStats = listOf(statRemote)),
    ).playbackStats
    check(
        "sync-merge-stats-max",
        mergedStats.size == 1 && mergedStats.first().totalListenMs == 9_000L && mergedStats.first().playCount == 4,
    )

    // 最近播放：按身份去重并保留最新时间
    val recentMerged = moe.ouom.neriplayer.desktop.sync.SyncMerger.merge(
        moe.ouom.neriplayer.desktop.sync.SyncData(
            recentPlays = listOf(
                moe.ouom.neriplayer.desktop.sync.SyncRecentPlay(song = songA, playedAt = 100L)
            )
        ),
        moe.ouom.neriplayer.desktop.sync.SyncData(
            recentPlays = listOf(
                moe.ouom.neriplayer.desktop.sync.SyncRecentPlay(song = songA, playedAt = 500L)
            )
        ),
    ).recentPlays
    check("sync-merge-recent-latest", recentMerged.size == 1 && recentMerged.first().playedAt == 500L)

    // 删除墓碑：比 addedAt 更新的删除记录应移除歌曲
    val tombstone = moe.ouom.neriplayer.desktop.sync.SyncPlaylistSongDeletion(
        playlistId = 1L,
        songId = songB.id,
        album = songB.album,
        deletedAt = 5_000L,
    )
    val afterDeletion = moe.ouom.neriplayer.desktop.sync.SyncMerger.merge(
        moe.ouom.neriplayer.desktop.sync.SyncData(
            playlists = listOf(
                moe.ouom.neriplayer.desktop.sync.SyncPlaylist(id = 1L, name = "列表", songs = listOf(songA), modifiedAt = 1L)
            )
        ),
        moe.ouom.neriplayer.desktop.sync.SyncData(
            playlists = listOf(
                moe.ouom.neriplayer.desktop.sync.SyncPlaylist(id = 1L, name = "列表", songs = listOf(songB), modifiedAt = 2L)
            ),
            playlistSongDeletions = listOf(tombstone),
        ),
    ).playlists.first().songs
    check("sync-merge-deletion-tombstone", afterDeletion.size == 1 && afterDeletion.first().id == songA.id)

    checkArtworkDownscale()
}

/**
 * 本轮同步新增的纯逻辑检查：翻译容差、评论富媒体、悬浮歌词换行、
 * 拖动落点、批量整理与 WebDAV 条件写。
 */
private fun checkDesktopSyncFeatures() {
    // 1. 翻译容差匹配：差几十毫秒要能对上，差太多不能硬塞
    val base = listOf(
        moe.ouom.neriplayer.desktop.core.LyricLine(1_000, "第一句"),
        moe.ouom.neriplayer.desktop.core.LyricLine(3_000, "第二句"),
        moe.ouom.neriplayer.desktop.core.LyricLine(5_000, "第三句"),
    )
    val translated = listOf(
        moe.ouom.neriplayer.desktop.core.LyricLine(1_080, "first"),
        moe.ouom.neriplayer.desktop.core.LyricLine(2_900, "second"),
        moe.ouom.neriplayer.desktop.core.LyricLine(5_800, "too far"),
    )
    val merged = LrcParser.merge(base, translated)
    check("lyric-align-near", merged[0].translation == "first", "${merged[0].translation}")
    check("lyric-align-slight-early", merged[1].translation == "second", "${merged[1].translation}")
    check("lyric-align-out-of-tolerance", merged[2].translation == null, "${merged[2].translation}")

    // 2. 制作信息行不参与对齐，译文要向下贴到正文行
    val creditMerged = LrcParser.merge(
        listOf(
            moe.ouom.neriplayer.desktop.core.LyricLine(0, "作词: 某人"),
            moe.ouom.neriplayer.desktop.core.LyricLine(0, "正文第一句"),
            moe.ouom.neriplayer.desktop.core.LyricLine(3_000, "正文第二句"),
        ),
        listOf(
            moe.ouom.neriplayer.desktop.core.LyricLine(0, "作词: 某某"),
            moe.ouom.neriplayer.desktop.core.LyricLine(0, "line one"),
            moe.ouom.neriplayer.desktop.core.LyricLine(3_000, "line two"),
        ),
    )
    check("lyric-credit-line-skipped", creditMerged[0].translation == null, "${creditMerged[0].translation}")
    check("lyric-credit-aligns-body", creditMerged[1].translation == "line one", "${creditMerged[1].translation}")
    check("lyric-credit-next-body", creditMerged[2].translation == "line two", "${creditMerged[2].translation}")

    // 3. 未翻译占位「//」不算翻译
    val placeholder = LrcParser.merge(
        listOf(moe.ouom.neriplayer.desktop.core.LyricLine(0, "text")),
        listOf(moe.ouom.neriplayer.desktop.core.LyricLine(0, "//")),
    )
    check("lyric-placeholder-ignored", placeholder[0].translation == null, "${placeholder[0].translation}")

    // 4. 评论：网易云表情 / 等级 / 引用 / 楼中楼
    val neteaseObject = moe.ouom.neriplayer.desktop.net.NeriJsonParser.parse(
        """{"commentId":11,"content":"好听[大笑]","likedCount":7,"time":1700000000000,
            "user":{"nickname":"甲","avatarUrl":"http://p1.music.126.net/a.jpg","level":6},
            "showFloorComment":{"replyCount":3,"comments":[{"commentId":12,"content":"回复一","user":{"nickname":"乙"}}]},
            "beReplied":[{"content":"被引用的原文","user":{"nickname":"丙"}}]}""",
    ).asObject()
    val neteaseComment = neteaseObject?.let {
        moe.ouom.neriplayer.desktop.net.neteaseCommentFromJson(moe.ouom.neriplayer.desktop.net.JsonObjectSelf(it))
    }
    check(
        "comment-netease-emote",
        neteaseComment?.emotes?.singleOrNull()?.url?.contains("emoji_86") == true,
        "${neteaseComment?.emotes}",
    )
    check("comment-netease-level", neteaseComment?.userLevel == 6, "${neteaseComment?.userLevel}")
    check("comment-netease-quote", neteaseComment?.quote?.content == "被引用的原文", "${neteaseComment?.quote}")
    check(
        "comment-netease-floor",
        neteaseComment?.replyCount == 3 && neteaseComment?.replies?.size == 1,
        "count=${neteaseComment?.replyCount} preview=${neteaseComment?.replies?.size}",
    )

    // 5. 评论：B 站表情 / 配图 / 头像协议 / 秒级时间戳
    val biliObject = moe.ouom.neriplayer.desktop.net.NeriJsonParser.parse(
        """{"rpid":21,"content":{"message":"看图[doge]",
            "emote":{"[doge]":{"url":"//i0.hdslb.com/e.png"}},
            "pictures":[{"img_src":"//i0.hdslb.com/a.jpg","img_width":1200,"img_height":800}]},
            "member":{"uname":"丁","avatar":"//i0.hdslb.com/u.jpg","level_info":{"current_level":4}},
            "ctime":1700000000,"like":9}""",
    ).asObject()
    val biliComment = biliObject?.let {
        moe.ouom.neriplayer.desktop.net.biliCommentFromJson(moe.ouom.neriplayer.desktop.net.JsonObjectSelf(it))
    }
    check("comment-bili-emote", biliComment?.emotes?.singleOrNull()?.url == "https://i0.hdslb.com/e.png", "${biliComment?.emotes}")
    check("comment-bili-image", biliComment?.images?.singleOrNull()?.url == "https://i0.hdslb.com/a.jpg", "${biliComment?.images}")
    check("comment-bili-image-ratio", biliComment?.images?.firstOrNull()?.aspectRatio == 1.5f, "${biliComment?.images?.firstOrNull()?.aspectRatio}")
    check("comment-bili-avatar-scheme", biliComment?.avatarUrl?.startsWith("https://") == true, "${biliComment?.avatarUrl}")
    check("comment-bili-time", biliComment?.timeMs == 1_700_000_000_000L, "${biliComment?.timeMs}")

    // 6. 正文切分：文本与内联表情按出现顺序交错
    val segments = moe.ouom.neriplayer.desktop.net.splitCommentContent(
        "前[大笑]后[大笑]",
        moe.ouom.neriplayer.desktop.net.neteaseEmotesIn("前[大笑]后[大笑]"),
    )
    check("comment-split-count", segments.size == 4, "segments=${segments.size}")
    check(
        "comment-split-order",
        (segments.getOrNull(0) as? moe.ouom.neriplayer.desktop.net.CommentSegment.Text)?.text == "前" &&
            segments.getOrNull(1) is moe.ouom.neriplayer.desktop.net.CommentSegment.Emote &&
            (segments.getOrNull(2) as? moe.ouom.neriplayer.desktop.net.CommentSegment.Text)?.text == "后",
        "$segments",
    )

    // 7. 悬浮歌词：句数归一化、长句模式、窗口高度
    val compactHeight = moe.ouom.neriplayer.desktop.ui.floatingWindowHeightDp(
        moe.ouom.neriplayer.desktop.core.AppSettings(),
    )
    val threeLines = moe.ouom.neriplayer.desktop.ui.floatingWindowHeightDp(
        moe.ouom.neriplayer.desktop.core.AppSettings(floatingLyricsSentenceCount = 3),
    )
    val wrapped = moe.ouom.neriplayer.desktop.ui.floatingWindowHeightDp(
        moe.ouom.neriplayer.desktop.core.AppSettings(floatingLyricsLongLineMode = "wrap"),
    )
    check(
        "floating-sentence-clamp",
        moe.ouom.neriplayer.desktop.ui.normalizeFloatingSentenceCount(0) == 1 &&
            moe.ouom.neriplayer.desktop.ui.normalizeFloatingSentenceCount(9) == 3,
    )
    check(
        "floating-long-mode",
        moe.ouom.neriplayer.desktop.ui.normalizeFloatingLongLineMode(" WRAP ") == "wrap" &&
            moe.ouom.neriplayer.desktop.ui.normalizeFloatingLongLineMode("bogus") == "scroll",
    )
    check("floating-height-preview", threeLines > compactHeight, "$compactHeight -> $threeLines")
    check("floating-height-wrap", wrapped > compactHeight, "$compactHeight -> $wrapped")

    // 8. 拖动排序落点换算（item 0 是歌单头部）
    val visible = listOf(
        Triple(0, 0, 120),
        Triple(1, 120, 64),
        Triple(2, 184, 64),
        Triple(3, 248, 64),
    )
    check(
        "drag-target-second-song",
        moe.ouom.neriplayer.desktop.ui.screens.songIndexAtPointer(200f, visible, 5) == 1,
        "${moe.ouom.neriplayer.desktop.ui.screens.songIndexAtPointer(200f, visible, 5)}",
    )
    check("drag-target-top", moe.ouom.neriplayer.desktop.ui.screens.songIndexAtPointer(-10f, visible, 5) == 0)
    check("drag-target-tail", moe.ouom.neriplayer.desktop.ui.screens.songIndexAtPointer(9_000f, visible, 5) == 2)

    // 9. 批量整理：时长容差与状态文案
    check(
        "curation-duration-tolerance",
        moe.ouom.neriplayer.desktop.core.LocalCurationService.durationToleranceMs(200_000) == 12_000L &&
            moe.ouom.neriplayer.desktop.core.LocalCurationService.durationToleranceMs(1_000) == 7_000L &&
            moe.ouom.neriplayer.desktop.core.LocalCurationService.durationToleranceMs(400_000) == 15_000L,
    )
    val readyItem = moe.ouom.neriplayer.desktop.core.CurationItem(
        song = Song(key = "local:a", source = MediaSource.LOCAL, title = "A"),
        phase = moe.ouom.neriplayer.desktop.core.CurationPhase.READY,
        lyric = "x",
        coverUrl = "y",
    )
    check("curation-status-text", readyItem.statusText.contains("歌词") && readyItem.statusText.contains("封面"), readyItem.statusText)

    // 10. WebDAV 条件写：弱 ETag 不能用于 If-Match
    check(
        "webdav-strong-etag",
        moe.ouom.neriplayer.desktop.sync.WebDavSyncTransport.strongETag("\"abc\"") == "\"abc\"" &&
            moe.ouom.neriplayer.desktop.sync.WebDavSyncTransport.strongETag("W/\"abc\"").isEmpty(),
    )
    check(
        "webdav-content-compare",
        moe.ouom.neriplayer.desktop.sync.WebDavSyncTransport.sameContent(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)) &&
            !moe.ouom.neriplayer.desktop.sync.WebDavSyncTransport.sameContent(byteArrayOf(1), byteArrayOf(2)),
    )

    // 11. 损坏的远端同步文件必须抛错：返回空数据会被当成「远端没数据」而覆盖云端
    val corruptZstd = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte(), 1, 2, 3, 4)
    check(
        "sync-corrupt-zstd-throws",
        runCatching { moe.ouom.neriplayer.desktop.sync.SyncDataSerializer.deserialize(corruptZstd) }.isFailure,
    )
    check(
        "sync-corrupt-json-throws",
        runCatching {
            moe.ouom.neriplayer.desktop.sync.SyncDataSerializer.deserialize("{broken".toByteArray())
        }.isFailure,
    )

    // 12. 只有配图、没有文字的 B 站评论也要保留
    val imageOnlyObject = moe.ouom.neriplayer.desktop.net.NeriJsonParser.parse(
        """{"rpid":31,"content":{"message":"","pictures":[{"img_src":"//i0.hdslb.com/x.jpg"}]},
            "member":{"uname":"戊"},"ctime":1700000000}""",
    ).asObject()
    val imageOnlyComment = imageOnlyObject?.let {
        moe.ouom.neriplayer.desktop.net.biliCommentFromJson(moe.ouom.neriplayer.desktop.net.JsonObjectSelf(it))
    }
    check(
        "comment-bili-image-only",
        imageOnlyComment?.images?.size == 1 && imageOnlyComment.content.isEmpty(),
        "images=${imageOnlyComment?.images?.size}",
    )

    // 13. Cookie 归属按域名边界判断，evilbilibili.com 不能拿到 B 站 Cookie
    val httpForTest = moe.ouom.neriplayer.desktop.net.HttpService()
    check(
        "cookie-domain-boundary",
        httpForTest.jarKeyFor("evilbilibili.com") == "evilbilibili.com" &&
            httpForTest.jarKeyFor("api.bilibili.com") == "bilibili" &&
            httpForTest.jarKeyFor("music.163.com") == "netease",
        "${httpForTest.jarKeyFor("evilbilibili.com")} / ${httpForTest.jarKeyFor("api.bilibili.com")}",
    )

    // 14. 「关于」里的版本号来自构建脚本生成，必须是 x.y.z 而不是写死的旧值
    val buildVersion = moe.ouom.neriplayer.desktop.core.BuildInfo.VERSION
    check(
        "build-version-present",
        Regex("""^\d+\.\d+\.\d+$""").matches(buildVersion) && buildVersion != "0.0.0",
        buildVersion,
    )

    // 15. 删除墓碑：本地删掉的歌不能又被远端的「并集」合并回来
    val tombSong = moe.ouom.neriplayer.desktop.sync.SyncSong(id = 1L, name = "删掉的歌", album = "专辑", addedAt = 1_000L)
    val tombMerged = moe.ouom.neriplayer.desktop.sync.SyncMerger.merge(
        moe.ouom.neriplayer.desktop.sync.SyncData(
            playlists = listOf(
                moe.ouom.neriplayer.desktop.sync.SyncPlaylist(id = 7L, name = "歌单", songs = emptyList(), modifiedAt = 2_000L),
            ),
            playlistSongDeletions = listOf(
                moe.ouom.neriplayer.desktop.sync.SyncPlaylistSongDeletion(
                    playlistId = 7L,
                    songId = 1L,
                    album = "专辑",
                    deletedAt = 2_000L,
                ),
            ),
        ),
        moe.ouom.neriplayer.desktop.sync.SyncData(
            playlists = listOf(
                moe.ouom.neriplayer.desktop.sync.SyncPlaylist(
                    id = 7L,
                    name = "歌单",
                    songs = listOf(tombSong),
                    modifiedAt = 1_500L,
                ),
            ),
        ),
    )
    check(
        "sync-tombstone-removes-song",
        tombMerged.playlists.first { it.id == 7L }.songs.isEmpty(),
        "songs=${tombMerged.playlists.first { it.id == 7L }.songs.size}",
    )

    // 删除之后又重新加入的歌（addedAt 晚于 deletedAt）必须保留
    val readdedMerged = moe.ouom.neriplayer.desktop.sync.SyncMerger.merge(
        moe.ouom.neriplayer.desktop.sync.SyncData(
            playlists = listOf(
                moe.ouom.neriplayer.desktop.sync.SyncPlaylist(
                    id = 7L,
                    name = "歌单",
                    songs = listOf(tombSong.copy(addedAt = 3_000L)),
                    modifiedAt = 3_000L,
                ),
            ),
        ),
        moe.ouom.neriplayer.desktop.sync.SyncData(
            playlists = listOf(
                moe.ouom.neriplayer.desktop.sync.SyncPlaylist(
                    id = 7L,
                    name = "歌单",
                    songs = emptyList(),
                    modifiedAt = 1_500L,
                ),
            ),
            playlistSongDeletions = listOf(
                moe.ouom.neriplayer.desktop.sync.SyncPlaylistSongDeletion(
                    playlistId = 7L,
                    songId = 1L,
                    album = "专辑",
                    deletedAt = 2_000L,
                ),
            ),
        ),
    )
    check(
        "sync-tombstone-keeps-readded",
        readdedMerged.playlists.first { it.id == 7L }.songs.size == 1,
        "songs=${readdedMerged.playlists.first { it.id == 7L }.songs.size}",
    )

    // 16. B 站曲目身份不能全靠 id=0：两首不同的 bvid 必须是两个身份
    val biliA = Song(
        key = "bili:BV1AA",
        source = MediaSource.BILIBILI,
        remoteId = "BV1AA",
        title = "A",
        album = "哔哩哔哩",
    )
    val biliB = biliA.copy(key = "bili:BV1BB", remoteId = "BV1BB", title = "B")
    check(
        "sync-bili-identity-unique",
        moe.ouom.neriplayer.desktop.sync.SyncMapping.identityKey(biliA) !=
            moe.ouom.neriplayer.desktop.sync.SyncMapping.identityKey(biliB),
        "${moe.ouom.neriplayer.desktop.sync.SyncMapping.identityKey(biliA)} vs " +
            moe.ouom.neriplayer.desktop.sync.SyncMapping.identityKey(biliB),
    )
    val biliRoundTrip = moe.ouom.neriplayer.desktop.sync.SyncMapping.fromSyncSong(
        moe.ouom.neriplayer.desktop.sync.SyncMapping.toSyncSong(biliA),
    )
    check(
        "sync-bili-roundtrip",
        biliRoundTrip.source == MediaSource.BILIBILI && biliRoundTrip.remoteId == "BV1AA",
        "source=${biliRoundTrip.source} remoteId=${biliRoundTrip.remoteId}",
    )

    // 17. 墓碑存储：记录、去重、抑制
    val tombFile = java.io.File(System.getProperty("java.io.tmpdir"), "neri-selftest-tombstones.json")
    tombFile.delete()
    val tombStore = moe.ouom.neriplayer.desktop.sync.SyncTombstoneStore(tombFile)
    val tombPlaylist = moe.ouom.neriplayer.desktop.core.Playlist(
        id = "aaaa",
        name = "测试",
        songs = listOf(biliA),
        updatedAt = 1L,
    )
    tombStore.recordSongRemoved(tombPlaylist, biliA, now = 1_000L)
    tombStore.recordSongRemoved(tombPlaylist, biliA, now = 2_000L)
    check(
        "sync-tombstone-store-dedup",
        tombStore.snapshot().songDeletions.size == 1 &&
            tombStore.snapshot().songDeletions.first().deletedAt == 2_000L,
        "${tombStore.snapshot().songDeletions.size}",
    )
    tombStore.withRecordingSuppressed {
        tombStore.recordSongRemoved(tombPlaylist, biliA, now = 3_000L)
    }
    check(
        "sync-tombstone-store-suppressed",
        tombStore.snapshot().songDeletions.size == 1,
        "${tombStore.snapshot().songDeletions.size}",
    )
    tombFile.delete()

    log("desktop sync checks done")
}

/**
 * 封面解码回归：在线音源的封面常见 3000×3000 甚至 4096×4096，
 * 整张解码单张就要 34~64 MB，必须按最长边缩放后再进缓存。
 */
private fun checkArtworkDownscale() {
    val side = 3000
    val image = java.awt.image.BufferedImage(side, side, java.awt.image.BufferedImage.TYPE_INT_RGB)
    image.createGraphics().apply {
        color = java.awt.Color(176, 58, 92)
        fillRect(0, 0, side, side)
        dispose()
    }
    val buffer = java.io.ByteArrayOutputStream()
    val written = runCatching { javax.imageio.ImageIO.write(image, "jpg", buffer) }.getOrDefault(false)
    if (!written) {
        check("artwork-downscale", false, "无法生成测试用 JPEG（缺少 ImageIO 写出支持）")
        return
    }
    val decoded = moe.ouom.neriplayer.desktop.ui.decodeArtwork(buffer.toByteArray())
    val longest = decoded?.let { maxOf(it.width, it.height) } ?: 0
    check(
        "artwork-downscale",
        longest in 1..768,
        "3000×3000 → ${decoded?.width}×${decoded?.height}（单张 ${longest.toLong() * longest * 4 / 1048576} MB）",
    )
    log("artwork cache: ${moe.ouom.neriplayer.desktop.ui.artworkCacheStats()}")
}
