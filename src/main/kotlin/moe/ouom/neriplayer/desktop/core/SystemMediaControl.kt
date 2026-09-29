package moe.ouom.neriplayer.desktop.core

import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Windows 系统媒体控制。
 *
 * 对应 Linux 版的 MPRIS over D-Bus，分两条腿走路：
 *
 * 1. **展示（SMTC）**：用 JNA 直接按 ABI 调用 WinRT 的
 *    `Windows.Media.SystemMediaTransportControls` ——
 *    `RoGetActivationFactory` 取到 `ISystemMediaTransportControlsInterop`，
 *    再 `GetForWindow` 拿到属于本进程窗口的会话，然后写入曲目元数据、
 *    播放状态与进度。系统媒体浮层（按媒体键时弹出的那个面板）、
 *    音量键旁边的播放信息、锁屏界面都读这些数据。
 *
 * 2. **控制（全局媒体热键）**：键盘上的播放/暂停、上一首、下一首、停止
 *    注册成全局热键（`RegisterHotKey`），由应用自己处理。
 *
 * 为什么不把 SMTC 的按钮事件也接进来：WinRT 的事件委托要求调用方提供一个
 * 手写 vtable 的 COM 对象，而 JVM 侧的 JNA 回调桩在 WinRT 的事件注册路径上
 * 会让进程直接以 `0xC0000409` 崩掉（已实测：换成显式回调类、公开接口、
 * 修正 vtable 槽位后依然如此，且回调函数根本没被进入）。
 * 因此这里只使用 SMTC 的「数据写入」方向 —— 系统浮层照常显示当前曲目，
 * 而媒体键的「读入」方向交给更可靠的全局热键。
 *
 * 所有 COM 调用固定在一条 MTA 线程上执行（[worker]）：SMTC 只能在初始化过
 * COM 的线程上创建与访问。
 */
class SystemMediaControl(
    val snapshotProvider: () -> NowPlayingSnapshot?,
    val onPlayPause: () -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit,
    val onStop: () -> Unit,
    val shuffleProvider: () -> Boolean,
    val repeatProvider: () -> RepeatMode,
) {

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "neri-media-control").apply { isDaemon = true }
    }

    @Volatile
    private var session: SmtcSession? = null

    @Volatile
    private var hotkeys: MediaHotkeys? = null

    @Volatile
    private var ticker: Thread? = null

    /** 是否已经接管系统媒体控制（媒体浮层或媒体键任一可用）。 */
    val running: Boolean get() = session != null || hotkeys != null

    /** 当前生效的实现方式，用于设置页展示。 */
    val backend: String
        get() = when {
            session != null && hotkeys != null -> "SMTC 系统媒体浮层 + 全局媒体热键"
            session != null -> "SMTC 系统媒体浮层"
            hotkeys != null -> "全局媒体热键"
            else -> "未启用"
        }

    /** 启动系统媒体控制；完全不可用时返回 false（不影响应用其它功能）。 */
    fun start(): Boolean {
        if (running) return true

        val opened = runCatching {
            worker.submit(
                Callable { SmtcSession.open(shuffleProvider, repeatProvider) },
            ).get(25, TimeUnit.SECONDS)
        }.getOrElse { error ->
            println("[smtc] 初始化失败：${error.message}")
            null
        }
        if (opened != null) {
            session = opened
            runCatching { worker.execute { runCatching { opened.push(snapshotProvider()) } } }
            startTimelineTicker()
        }

        // 控制方向始终走全局媒体热键：不依赖 WinRT 的事件回传
        val fallback = MediaHotkeys(
            onPlayPause = onPlayPause,
            onNext = onNext,
            onPrevious = onPrevious,
            onStop = onStop,
        )
        if (fallback.start()) {
            hotkeys = fallback
        }

        if (running) {
            println("[smtc] 系统媒体控制已就绪：${backend}")
        } else {
            println("[smtc] 系统媒体控制不可用，跳过（不影响播放）")
        }
        return running
    }

    fun stop() {
        ticker?.interrupt()
        ticker = null
        val current = session
        session = null
        if (current != null) {
            runCatching { worker.submit { current.close() }.get(5, TimeUnit.SECONDS) }
        }
        hotkeys?.stop()
        hotkeys = null
    }

    /** 播放状态 / 歌曲变化后调用，把最新的播放信息推给系统。 */
    fun refresh(vararg properties: String) {
        val current = session ?: return
        runCatching {
            worker.execute { runCatching { current.push(snapshotProvider()) } }
        }
    }

    /**
     * 系统媒体浮层的进度条靠 [SmtcSession.pushTimeline] 刷新。
     * 上层只在切歌 / 暂停时调用 [refresh]，这里额外每秒补一次进度，
     * 让浮层里的进度条跟得上播放。
     */
    private fun startTimelineTicker() {
        if (ticker != null) return
        ticker = Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(1_000)
                } catch (interrupted: InterruptedException) {
                    break
                }
                val current = session
                if (current != null) {
                    runCatching {
                        worker.execute { runCatching { current.pushTimeline(snapshotProvider()) } }
                    }
                }
            }
        }, "neri-media-timeline").apply {
            isDaemon = true
            start()
        }
    }
}

