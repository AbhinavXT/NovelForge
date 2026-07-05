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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    novelId: String,
    chapterId: String,
    chapterUrl: String,
    novelUrl: String,
    repository: NovelRepository,
    ttsManager: TTSManager,
    themePreferences: ThemePreferences? = null,
    statsTracker: com.abhinavxt.novelreader.data.ReadingStatsTracker? = null,
    chapterPrefetcher: ChapterPrefetcher? = null,
    onBackClick: () -> Unit,
    viewModel: ReaderViewModel = viewModel(
        factory = ReaderViewModel.provideFactory(novelId, chapterId, chapterUrl, novelUrl = novelUrl, repository, themePreferences, statsTracker, chapterPrefetcher)
    )
) {
    val uiState by viewModel.uiState.collectAsState()

    // TTS state
    val ttsState by ttsManager.state.collectAsState()
    val currentSentence by ttsManager.currentSentenceIndex.collectAsState()
    val currentTTSParagraph by ttsManager.currentParagraphIndex.collectAsState()
    val ttsSettings by ttsManager.settings.collectAsState()
    val shouldAutoContinue by ttsManager.shouldAutoContinue.collectAsState()
    var showTTSControls by remember { mutableStateOf(false) }
    var showModelDownloadDialog by remember { mutableStateOf(false) }

    // Bookmark state
    val isInLibrary by viewModel.isInLibrary.collectAsState()
    // Bookmark event — collect one-shot from Channel
    var bookmarkSavedFlag by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.bookmarkSavedEvent.collect {
            bookmarkSavedFlag = true
        }
    }

    // Dictionary state
    val dictionaryState by viewModel.dictionaryState.collectAsState()

    // Reading time estimate
    val estimatedMinutesLeft by viewModel.estimatedMinutesLeft.collectAsState()
    val userWPM by viewModel.userWPM.collectAsState()

    // Infinite scroll (stitching)
    val stitchedChapters by viewModel.stitchedChapters.collectAsState()
    val stitchTail by viewModel.stitchTail.collectAsState()
    val activeChapterInfo by viewModel.activeChapterInfo.collectAsState()

    // Highlight state — highlights for the current chapter
    val chapterHighlights by viewModel.chapterHighlights.collectAsState()
    var highlightSavedFlag by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.highlightSavedEvent.collect {
            highlightSavedFlag = true
        }
    }

    // Stop TTS when leaving screen
    DisposableEffect(Unit) {
        onDispose {
            ttsManager.stop()
        }
    }

    // Auto-continue TTS when chapter changes
    LaunchedEffect(uiState) {
        val state = uiState
        if (state is ReaderUiState.Success && shouldAutoContinue) {
            // Small delay to ensure UI is ready
            kotlinx.coroutines.delay(500)
            ttsManager.autoContinueIfNeeded(
                text = state.chapter.content,
                novelTitle = state.chapter.novelTitle,
                chapterTitle = state.chapter.chapterTitle
            ) {
                // On this chapter complete, go to next with auto-retry
                // enabled — relative to the chapter being READ, which
                // with stitching can be past the anchor.
                val canNext = viewModel.activeChapterInfo.value?.canGoNext
                    ?: viewModel.canGoNext()
                if (canNext) {
                    viewModel.goToNextOfActiveWithRetry()
                }
            }
        }
    }

    when (val state = uiState) {
        is ReaderUiState.Loading -> {
            LoadingScreen()
        }

        is ReaderUiState.Error -> {
            ErrorScreen(
                message = state.message,
                onRetry = { viewModel.reloadChapter() }
            )
        }

        is ReaderUiState.Success -> {
            ReaderContent(
                chapter = state.chapter,
                settings = state.settings,
                ttsManager = ttsManager,
                ttsState = ttsState,
                currentSentence = currentSentence,
                currentTTSParagraph = currentTTSParagraph,
                ttsSettings = ttsSettings,
                showTTSControls = showTTSControls,
                onToggleTTSControls = { showTTSControls = !showTTSControls },
                onBackClick = {
                    ttsManager.stop()
                    onBackClick()
                },
                // Active-relative: with stitching, "next/previous" means
                // relative to the chapter being read, not the anchor.
                // Without stitching these are identical to the originals.
                onPreviousChapter = { viewModel.goToPreviousOfActive() },
                onNextChapter = { viewModel.goToNextOfActive() },
                onNextChapterWithRetry = { viewModel.goToNextOfActiveWithRetry() },
                onIncreaseFontSize = { viewModel.increaseFontSize() },
                onDecreaseFontSize = { viewModel.decreaseFontSize() },
                onCycleTheme = { viewModel.cycleTheme() },
                onCycleFont = { viewModel.cycleFont() },
                onSetTheme = { theme ->
                    viewModel.updateSettings(state.settings.copy(theme = theme))
                },
                onSetFont = { font ->
                    viewModel.updateSettings(state.settings.copy(font = font))
                },
                onUpdateSettings = { newSettings ->
                    viewModel.updateSettings(newSettings)
                },
                canGoPrevious = activeChapterInfo?.canGoPrevious ?: viewModel.canGoPrevious(),
                canGoNext = activeChapterInfo?.canGoNext ?: viewModel.canGoNext(),
                onSaveParagraphIndex = { index ->
                    viewModel.saveParagraphIndex(index)
                },
                // Bookmark parameters
                isInLibrary = isInLibrary,
                bookmarkSaved = bookmarkSavedFlag,
                onAddBookmark = { index, text ->
                    viewModel.addBookmarkAtParagraph(index, text)
                },
                onBookmarkSavedShown = { bookmarkSavedFlag = false },
                onShowModelDownload = { showModelDownloadDialog = true },
                // Dictionary parameters
                dictionaryState = dictionaryState,
                onLookupWord = { word -> viewModel.lookupWord(word) },
                onDismissDictionary = { viewModel.dismissDictionary() },
                estimatedMinutesLeft = estimatedMinutesLeft,
                userWPM = userWPM,
                // Infinite scroll (stitching)
                stitchedChapters = stitchedChapters,
                stitchTail = stitchTail,
                onRequestStitchNext = { viewModel.requestStitchNext() },
                onActiveChapterChanged = { chapterId ->
                    viewModel.onActiveChapterChanged(chapterId)
                },
                // Highlight parameters
                chapterHighlights = chapterHighlights,
                highlightSaved = highlightSavedFlag,
                onAddHighlight = { paragraphIndex, selectedText, paragraphText ->
                    // Offsets computed against the exact paragraph the
                    // selection happened in — passed up by the reader so
                    // this works for stitched chapters too.
                    val startOffset = paragraphText.indexOf(selectedText).coerceAtLeast(0)
                    val endOffset = (startOffset + selectedText.length)
                        .coerceAtMost(paragraphText.length)
                    viewModel.addHighlight(paragraphIndex, startOffset, endOffset, selectedText)
                },
                onHighlightSavedShown = { highlightSavedFlag = false }
            )
        }
    }

    // Model download dialog — shown at top level so it overlays everything
    if (showModelDownloadDialog) {
        ModelDownloadDialog(
            modelManager = ttsManager.modelManager,
            onDismiss = { showModelDownloadDialog = false },
            onModelDownloaded = {
                ttsManager.refreshVoiceList()
            }
        )
    }
}

@Composable
private fun LoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Loading chapter...",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun ErrorScreen(
    message: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Failed to Load",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Retry")
            }
        }
    }
}

