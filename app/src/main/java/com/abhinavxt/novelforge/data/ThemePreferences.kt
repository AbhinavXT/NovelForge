package com.abhinavxt.novelforge.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark")
}

/**
 * App-wide theme — mirrors the reader's ReaderTheme grid.
 *
 * DEFAULT keeps the Material behavior: ThemeMode (System/Light/Dark)
 * plus the accent ColorScheme picker (incl. Dynamic). Every palette
 * theme is a fixed, complete Material scheme built from the same
 * community palettes the reader uses — mode and accent pickers don't
 * apply to them, exactly like the reader's theme grid.
 *
 * isDark is null for DEFAULT (ThemeMode decides) and CUSTOM
 * (background luminance decides).
 */
enum class AppTheme(val label: String, val isDark: Boolean?) {
    DEFAULT("Default", null),

    // ── Light palettes ───────────────────────────────────────────
    PAPER("Paper", false),
    SEPIA("Sepia", false),
    SOLARIZED_LIGHT("Solarized", false),

    // ── Dark palettes ────────────────────────────────────────────
    DARK("Dark", true),
    AMOLED("AMOLED", true),
    NORD("Nord", true),
    DRACULA("Dracula", true),
    GRUVBOX("Gruvbox", true),
    CATPPUCCIN("Catppuccin", true),
    NAVY("Navy", true),
    GREY("Grey", true),

    // ── User-defined: app background swatch + accent seed ────────
    CUSTOM("Custom", null)
}

enum class ColorScheme(val label: String) {
    DYNAMIC("Dynamic"),
    PURPLE("Purple"),
    BLUE("Blue"),
    GREEN("Green"),
    TEAL("Teal"),
    RED("Red"),
    ORANGE("Orange"),
    PINK("Pink"),
    INDIGO("Indigo"),
    CYAN("Cyan"),
    AMBER("Amber"),

    // User-defined — light/dark schemes are derived from
    // ThemePreferences.customPrimaryColor (a seed color the user
    // picks from a swatch row), mirroring the reader's CUSTOM theme.
    CUSTOM("Custom")
}

/**
 * App-wide UI font. DEFAULT keeps the original pairing
 * (Outfit for headings, Literata for body). Every other option
 * applies one family across all Material text styles — same
 * bundled .ttf files the reader already ships.
 */
enum class AppFont(val label: String) {
    DEFAULT("Default"),
    LITERATA("Literata"),
    LORA("Lora"),
    MERRIWEATHER("Merriweather"),
    CRIMSON_TEXT("Crimson Text"),
    SOURCE_SANS("Source Sans"),
    NOTO_SANS("Noto Sans"),
    OPEN_DYSLEXIC("OpenDyslexic"),
    JETBRAINS_MONO("JetBrains Mono"),
    DANCING_SCRIPT("Dancing Script")
}

/**
 * How the Library tab renders the collection.
 * Persisted so the choice survives restarts.
 */
enum class LibraryViewMode(val label: String) {
    LIST("List"),
    GRID("Grid")
}

enum class DictionaryLanguage(val code: String, val label: String) {
    ENGLISH("en", "English"),
    HINDI("hi", "Hindi"),
    JAPANESE("ja", "Japanese"),
    CHINESE("zh", "Chinese"),
    FRENCH("fr", "French"),
    SPANISH("es", "Spanish")
}

class ThemePreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(loadThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _colorScheme = MutableStateFlow(loadColorScheme())
    val colorScheme: StateFlow<ColorScheme> = _colorScheme.asStateFlow()

    private val _appFont = MutableStateFlow(loadAppFont())
    val appFont: StateFlow<AppFont> = _appFont.asStateFlow()

    private val _appTheme = MutableStateFlow(loadAppTheme())
    val appTheme: StateFlow<AppTheme> = _appTheme.asStateFlow()

    // Background for AppTheme.CUSTOM. ARGB Long, same convention as
    // the reader's customBackgroundColor.
    private val _customAppBackground = MutableStateFlow(loadCustomAppBackground())
    val customAppBackground: StateFlow<Long> = _customAppBackground.asStateFlow()

    // Seed color for ColorScheme.CUSTOM. Stored as an ARGB Long, same
    // convention as ReaderSettings.customBackgroundColor.
    private val _customPrimaryColor = MutableStateFlow(loadCustomPrimaryColor())
    val customPrimaryColor: StateFlow<Long> = _customPrimaryColor.asStateFlow()

    private val _dictionaryLanguage = MutableStateFlow(loadDictionaryLanguage())
    val dictionaryLanguage: StateFlow<DictionaryLanguage> = _dictionaryLanguage.asStateFlow()

    private val _libraryViewMode = MutableStateFlow(loadLibraryViewMode())
    val libraryViewMode: StateFlow<LibraryViewMode> = _libraryViewMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _themeMode.value = mode
    }

    fun setColorScheme(scheme: ColorScheme) {
        prefs.edit().putString(KEY_COLOR_SCHEME, scheme.name).apply()
        _colorScheme.value = scheme
    }

    fun setAppFont(font: AppFont) {
        prefs.edit().putString(KEY_APP_FONT, font.name).apply()
        _appFont.value = font
    }

    fun setAppTheme(theme: AppTheme) {
        prefs.edit().putString(KEY_APP_THEME, theme.name).apply()
        _appTheme.value = theme
    }

    fun setCustomAppBackground(color: Long) {
        prefs.edit().putLong(KEY_CUSTOM_APP_BACKGROUND, color).apply()
        _customAppBackground.value = color
    }

    fun setCustomPrimaryColor(color: Long) {
        prefs.edit().putLong(KEY_CUSTOM_PRIMARY, color).apply()
        _customPrimaryColor.value = color
    }

    fun setDictionaryLanguage(language: DictionaryLanguage) {
        prefs.edit().putString(KEY_DICTIONARY_LANGUAGE, language.name).apply()
        _dictionaryLanguage.value = language
    }

    fun setLibraryViewMode(mode: LibraryViewMode) {
        prefs.edit().putString(KEY_LIBRARY_VIEW_MODE, mode.name).apply()
        _libraryViewMode.value = mode
    }

    private fun loadThemeMode(): ThemeMode {
        val name = prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
        return try {
            ThemeMode.valueOf(name ?: ThemeMode.SYSTEM.name)
        } catch (_: IllegalArgumentException) {
            ThemeMode.SYSTEM
        }
    }

    private fun loadColorScheme(): ColorScheme {
        val name = prefs.getString(KEY_COLOR_SCHEME, ColorScheme.DYNAMIC.name)
        return try {
            ColorScheme.valueOf(name ?: ColorScheme.DYNAMIC.name)
        } catch (_: IllegalArgumentException) {
            ColorScheme.DYNAMIC
        }
    }

    private fun loadAppFont(): AppFont {
        val name = prefs.getString(KEY_APP_FONT, AppFont.DEFAULT.name)
        return try {
            AppFont.valueOf(name ?: AppFont.DEFAULT.name)
        } catch (_: IllegalArgumentException) {
            AppFont.DEFAULT
        }
    }

    private fun loadAppTheme(): AppTheme {
        val name = prefs.getString(KEY_APP_THEME, AppTheme.DEFAULT.name)
        return try {
            AppTheme.valueOf(name ?: AppTheme.DEFAULT.name)
        } catch (_: IllegalArgumentException) {
            AppTheme.DEFAULT
        }
    }

    private fun loadCustomAppBackground(): Long {
        return prefs.getLong(KEY_CUSTOM_APP_BACKGROUND, DEFAULT_CUSTOM_APP_BACKGROUND)
    }

    private fun loadCustomPrimaryColor(): Long {
        // Default seed = the app's original purple, so switching to
        // CUSTOM before ever picking a swatch still looks intentional.
        return prefs.getLong(KEY_CUSTOM_PRIMARY, DEFAULT_CUSTOM_PRIMARY)
    }

    private fun loadLibraryViewMode(): LibraryViewMode {
        val name = prefs.getString(KEY_LIBRARY_VIEW_MODE, LibraryViewMode.LIST.name)
        return try {
            LibraryViewMode.valueOf(name ?: LibraryViewMode.LIST.name)
        } catch (_: IllegalArgumentException) {
            LibraryViewMode.LIST
        }
    }

    private fun loadDictionaryLanguage(): DictionaryLanguage {
        val name = prefs.getString(KEY_DICTIONARY_LANGUAGE, DictionaryLanguage.ENGLISH.name)
        return try {
            DictionaryLanguage.valueOf(name ?: DictionaryLanguage.ENGLISH.name)
        } catch (_: IllegalArgumentException) {
            DictionaryLanguage.ENGLISH
        }
    }

    companion object {
        private const val PREFS_NAME = "novel_reader_theme"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_COLOR_SCHEME = "color_scheme"
        private const val KEY_DICTIONARY_LANGUAGE = "dictionary_language"
        private const val KEY_LIBRARY_VIEW_MODE = "library_view_mode"
        private const val KEY_APP_FONT = "app_font"
        private const val KEY_APP_THEME = "app_theme"
        private const val KEY_CUSTOM_PRIMARY = "custom_primary_color"
        private const val KEY_CUSTOM_APP_BACKGROUND = "custom_app_background"

        const val DEFAULT_CUSTOM_PRIMARY = 0xFF6650A4L  // original purple
        const val DEFAULT_CUSTOM_APP_BACKGROUND = 0xFF1E2127L  // soft charcoal
    }
}