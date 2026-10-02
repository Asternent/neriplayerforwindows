package moe.ouom.neriplayer.desktop.ui

import moe.ouom.neriplayer.desktop.core.LyricLine

/**
 * 歌词第二行显示什么。原文永远是第一行。
 *
 * 与上游 Android 版一致：**翻译与音译互斥**，同一时刻只显示其中一行 ——
 * 两行都堆在原文下面会把行距撑得很开，反而不好读。
 */
enum class LyricsSecondaryLineMode { TRANSLATION, PHONETIC, NONE }

/** 这批歌词里有没有可用的翻译。 */
fun hasLyricTranslation(lines: List<LyricLine>): Boolean =
    lines.any { !it.translation.isNullOrBlank() }

/** 这批歌词里有没有可用的音译（罗马音）。 */
fun hasLyricPhonetic(lines: List<LyricLine>): Boolean =
    lines.any { !it.romanization.isNullOrBlank() }

/**
 * 由两个设置开关 + 歌词实际内容推导出该显示哪一种第二行。
 *
 * 关掉第二行 → 无；开了音译且确实有音译 → 音译；否则有翻译就翻译、没翻译但有音译就音译。
 */
fun resolveLyricsSecondaryLineMode(
    showSecondaryLine: Boolean,
    preferPhonetic: Boolean,
    hasTranslation: Boolean,
    hasPhonetic: Boolean,
): LyricsSecondaryLineMode = when {
    !showSecondaryLine -> LyricsSecondaryLineMode.NONE
    preferPhonetic && hasPhonetic -> LyricsSecondaryLineMode.PHONETIC
    hasTranslation -> LyricsSecondaryLineMode.TRANSLATION
    hasPhonetic -> LyricsSecondaryLineMode.PHONETIC
    else -> LyricsSecondaryLineMode.NONE
}

/**
 * 快捷切换按钮：在「可用模式 + 原文」之间循环。
 *
 * 翻译与音译都有时是 翻译 → 音译 → 原文 → 翻译；只有一种时就是 该模式 ↔ 原文。
 */
fun nextLyricsSecondaryLineMode(
    current: LyricsSecondaryLineMode,
    hasTranslation: Boolean,
    hasPhonetic: Boolean,
): LyricsSecondaryLineMode {
    val cycle = buildList {
        if (hasTranslation) add(LyricsSecondaryLineMode.TRANSLATION)
        if (hasPhonetic) add(LyricsSecondaryLineMode.PHONETIC)
        add(LyricsSecondaryLineMode.NONE)
    }
    if (cycle.size <= 1) return LyricsSecondaryLineMode.NONE
    val index = cycle.indexOf(current)
    return cycle[(if (index < 0) 0 else index + 1) % cycle.size]
}

/** 按钮上的短标签。 */
fun LyricsSecondaryLineMode.shortLabel(): String = when (this) {
    LyricsSecondaryLineMode.TRANSLATION -> "译"
    LyricsSecondaryLineMode.PHONETIC -> "音"
    LyricsSecondaryLineMode.NONE -> "原"
}

fun LyricsSecondaryLineMode.description(): String = when (this) {
    LyricsSecondaryLineMode.TRANSLATION -> "第二行：翻译"
    LyricsSecondaryLineMode.PHONETIC -> "第二行：音译"
    LyricsSecondaryLineMode.NONE -> "只显示原文"
}

/** 某一行在当前模式下应该显示的副文本，没有就返回 null。 */
fun secondaryLyricText(line: LyricLine, mode: LyricsSecondaryLineMode): String? = when (mode) {
    LyricsSecondaryLineMode.TRANSLATION -> line.translation?.takeIf { it.isNotBlank() }
    LyricsSecondaryLineMode.PHONETIC -> line.romanization?.takeIf { it.isNotBlank() }
    LyricsSecondaryLineMode.NONE -> null
}
