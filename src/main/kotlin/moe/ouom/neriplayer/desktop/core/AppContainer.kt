package moe.ouom.neriplayer.desktop.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import moe.ouom.neriplayer.desktop.net.LyricSource
import moe.ouom.neriplayer.desktop.net.OnlineRepository
import moe.ouom.neriplayer.desktop.sync.GitHubSyncManager
import moe.ouom.neriplayer.desktop.sync.SyncConfigStore

/** 全局依赖容器，替代 Android 端的 Application 级单例。 */
class AppContainer {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository()
    val library = LibraryRepository(settings)
    val playlists = PlaylistRepository()
    val history = HistoryRepository()
    val stats = StatsRepository(history)
    val online = OnlineRepository()
    val http: moe.ouom.neriplayer.desktop.net.HttpService get() = online.httpService
    val accounts = AccountRepository(online.httpService)
    val syncConfig = SyncConfigStore()
    val downloadCatalog = DownloadCatalog()
    val downloads = DownloadManager(
        online = online,
        settings = settings,
        catalog = downloadCatalog,
        scope = scope,
        lyricsProvider = { song -> lyrics.load(song).raw.takeIf { it.isNotBlank() } },
    )
    val mediaControl = SystemMediaControl(
        snapshotProvider = {
            buildNowPlayingSnapshot(
                song = player.currentSong.value,
                durationMs = player.durationMs.value,
                positionMs = player.positionMs.value,
                playing = player.state.value == PlaybackState.PLAYING,
            )
        },
        onPlayPause = { player.togglePlayPause() },
        onNext = { player.next() },
        onPrevious = { player.previous() },
        onStop = { player.pause() },
        shuffleProvider = { player.shuffle.value },
        repeatProvider = { player.repeatMode.value },
    )
    val sync = GitHubSyncManager(
        configStore = syncConfig,
        playlists = playlists,
        history = history,
        stats = stats,
        library = library,
        http = online.httpService,
    )
    val neteaseLogin = moe.ouom.neriplayer.desktop.net.NeteaseLogin(online.httpService)
    val biliLogin = moe.ouom.neriplayer.desktop.net.BiliLogin(online.httpService)
    val lyrics = LyricsRepository(
        remoteProvider = { song -> online.lyrics(song) },
        preferredProvider = { song, source -> online.preferredLyrics(song, source) },
        preferredSource = { LyricSource.of(settings.current.lyricSourcePreference) },
        offsetMs = { source -> settings.current.lyricOffsetMs(source) },
    )
    val player = PlayerManager(
        settings = settings,
        history = history,
        stats = stats,
        lyricsRepository = lyrics,
        online = online,
        scope = scope,
        downloads = downloadCatalog,
    )

    /** 改了歌词来源、偏移或音译开关后调用：清空歌词缓存并重新加载当前歌曲。 */
    fun reloadLyricsAfterSourceChange() {
        lyrics.invalidateAll()
        player.reloadLyrics()
    }

    fun bootstrap(scanLibrary: Boolean = true) {
        library.load()
        downloadCatalog.load()
        accounts.load()
        playlists.load()
        history.load()
        stats.load()
        player.attachSnapshotFlow()
        player.restoreLastQueue()
        scope.launch(Dispatchers.IO) { refreshAccountProfiles() }
        observeLocalChangesForAutoSync()
        startMediaControlBridge()
        if (scanLibrary) {
            scope.launch { library.scan() }
        }
    }

    private var localRevision = 0L

    /** 播放状态变化时同步给系统媒体控制；并按设置启停服务。 */
    private fun startMediaControlBridge() {
        scope.launch {
            combine(
                player.currentSong,
                player.state,
                player.volume,
                player.shuffle,
                player.repeatMode,
            ) { song, state, volume, shuffle, repeat ->
                listOf(song?.key, state, volume, shuffle, repeat).joinToString("|")
            }.collect {
                if (mediaControl.running) mediaControl.refresh()
            }
        }
        scope.launch {
            settings.state
                .map { it.mprisEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) {
                        mediaControl.start()
                    } else {
                        mediaControl.stop()
                    }
                }
        }
    }

    /** 本地歌单 / 历史 / 统计变化后，若开启了自动同步则静默同步一次。 */
    private fun observeLocalChangesForAutoSync() {
        scope.launch {
            combine(playlists.playlists, history.entries, stats.stats) { p, h, s ->
                Triple(p.size, h.size, s.size)
            }.collect { localRevision += 1 }
        }
        scope.launch {
            var lastSyncedRevision = -1L
            while (true) {
                delay(15_000)
                val config = syncConfig.current
                if (!config.autoSync || !config.configured) continue
                if (lastSyncedRevision < 0L) {
                    // 启动后的第一次循环只记录基线，避免刚打开就上传
                    lastSyncedRevision = localRevision
                    continue
                }
                if (localRevision == lastSyncedRevision) continue
                sync.performSync()
                lastSyncedRevision = localRevision
            }
        }
    }

    /**
     * 登录时若账号信息接口临时失败，会以「只有 Cookie」的形式保存登录态；
     * 这里在启动时补拉一次昵称与 UID，让界面与「我的歌单」恢复正常。
     */
    private fun refreshAccountProfiles() {
        accounts.accountOf(MediaSource.NETEASE)
            ?.takeIf { it.nickname.isBlank() || it.userId.isBlank() }
            ?.let { runCatching { neteaseLogin.fetchProfile() }.getOrNull() }
            ?.let { accounts.save(MediaSource.NETEASE, it, "netease") }
        accounts.accountOf(MediaSource.BILIBILI)
            ?.takeIf { it.nickname.isBlank() || it.userId.isBlank() }
            ?.let { runCatching { biliLogin.fetchProfile() }.getOrNull() }
            ?.let { accounts.save(MediaSource.BILIBILI, it, "bilibili") }
    }
}