/** 当前播放信息快照（供系统媒体控制 / 托盘 / 通知使用）。 */
data class NowPlayingSnapshot(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val positionMs: Long,
    val playing: Boolean,
    val artUrl: String?,
    val source: String,
)

/** 由当前歌曲与播放状态构造系统媒体控制 / 托盘用的信息快照。 */
fun buildNowPlayingSnapshot(
    song: Song?,
    durationMs: Long,
    positionMs: Long,
    playing: Boolean,
): NowPlayingSnapshot? {
    if (song == null) return null
    return NowPlayingSnapshot(
        trackId = song.key,
        title = song.displayName(),
        artist = song.artistText(),
        album = song.album,
        durationMs = durationMs,
        positionMs = positionMs,
        playing = playing,
        artUrl = song.artworkUrl,
        source = song.source.name.lowercase(),
    )
}

// ---------------------------------------------------------------------------
// WinRT / COM 基础设施
// ---------------------------------------------------------------------------

/** combase.dll：WinRT 初始化与激活工厂。 */
private interface CombaseLib : Library {
    fun RoInitialize(initType: Int): Int
    fun RoUninitialize()
    fun RoGetActivationFactory(classId: Pointer, iid: Pointer, factory: PointerByReference): Int
    fun RoActivateInstance(classId: Pointer, instance: PointerByReference): Int
}

/** WinRT 字符串（HSTRING）相关 API。 */
private interface WinRtStringLib : Library {
    fun WindowsCreateString(source: WString, length: Int, out: PointerByReference): Int
    fun WindowsDeleteString(value: Pointer): Int
}

private const val S_OK = 0
private const val S_FALSE = 1
private const val RO_INIT_MULTITHREADED = 1

/** 把 GUID 字符串按 Windows 的内存布局（前 3 段小端）写成 16 字节。 */
private fun guidBytes(uuid: String): ByteArray {
    val parts = uuid.trim().split('-')
    require(parts.size == 5) { "非法 GUID：$uuid" }
    val bytes = ByteArray(16)
    val data1 = parts[0].toLong(16)
    for (index in 0 until 4) bytes[index] = (data1 shr (8 * index)).toByte()
    val data2 = parts[1].toLong(16)
    for (index in 0 until 2) bytes[4 + index] = (data2 shr (8 * index)).toByte()
    val data3 = parts[2].toLong(16)
    for (index in 0 until 2) bytes[6 + index] = (data3 shr (8 * index)).toByte()
    val tail = parts[3] + parts[4]
    for (index in 0 until 8) bytes[8 + index] = tail.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    return bytes
}

/** 分配一个 GUID 的内存（调用期间必须保持强引用）。 */
private fun guidMemory(uuid: String): Memory = Memory(16).apply { write(0, guidBytes(uuid), 0, 16) }

/** 取 COM 接口指针第 [index] 个 vtable 槽位。 */
private fun vtableFunction(instance: Pointer, index: Int): Function {
    val vtable = instance.getPointer(0)
    return Function.getFunction(vtable.getPointer(index.toLong() * Native.POINTER_SIZE))
}

