package moe.ouom.neriplayer.desktop.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Windows 目录布局（对应 Linux 版的 XDG 目录）。
 *
 * Windows 把「漫游配置」和「本机数据」分成两棵树：
 *  - **%APPDATA%**（Roaming）：设置、账号、同步配置 —— 体积小，跟着账号漫游；
 *  - **%LOCALAPPDATA%**（Local）：曲库、缓存、日志 —— 体积大，不必漫游。
 *
 * 与 Linux 版一样把两者分开存放，这样「清理缓存」不会顺手把设置删掉。
 * 企业环境里这两项常被重定向到网络盘，所以一律走环境变量而不是硬编码路径。
 * 另外提供 [NERIPLAYER_CONFIG_HOME] / [NERIPLAYER_DATA_HOME] / [NERIPLAYER_CACHE_HOME]
 * 三个环境变量，便于把数据放到便携目录（对应 Linux 版的 XDG_* 覆盖）。
 */
object AppDirs {
    private val userHome: String = System.getProperty("user.home")

    /** 先看显式覆盖，再看 Windows 的已知目录，最后回落到用户主目录下的默认位置。 */
    private fun baseDirectory(override: String, knownFolder: String, fallback: String): File {
        System.getenv(override)?.takeIf { it.isNotBlank() }?.let { return File(it.trim()) }
        System.getenv(knownFolder)?.takeIf { it.isNotBlank() }?.let { return File(it.trim()) }
        return File(userHome, fallback)
    }

    /** 设置 / 账号 / 同步配置：%APPDATA%\NeriPlayer */
    private val roaming: File by lazy {
        baseDirectory("NERIPLAYER_CONFIG_HOME", "APPDATA", "AppData/Roaming")
    }

    /** 曲库 / 缓存 / 日志：%LOCALAPPDATA%\NeriPlayer */
    private val local: File by lazy {
        baseDirectory("NERIPLAYER_DATA_HOME", "LOCALAPPDATA", "AppData/Local")
    }

    val configDir: File by lazy { File(roaming, "NeriPlayer").apply { mkdirs() } }
    val dataDir: File by lazy { File(local, "NeriPlayer").apply { mkdirs() } }
    val cacheDir: File by lazy {
        val override = System.getenv("NERIPLAYER_CACHE_HOME")?.takeIf { it.isNotBlank() }
        (override?.let { File(it.trim()) } ?: File(dataDir, "cache")).apply { mkdirs() }
    }

    val artworkDir: File by lazy { File(cacheDir, "artwork").apply { mkdirs() } }
    val coverDir: File by lazy { File(cacheDir, "covers").apply { mkdirs() } }
    val logDir: File by lazy { File(dataDir, "logs").apply { mkdirs() } }

    val libraryFile: File get() = File(dataDir, "library.json")
    val playlistFile: File get() = File(dataDir, "playlists.json")
    val historyFile: File get() = File(dataDir, "history.json")
    val statsFile: File get() = File(dataDir, "stats.json")
    val settingsFile: File get() = File(configDir, "settings.json")
    val searchHistoryFile: File get() = File(dataDir, "search_history.json")
    val accountFile: File get() = File(configDir, "accounts.json")
    val syncConfigFile: File get() = File(configDir, "sync.json")
    val downloadCatalogFile: File get() = File(dataDir, "downloads.json")
}

val NeriJson: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
}

/** 简单的 JSON 文件存储，写入采用「临时文件 + 原子重命名」。 */
class JsonFileStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val defaultProvider: () -> T,
) {
    private val lock = Any()

    fun load(): T = synchronized(lock) {
        if (!file.exists()) return@synchronized defaultProvider()
        runCatching { NeriJson.decodeFromString(serializer, file.readText()) }
            .getOrElse { defaultProvider() }
    }

    fun save(value: T) = synchronized(lock) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(NeriJson.encodeToString(serializer, value))
            // Windows 上 renameTo 不能覆盖已存在的文件，必须先删掉目标
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }
}

enum class DarkModeSetting { AUTO, LIGHT, DARK }

@Serializable
data class HomeCardSettings(
    val continuePlaying: Boolean = true,
    val guessYouLike: Boolean = true,
    val hotTracks: Boolean = true,
    val radar: Boolean = true,
    val recommended: Boolean = true,
)

