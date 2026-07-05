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

@Composable
internal fun ChapterEndNavigation(
    chapter: ReaderChapterData,
    colors: ThemeColors,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "— End of ${chapter.chapterTitle} —",
            color = colors.secondaryText,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (chapter.totalChapters > 0) {
            Text(
                text = "Chapter ${chapter.chapterNumber} of ${chapter.totalChapters}",
                color = colors.secondaryText,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onPreviousChapter,
                enabled = canGoPrevious,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = colors.text,
                    disabledContentColor = colors.secondaryText.copy(alpha = 0.5f)
                )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text("Previous")
            }

            Button(
                onClick = onNextChapter,
                enabled = canGoNext,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = colors.secondaryText.copy(alpha = 0.2f),
                    disabledContentColor = colors.secondaryText.copy(alpha = 0.5f)
                )
            ) {
                Text("Next Chapter")
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }

        if (canGoNext && chapter.nextChapter != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Up next: ${chapter.nextChapter.title}",
                color = colors.secondaryText,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        if (chapter.isLastChapter && chapter.totalChapters > 0 && !canGoNext) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "🎉 You've reached the latest chapter!",
                color = colors.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
internal fun ReaderTopBar(
    chapterTitle: String,
    novelTitle: String,
    chapterNumber: Int,
    totalChapters: Int,
    ttsState: TTSState,
    estimatedMinutesLeft: Int? = null,
    onBackClick: () -> Unit,
    onTTSClick: () -> Unit,
    isAutoScrollActive: Boolean = false,
    onAutoScrollClick: () -> Unit = {},
    backgroundColor: Color,
    contentColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBackClick) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close reader",
                tint = contentColor
            )
        }

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = chapterTitle,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = contentColor
            )
            Row {
                Text(
                    text = novelTitle,
                    fontSize = 11.sp,
                    color = contentColor.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (totalChapters > 0) {
                    Text(
                        text = " • $chapterNumber/$totalChapters",
                        fontSize = 11.sp,
                        color = contentColor.copy(alpha = 0.7f)
                    )
                }
                // Reading time estimate — shows "~12 min" based on user's WPM
                if (estimatedMinutesLeft != null) {
                    Text(
                        text = " • ~${estimatedMinutesLeft} min",
                        fontSize = 11.sp,
                        color = contentColor.copy(alpha = 0.5f)
                    )
                }
            }
        }

        // Auto-scroll toggle. Disabled while TTS is playing — they're
        // mutually exclusive (see the TTS mutex LaunchedEffect in
        // ReaderContent). When active, icon shows Pause; otherwise PlayArrow.
        IconButton(
            onClick = onAutoScrollClick,
            enabled = ttsState != TTSState.PLAYING
        ) {
            Icon(
                imageVector = if (isAutoScrollActive) Icons.Filled.Pause
                else Icons.Filled.PlayArrow,
                contentDescription = if (isAutoScrollActive)
                    "Stop auto-scroll" else "Start auto-scroll",
                tint = when {
                    isAutoScrollActive -> MaterialTheme.colorScheme.primary
                    ttsState == TTSState.PLAYING -> contentColor.copy(alpha = 0.3f)
                    else -> contentColor
                }
            )
        }

        IconButton(onClick = onTTSClick) {
            Icon(
                imageVector = Icons.Default.Headphones,
                contentDescription = "Text to Speech",
                tint = if (ttsState == TTSState.PLAYING)
                    MaterialTheme.colorScheme.primary
                else
                    contentColor
            )
        }
    }
}

@Composable
internal fun ReaderBottomBar(
    settings: ReaderSettings,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onIncreaseFontSize: () -> Unit,
    onDecreaseFontSize: () -> Unit,
    onOpenAppearance: () -> Unit,
    onToggleFullscreen: () -> Unit,
    backgroundColor: Color,
    contentColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Previous chapter
        IconButton(
            onClick = onPreviousClick,
            enabled = canGoPrevious
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Previous chapter",
                tint = if (canGoPrevious) contentColor else contentColor.copy(alpha = 0.3f)
            )
        }

        // Font size controls
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onDecreaseFontSize,
                enabled = settings.fontSize > 12
            ) {
                Icon(
                    imageVector = Icons.Filled.Remove,
                    contentDescription = "Decrease font size",
                    tint = contentColor
                )
            }

            Text(
                text = "${settings.fontSize}",
                color = contentColor,
                fontSize = 14.sp,
                modifier = Modifier.width(24.dp),
                textAlign = TextAlign.Center
            )

            IconButton(
                onClick = onIncreaseFontSize,
                enabled = settings.fontSize < 28
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Increase font size",
                    tint = contentColor
                )
            }
        }

        // Appearance button — opens theme/font bottom sheet
        IconButton(onClick = onOpenAppearance) {
            Icon(
                imageVector = Icons.Filled.Palette,
                contentDescription = "Theme & font settings",
                tint = contentColor
            )
        }

        // Fullscreen button — enters immersive mode (hides bars)
        IconButton(onClick = onToggleFullscreen) {
            Icon(
                imageVector = Icons.Filled.Fullscreen,
                contentDescription = "Enter fullscreen",
                tint = contentColor
            )
        }

        // Next chapter
        IconButton(
            onClick = onNextClick,
            enabled = canGoNext
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Next chapter",
                tint = if (canGoNext) contentColor else contentColor.copy(alpha = 0.3f)
            )
        }
    }
}