private fun releaseInterface(instance: Pointer?) {
    if (instance == null) return
    runCatching { vtableFunction(instance, 2).invokeInt(arrayOf(instance)) }
}

/** WinRT 全局环境（进程内共享）。 */
private object WinRt {

    private val combase: CombaseLib? by lazy {
        runCatching { Native.load("combase", CombaseLib::class.java) }.getOrNull()
    }

    private val strings: WinRtStringLib? by lazy {
        runCatching {
            Native.load("api-ms-win-core-winrt-string-l1-1-0", WinRtStringLib::class.java)
        }.getOrNull()
    }

    val available: Boolean get() = combase != null && strings != null

    /** 在当前线程初始化 COM（MTA）。 */
    fun initialize(): Boolean {
        val lib = combase ?: return false
        val hr = runCatching { lib.RoInitialize(RO_INIT_MULTITHREADED) }.getOrDefault(-1)
        // S_OK = 本次初始化成功；S_FALSE = 本线程已经初始化过，同样可用
        return hr == S_OK || hr == S_FALSE
    }

    fun uninitialize() {
        runCatching { combase?.RoUninitialize() }
    }

    fun createString(value: String): Pointer? {
        val lib = strings ?: return null
        val out = PointerByReference()
        val hr = runCatching { lib.WindowsCreateString(WString(value), value.length, out) }.getOrDefault(-1)
        return if (hr == S_OK) out.value else null
    }

    fun deleteString(value: Pointer?) {
        if (value == null) return
        runCatching { strings?.WindowsDeleteString(value) }
    }

    /** 短暂使用一个 HSTRING，结束后自动释放。 */
    fun <T> withString(value: String, block: (Pointer) -> T): T? {
        val handle = createString(value) ?: return null
        return try {
            block(handle)
        } finally {
            deleteString(handle)
        }
    }

    fun activationFactory(className: String, iid: String): Pointer? {
        val lib = combase ?: return null
        val guid = guidMemory(iid)
        val out = PointerByReference()
        val hr = withString(className) { handle ->
            lib.RoGetActivationFactory(handle, guid, out)
        } ?: return null
        if (hr == S_OK) return out.value
        println("[smtc] 激活 $className 失败（hr=0x${"%08X".format(hr)}）")
        return null
    }

    fun activateInstance(className: String): Pointer? {
        val lib = combase ?: return null
        val out = PointerByReference()
        val hr = withString(className) { handle -> lib.RoActivateInstance(handle, out) } ?: return null
        return if (hr == S_OK) out.value else null
    }
}

// ---------------------------------------------------------------------------
// SMTC 会话
// ---------------------------------------------------------------------------

// Windows SDK 接口 IID（windows.media.h / SystemMediaTransportControlsInterop.h）
private const val IID_SMTC_INTEROP = "DDB0472D-C911-4A1F-86D9-DC3D71A95F5A"
private const val IID_SMTC = "99FA3FF4-1742-42A6-902E-087D41F965EC"
private const val IID_SMTC2 = "EA98D2F6-7F3C-4AF2-A586-72889808EFB1"
private const val IID_DISPLAY_UPDATER = "8ABBC53E-FA55-4ECF-AD8E-C984E5DD1550"
private const val IID_MUSIC_PROPERTIES = "6BBF0C59-D0A0-4D26-92A0-F978E1D18E7B"
private const val IID_MUSIC_PROPERTIES2 = "00368462-97D3-44B9-B00F-008AFCEFAF18"
private const val IID_TIMELINE = "5125316A-C3A2-475B-8507-93534DC88F15"

// 下面这些槽位编号逐条对齐 Windows SDK 的 windows.media.h（IUnknown 占 0-2，
// IInspectable 占 3-5，之后才是接口自己的方法）。写错会跳到 vtable 之外直接崩进程，
// 改动前务必对照头文件重新数一遍。
//
// ISystemMediaTransportControls
private const val SMTC_PUT_PLAYBACK_STATUS = 7
private const val SMTC_GET_DISPLAY_UPDATER = 8
private const val SMTC_PUT_IS_ENABLED = 11
private const val SMTC_PUT_IS_PLAY_ENABLED = 13
private const val SMTC_PUT_IS_STOP_ENABLED = 15
private const val SMTC_PUT_IS_PAUSE_ENABLED = 17
private const val SMTC_PUT_IS_PREVIOUS_ENABLED = 25
private const val SMTC_PUT_IS_NEXT_ENABLED = 27

