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
                // On this chapter complete, go to next with auto-retry enabled
                if (viewModel.canGoNext()) {
                    viewModel.goToNextChapterWithRetry()
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
                onPreviousChapter = { viewModel.goToPreviousChapter() },
                onNextChapter = { viewModel.goToNextChapter() },
                onNextChapterWithRetry = { viewModel.goToNextChapterWithRetry() },
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
                canGoPrevious = viewModel.canGoPrevious(),
                canGoNext = viewModel.canGoNext(),
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
                // Highlight parameters
                chapterHighlights = chapterHighlights,
                highlightSaved = highlightSavedFlag,
                onAddHighlight = { paragraphIndex, selectedText ->
                    val currentState = viewModel.uiState.value
                    if (currentState is ReaderUiState.Success) {
                        val paragraph = currentState.chapter.paragraphs.getOrNull(paragraphIndex) ?: ""
                        val startOffset = paragraph.indexOf(selectedText).coerceAtLeast(0)
                        val endOffset = (startOffset + selectedText.length).coerceAtMost(paragraph.length)
                        viewModel.addHighlight(paragraphIndex, startOffset, endOffset, selectedText)
                    }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderContent(
    chapter: ReaderChapterData,
    settings: ReaderSettings,
    ttsManager: TTSManager,
    ttsState: TTSState,
    currentSentence: Int,
    currentTTSParagraph: Int,
    ttsSettings: com.abhinavxt.novelreader.data.TTSSettings,
    showTTSControls: Boolean,
    onToggleTTSControls: () -> Unit,
    onBackClick: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onNextChapterWithRetry: () -> Unit,
    onIncreaseFontSize: () -> Unit,
    onDecreaseFontSize: () -> Unit,
    onCycleTheme: () -> Unit,
    onCycleFont: () -> Unit,
    onSetTheme: (ReaderTheme) -> Unit,
    onSetFont: (ReaderFont) -> Unit,
    onUpdateSettings: (ReaderSettings) -> Unit,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onSaveParagraphIndex: (Int) -> Unit,
    // Bookmark parameters
    isInLibrary: Boolean,
    bookmarkSaved: Boolean,
    onAddBookmark: (paragraphIndex: Int, paragraphText: String) -> Unit,
    onBookmarkSavedShown: () -> Unit,
    onShowModelDownload: () -> Unit,
    // Dictionary
    dictionaryState: DictionaryState,
    onLookupWord: (String) -> Unit,
    onDismissDictionary: () -> Unit,
    estimatedMinutesLeft: Int? = null,
    // Highlight parameters
    chapterHighlights: List<HighlightEntity> = emptyList(),
    highlightSaved: Boolean = false,
    onAddHighlight: (paragraphIndex: Int, selectedText: String) -> Unit = { _, _ -> },
    onHighlightSavedShown: () -> Unit = {}
) {
    val colors = getThemeColors(settings.theme)

    // ── Immersive mode ──────────────────────────────────────────
    // Tap center of screen to toggle. Hides top bar, bottom bar, and system bars.
    var isImmersive by remember { mutableStateOf(false) }

    // Control system bars (status bar + navigation bar)
    val view = LocalView.current
    DisposableEffect(isImmersive) {
        val window = (view.context as? android.app.Activity)?.window
            ?: return@DisposableEffect onDispose {}
        val controller = WindowInsetsControllerCompat(window, view)
        if (isImmersive) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            // Always restore system bars when leaving the reader
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Appearance bottom sheet state
    var showAppearanceSheet by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    var swipeOffset by remember { mutableFloatStateOf(0f) }

    // Selection clearing: increment this key to force SelectionContainer recomposition
    var selectionKey by remember { mutableIntStateOf(0) }

    // Wrap onLookupWord to also clear selection
    val lookupWordAndClearSelection: (String) -> Unit = { word ->
        onLookupWord(word)
        selectionKey++
    }

    // Snackbar for bookmark confirmation
    val snackbarHostState = remember { SnackbarHostState() }

    // Show snackbar when a bookmark is saved
    LaunchedEffect(bookmarkSaved) {
        if (bookmarkSaved) {
            snackbarHostState.showSnackbar("Bookmark saved!")
            onBookmarkSavedShown()
        }
    }

    // Show snackbar when a highlight is saved
    LaunchedEffect(highlightSaved) {
        if (highlightSaved) {
            snackbarHostState.showSnackbar("Highlight saved!")
            onHighlightSavedShown()
        }
    }

    LaunchedEffect(chapter.chapterId) {
        // Always scroll to the saved position — for new chapters this is 0
        // (top of page), for resumed chapters it's where the user left off.
        // The old code had `if (savedParagraphIndex > 0)` which caused
        // new chapters to start in the middle because the LazyColumn
        // retained its previous scroll position.
        delay(100)
        listState.scrollToItem(chapter.savedParagraphIndex)
    }

    // Auto-scroll to current TTS paragraph
    LaunchedEffect(currentTTSParagraph, ttsState) {
        if (ttsState == TTSState.PLAYING && currentTTSParagraph >= 0) {
            // Only scroll if the paragraph is not visible
            val firstVisible = listState.firstVisibleItemIndex
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: firstVisible

            if (currentTTSParagraph < firstVisible || currentTTSParagraph > lastVisible) {
                listState.animateScrollToItem(currentTTSParagraph)
            }
        }
    }

    LaunchedEffect(listState.firstVisibleItemIndex) {
        delay(500)
        onSaveParagraphIndex(listState.firstVisibleItemIndex)
    }

    DisposableEffect(Unit) {
        onDispose {
            onSaveParagraphIndex(listState.firstVisibleItemIndex)
        }
    }

    // ── Quick settings bottom sheet: reading mode, themes, fonts, etc. ──
    if (showAppearanceSheet) {
        QuickSettingsSheet(
            settings = settings,
            onSettingsChanged = { newSettings -> onUpdateSettings(newSettings) },
            onNavigateToPronunciation = null, // Wire up if nav controller is available
            onDismiss = { showAppearanceSheet = false }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            AnimatedVisibility(
                visible = !isImmersive,
                enter = slideInVertically(initialOffsetY = { -it }),
                exit = slideOutVertically(targetOffsetY = { -it })
            ) {
                ReaderTopBar(
                    chapterTitle = chapter.chapterTitle,
                    novelTitle = chapter.novelTitle,
                    chapterNumber = chapter.chapterNumber,
                    totalChapters = chapter.totalChapters,
                    ttsState = ttsState,
                    estimatedMinutesLeft = estimatedMinutesLeft,
                    onBackClick = onBackClick,
                    onTTSClick = onToggleTTSControls,
                    backgroundColor = colors.background,
                    contentColor = colors.text
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = !isImmersive,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it })
            ) {
                ReaderBottomBar(
                    settings = settings,
                    canGoPrevious = canGoPrevious,
                    canGoNext = canGoNext,
                    onPreviousClick = onPreviousChapter,
                    onNextClick = onNextChapter,
                    onIncreaseFontSize = onIncreaseFontSize,
                    onDecreaseFontSize = onDecreaseFontSize,
                    onOpenAppearance = { showAppearanceSheet = true },
                    onToggleFullscreen = { isImmersive = true },
                    backgroundColor = colors.background,
                    contentColor = colors.text
                )
            }
        },
        containerColor = colors.background
    ) { paddingValues ->
        // ── Keep screen on while reading (if enabled) ───────────
        val screenView = LocalView.current
        DisposableEffect(settings.keepScreenOn) {
            if (settings.keepScreenOn) { screenView.keepScreenOn = true }
            onDispose { screenView.keepScreenOn = false }
        }

        // ── Volume key navigation (scroll mode) ────────────────
        // Collects volume key events from the Application flow.
        // In scroll mode, scrolls by ~80% of visible height per press.
        // In paged mode, PagedReaderContent handles it internally.
        if (settings.volumeKeyNavigation && settings.readingMode == ReadingMode.SCROLL) {
            val app = (screenView.context.applicationContext as? NovelReaderApplication)
            if (app != null) {
                LaunchedEffect(Unit) {
                    app.volumeKeyEvents.collect { event ->
                        val visibleHeight = listState.layoutInfo.viewportEndOffset -
                                listState.layoutInfo.viewportStartOffset
                        val scrollAmount = (visibleHeight * 0.8f)
                        when (event) {
                            VolumeKeyEvent.DOWN -> listState.animateScrollBy(scrollAmount)
                            VolumeKeyEvent.UP -> listState.animateScrollBy(-scrollAmount)
                        }
                    }
                }
            }
        }

        // ── Common wrapper Box ──────────────────────────────────
        // Both reading modes sit inside this Box so the TTS controls
        // and immersive mode overlay can float on top of either mode.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // ── Reading mode: scroll vs paged ───────────────────
            if (settings.readingMode == ReadingMode.PAGED) {
                // Paged mode — horizontal pages with swipe + tap-to-turn
                PagedReaderContent(
                    paragraphs = chapter.paragraphs,
                    settings = settings,
                    colors = colors,
                    savedParagraphIndex = chapter.savedParagraphIndex,
                    onParagraphIndexChanged = { index -> onSaveParagraphIndex(index) },
                    onToggleImmersive = { isImmersive = !isImmersive },
                    onPreviousChapter = onPreviousChapter,
                    onNextChapter = onNextChapter,
                    canGoPrevious = canGoPrevious,
                    canGoNext = canGoNext,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (colors.isPaper) paperBackgroundModifier()
                            else Modifier.background(colors.background)
                        )
                )
            } else {
                // ── Scroll mode (existing behavior) ─────────────
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (colors.isPaper) paperBackgroundModifier()
                            else Modifier.background(colors.background)
                        )
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (swipeOffset < -100 && canGoNext) {
                                        onNextChapter()
                                    } else if (swipeOffset > 100 && canGoPrevious) {
                                        onPreviousChapter()
                                    }
                                    swipeOffset = 0f
                                },
                                onHorizontalDrag = { _, dragAmount ->
                                    swipeOffset += dragAmount
                                }
                            )
                        }
                        .padding(horizontal = settings.horizontalMargin.dp, vertical = 8.dp)
                ) {
                    itemsIndexed(
                        items = chapter.paragraphs,
                        key = { index, _ -> "${chapter.chapterId}_$index" }
                    ) { index, paragraph ->
                        val isCurrentParagraph = ttsState == TTSState.PLAYING && index == currentTTSParagraph

                        BookmarkableParagraph(
                            paragraph = paragraph,
                            index = index,
                            selectionKey = selectionKey,
                            isInLibrary = isInLibrary,
                            isCurrentTTSParagraph = isCurrentParagraph,
                            ttsManager = ttsManager,
                            colors = colors,
                            settings = settings,
                            highlights = chapterHighlights.filter { it.paragraphIndex == index },
                            onAddBookmark = { idx, text ->
                                onAddBookmark(idx, text)
                                selectionKey++
                            },
                            onLookupWord = lookupWordAndClearSelection,
                            onHighlight = { selectedText ->
                                onAddHighlight(index, selectedText)
                                selectionKey++
                            }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = colors.secondaryText.copy(alpha = 0.3f))
                        Spacer(modifier = Modifier.height(24.dp))
                    }

                    item {
                        ChapterEndNavigation(
                            chapter = chapter,
                            colors = colors,
                            canGoPrevious = canGoPrevious,
                            canGoNext = canGoNext,
                            onPreviousChapter = onPreviousChapter,
                            onNextChapter = onNextChapter
                        )
                        Spacer(modifier = Modifier.height(120.dp))
                    }
                }
            } // end reading mode if/else

            // ── Immersive mode: "tap to exit" pill at top ───────
            // Visible in BOTH scroll and paged modes.
            if (isImmersive) {
                Surface(
                    onClick = { isImmersive = false },
                    color = colors.text.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 2.dp)
                ) {
                    Text(
                        text = "tap to show bars",
                        color = colors.text.copy(alpha = 0.5f),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }

            // ── TTS controls — visible in BOTH modes ────────────
            AnimatedVisibility(
                visible = showTTSControls,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                TTSControlsPanel(
                    ttsManager = ttsManager,
                    ttsState = ttsState,
                    currentSentence = currentSentence,
                    ttsSettings = ttsSettings,
                    chapterContent = chapter.content,
                    novelTitle = chapter.novelTitle,
                    chapterTitle = chapter.chapterTitle,
                    canGoNext = canGoNext,
                    startFromParagraph = if (settings.readingMode == ReadingMode.SCROLL) {
                        listState.firstVisibleItemIndex
                    } else 0,
                    onNextChapter = onNextChapter,
                    onNextChapterWithRetry = onNextChapterWithRetry,
                    onShowModelDownload = onShowModelDownload,
                    onClose = {
                        ttsManager.stop()
                        onToggleTTSControls()
                    }
                )
            }
        } // end common wrapper Box

    }

    // Dictionary bottom sheet — shown when user taps "Define" on selected text
    DictionaryBottomSheet(
        dictionaryState = dictionaryState,
        onDismiss = onDismissDictionary
    )
}

