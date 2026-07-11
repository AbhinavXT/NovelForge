package com.abhinavxt.novelforge.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import com.abhinavxt.novelforge.data.AppFont
import com.abhinavxt.novelforge.data.AppTheme
import com.abhinavxt.novelforge.data.ColorScheme as AppColorScheme
import com.abhinavxt.novelforge.data.ThemeMode

// ── Purple (default) ─────────────────────────────────────────────
private val PurpleDark = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)
private val PurpleLight = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

// ── Blue ─────────────────────────────────────────────────────────
private val BlueDark = darkColorScheme(
    primary = Blue80,
    secondary = BlueGrey80,
    tertiary = BlueAccent80
)
private val BlueLight = lightColorScheme(
    primary = Blue40,
    secondary = BlueGrey40,
    tertiary = BlueAccent40
)

// ── Green ────────────────────────────────────────────────────────
private val GreenDark = darkColorScheme(
    primary = Green80,
    secondary = GreenGrey80,
    tertiary = GreenAccent80
)
private val GreenLight = lightColorScheme(
    primary = Green40,
    secondary = GreenGrey40,
    tertiary = GreenAccent40
)

// ── Teal ─────────────────────────────────────────────────────────
private val TealDark = darkColorScheme(
    primary = Teal80,
    secondary = TealGrey80,
    tertiary = TealAccent80
)
private val TealLight = lightColorScheme(
    primary = Teal40,
    secondary = TealGrey40,
    tertiary = TealAccent40
)

// ── Red ──────────────────────────────────────────────────────────
private val RedDark = darkColorScheme(
    primary = Red80,
    secondary = RedGrey80,
    tertiary = RedAccent80
)
private val RedLight = lightColorScheme(
    primary = Red40,
    secondary = RedGrey40,
    tertiary = RedAccent40
)

// ── Orange ───────────────────────────────────────────────────────
private val OrangeDark = darkColorScheme(
    primary = Orange80,
    secondary = OrangeGrey80,
    tertiary = OrangeAccent80
)
private val OrangeLight = lightColorScheme(
    primary = Orange40,
    secondary = OrangeGrey40,
    tertiary = OrangeAccent40
)

// ── Pink ─────────────────────────────────────────────────────────
private val PinkDark = darkColorScheme(
    primary = Pink80Accent,
    secondary = PinkGrey80,
    tertiary = PinkAccent80
)
private val PinkLight = lightColorScheme(
    primary = Pink40Accent,
    secondary = PinkGrey40,
    tertiary = PinkAccent40
)

// ── Indigo ───────────────────────────────────────────────────────
private val IndigoDark = darkColorScheme(
    primary = Indigo80,
    secondary = IndigoGrey80,
    tertiary = IndigoAccent80
)
private val IndigoLight = lightColorScheme(
    primary = Indigo40,
    secondary = IndigoGrey40,
    tertiary = IndigoAccent40
)

// ── Cyan ─────────────────────────────────────────────────────────
private val CyanDark = darkColorScheme(
    primary = Cyan80,
    secondary = CyanGrey80,
    tertiary = CyanAccent80
)
private val CyanLight = lightColorScheme(
    primary = Cyan40,
    secondary = CyanGrey40,
    tertiary = CyanAccent40
)

// ── Amber ────────────────────────────────────────────────────────
private val AmberDark = darkColorScheme(
    primary = Amber80,
    secondary = AmberGrey80,
    tertiary = AmberAccent80
)
private val AmberLight = lightColorScheme(
    primary = Amber40,
    secondary = AmberGrey40,
    tertiary = AmberAccent40
)

// ─────────────────────────────────────────────────────────────────
// Custom scheme generation (ColorScheme.CUSTOM)
//
// Derives light/dark Material schemes from one user-picked seed
// color, dependency-free. Not full Material You tonal-palette math,
// but the same idea: keep the seed's hue, move lightness/saturation
// to the tones each role needs. Container roles are set explicitly
// because the M3 defaults (baseline purple) would clash with an
// arbitrary seed hue.
// ─────────────────────────────────────────────────────────────────

private fun Color.toHsl(): FloatArray {
    val r = red; val g = green; val b = blue
    val max = maxOf(r, g, b); val min = minOf(r, g, b)
    val l = (max + min) / 2f
    if (max == min) return floatArrayOf(0f, 0f, l)
    val d = max - min
    val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
    val h = when (max) {
        r -> ((g - b) / d + (if (g < b) 6f else 0f)) * 60f
        g -> ((b - r) / d + 2f) * 60f
        else -> ((r - g) / d + 4f) * 60f
    }
    return floatArrayOf(h, s, l)
}

