package moe.ouom.neriplayer.desktop.core

import java.io.File

/**
 * 定位 ffmpeg / ffprobe。
 *
 * Linux 版由 deb 声明依赖，装完 `ffmpeg` 就在 PATH 上；Windows 没有统一的包管理器，
 * 几种常见装法会把可执行文件放在完全不同的位置：
 *  - winget（`Gyan.FFmpeg`）：`%LOCALAPPDATA%\Microsoft\WinGet\Links\ffmpeg.exe`；
 *  - Chocolatey：`C:\ProgramData\chocolatey\bin\ffmpeg.exe`；
 *  - Scoop：`%USERPROFILE%\scoop\shims\ffmpeg.exe`；
 *  - 手动解压：`C:\ffmpeg\bin\ffmpeg.exe` 之类。
 *
 * 所以解析顺序是：先 PATH（覆盖 winget 的 Links 目录），再逐个探测常见位置，
 * 最后才回落到裸名字交给系统解析 —— 解析结果缓存一次，避免每首歌都去扫盘。
 */
object FfmpegBinaries {

    /** 解析到的 ffmpeg 路径；找不到时就是裸名字，交给 ProcessBuilder 去 PATH 里找。 */
    val ffmpeg: String by lazy { locate("ffmpeg") }

    /** 解析到的 ffprobe 路径。 */
    val ffprobe: String by lazy { locate("ffprobe") }

    /** 是否真的找到了可执行文件。 */
    val resolved: Boolean by lazy { ffmpeg != "ffmpeg" || onPath("ffmpeg") != null }

    private fun locate(name: String): String {
        onPath(name)?.let { return it }
        val fileName = "$name.exe"
        for (directory in candidateDirectories()) {
            val candidate = File(directory, fileName)
            if (candidate.isFile) return candidate.absolutePath
        }
        // winget 的 Packages 目录里带版本号，名字不固定，做一次有深度上限的扫描
        return scanWinGetPackages(fileName) ?: name
    }

    /** 在 PATH 里找；不用 `where.exe`，省得为每次启动多起一个进程。 */
    private fun onPath(name: String): String? {
        val raw = System.getenv("PATH").orEmpty()
        if (raw.isBlank()) return null
        val fileName = "$name.exe"
        for (entry in raw.split(File.pathSeparatorChar)) {
            if (entry.isBlank()) continue
            val candidate = File(entry.trim().trim('"'), fileName)
            if (candidate.isFile) return candidate.absolutePath
        }
        return null
    }

    private fun candidateDirectories(): List<String> = listOfNotNull(
        System.getenv("LOCALAPPDATA")?.let { "$it\\Microsoft\\WinGet\\Links" },
        System.getenv("ProgramData")?.let { "$it\\chocolatey\\bin" },
        System.getProperty("user.home")?.let { "$it\\scoop\\shims" },
        "C:\\ffmpeg\\bin",
        System.getenv("ProgramFiles")?.let { "$it\\ffmpeg\\bin" },
        System.getenv("ProgramData")?.let { "$it\\ffmpeg\\bin" },
        System.getenv("LOCALAPPDATA")?.let { "$it\\Programs\\ffmpeg\\bin" },
    )

    /** winget 装出来的包目录是 `Gyan.FFmpeg_Microsoft.Winget.Source_.../ffmpeg-<版本>-full_build/bin`。 */
    private fun scanWinGetPackages(fileName: String): String? = runCatching {
        val root = System.getenv("LOCALAPPDATA")?.let { File("$it\\Microsoft\\WinGet\\Packages") }
        if (root == null || !root.isDirectory) return@runCatching null
        root.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("Gyan.FFmpeg", ignoreCase = true) }
            ?.asSequence()
            ?.flatMap { packageDir ->
                packageDir.walkTopDown().maxDepth(4).filter { it.isFile && it.name == fileName }
            }
            ?.firstOrNull()
            ?.absolutePath
    }.getOrNull()
}
