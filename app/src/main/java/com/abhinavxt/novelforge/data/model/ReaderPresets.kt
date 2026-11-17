package com.abhinavxt.novelforge.data.model

import kotlin.math.abs

/**
 * One-tap looks. Each is a pure function over [ReaderSettings] that sets
 * look fields only (theme, font, spacing, margins, alignment, drop cap) and
 * never reading mode, tap zones, TTS or brightness, so applying one cannot
 * change how the reader behaves. Values sit on the quick settings sliders'
 * own steps, so a preset can be nudged afterwards like any other setting.
 */
enum class ReaderPreset(val displayName: String) {

    /** Warm light page, bookish serif, justified and hyphenated, with an initial. */
    PAPER("Paper") {
        override fun applyTo(s: ReaderSettings) = s.copy(
            theme = ReaderTheme.PAPER, font = ReaderFont.LITERATA,
            // An imported font wins over [font], so Paper must clear it to show Literata.
            customFont = null,
            fontSize = 18, lineSpacing = 1.6f, horizontalMargin = 16, paragraphSpacing = 12,
            justifyText = true, hyphenation = true, dropCap = true,
        )
    },

    /** Dark page, a little more air between lines, ragged right. */
    NIGHT("Night") {
        override fun applyTo(s: ReaderSettings) = s.copy(
            theme = ReaderTheme.DARK, lineSpacing = 1.8f, justifyText = false,
        )
    },

    /** Few words per line, large and airy. */
    FOCUS("Focus") {
        override fun applyTo(s: ReaderSettings) = s.copy(
            horizontalMargin = 32, fontSize = 20, lineSpacing = 2.0f, paragraphSpacing = 24,
            justifyText = false,
        )
    },

    /** More text per screen. */
    COMPACT("Compact") {
        override fun applyTo(s: ReaderSettings) = s.copy(
            horizontalMargin = 8, fontSize = 15, lineSpacing = 1.4f, paragraphSpacing = 4,
        )
    };

    abstract fun applyTo(s: ReaderSettings): ReaderSettings

    /** True when [s] already has every value this preset sets. */
    fun matches(s: ReaderSettings): Boolean {
        val applied = applyTo(s)
        // Slider values are floats that can land a hair off the preset's.
        return abs(applied.lineSpacing - s.lineSpacing) < 0.05f &&
            applied.copy(lineSpacing = s.lineSpacing) == s
    }
}
