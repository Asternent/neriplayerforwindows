package moe.ouom.neriplayer.desktop.core

import java.awt.Toolkit

/**
 * 界面缩放（HiDPI 适配）。
 *
 * Windows 上 Compose Desktop（Skiko）本身就会跟随系统缩放：系统设 150% 时
 * `LocalDensity` 就已经是 1.5，界面是正常大小。所以这里的「跟随系统」
 * **必须以平台真实密度为准**，否则会在已经放大的基础上再放大一次。
 *
 * 对应地，[resolve] 返回的值如果与 Compose 给的一致，`ApplyUiScale` 就什么都不做；
 * 用户在设置里手动指定比例时才会真正叠加一个换算系数。
 */
object UiScale {

    /** 设置里用 0 表示「跟随系统」。 */
    const val SYSTEM: Float = 0f

    const val MIN: Float = 1f
    const val MAX: Float = 3f

    /** 可选的界面缩放比例（0 = 跟随系统）。 */
    val PRESETS: List<Pair<Float, String>> = listOf(
        SYSTEM to "跟随系统",
        1f to "100%",
        1.25f to "125%",
        1.5f to "150%",
        1.75f to "175%",
        2f to "200%",
        2.5f to "250%",
    )

    private var detectedSource: String = "默认"

    private val detected: Float by lazy { detect() }

    /** 系统缩放的来源说明，用于设置页展示。 */
    val source: String get() = detectedSource

    fun systemScale(): Float = detected

    /** 把设置项换算成实际生效的缩放比例。 */
    fun resolve(setting: Float): Float =
        if (setting <= 0f) systemScale() else setting.coerceIn(MIN, MAX)

    private fun detect(): Float {
        // 显式覆盖优先（调试用，也方便把窗口放到特定显示器上截图）
        System.getenv("NERIPLAYER_UI_SCALE")?.trim()?.toFloatOrNull()?.let { value ->
            if (value > 0f) {
                detectedSource = "环境变量 NERIPLAYER_UI_SCALE"
                return value.coerceIn(MIN, MAX)
            }
        }

        // JVM 参数是「用户明确要求」，直接采用
        System.getProperty("sun.java2d.uiScale")?.trim()?.toFloatOrNull()?.let { value ->
            if (value > 1.001f) {
                detectedSource = "JVM 参数 sun.java2d.uiScale"
                return value.coerceIn(MIN, MAX)
            }
        }

        // 平台缩放：Compose 实际用的就是它，跟随系统时保持与之一致，避免二次放大
        val platform = platformScale()
        if (platform > 1.001f) {
            detectedSource = "系统缩放 ${(platform * 100).toInt()}%"
            return platform.coerceIn(MIN, MAX)
        }

        // 平台没报缩放时的兜底：直接看屏幕 DPI
        // （某些远程桌面 / 虚拟显示驱动下 AWT 会一直返回 1.0）
        windowsSystemScale()?.let { scale ->
            detectedSource = "屏幕 DPI（${(scale * 96).toInt()} dpi）"
            return scale.coerceIn(MIN, MAX)
        }

        detectedSource = "未检测到系统缩放"
        return 1f
    }

    /** AWT 的默认变换：Windows 上就是显示器缩放。 */
    private fun platformScale(): Float = runCatching {
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice
            .defaultConfiguration
            .defaultTransform
            .scaleX
            .toFloat()
    }.getOrDefault(1f).takeIf { it > 0f } ?: 1f

    /** 屏幕 DPI 换算成缩放比例（96 dpi = 100%）。 */
    private fun windowsSystemScale(): Float? = runCatching {
        val dpi = Toolkit.getDefaultToolkit().screenResolution
        if (dpi > 96) dpi.toFloat() / 96f else null
    }.getOrNull()
}