/**
 * A paragraph of text that supports native text selection.
 * The selection toolbar shows: Copy, Define, Highlight, and Bookmark (if in library).
 * "Define" looks up the selected word in the dictionary.
 * "Highlight" saves the selected text as a colored highlight.
 * "Bookmark" saves the paragraph as a bookmark.
 */
@Composable
private fun BookmarkableParagraph(
    paragraph: String,
    index: Int,
    selectionKey: Int,
    isInLibrary: Boolean,
    isCurrentTTSParagraph: Boolean,
    ttsManager: TTSManager,
    colors: ThemeColors,
    settings: ReaderSettings,
    highlights: List<HighlightEntity> = emptyList(),
    onAddBookmark: (Int, String) -> Unit,
    onLookupWord: (String) -> Unit,
    onHighlight: (String) -> Unit = {}
) {
    val view = LocalView.current

    val textToolbar = remember(view, index, isInLibrary) {
        ReaderTextToolbar(
            view = view,
            onDefineRequested = { selectedText -> onLookupWord(selectedText) },
            onBookmarkRequested = if (isInLibrary) {
                { onAddBookmark(index, paragraph) }
            } else null,
            onHighlightRequested = if (isInLibrary) {
                { selectedText -> onHighlight(selectedText) }
            } else null
        )
    }

    CompositionLocalProvider(LocalTextToolbar provides textToolbar) {
        key(selectionKey) {
            SelectionContainer {
                if (isCurrentTTSParagraph) {
                    HighlightedParagraph(
                        paragraph = paragraph,
                        currentSentenceIndex = ttsManager.currentSentenceInParagraph.collectAsState().value,
                        textColor = colors.text,
                        highlightColor = colors.text.copy(alpha = 0.15f),
                        fontSize = settings.fontSize,
                        lineSpacing = settings.lineSpacing,
                        fontFamily = settings.font.toFontFamily(),
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                } else if (highlights.isNotEmpty()) {
                    // Render paragraph with highlight overlays using AnnotatedString
                    val annotatedText = buildAnnotatedString {
                        append(paragraph)
                        highlights.forEach { hl ->
                            val s = hl.startOffset.coerceIn(0, paragraph.length)
                            val e = hl.endOffset.coerceIn(s, paragraph.length)
                            if (s < e) {
                                addStyle(
                                    SpanStyle(background = getHighlightColor(hl.color)),
                                    s, e
                                )
                            }
                        }
                    }
                    Text(
                        text = annotatedText,
                        color = colors.text,
                        fontSize = settings.fontSize.sp,
                        fontFamily = settings.font.toFontFamily(),
                        lineHeight = (settings.fontSize * settings.lineSpacing).sp,
                        textAlign = TextAlign.Justify,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                } else {
                    Text(
                        text = paragraph,
                        color = colors.text,
                        fontSize = settings.fontSize.sp,
                        fontFamily = settings.font.toFontFamily(),
                        lineHeight = (settings.fontSize * settings.lineSpacing).sp,
                        textAlign = TextAlign.Justify,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }
            }
        }
    }
}

/**
 * Custom TextToolbar that adds "Define", "Highlight", and optionally "Bookmark"
 * actions alongside the standard "Copy" in the floating text selection menu.
 */
private class ReaderTextToolbar(
    private val view: View,
    private val onDefineRequested: (String) -> Unit,
    private val onBookmarkRequested: (() -> Unit)?,
    private val onHighlightRequested: ((String) -> Unit)? = null
) : TextToolbar {

    private var actionMode: ActionMode? = null

    override val status: TextToolbarStatus
        get() = if (actionMode != null) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        val callback = object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                if (onCopyRequested != null) {
                    menu.add(0, MENU_COPY, 0, "Copy")
                }
                menu.add(0, MENU_DEFINE, 1, "Define")
                if (onHighlightRequested != null) {
                    menu.add(0, MENU_HIGHLIGHT, 2, "Highlight")
                }
                if (onBookmarkRequested != null) {
                    menu.add(0, MENU_BOOKMARK, 3, "Bookmark")
                }
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    MENU_COPY -> {
                        onCopyRequested?.invoke()
                    }
                    MENU_DEFINE -> {
                        // Copy text to clipboard first, then read it for lookup
                        onCopyRequested?.invoke()
                        val clipboard = view.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val selectedText = clipboard.primaryClip
                            ?.getItemAt(0)?.text?.toString()?.trim() ?: ""
                        if (selectedText.isNotBlank()) {
                            onDefineRequested(selectedText)
                        }
                    }
                    MENU_HIGHLIGHT -> {
                        // Copy text to clipboard, then pass it to the highlight handler
                        onCopyRequested?.invoke()
                        val clipboard = view.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val selectedText = clipboard.primaryClip
                            ?.getItemAt(0)?.text?.toString()?.trim() ?: ""
                        if (selectedText.isNotBlank()) {
                            onHighlightRequested?.invoke(selectedText)
                        }
                    }
                    MENU_BOOKMARK -> {
                        onBookmarkRequested?.invoke()
                    }
                }
                mode.finish()
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                actionMode = null
            }

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: android.graphics.Rect) {
                outRect.set(
                    rect.left.toInt(),
                    rect.top.toInt(),
                    rect.right.toInt(),
                    rect.bottom.toInt()
                )
            }
        }

        actionMode?.finish()
        actionMode = view.startActionMode(callback, ActionMode.TYPE_FLOATING)
    }

    override fun hide() {
        actionMode?.finish()
        actionMode = null
    }

    companion object {
        private const val MENU_COPY = 1
        private const val MENU_DEFINE = 2
        private const val MENU_HIGHLIGHT = 3
        private const val MENU_BOOKMARK = 4
    }
}