// ISystemMediaTransportControls2
private const val SMTC2_PUT_AUTO_REPEAT_MODE = 4
private const val SMTC2_PUT_SHUFFLE_ENABLED = 6
private const val SMTC2_UPDATE_TIMELINE = 9

// ISystemMediaTransportControlsDisplayUpdater
private const val UPDATER_PUT_TYPE = 7
private const val UPDATER_GET_MUSIC_PROPERTIES = 12
private const val UPDATER_UPDATE = 17

// IMusicDisplayProperties：6 get_Title / 7 put_Title / 8 get_AlbumArtist /
// 9 put_AlbumArtist / 10 get_Artist / 11 put_Artist
private const val MUSIC_PUT_TITLE = 7
private const val MUSIC_PUT_ARTIST = 11

// IMusicDisplayProperties2：6 get_AlbumTitle / 7 put_AlbumTitle
private const val MUSIC2_PUT_ALBUM_TITLE = 7

// ISystemMediaTransportControlsTimelineProperties：6 get_StartTime / 7 put_StartTime /
// 8 get_EndTime / 9 put_EndTime / 10 get_MinSeekTime / 11 put_MinSeekTime /
// 12 get_MaxSeekTime / 13 put_MaxSeekTime / 14 get_Position / 15 put_Position
private const val TIMELINE_PUT_START = 7
private const val TIMELINE_PUT_END = 9
private const val TIMELINE_PUT_MIN_SEEK = 11
private const val TIMELINE_PUT_MAX_SEEK = 13
private const val TIMELINE_PUT_POSITION = 15

// MediaPlaybackStatus / MediaPlaybackType
private const val MEDIA_PLAYBACK_STATUS_STOPPED = 2
private const val MEDIA_PLAYBACK_STATUS_PLAYING = 3
private const val MEDIA_PLAYBACK_STATUS_PAUSED = 4
private const val MEDIA_PLAYBACK_TYPE_MUSIC = 1