/** Same hue as the seed, with saturation/lightness pinned to a tone. */
private fun tone(hsl: FloatArray, saturation: Float, lightness: Float): Color =
    Color.hsl(hsl[0], saturation.coerceIn(0f, 1f), lightness.coerceIn(0f, 1f))

private fun customColorScheme(seed: Long, dark: Boolean): androidx.compose.material3.ColorScheme {
    val hsl = Color(seed).toHsl()
    // Guard near-grey seeds: give them a little saturation so the
    // derived containers don't collapse into plain grey.
    val s = maxOf(hsl[1], 0.25f)
    val secondaryS = s * 0.45f
    val tertiaryHsl = floatArrayOf((hsl[0] + 60f) % 360f, hsl[1], hsl[2])

    return if (dark) {
        darkColorScheme(
            primary = tone(hsl, s, 0.80f),
            onPrimary = tone(hsl, s, 0.18f),
            primaryContainer = tone(hsl, s, 0.30f),
            onPrimaryContainer = tone(hsl, s, 0.90f),
            secondary = tone(hsl, secondaryS, 0.78f),
            secondaryContainer = tone(hsl, secondaryS, 0.30f),
            onSecondaryContainer = tone(hsl, secondaryS, 0.90f),
            tertiary = tone(tertiaryHsl, s, 0.80f),
        )
    } else {
        lightColorScheme(
            primary = tone(hsl, s, 0.40f),
            onPrimary = Color.White,
            primaryContainer = tone(hsl, s, 0.90f),
            onPrimaryContainer = tone(hsl, s, 0.12f),
            secondary = tone(hsl, secondaryS, 0.38f),
            secondaryContainer = tone(hsl, secondaryS, 0.88f),
            onSecondaryContainer = tone(hsl, secondaryS, 0.12f),
            tertiary = tone(tertiaryHsl, s, 0.40f),
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// App palette themes (AppTheme) — the reader's theme grid, app-wide.
//
// bg/text/secondaryText are byte-identical to getThemeColors() in
// ReaderTheming.kt so the app chrome and the reader agree. surface
// is the palette's canonical lifted tone (nord1, Dracula Current
// Line, Catppuccin Surface0, gruvbox bg1, …); primary/secondary are
// each palette's canonical accents.
// ─────────────────────────────────────────────────────────────────

private data class AppPalette(
    val bg: Color,
    val surface: Color,
    val text: Color,
    val secondaryText: Color,
    val primary: Color,
    val secondary: Color,
    val isDark: Boolean,
)

private fun paletteFor(theme: AppTheme): AppPalette = when (theme) {
    AppTheme.PAPER -> AppPalette(
        bg = Color(0xFFF5F0E8), surface = Color(0xFFFCF9F3),
        text = Color(0xFF2D2A26), secondaryText = Color(0xFF5C5347),
        primary = Color(0xFF8B5E3C), secondary = Color(0xFF6B5D4B),
        isDark = false
    )
    AppTheme.SEPIA -> AppPalette(
        bg = Color(0xFFFBF0D9), surface = Color(0xFFFFF8E7),
        text = Color(0xFF5F4B32), secondaryText = Color(0xFF8B7355),
        primary = Color(0xFF8B5E34), secondary = Color(0xFF8B7355),
        isDark = false
    )
    AppTheme.SOLARIZED_LIGHT -> AppPalette(
        bg = Color(0xFFFDF6E3), surface = Color(0xFFEEE8D5),   // base3 / base2
        text = Color(0xFF657B83), secondaryText = Color(0xFF93A1A1),
        primary = Color(0xFF268BD2), secondary = Color(0xFF2AA198), // blue / cyan
        isDark = false
    )
    AppTheme.DARK -> AppPalette(
        bg = Color(0xFF1A1A1A), surface = Color(0xFF242424),
        text = Color(0xFFD4D0C8), secondaryText = Color(0xFF808080),
        primary = Purple80, secondary = PurpleGrey80,
        isDark = true
    )
    AppTheme.AMOLED -> AppPalette(
        bg = Color(0xFF000000), surface = Color(0xFF0D0D0D),
        text = Color(0xFFC8C8C8), secondaryText = Color(0xFF666666),
        primary = Color(0xFF80CBC4), secondary = Color(0xFF90A4AE),
        isDark = true
    )
    AppTheme.NORD -> AppPalette(
        bg = Color(0xFF2E3440), surface = Color(0xFF3B4252),   // nord0 / nord1
        text = Color(0xFFD8DEE9), secondaryText = Color(0xFF7B88A1),
        primary = Color(0xFF88C0D0), secondary = Color(0xFF81A1C1), // frost
        isDark = true
    )
    AppTheme.DRACULA -> AppPalette(
        bg = Color(0xFF282A36), surface = Color(0xFF343746),   // Background / lifted
        text = Color(0xFFF8F8F2), secondaryText = Color(0xFF6272A4),
        primary = Color(0xFFBD93F9), secondary = Color(0xFFFF79C6), // Purple / Pink
        isDark = true
    )
    AppTheme.GRUVBOX -> AppPalette(
        bg = Color(0xFF282828), surface = Color(0xFF3C3836),   // bg0 / bg1
        text = Color(0xFFEBDBB2), secondaryText = Color(0xFF928374),
        primary = Color(0xFFFABD2F), secondary = Color(0xFFFE8019), // yellow / orange
        isDark = true
    )
    AppTheme.CATPPUCCIN -> AppPalette(
        bg = Color(0xFF1E1E2E), surface = Color(0xFF313244),   // Base / Surface0
        text = Color(0xFFCDD6F4), secondaryText = Color(0xFF7F849C),
        primary = Color(0xFFCBA6F7), secondary = Color(0xFFF5C2E7), // Mauve / Pink
        isDark = true
    )
    AppTheme.NAVY -> AppPalette(
        bg = Color(0xFF0D1B2A), surface = Color(0xFF16283C),
        text = Color(0xFFB0C4DE), secondaryText = Color(0xFF6A8299),
        primary = Color(0xFF64B5F6), secondary = Color(0xFF6A8299),
        isDark = true
    )
    AppTheme.GREY -> AppPalette(
        bg = Color(0xFF303030), surface = Color(0xFF3B3B3B),
        text = Color(0xFFC0BCB4), secondaryText = Color(0xFF8A8580),
        primary = Color(0xFFBCAAA4), secondary = Color(0xFF8A8580),
        isDark = true
    )
    // DEFAULT / CUSTOM never reach paletteFor — guarded by callers.
    else -> error("No palette for $theme")
}

/** Shift a color's lightness — used to derive surfaceContainer steps. */
private fun lift(color: Color, amount: Float): Color {
    val hsl = color.toHsl()
    return Color.hsl(hsl[0], hsl[1], (hsl[2] + amount).coerceIn(0f, 1f))
}

/**
 * Build a complete Material scheme from a palette. The
 * surfaceContainer* roles are set explicitly — M3 components (top
 * bars on scroll, bottom sheets, nav bars, menus) draw from them,
 * and their constructor defaults are baseline purple-grey which
 * would clash over e.g. Nord.
 */
private fun paletteColorScheme(p: AppPalette): androidx.compose.material3.ColorScheme {
    val pHsl = p.primary.toHsl()
    val dir = if (p.isDark) 1f else -1f   // lift dark surfaces up, light down

    val primaryContainer = tone(pHsl, maxOf(pHsl[1], 0.25f), if (p.isDark) 0.30f else 0.88f)
    val onPrimaryContainer = tone(pHsl, maxOf(pHsl[1], 0.25f), if (p.isDark) 0.90f else 0.12f)
    val onAccent = if (p.isDark) p.bg else Color.White
    val surfaceVariant = lift(p.surface, dir * 0.04f)

    return if (p.isDark) {
        darkColorScheme(
            primary = p.primary,
            onPrimary = onAccent,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = p.secondary,
            onSecondary = onAccent,
            secondaryContainer = lift(p.surface, 0.06f),
            onSecondaryContainer = p.text,
            tertiary = p.secondary,
            background = p.bg,
            onBackground = p.text,
            surface = p.bg,
            onSurface = p.text,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = p.secondaryText,
            outline = p.secondaryText,
            outlineVariant = lift(p.surface, 0.08f),
            surfaceContainerLowest = p.bg,
            surfaceContainerLow = lift(p.bg, 0.02f),
            surfaceContainer = p.surface,
            surfaceContainerHigh = lift(p.surface, 0.03f),
            surfaceContainerHighest = lift(p.surface, 0.06f),
        )
    } else {
        lightColorScheme(
            primary = p.primary,
            onPrimary = onAccent,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = p.secondary,
            onSecondary = onAccent,
            secondaryContainer = lift(p.surface, -0.06f),
            onSecondaryContainer = p.text,
            tertiary = p.secondary,
            background = p.bg,
            onBackground = p.text,
            surface = p.bg,
            onSurface = p.text,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = p.secondaryText,
            outline = p.secondaryText,
            outlineVariant = lift(p.surface, -0.08f),
            surfaceContainerLowest = lift(p.bg, 0.02f),
            surfaceContainerLow = lift(p.bg, 0.01f),
            surfaceContainer = p.surface,
            surfaceContainerHigh = lift(p.surface, -0.02f),
            surfaceContainerHighest = lift(p.surface, -0.04f),
        )
    }
}

/**
 * Custom app theme: user-picked background + accent seed. Text and
 * derived roles follow background luminance — same rule the reader
 * uses (secondary text = text at reduced strength).
 */
private fun customAppColorScheme(background: Long, primarySeed: Long): androidx.compose.material3.ColorScheme {
    val bg = Color(background)
    val isDark = bg.luminance() < 0.5f
    val text = if (isDark) Color(0xFFE3E0DA) else Color(0xFF24211D)
    val palette = AppPalette(
        bg = bg,
        surface = lift(bg, if (isDark) 0.05f else -0.03f),
        text = text,
        secondaryText = text.copy(alpha = 0.65f).compositeOver(bg),
        primary = Color(primarySeed).let { seed ->
            // Keep the seed's hue; pin lightness so the accent stays
            // usable on the picked background.
            val hsl = seed.toHsl()
            tone(hsl, maxOf(hsl[1], 0.25f), if (isDark) 0.75f else 0.40f)
        },
        secondary = text.copy(alpha = 0.8f).compositeOver(bg),
        isDark = isDark,
    )
    return paletteColorScheme(palette)
}

/**
 * Preview colors (background, text) for the Settings theme grid.
 * Returns null for DEFAULT and CUSTOM — the picker previews those
 * from live state (current scheme / picked custom background).
 */
fun appThemePreviewColors(theme: AppTheme): Pair<Color, Color>? =
    when (theme) {
        AppTheme.DEFAULT, AppTheme.CUSTOM -> null
        else -> paletteFor(theme).let { it.bg to it.text }
    }

/**
 * Whether the app should be treated as dark — used both for scheme
 * selection and for system-bar icon contrast in MainActivity.
 * DEFAULT follows ThemeMode; palettes are fixed; CUSTOM follows the
 * picked background's luminance.
 */
fun resolveDarkTheme(
    appTheme: AppTheme,
    themeMode: ThemeMode,
    customAppBackground: Long,
    systemDark: Boolean,
): Boolean = when (appTheme) {
    AppTheme.DEFAULT -> when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemDark
    }
    AppTheme.CUSTOM -> Color(customAppBackground).luminance() < 0.5f
    else -> appTheme.isDark == true
}

@Composable
fun NovelReaderTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    appColorScheme: AppColorScheme = AppColorScheme.DYNAMIC,
    appTheme: AppTheme = AppTheme.DEFAULT,
    appFont: AppFont = AppFont.DEFAULT,
    customPrimaryColor: Long = com.abhinavxt.novelforge.data.ThemePreferences.DEFAULT_CUSTOM_PRIMARY,
    customAppBackground: Long = com.abhinavxt.novelforge.data.ThemePreferences.DEFAULT_CUSTOM_APP_BACKGROUND,
    content: @Composable () -> Unit
) {
    val darkTheme = resolveDarkTheme(
        appTheme = appTheme,
        themeMode = themeMode,
        customAppBackground = customAppBackground,
        systemDark = isSystemInDarkTheme(),
    )

    val colorScheme = when (appTheme) {
        // DEFAULT — original behavior: mode + accent picker + Dynamic
        AppTheme.DEFAULT -> when (appColorScheme) {
            AppColorScheme.DYNAMIC -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val context = LocalContext.current
                    if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                } else {
                    // Fallback to Purple on pre-Android 12
                    if (darkTheme) PurpleDark else PurpleLight
                }
            }
            AppColorScheme.PURPLE -> if (darkTheme) PurpleDark else PurpleLight
            AppColorScheme.BLUE -> if (darkTheme) BlueDark else BlueLight
            AppColorScheme.GREEN -> if (darkTheme) GreenDark else GreenLight
            AppColorScheme.TEAL -> if (darkTheme) TealDark else TealLight
            AppColorScheme.RED -> if (darkTheme) RedDark else RedLight
            AppColorScheme.ORANGE -> if (darkTheme) OrangeDark else OrangeLight
            AppColorScheme.PINK -> if (darkTheme) PinkDark else PinkLight
            AppColorScheme.INDIGO -> if (darkTheme) IndigoDark else IndigoLight
            AppColorScheme.CYAN -> if (darkTheme) CyanDark else CyanLight
            AppColorScheme.AMBER -> if (darkTheme) AmberDark else AmberLight
            AppColorScheme.CUSTOM ->
                remember(customPrimaryColor, darkTheme) {
                    customColorScheme(customPrimaryColor, darkTheme)
                }
        }

        // CUSTOM — user background + accent seed
        AppTheme.CUSTOM ->
            remember(customAppBackground, customPrimaryColor) {
                customAppColorScheme(customAppBackground, customPrimaryColor)
            }

        // Palette themes — fixed complete schemes
        else -> remember(appTheme) {
            paletteColorScheme(paletteFor(appTheme))
        }
    }

    val typography = remember(appFont) { buildTypography(appFont) }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content
    )
}
