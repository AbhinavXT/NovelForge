package com.abhinavxt.novelreader.ui.screens

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abhinavxt.novelreader.R
import com.abhinavxt.novelreader.data.DictionaryState
import com.abhinavxt.novelreader.data.NovelRepository
import com.abhinavxt.novelreader.data.TTSManager
import com.abhinavxt.novelreader.data.TTSState
import com.abhinavxt.novelreader.data.SleepTimerMode
import com.abhinavxt.novelreader.data.ThemePreferences
import com.abhinavxt.novelreader.data.model.ReaderSettings
import com.abhinavxt.novelreader.data.model.ReaderTheme
import com.abhinavxt.novelreader.data.model.ReaderFont
import com.abhinavxt.novelreader.data.model.ReadingMode
import com.abhinavxt.novelreader.data.database.HighlightEntity
import com.abhinavxt.novelreader.ui.components.QuickSettingsSheet
import com.abhinavxt.novelreader.ui.components.PagedReaderContent
import com.abhinavxt.novelreader.ui.viewmodel.ReaderChapterData
import com.abhinavxt.novelreader.ui.viewmodel.ReaderUiState
import com.abhinavxt.novelreader.ui.viewmodel.ReaderViewModel
import com.abhinavxt.novelreader.data.tts.VoiceInfo
import com.abhinavxt.novelreader.ui.components.ModelDownloadDialog
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import kotlinx.coroutines.delay
import com.abhinavxt.novelreader.data.ChapterPrefetcher
import com.abhinavxt.novelreader.NovelReaderApplication
import com.abhinavxt.novelreader.VolumeKeyEvent
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.abhinavxt.novelreader.util.AutoScrollController

// ─────────────────────────────────────────────────────────────────
// Split out of the original ReaderScreen.kt (Phase 3 refactor).
// Same package, pure move — no behavior change. Declarations used
// across files were promoted private → internal.
// ─────────────────────────────────────────────────────────────────

data class ThemeColors(
    val background: Color,
    val text: Color,
    val secondaryText: Color,
    val isPaper: Boolean = false
)

/**
 * Map highlight color name to a translucent Color for the reader overlay.
 * These are intentionally semi-transparent (alpha ~0x40) so the
 * underlying text remains readable across all reader themes.
 */
fun getHighlightColor(colorName: String): Color {
    return when (colorName) {
        "YELLOW" -> Color(0x40FFD700)
        "GREEN"  -> Color(0x4000C853)
        "BLUE"   -> Color(0x402979FF)
        "PINK"   -> Color(0x40FF4081)
        "PURPLE" -> Color(0x40AA00FF)
        "ORANGE" -> Color(0x40FF6D00)
        else     -> Color(0x40FFD700)
    }
}