/** 一个已注册的 SMTC 会话。所有方法都必须在 [SystemMediaControl] 的 worker 线程上调用。 */
private class SmtcSession(
    private val control: Pointer,
    private val control2: Pointer?,
    private val displayUpdater: Pointer,
    private val musicProperties: Pointer,
    private val musicProperties2: Pointer?,
    private val timeline: Pointer?,
    private val shuffleProvider: () -> Boolean,
    private val repeatProvider: () -> RepeatMode,
) {

    private var lastTrackId: String? = null

    /** 把整份播放信息推给系统（歌曲切换 / 播放状态变化时调用）。 */
    fun push(snapshot: NowPlayingSnapshot?) {
        val playing = snapshot?.playing == true
        call(
            control,
            SMTC_PUT_PLAYBACK_STATUS,
            when {
                snapshot == null -> MEDIA_PLAYBACK_STATUS_STOPPED
                playing -> MEDIA_PLAYBACK_STATUS_PLAYING
                else -> MEDIA_PLAYBACK_STATUS_PAUSED
            },
        )
        putBoolean(control, SMTC_PUT_IS_ENABLED, true)
        putBoolean(control, SMTC_PUT_IS_PLAY_ENABLED, true)
        putBoolean(control, SMTC_PUT_IS_PAUSE_ENABLED, true)
        putBoolean(control, SMTC_PUT_IS_STOP_ENABLED, true)
        putBoolean(control, SMTC_PUT_IS_PREVIOUS_ENABLED, true)
        putBoolean(control, SMTC_PUT_IS_NEXT_ENABLED, true)

        if (control2 != null) {
            putBoolean(control2, SMTC2_PUT_SHUFFLE_ENABLED, shuffleProvider())
            call(control2, SMTC2_PUT_AUTO_REPEAT_MODE, repeatModeOf(repeatProvider()))
        }

        if (snapshot == null) {
            lastTrackId = null
            updateDisplay()
            return
        }

        // 元数据只在真的换歌时重写：Update() 会让系统重建浮层，每秒都调会闪
        if (snapshot.trackId != lastTrackId) {
            lastTrackId = snapshot.trackId
            call(displayUpdater, UPDATER_PUT_TYPE, MEDIA_PLAYBACK_TYPE_MUSIC)
            WinRt.withString(snapshot.title) { value -> call(musicProperties, MUSIC_PUT_TITLE, value) }
            WinRt.withString(snapshot.artist) { value -> call(musicProperties, MUSIC_PUT_ARTIST, value) }
            if (musicProperties2 != null) {
                WinRt.withString(snapshot.album) { value ->
                    call(musicProperties2, MUSIC2_PUT_ALBUM_TITLE, value)
                }
            }
            updateDisplay()
        }

        pushTimeline(snapshot)
    }

    /** 只刷新进度：系统媒体浮层的进度条靠它跟上播放。 */
    fun pushTimeline(snapshot: NowPlayingSnapshot?) {
        val props = timeline ?: return
        val duration = (snapshot?.durationMs ?: 0L).coerceAtLeast(0L)
        val position = (snapshot?.positionMs ?: 0L).coerceIn(0L, duration)
        // TimeSpan 的单位是 100ns
        call(props, TIMELINE_PUT_START, 0L)
        call(props, TIMELINE_PUT_END, duration * 10_000L)
        call(props, TIMELINE_PUT_MIN_SEEK, 0L)
        call(props, TIMELINE_PUT_MAX_SEEK, duration * 10_000L)
        call(props, TIMELINE_PUT_POSITION, position * 10_000L)
        call(control2 ?: return, SMTC2_UPDATE_TIMELINE, props)
    }

    fun close() {
        releaseInterface(displayUpdater)
        releaseInterface(musicProperties)
        releaseInterface(musicProperties2)
        releaseInterface(timeline)
        releaseInterface(control2)
        releaseInterface(control)
        WinRt.uninitialize()
    }

    // ------------------------------------------------------------ 内部工具

    private fun updateDisplay() {
        runCatching { vtableFunction(displayUpdater, UPDATER_UPDATE).invokeInt(arrayOf(displayUpdater)) }
    }

    private fun call(target: Pointer, slot: Int, value: Any) {
        runCatching {
            val function = vtableFunction(target, slot)
            when (value) {
                is Long -> function.invokeInt(arrayOf(target, value))
                is Pointer -> function.invokeInt(arrayOf(target, value))
                else -> function.invokeInt(arrayOf(target, value as Int))
            }
        }
    }

    private fun putBoolean(target: Pointer, slot: Int, value: Boolean) {
        call(target, slot, if (value) 1 else 0)
    }

    companion object {

        /**
         * 创建 SMTC 会话。
         *
         * SMTC 绑定到具体窗口，主窗口是 Compose 异步建出来的，
         * 启动瞬间可能还没有，这里会等窗口出现。
         */
        fun open(
            shuffleProvider: () -> Boolean,
            repeatProvider: () -> RepeatMode,
        ): SmtcSession? {
            if (!WinRt.available) {
                println("[smtc] 当前系统没有可用的 WinRT（combase / HSTRING 加载失败）")
                return null
            }
            if (!WinRt.initialize()) {
                println("[smtc] COM 初始化失败")
                return null
            }

            val hwnd = waitForAppWindow() ?: run {
                println("[smtc] 找不到本进程的主窗口，无法注册系统媒体浮层")
                WinRt.uninitialize()
                return null
            }

            val interop = WinRt.activationFactory("Windows.Media.SystemMediaTransportControls", IID_SMTC_INTEROP)
            if (interop == null) {
                WinRt.uninitialize()
                return null
            }

            val controlRef = PointerByReference()
            val iid = guidMemory(IID_SMTC)
            val hr = runCatching {
                vtableFunction(interop, 6).invokeInt(arrayOf(interop, hwnd, iid, controlRef))
            }.getOrDefault(-1)
            val control = controlRef.value
            if (hr != S_OK || control == null) {
                println("[smtc] GetForWindow 失败（hr=0x${"%08X".format(hr)}）")
                WinRt.uninitialize()
                return null
            }

            val updaterRef = PointerByReference()
            val updaterHr = runCatching {
                vtableFunction(control, SMTC_GET_DISPLAY_UPDATER).invokeInt(arrayOf(control, updaterRef))
            }.getOrDefault(-1)
            val displayUpdater = updaterRef.value
            if (updaterHr != S_OK || displayUpdater == null) {
                println("[smtc] 拿不到 DisplayUpdater，跳过系统媒体浮层")
                releaseInterface(control)
                WinRt.uninitialize()
                return null
            }

            // 必须先把播放类型标成「音乐」，DisplayUpdater 才会给出 MusicProperties：
            // Type 默认是 Unknown，此时 get_MusicProperties 会以 E_ILLEGAL_METHOD_CALL 失败。
            runCatching {
                vtableFunction(displayUpdater, UPDATER_PUT_TYPE)
                    .invokeInt(arrayOf(displayUpdater, MEDIA_PLAYBACK_TYPE_MUSIC))
            }

            val musicRef = PointerByReference()
            val musicHr = runCatching {
                vtableFunction(displayUpdater, UPDATER_GET_MUSIC_PROPERTIES)
                    .invokeInt(arrayOf(displayUpdater, musicRef))
            }.getOrDefault(-1)
            val music = musicRef.value
            if (musicHr != S_OK || music == null) {
                println("[smtc] 拿不到 MusicDisplayProperties，跳过系统媒体浮层（hr=0x${"%08X".format(musicHr)}）")
                releaseInterface(displayUpdater)
                releaseInterface(control)
                WinRt.uninitialize()
                return null
            }

            // 只解析一次 ISystemMediaTransportControls2：时间轴和「随机/循环」都挂在它上面
            val control2 = query(control, IID_SMTC2)
            return SmtcSession(
                control = control,
                control2 = control2,
                displayUpdater = displayUpdater,
                musicProperties = music,
                // IMusicDisplayProperties2（带 AlbumTitle）挂在 MusicDisplayProperties 上
                musicProperties2 = query(music, IID_MUSIC_PROPERTIES2),
                timeline = control2?.let { timelineProperties() },
                shuffleProvider = shuffleProvider,
                repeatProvider = repeatProvider,
            )
        }

        /** 等主窗口出现。 */
        private fun waitForAppWindow(): Pointer? {
            repeat(40) {
                val found = findAppWindow()
                if (found != null) return found
                Thread.sleep(250)
            }
            return null
        }

        /** 找本进程可见的顶层窗口（Compose Desktop 的窗口类名是 SunAwtFrame）。 */
        private fun findAppWindow(): Pointer? {
            val pid = Kernel32.INSTANCE.GetCurrentProcessId()
            var found: Pointer? = null
            val callback = WinUser.WNDENUMPROC { hwnd, _ ->
                val ownerPid = IntByReference()
                User32.INSTANCE.GetWindowThreadProcessId(hwnd, ownerPid)
                if (ownerPid.value == pid && User32.INSTANCE.IsWindowVisible(hwnd)) {
                    val buffer = CharArray(256)
                    User32.INSTANCE.GetClassName(hwnd, buffer, buffer.size)
                    val name = String(buffer).substringBefore('\u0000')
                    if (name == "SunAwtFrame" || name == "SunAwtDialog") {
                        // HWND 是 PointerType，转成裸 Pointer 交给后面的 WinRT 调用
                        found = hwnd.pointer
                        return@WNDENUMPROC false
                    }
                }
                true
            }
            runCatching { User32.INSTANCE.EnumWindows(callback, null) }
            return found
        }

        private fun query(source: Pointer, iid: String): Pointer? {
            val out = PointerByReference()
            val guid = guidMemory(iid)
            val hr = runCatching {
                vtableFunction(source, 0).invokeInt(arrayOf(source, guid, out))
            }.getOrDefault(-1)
            return if (hr == S_OK) out.value else null
        }

        private fun timelineProperties(): Pointer? {
            val instance = WinRt.activateInstance("Windows.Media.SystemMediaTransportControlsTimelineProperties")
                ?: return null
            return query(instance, IID_TIMELINE)
        }

        private fun repeatModeOf(mode: RepeatMode): Int = when (mode) {
            RepeatMode.ONE -> 1
            RepeatMode.ALL -> 2
            RepeatMode.OFF -> 0
        }
    }
}

