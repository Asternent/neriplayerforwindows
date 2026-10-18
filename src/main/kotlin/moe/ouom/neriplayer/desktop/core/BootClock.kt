package moe.ouom.neriplayer.desktop.core

/**
 * 启动耗时打点。
 *
 * 桌面端最容易退化的指标是「双击到能操作」的时间，分散在 main / Compose 首帧 / 主题与首页里，
 * 统一用这一处输出 `[boot] 阶段 +Nms`，方便对比优化前后的差异。
 */
object BootClock {
    private val startedAt = System.nanoTime()

    fun mark(stage: String) {
        println("[boot] $stage +${(System.nanoTime() - startedAt) / 1_000_000}ms")
    }
}