@Serializable
data class AppSettings(
    val themeSeedColor: String = "0061A4",
    val customSeedColors: List<String> = emptyList(),
    val dynamicColor: Boolean = false,
    val darkMode: DarkModeSetting = DarkModeSetting.AUTO,
    val paletteStyle: String = "TonalSpot",
    val colorSpec: String = "SPEC_2021",
    val musicFolders: List<String> = emptyList(),
    val volume: Float = 0.85f,
    val playbackSpeed: Float = 1.0f,
    val pitchSemitone: Float = 0.0f,
    val loudnessEnhancer: Boolean = false,
    val equalizerEnabled: Boolean = false,
    val equalizerPreset: String = "平直",
    val equalizerBands: List<Float> = List(10) { 0f },
    val lyricsFontScale: Float = 1.0f,
    val showLyricTranslation: Boolean = true,
    val coverShowsLyrics: Boolean = true,
    val showNowPlayingTitle: Boolean = true,
    val songTitleMarquee: Boolean = true,
    /** 界面缩放（HiDPI）：0 = 跟随系统缩放，其余为 1.0 / 1.25 / 1.5 … 的固定比例。 */
    val uiScale: Float = 0f,
    val qualityPreference: String = "exhigh",
    val neteaseEnabled: Boolean = true,
    val bilibiliEnabled: Boolean = true,
    val youtubeEnabled: Boolean = false,
    val defaultStartTab: String = "home",
    val sleepTimerMinutes: Int = 30,
    val resumeLastQueue: Boolean = true,
    val lastQueue: List<Song> = emptyList(),
    val lastQueueIndex: Int = 0,
    val lastPositionMs: Long = 0L,
    val homeCards: HomeCardSettings = HomeCardSettings(),
    val libraryViewMode: String = "list",
    val cacheLimitMb: Int = 1024,
    val onboardingAccepted: Boolean = false,
    val exploreSearchHistory: List<String> = emptyList(),

    // ---------------------------------------------------------------- 悬浮歌词
    val floatingLyricsEnabled: Boolean = false,
    /** 主窗口获得焦点时隐藏悬浮歌词，避免遮挡应用页面。 */
    val floatingLyricsHideInApp: Boolean = false,
    /** 锁定位置后不可拖动。 */
    val floatingLyricsLocked: Boolean = false,
    val floatingLyricsTextColor: String = "WHITE",
    /** SHADOW（阴影）或 OUTLINE（描边）。 */
    val floatingLyricsRenderStyle: String = "SHADOW",
    val floatingLyricsShadowColor: String = "BLACK",
    val floatingLyricsOutlineColor: String = "BLACK",
    val floatingLyricsFontSize: Float = 30f,
    val floatingLyricsOutlineWidth: Float = 2.0f,
    val floatingLyricsShadowBlur: Float = 6.0f,
    val floatingLyricsLyricAlpha: Float = 1.0f,
    val floatingLyricsTranslationAlpha: Float = 0.75f,
    val floatingLyricsBackgroundAlpha: Float = 0.35f,
    /** 悬浮窗底色（配合背景不透明度使用）。 */
    val floatingLyricsBackgroundColor: String = "BLACK",
    val floatingLyricsShowTranslation: Boolean = true,
    val floatingLyricsRevealAnimation: Boolean = true,
    val floatingLyricsMaxWidthDp: Float = 900f,
    /** LEFT / CENTER / RIGHT */
    val floatingLyricsAlignment: String = "CENTER",
    /** 位置用屏幕比例表示，支持多分辨率与多屏。 */
    val floatingLyricsPositionX: Float = 0.5f,
    val floatingLyricsPositionY: Float = 0.85f,

    // ---------------------------------------------------------------- 下载
    /** 下载目录，留空表示默认「音乐\NeriPlayer」。 */
    val downloadDirectory: String = "",
    val downloadQuality: String = "exhigh",
    val downloadConcurrency: Int = 2,
    val downloadNotifyOnComplete: Boolean = true,

    // ---------------------------------------------------------------- 后台与系统控制
    /** 关闭窗口时最小化到托盘继续后台播放。 */
    val closeToTray: Boolean = true,
    /** 最小化时隐藏到托盘。 */
    val minimizeToTray: Boolean = true,
    /** 歌曲变化时发送系统通知（窗口隐藏时）。 */
    val notifyOnSongChange: Boolean = true,
    /** 注册 Windows 系统媒体控制（SMTC），使媒体键 / 系统媒体浮层可以控制播放。 */
    val mprisEnabled: Boolean = true,
    /** 是否已经提示过「已最小化到托盘」。 */
    val trayHintShown: Boolean = false,
)

/** 设置仓库：内存 StateFlow + JSON 持久化。 */
class SettingsRepository {
    private val store = JsonFileStore(AppDirs.settingsFile, AppSettings.serializer()) { AppSettings() }
    private val _state = MutableStateFlow(loadDefaults())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    private fun loadDefaults(): AppSettings {
        val loaded = store.load()
        val defaultMusic = defaultMusicDirectory()
        return if (loaded.musicFolders.isEmpty() && defaultMusic.isDirectory) {
            loaded.copy(musicFolders = listOf(defaultMusic.absolutePath))
        } else {
            loaded
        }
    }

    val current: AppSettings get() = _state.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        _state.value = next
        store.save(next)
    }

    fun replace(next: AppSettings) {
        _state.value = next
        store.save(next)
    }
}

/**
 * 默认音乐目录。
 *
 * Windows 的「音乐」已知文件夹默认就在 `%USERPROFILE%\Music`，
 * 但用户可能在资源管理器里把它挪到别的盘。这里先问真正的已知文件夹，
 * 取不到再退回默认路径，行为与 Linux 版「~\/Music 存在才加入曲库」一致。
 */
fun defaultMusicDirectory(): File {
    windowsKnownFolder("My Music")?.let { if (it.isDirectory) return it }
    return File(System.getProperty("user.home"), "Music")
}

/**
 * 从注册表读 Windows 已知文件夹的实际位置（用户可能在资源管理器里改过）。
 * 读不到时返回 null，由调用方兜底。
 */
private fun windowsKnownFolder(name: String): File? = runCatching {
    val process = ProcessBuilder(
        "reg", "query",
        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\User Shell Folders",
        "/v", name,
    ).redirectErrorStream(true).start()
    val text = process.inputStream.bufferedReader().use { it.readText() }
    process.waitFor()
    val raw = Regex("REG_EXPAND_SZ\\s+(.+)").find(text)?.groupValues?.get(1)?.trim()
        ?: Regex("REG_SZ\\s+(.+)").find(text)?.groupValues?.get(1)?.trim()
        ?: return@runCatching null
    val expanded = raw
        .replace("%USERPROFILE%", System.getProperty("user.home"), ignoreCase = true)
        .replace(Regex("%([A-Za-z_][A-Za-z0-9_]*)%")) { match ->
            System.getenv(match.groupValues[1]).orEmpty()
        }
    File(expanded).takeIf { it.isAbsolute }
}.getOrNull()