@SuppressLint("DefaultLocale")
@Composable
private fun TTSControlsPanel(
    ttsManager: TTSManager,
    ttsState: TTSState,
    currentSentence: Int,
    ttsSettings: com.abhinavxt.novelreader.data.TTSSettings,
    chapterContent: String,
    novelTitle: String,
    chapterTitle: String,
    canGoNext: Boolean,
    startFromParagraph: Int,
    onNextChapter: () -> Unit,
    onNextChapterWithRetry: () -> Unit,
    onShowModelDownload: () -> Unit,
    onClose: () -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }
    var showVoiceSelector by remember { mutableStateOf(false) }
    var showSleepMenu by remember { mutableStateOf(false) }

    val availableVoices by ttsManager.availableVoices.collectAsState()
    val currentVoice by ttsManager.currentVoice.collectAsState()
    val sleepMode by ttsManager.sleepTimerMode.collectAsState()
    val sleepRemainingMs by ttsManager.sleepTimerRemainingMs.collectAsState()

    val totalSentences = ttsManager.getSentenceCount()
    val progress = if (totalSentences > 0) (currentSentence + 1).toFloat() / totalSentences else 0f

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 12.dp,
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 16.dp, start = 20.dp, end = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Drag handle
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        RoundedCornerShape(2.dp)
                    )
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Chapter info + close
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = chapterTitle,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (totalSentences > 0) {
                        Text(
                            text = "${currentSentence + 1} / $totalSentences",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Settings gear
                IconButton(
                    onClick = { showSettings = !showSettings },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        modifier = Modifier.size(20.dp),
                        tint = if (showSettings) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Sleep timer button + dropdown
                Box {
                    IconButton(
                        onClick = { showSleepMenu = !showSleepMenu },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (sleepMode != SleepTimerMode.NONE)
                                Icons.Default.Timer else Icons.Default.TimerOff,
                            contentDescription = "Sleep timer",
                            modifier = Modifier.size(20.dp),
                            tint = if (sleepMode != SleepTimerMode.NONE)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = showSleepMenu,
                        onDismissRequest = { showSleepMenu = false }
                    ) {
                        SleepTimerMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = mode.label,
                                        color = if (mode == sleepMode)
                                            MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = {
                                    ttsManager.setSleepTimer(mode)
                                    showSleepMenu = false
                                }
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Sleep timer status — shown when a timer is active
            if (sleepMode != SleepTimerMode.NONE) {
                val timerText = if (sleepMode == SleepTimerMode.END_OF_CHAPTER) {
                    "⏱ Stopping at end of chapter"
                } else if (sleepRemainingMs > 0) {
                    val mins = (sleepRemainingMs / 60_000).toInt()
                    val secs = ((sleepRemainingMs % 60_000) / 1000).toInt()
                    "⏱ Sleep in ${mins}:${String.format("%02d", secs)}"
                } else {
                    "⏱ ${sleepMode.label}"
                }
                Text(
                    text = timerText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }

            // Progress bar
            if (totalSentences > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Playback controls — centered, clean
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Stop
                IconButton(
                    onClick = { ttsManager.stop() },
                    enabled = ttsState != TTSState.IDLE,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop",
                        modifier = Modifier.size(22.dp),
                        tint = if (ttsState != TTSState.IDLE)
                            MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Previous sentence
                IconButton(
                    onClick = { ttsManager.skipToPrevious() },
                    enabled = ttsState == TTSState.PLAYING || ttsState == TTSState.PAUSED,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Play/Pause — larger prominent button
                Surface(
                    onClick = {
                        when (ttsState) {
                            TTSState.PLAYING -> ttsManager.pause()
                            TTSState.PAUSED -> ttsManager.resume()
                            else -> {
                                ttsManager.speakText(
                                    text = chapterContent,
                                    startFromParagraph = startFromParagraph,
                                    novelTitle = novelTitle,
                                    chapterTitle = chapterTitle
                                ) {
                                    if (canGoNext) {
                                        onNextChapterWithRetry()
                                    }
                                }
                            }
                        }
                    },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(60.dp),
                    shadowElevation = 4.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (ttsState == TTSState.LOADING) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                imageVector = if (ttsState == TTSState.PLAYING)
                                    Icons.Default.Pause
                                else
                                    Icons.Default.PlayArrow,
                                contentDescription = if (ttsState == TTSState.PLAYING) "Pause" else "Play",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Next sentence
                IconButton(
                    onClick = { ttsManager.skipToNext() },
                    enabled = ttsState == TTSState.PLAYING || ttsState == TTSState.PAUSED,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Spacer to balance stop button
                Spacer(modifier = Modifier.size(40.dp))
            }

            // Voice name pill
            if (currentVoice != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    onClick = { showVoiceSelector = true },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.RecordVoiceOver,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = currentVoice?.displayName ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Error state
            if (ttsState == TTSState.ERROR) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "TTS Error — check device settings",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }

            // Settings panel (collapsible)
            AnimatedVisibility(visible = showSettings) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    HorizontalDivider(
                        modifier = Modifier.padding(bottom = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    SettingSlider(
                        label = "Speed",
                        value = ttsSettings.speed,
                        valueDisplay = String.format("%.1fx", ttsSettings.speed),
                        onValueChange = { ttsManager.setSpeed(it) },
                        valueRange = 0.5f..2.0f,
                        steps = 5
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    SettingSlider(
                        label = "Pitch",
                        value = ttsSettings.pitch,
                        valueDisplay = String.format("%.1f", ttsSettings.pitch),
                        onValueChange = { ttsManager.setPitch(it) },
                        valueRange = 0.5f..2.0f,
                        steps = 5
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    SettingSlider(
                        label = "Volume",
                        value = ttsSettings.volume,
                        valueDisplay = "${(ttsSettings.volume * 100).toInt()}%",
                        onValueChange = { ttsManager.setVolume(it) },
                        valueRange = 0f..1f
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    SettingSlider(
                        label = "Sentence Gap",
                        value = ttsSettings.sentencePauseMs.toFloat(),
                        valueDisplay = "${ttsSettings.sentencePauseMs}ms",
                        onValueChange = { ttsManager.setSentencePause(it.toLong()) },
                        valueRange = 0f..2000f,
                        steps = 7
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    SettingSlider(
                        label = "Paragraph Gap",
                        value = ttsSettings.paragraphPauseMs.toFloat(),
                        valueDisplay = "${ttsSettings.paragraphPauseMs}ms",
                        onValueChange = { ttsManager.setParagraphPause(it.toLong()) },
                        valueRange = 0f..3000f,
                        steps = 5
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Download neural voices
                    TextButton(
                        onClick = onShowModelDownload,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Get Neural Voices")
                    }
                }
            }
        }
    }

    // Voice selector dialog
    if (showVoiceSelector) {
        VoiceSelectorDialog(
            voices = availableVoices,
            currentVoice = currentVoice,
            onVoiceSelected = { ttsManager.setVoice(it) },
            onDismiss = { showVoiceSelector = false }
        )
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    valueDisplay: String,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = valueDisplay,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun VoiceSelectorDialog(
    voices: List<VoiceInfo>,
    currentVoice: VoiceInfo?,
    onVoiceSelected: (VoiceInfo) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Voice") },
        text = {
            if (voices.isEmpty()) {
                Text("No voices available. Please check your device TTS settings.")
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                ) {
                    // Group voices by engine
                    val groupedVoices: Map<String, List<VoiceInfo>> =
                        voices.groupBy { it.engineId }

                    groupedVoices.forEach { (engine: String, engineVoices: List<VoiceInfo>) ->
                        item {
                            Text(
                                text = engine.ifBlank { "Voices" },
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }

                        items(
                            items = engineVoices,
                            key = { it.id }
                        ) { voice: VoiceInfo ->
                            VoiceItem(
                                voice = voice,
                                isSelected = voice.id == currentVoice?.id,
                                displayName = voice.displayName,
                                onClick = {
                                    onVoiceSelected(voice)
                                    onDismiss()
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun VoiceItem(
    voice: VoiceInfo,
    isSelected: Boolean,
    displayName: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer
        else
            Color.Transparent,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = if (voice.isDownloaded)
                        "Available offline"
                    else
                        "Not downloaded",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun ChapterEndNavigation(
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
private fun ReaderTopBar(
    chapterTitle: String,
    novelTitle: String,
    chapterNumber: Int,
    totalChapters: Int,
    ttsState: TTSState,
    estimatedMinutesLeft: Int? = null,
    onBackClick: () -> Unit,
    onTTSClick: () -> Unit,
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
private fun ReaderBottomBar(
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
    }
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

/**
 * Paragraph with highlighted current sentence for TTS
 */
@Composable
private fun HighlightedParagraph(
    paragraph: String,
    currentSentenceIndex: Int,
    textColor: Color,
    highlightColor: Color,
    fontSize: Int,
    lineSpacing: Float = 1.6f,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier
) {
    // Split paragraph into sentences
    val sentences = remember(paragraph) {
        paragraph.split(Regex("(?<=[.!?])\\s+"))
            .filter { it.isNotBlank() }
    }

    val annotatedString = buildAnnotatedString {
        sentences.forEachIndexed { index, sentence ->
            if (index == currentSentenceIndex) {
                // Highlight current sentence
                withStyle(
                    style = SpanStyle(
                        background = highlightColor
                    )
                ) {
                    append(sentence)
                }
            } else {
                append(sentence)
            }

            // Add space between sentences (except after last)
            if (index < sentences.size - 1) {
                append(" ")
            }
        }
    }

    Text(
        text = annotatedString,
        color = textColor,
        fontSize = fontSize.sp,
        fontFamily = fontFamily,
        lineHeight = (fontSize * lineSpacing).sp,
        textAlign = TextAlign.Justify,
        modifier = modifier
    )
}

/**
 * Bottom sheet that displays dictionary definitions for a selected word.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DictionaryBottomSheet(
    dictionaryState: DictionaryState,
    onDismiss: () -> Unit
) {
    if (dictionaryState is DictionaryState.Idle) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            when (dictionaryState) {
                is DictionaryState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is DictionaryState.NotFound -> {
                    Text(
                        text = dictionaryState.word,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No definition found for this word.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is DictionaryState.Error -> {
                    Text(
                        text = "Dictionary Error",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = dictionaryState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is DictionaryState.Success -> {
                    val result = dictionaryState.result

                    // Word + phonetic
                    Text(
                        text = result.word,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (result.phonetic != null) {
                        Text(
                            text = result.phonetic,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (result.language != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = result.language,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Definitions grouped by part of speech
                    var lastPartOfSpeech = ""
                    result.definitions.forEach { def ->
                        if (def.partOfSpeech != lastPartOfSpeech) {
                            if (lastPartOfSpeech.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                            Text(
                                text = def.partOfSpeech,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            lastPartOfSpeech = def.partOfSpeech
                        }

                        Row(modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)) {
                            Text(
                                text = "•  ",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Column {
                                Text(
                                    text = def.definition,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (def.example != null) {
                                    Text(
                                        text = "\"${def.example}\"",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                is DictionaryState.Idle -> { /* won't reach here */ }
            }
        }
    }
}