fun getThemeColors(theme: ReaderTheme): ThemeColors {
    return when (theme) {
        // ── Daytime themes ──────────────────────────────────────
        ReaderTheme.PAPER -> ThemeColors(
            background = Color(0xFFF5F0E8),    // Soft cream — lower luminance than #FFF
            text = Color(0xFF2D2A26),           // Near-black brown, warm
            secondaryText = Color(0xFF5C5347),
            isPaper = true
        )
        ReaderTheme.SEPIA -> ThemeColors(
            background = Color(0xFFFBF0D9),    // Exact Kindle sepia background
            text = Color(0xFF5F4B32),           // Exact Kindle sepia text
            secondaryText = Color(0xFF8B7355)
        )
        ReaderTheme.SOLARIZED_LIGHT -> ThemeColors(
            background = Color(0xFFFDF6E3),    // base3
            text = Color(0xFF657B83),           // base00
            secondaryText = Color(0xFF93A1A1)   // base1
        )

        // ── Nighttime / dark themes ─────────────────────────────
        ReaderTheme.DARK -> ThemeColors(
            background = Color(0xFF1A1A1A),
            text = Color(0xFFD4D0C8),           // Warm grey — not harsh #E0E0E0
            secondaryText = Color(0xFF808080)
        )
        ReaderTheme.AMOLED -> ThemeColors(
            background = Color(0xFF000000),
            text = Color(0xFFC8C8C8),           // Slightly muted to avoid OLED bloom
            secondaryText = Color(0xFF666666)
        )
        ReaderTheme.NORD -> ThemeColors(
            background = Color(0xFF2E3440),    // nord0 — Polar Night
            text = Color(0xFFD8DEE9),           // nord4 — Snow Storm
            secondaryText = Color(0xFF7B88A1)
        )
        ReaderTheme.DRACULA -> ThemeColors(
            background = Color(0xFF282A36),    // spec.draculatheme.com Background
            text = Color(0xFFF8F8F2),           // Foreground
            secondaryText = Color(0xFF6272A4)   // Comment
        )
        ReaderTheme.GRUVBOX -> ThemeColors(
            background = Color(0xFF282828),    // morhetz/gruvbox bg0
            text = Color(0xFFEBDBB2),           // fg — warm cream
            secondaryText = Color(0xFF928374)   // grey
        )
        ReaderTheme.CATPPUCCIN -> ThemeColors(
            background = Color(0xFF1E1E2E),    // catppuccin/catppuccin Base
            text = Color(0xFFCDD6F4),           // Text
            secondaryText = Color(0xFF7F849C)   // Overlay0
        )

        // ── Specialty ───────────────────────────────────────────
        ReaderTheme.NAVY -> ThemeColors(
            background = Color(0xFF0D1B2A),
            text = Color(0xFFB0C4DE),           // Light steel blue
            secondaryText = Color(0xFF6A8299)
        )
        ReaderTheme.GREY -> ThemeColors(
            background = Color(0xFF303030),
            text = Color(0xFFC0BCB4),           // Warm-neutral grey
            secondaryText = Color(0xFF8A8580)
        )

        // CUSTOM through the theme-only overload has no access to the
        // user's colors — falls back to Paper. Real custom rendering
        // goes through getThemeColors(settings) below.
        ReaderTheme.CUSTOM -> ThemeColors(
            background = Color(0xFFF5F0E8),
            text = Color(0xFF2D2A26),
            secondaryText = Color(0xFF5C5347),
            isPaper = true
        )
    }
}

/**
 * Settings-aware variant: resolves CUSTOM from the user's stored
 * colors; every other theme delegates to the enum overload.
 * secondaryText derives from the text color at 60% alpha, which works
 * on both light and dark custom backgrounds.
 */
fun getThemeColors(settings: ReaderSettings): ThemeColors {
    if (settings.theme == ReaderTheme.CUSTOM) {
        val text = Color(settings.customTextColor)
        return ThemeColors(
            background = Color(settings.customBackgroundColor),
            text = text,
            secondaryText = text.copy(alpha = 0.6f),
            isPaper = false
        )
    }
    return getThemeColors(settings.theme)
}

fun ReaderFont.toFontFamily(): FontFamily {
    return when (this) {
        // Serif fonts — best for long-form fiction reading
        ReaderFont.LITERATA -> FontFamily(Font(R.font.literata_regular))
        ReaderFont.LORA -> FontFamily(Font(R.font.lora_regular))
        ReaderFont.MERRIWEATHER -> FontFamily(Font(R.font.merriweather_regular))
        ReaderFont.CRIMSON_TEXT -> FontFamily(Font(R.font.crimson_text_regular))
        // Sans-serif — cleaner for non-fiction / UI-feel
        ReaderFont.SOURCE_SANS -> FontFamily(Font(R.font.source_sans_regular))
        ReaderFont.NOTO_SANS -> FontFamily(Font(R.font.noto_sans_regular))
        // Accessibility
        ReaderFont.OPEN_DYSLEXIC -> FontFamily(Font(R.font.open_dyslexic_regular))
        // Monospace
        ReaderFont.JETBRAINS_MONO -> FontFamily(Font(R.font.jetbrains_mono_regular))
    }
}

@Composable
fun paperBackgroundModifier(): Modifier {
    return Modifier
        .background(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFFFBF8F2),
                    Color(0xFFF8F4EC),
                    Color(0xFFF3EEE4),
                ),
                start = Offset(0f, 0f),
                end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
            )
        )
}