// ---------------------------------------------------------------------------
// 控制方向：全局媒体热键
// ---------------------------------------------------------------------------

private const val WM_HOTKEY = 0x0312
private const val MOD_NOREPEAT = 0x4000
private const val PM_REMOVE = 0x0001

private const val VK_MEDIA_NEXT_TRACK = 0xB0
private const val VK_MEDIA_PREV_TRACK = 0xB1
private const val VK_MEDIA_STOP = 0xB2
private const val VK_MEDIA_PLAY_PAUSE = 0xB3

private interface User32HotkeyLib : StdCallLibrary {
    fun RegisterHotKey(hWnd: Pointer?, id: Int, fsModifiers: Int, vk: Int): Boolean
    fun UnregisterHotKey(hWnd: Pointer?, id: Int): Boolean
    fun PeekMessage(msg: Pointer, hWnd: Pointer?, min: Int, max: Int, remove: Int): Boolean
}

/**
 * 把媒体键注册成全局热键。
 *
 * 热键归属「注册它的线程」，所以整条消息循环跑在自己的线程上；
 * 停止时中断线程并在 finally 里注销热键。
 */
private class MediaHotkeys(
    private val onPlayPause: () -> Unit,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onStop: () -> Unit,
) {

    private val user32: User32HotkeyLib? by lazy {
        runCatching { Native.load("user32", User32HotkeyLib::class.java) }.getOrNull()
    }

    private var thread: Thread? = null

    @Volatile
    private var registered = false

    fun start(): Boolean {
        val lib = user32 ?: return false
        val worker = Thread({ loop(lib) }, "neri-media-hotkeys").apply {
            isDaemon = true
            start()
        }
        thread = worker
        repeat(30) {
            if (registered) return true
            if (!worker.isAlive) return false
            Thread.sleep(100)
        }
        return registered
    }

    fun stop() {
        val worker = thread ?: return
        thread = null
        runCatching { worker.interrupt() }
        runCatching { worker.join(1_500) }
    }

    private fun loop(lib: User32HotkeyLib) {
        val bindings = listOf(
            1 to VK_MEDIA_PLAY_PAUSE,
            2 to VK_MEDIA_NEXT_TRACK,
            3 to VK_MEDIA_PREV_TRACK,
            4 to VK_MEDIA_STOP,
        )
        val bound = bindings.filter { (id, key) ->
            runCatching { lib.RegisterHotKey(null, id, MOD_NOREPEAT, key) }.getOrDefault(false)
        }
        if (bound.isEmpty()) {
            println("[smtc] 媒体热键注册失败（可能已被其它播放器占用）")
            return
        }
        registered = true
        println("[smtc] 已注册全局媒体热键 ${bound.size} 个")
        val message = WinUser.MSG()
        message.write()
        try {
            while (!Thread.currentThread().isInterrupted) {
                val hasMessage = runCatching {
                    lib.PeekMessage(message.pointer, null, 0, 0, PM_REMOVE)
                }.getOrDefault(false)
                if (!hasMessage) {
                    Thread.sleep(25)
                    continue
                }
                message.read()
                if (message.message == WM_HOTKEY) {
                    when (message.wParam.toInt()) {
                        1 -> runCatching { onPlayPause() }
                        2 -> runCatching { onNext() }
                        3 -> runCatching { onPrevious() }
                        4 -> runCatching { onStop() }
                    }
                }
            }
        } finally {
            bound.forEach { (id, _) -> runCatching { lib.UnregisterHotKey(null, id) } }
            registered = false
        }
    }
}
