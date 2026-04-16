package com.abhinavxt.novelreader.data.model

/**
 * Reader color themes — curated from readability research.
 *
 * Daytime: warm low-luminance backgrounds reduce eye fatigue (CMU study).
 * Nighttime: dark backgrounds with muted text (not pure white) to lower glare.
 *
 * Key findings:
 *  - Kindle sepia (#FBF0D9/#5F4B32) is the most eye-comfortable warm theme
 *  - Pure white backgrounds cause excessive luminance — off-white is better
 *  - AMOLED pure-black is ideal at night (near-zero luminance)
 *  - Cool blue-grey backgrounds (Nord, Navy) speed up reading for all users
 *  - Gruvbox, Dracula, Catppuccin are community-proven low-fatigue palettes
 */
enum class ReaderTheme(val displayName: String, val isDark: Boolean) {

    // ── Daytime themes ──────────────────────────────────────────
    PAPER("Paper", false),                        // Soft cream — reduced glare vs pure white
    SEPIA("Sepia", false),                        // Kindle-matched warm — proven eye comfort
    SOLARIZED_LIGHT("Solarized Light", false),    // Scientifically designed L*a*b* contrast

    // ── Nighttime / dark themes ─────────────────────────────────
    DARK("Dark", true),                           // Standard dark — grey text, not pure white
    AMOLED("AMOLED", true),                       // Pure black for OLED screens, saves battery
    NORD("Nord", true),                           // Arctic blue-grey, very popular, low fatigue
    DRACULA("Dracula", true),                     // Purple-tinted dark, huge community following
    GRUVBOX("Gruvbox", true),                     // Retro warm dark, high readability
    CATPPUCCIN("Catppuccin", true),               // Soft pastel dark, most trending theme

    // ── Specialty ───────────────────────────────────────────────
    NAVY("Navy", true),                           // Deep blue night, late-night readers
    GREY("Grey", true),                           // Neutral, no color temperature bias
}

/**
 * Reader font families — bundled .ttf files for long-form screen reading.
 *
 * Selection based on NNG research (users read 14% faster in optimal font):
 *  - Literata: Google Play Books default, variable font, screen-optimized
 *  - Lora: warm calligraphic serif, community favorite for fiction
 *  - Merriweather: semi-condensed, readable at small sizes, bolder strokes
 *  - Crimson Text: thin elegant serif, most requested by novel reading communities
 *  - Source Sans 3: Adobe open-source sans-serif, excellent x-height
 *  - Noto Sans: Google's multilingual sans-serif, consistent across scripts
 *  - OpenDyslexic: accessibility font with weighted letter bottoms
 *  - JetBrains Mono: monospace with ligatures, clear I/l/1 differentiation
 */
enum class ReaderFont(val displayName: String, val category: FontCategory) {
    LITERATA("Literata", FontCategory.SERIF),               // Default — Google Play Books font
    LORA("Lora", FontCategory.SERIF),                       // Warm calligraphic serif
    MERRIWEATHER("Merriweather", FontCategory.SERIF),       // Semi-condensed, bolder
    CRIMSON_TEXT("Crimson Text", FontCategory.SERIF),        // Thin, elegant, community favorite
    SOURCE_SANS("Source Sans", FontCategory.SANS_SERIF),     // Adobe's open-source workhorse
    NOTO_SANS("Noto Sans", FontCategory.SANS_SERIF),         // Multilingual, consistent
    OPEN_DYSLEXIC("OpenDyslexic", FontCategory.ACCESSIBILITY), // Weighted bottoms for dyslexia
    JETBRAINS_MONO("JetBrains Mono", FontCategory.MONOSPACE),  // Monospace with ligatures
}

enum class FontCategory(val label: String) {
    SERIF("Serif"),
    SANS_SERIF("Sans Serif"),
    MONOSPACE("Monospace"),
    ACCESSIBILITY("Accessibility")
}

/**
 * Reading mode — scroll continuously like a web page, or page
 * through content horizontally like a physical book / Kindle.
 */
enum class ReadingMode(val displayName: String) {
    SCROLL("Scroll"),    // Vertical LazyColumn (current default)
    PAGED("Paged")       // Horizontal pager — one "page" of text at a time
}

/**
 * Page turn animation style for paged reading mode.
 * Controls how pages transition when the user swipes or taps to advance.
 */
enum class PageTransition(val displayName: String) {
    SLIDE("Slide"),       // Horizontal slide (default, most natural for pager)
    FADE("Fade"),         // Crossfade between pages
    CURL("Page Curl"),    // Simulated page curl (premium feel)
    NONE("None")          // Instant switch, no animation
}

data class ReaderSettings(
    val fontSize: Int = 16,
    val theme: ReaderTheme = ReaderTheme.PAPER,          // Off-white default, not pure white
    val font: ReaderFont = ReaderFont.LITERATA,          // Best screen reading font available
    val lineSpacing: Float = 1.6f,                       // 1.5–1.8x is the research sweet spot

    // ── New fields ──────────────────────────────────────────────

    val readingMode: ReadingMode = ReadingMode.SCROLL,   // Scroll vs paged
    val pageTransition: PageTransition = PageTransition.SLIDE,  // Animation for paged mode
    val horizontalMargin: Int = 16,                      // dp — left/right content padding (8–40)
    val keepScreenOn: Boolean = true,                    // Prevent screen dimming while reading
    val volumeKeyNavigation: Boolean = false,            // Use volume keys for page/chapter nav
)