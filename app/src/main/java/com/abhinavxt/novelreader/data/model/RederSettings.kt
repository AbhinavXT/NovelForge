package com.abhinavxt.novelreader.data.model

// Enum class defines a fixed set of named values.
// Unlike a String that could be anything, ReaderTheme can only be
// one of these three specific values. This prevents bugs from typos.
enum class ReaderTheme {
    LIGHT,      // White background, black text
    DARK,       // Dark background, light text
    SEPIA,       // Warm beige background, brown text (easy on eyes)

    /** A neutral grey background, less harsh than pure dark. */
    GREY,

    /** A slightly off-white background simulating paper. */
    PAPER,

    /** A dark blue background, often preferred for night reading. */
    NAVY,

    /** A popular low-contrast light theme for reduced eye strain. */
    SOLARIZED_LIGHT,

    /** A popular low-contrast dark theme for reduced eye strain. */
    SOLARIZED_DARK
}

// Font family options for the reader
enum class ReaderFont(val displayName: String) {
    SANS_SERIF("Sans Serif"),   // Clean, modern (default)
    SERIF("Serif"),             // Classic, book-like
    MONOSPACE("Monospace"),     // Fixed-width, code-like
    CURSIVE("Cursive")          // Handwriting style
}

// Data class holding all reader customization settings.
data class ReaderSettings(
    val fontSize: Int = 16,                      // Text size in sp
    val theme: ReaderTheme = ReaderTheme.LIGHT,  // Current color theme
    val font: ReaderFont = ReaderFont.SANS_SERIF // Font family
)