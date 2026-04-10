package com.abhinavxt.novelreader.data.model

enum class ReaderTheme {
    LIGHT,
    DARK,
    SEPIA,
    GREY,
    PAPER,
    NAVY,
    SOLARIZED_LIGHT,
    SOLARIZED_DARK,

    /** Nord — muted arctic blue-grey palette. */
    NORD,

    /** Mocha — dark brown with warm amber text (like Kindle). */
    MOCHA,

    /** Dracula — dark purple with pastel accents. */
    DRACULA,

    /** AMOLED — pure black for OLED screens. */
    AMOLED,

    /** Gruvbox — retro warm dark theme. */
    GRUVBOX,

    /** Catppuccin — soft pastel dark theme. */
    CATPPUCCIN
}

enum class ReaderFont(val displayName: String) {
    SANS_SERIF("Sans Serif"),
    SERIF("Serif"),
    MONOSPACE("Monospace"),
    CURSIVE("Cursive")
}

data class ReaderSettings(
    val fontSize: Int = 16,
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val font: ReaderFont = ReaderFont.SANS_SERIF
)