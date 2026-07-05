package com.abhinavxt.novelreader.data

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

enum class ColorScheme(val label: String) {
    DYNAMIC("Dynamic"),
    PURPLE("Purple"),
    BLUE("Blue"),
    GREEN("Green"),
    TEAL("Teal"),
    RED("Red"),
    ORANGE("Orange")
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
    }
}