package moe.ouom.neriplayer.desktop.core

import kotlin.math.roundToLong

/** 对齐上游的 ±5 秒 / 50 ms 量程，先夹值以防损坏配置的 Long 溢出。 */
fun normalizeLyricOffsetMs(value: Long): Long =
    (value.coerceIn(-5000L, 5000L).toDouble() / 50).roundToLong() * 50

internal fun AppSettings.normalizeLyricOffsets(): AppSettings = copy(
    lyricOffsetBuiltinMs = normalizeLyricOffsetMs(lyricOffsetBuiltinMs),
    lyricOffsetLrclibMs = normalizeLyricOffsetMs(lyricOffsetLrclibMs),
    lyricOffsetKugouMs = normalizeLyricOffsetMs(lyricOffsetKugouMs),
    lyricOffsetAmllMs = normalizeLyricOffsetMs(lyricOffsetAmllMs),
    lyricOffsetBiliCcMs = normalizeLyricOffsetMs(lyricOffsetBiliCcMs),
)
