package com.abhinavxt.novelreader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.abhinavxt.novelreader.data.ColorScheme as AppColorScheme
import com.abhinavxt.novelreader.data.ThemeMode

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

@Composable
fun NovelReaderTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    appColorScheme: AppColorScheme = AppColorScheme.DYNAMIC,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = when (appColorScheme) {
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
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}