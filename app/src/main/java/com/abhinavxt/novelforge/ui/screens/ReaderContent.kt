package com.abhinavxt.novelforge.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abhinavxt.novelforge.data.DictionaryState
import com.abhinavxt.novelforge.data.TTSManager
import com.abhinavxt.novelforge.data.TTSState
import com.abhinavxt.novelforge.data.model.ReaderSettings
import com.abhinavxt.novelforge.data.model.ReaderTheme
import com.abhinavxt.novelforge.data.model.ReaderFont
import com.abhinavxt.novelforge.data.model.ReadingMode
import com.abhinavxt.novelforge.data.model.TapAction
import com.abhinavxt.novelforge.data.database.HighlightEntity
import com.abhinavxt.novelforge.ui.components.QuickSettingsSheet
import com.abhinavxt.novelforge.ui.components.PagedReaderContent
import com.abhinavxt.novelforge.ui.viewmodel.ReaderChapterData
import com.abhinavxt.novelforge.ui.viewmodel.StitchedChapter
import com.abhinavxt.novelforge.ui.viewmodel.StitchTailState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.ceil
import com.abhinavxt.novelforge.NovelReaderApplication
import com.abhinavxt.novelforge.VolumeKeyEvent
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.abhinavxt.novelforge.util.AutoScrollController
import com.abhinavxt.novelforge.util.MatchHighlight

// ─────────────────────────────────────────────────────────────────
// Split out of the original ReaderScreen.kt (Phase 3 refactor).
// Same package, pure move — no behavior change. Declarations used
// across files were promoted private → internal.
// ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderContent(
    chapter: ReaderChapterData,
    settings: ReaderSettings,
    ttsManager: TTSManager,
    ttsState: TTSState,
    currentSentence: Int,
    currentTTSParagraph: Int,
    ttsSettings: com.abhinavxt.novelforge.data.TTSSettings,
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
    userWPM: Int? = null,
    // Infinite scroll (stitching)
    stitchedChapters: List<StitchedChapter> = emptyList(),
    stitchTail: StitchTailState = StitchTailState.END_OF_BOOK,
    onRequestStitchNext: () -> Unit = {},
    onActiveChapterChanged: (String) -> Unit = {},
    // Highlight parameters
    chapterHighlights: List<HighlightEntity> = emptyList(),
    highlightSaved: Boolean = false,
    onAddHighlight: (paragraphIndex: Int, selectedText: String, paragraphText: String) -> Unit = { _, _, _ -> },
    onHighlightSavedShown: () -> Unit = {}
) {
    val colors = getThemeColors(settings)

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

    // ── In-book find ────────────────────────────────────────────
    // Transient UI state, deliberately NOT in the ViewModel: closing
    // the reader should discard it, and matches derive entirely from
    // composition inputs (segments + query). Scroll mode searches the
    // whole stitched window; paged mode only renders the anchor
    // chapter, so it only searches that.
    var findActive by remember(chapter.chapterId) { mutableStateOf(false) }
    var findQuery by remember(chapter.chapterId) { mutableStateOf("") }
    var activeFindIndex by remember { mutableIntStateOf(0) }
    // Paged-mode jump requests: (monotonic id, paragraphIndex). The id
    // makes re-jumping to the same paragraph re-trigger the effect.
    var pagedFindRequest by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val findScope = rememberCoroutineScope()

    val listState = rememberLazyListState()

    // Activity handle for the left-edge brightness gesture (null-safe:
    // no-ops in previews or non-activity hosts)
    val readerActivity = LocalContext.current as? android.app.Activity

    // ── Stitched-window segment model ───────────────────────────
    // The LazyColumn renders the anchor chapter plus any stitched
    // chapters as one continuous list. Each segment records where its
    // paragraphs START in the global item index space; a divider item
    // sits BEFORE every stitched segment (hence the +1). Indices only
    // ever grow as chapters append, so scroll position never shifts.
    val segments = remember(chapter, stitchedChapters) {
        buildList {
            add(
                ReaderSegment(
                    chapterId = chapter.chapterId,
                    title = chapter.chapterTitle,
                    number = chapter.chapterNumber,
                    paragraphs = chapter.paragraphs,
                    content = chapter.content,
                    startIndex = 0
                )
            )
            var offset = chapter.paragraphs.size
            stitchedChapters.forEach { stitched ->
                add(
                    ReaderSegment(
                        chapterId = stitched.nav.id,
                        title = stitched.nav.title,
                        number = stitched.nav.number,
                        paragraphs = stitched.paragraphs,
                        content = stitched.content,
                        startIndex = offset + 1  // +1 = divider item
                    )
                )
                offset += 1 + stitched.paragraphs.size
            }
        }
    }
    val contentItemCount = remember(segments) {
        segments.last().let { it.startIndex + it.paragraphs.size }
    }

    // ── Find matches over the loaded text ───────────────────────
    // Recomputes when the query changes OR when stitching appends a
    // chapter (new text should become findable). Empty query → empty
    // list, so closing the bar (which clears the query) also clears
    // all match styling.
    val findMatches = remember(segments, findQuery, settings.readingMode) {
        val searchSegments = if (settings.readingMode == ReadingMode.PAGED) {
            segments.take(1)
        } else {
            segments
        }
        computeFindMatches(searchSegments, findQuery)
    }
    val safeFindIndex = activeFindIndex.coerceIn(0, (findMatches.size - 1).coerceAtLeast(0))
    val activeFindMatch = if (findActive) findMatches.getOrNull(safeFindIndex) else null

    val jumpToFindMatch: (Int) -> Unit = { idx ->
        findMatches.getOrNull(idx)?.let { m ->
            if (settings.readingMode == ReadingMode.PAGED) {
                pagedFindRequest = ((pagedFindRequest?.first ?: 0) + 1) to m.paragraphIndex
            } else {
                findScope.launch { listState.animateScrollToItem(m.globalIndex) }
            }
        }
    }

    // Auto-jump to the first match as the user types. Keyed on the
    // QUERY, not the match list — a stitch append also changes
    // findMatches and must not yank the scroll position.
    LaunchedEffect(findQuery) {
        if (findActive && findQuery.length >= MatchHighlight.MIN_QUERY_LENGTH) {
            activeFindIndex = 0
            if (findMatches.isNotEmpty()) jumpToFindMatch(0)
        }
    }

    // Global LazyColumn index → (segment, paragraph-within-segment).
    // A divider index resolves to the FOLLOWING segment at paragraph 0.
    val resolvePosition: (Int) -> Pair<ReaderSegment, Int> = remember(segments) {
        { global ->
            var result = segments.first() to 0
            for (seg in segments) {
                if (global < seg.startIndex) {
                    result = seg to 0
                    break
                }
                if (global < seg.startIndex + seg.paragraphs.size) {
                    result = seg to (global - seg.startIndex)
                    break
                }
                result = seg to (seg.paragraphs.size - 1).coerceAtLeast(0)
            }
            result
        }
    }

    // UI-side mirror of the ViewModel's active chapter. Local state so
    // per-frame reads don't round-trip through a flow; resets when the
    // anchor changes (hard navigation).
    var activeSegmentId by remember(chapter.chapterId) { mutableStateOf(chapter.chapterId) }
    val activeSegment = segments.firstOrNull { it.chapterId == activeSegmentId } ?: segments.first()

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

    // Auto-scroll to current TTS paragraph. TTS reads the ACTIVE
    // chapter, so its paragraph index maps to global index via the
    // active segment's start offset.
    LaunchedEffect(currentTTSParagraph, ttsState) {
        if (ttsState == TTSState.PLAYING && currentTTSParagraph >= 0) {
            val target = activeSegment.startIndex + currentTTSParagraph
            val firstVisible = listState.firstVisibleItemIndex
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: firstVisible

            if (target < firstVisible || target > lastVisible) {
                listState.animateScrollToItem(target)
            }
        }
    }

    // ── Position pipeline: active chapter + progress save ────────
    // Divider crossings switch the active chapter IMMEDIATELY (so
    // bookmarks/TTS/stats follow); the paragraph-index save is
    // debounced. collectLatest restarts on every scroll change,
    // reproducing the old LaunchedEffect+delay(500) debounce.
    LaunchedEffect(segments) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collectLatest { global ->
                val (seg, within) = resolvePosition(global)
                if (seg.chapterId != activeSegmentId) {
                    activeSegmentId = seg.chapterId
                    onActiveChapterChanged(seg.chapterId)
                }
                delay(500)
                onSaveParagraphIndex(within)
            }
    }

    // ── Stitch trigger: load the next chapter before the user arrives ──
    LaunchedEffect(segments, stitchTail, settings.readingMode) {
        if (settings.readingMode != ReadingMode.SCROLL) return@LaunchedEffect
        if (stitchTail != StitchTailState.LOADING_MORE) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastVisible ->
                if (lastVisible >= contentItemCount - 8) {
                    onRequestStitchNext()
                }
            }
    }

    val currentResolve by rememberUpdatedState(resolvePosition)
    DisposableEffect(Unit) {
        onDispose {
            val (_, within) = currentResolve(listState.firstVisibleItemIndex)
            onSaveParagraphIndex(within)
        }
    }

    // ── Dynamic "time left in chapter" ──────────────────────────
    // Personal WPM (fallback 230) × words from the current position to
    // the end of the ACTIVE chapter. Prefix sums keep it O(segments)
    // per recomposition.
    val wordPrefixSums = remember(segments) {
        segments.associate { seg ->
            seg.chapterId to IntArray(seg.paragraphs.size + 1).also { sums ->
                seg.paragraphs.forEachIndexed { i, para ->
                    sums[i + 1] = sums[i] + para.split(whitespaceRegex).count { it.isNotBlank() }
                }
            }
        }
    }
    val dynamicMinutesLeft: Int? = run {
        val sums = wordPrefixSums[activeSegment.chapterId] ?: return@run estimatedMinutesLeft
        val (seg, within) = resolvePosition(listState.firstVisibleItemIndex)
        if (seg.chapterId != activeSegment.chapterId) return@run estimatedMinutesLeft
        val remaining = sums.last() - sums[(within).coerceIn(0, sums.size - 1)]
        if (remaining <= 0) return@run null
        val wpm = (userWPM ?: 0).takeIf { it > 0 } ?: 230
        ceil(remaining / wpm.toFloat()).toInt().coerceAtLeast(1)
    }

    // ── Auto-scroll controller (teleprompter-style continuous drift) ──
    // Standalone of TTS — auto-scroll is for hands-free *reading*. TTS
    // already does its own current-paragraph-tracking scroll (see the
    // LaunchedEffect at top of this composable). The two are mutually
    // exclusive at the UI level (see TTS-mutex LaunchedEffect below).
    val autoScrollScope = rememberCoroutineScope()
    val autoScrollController = remember(listState) {
        AutoScrollController(autoScrollScope, listState)
    }
    val autoScrollState by autoScrollController.state.collectAsState()
    val isAutoScrollActive = autoScrollState != AutoScrollController.State.IDLE

    // Sync speed from settings → controller. Settings is the source of
    // truth; the controller reads from it. When the user adjusts the
    // slider in QuickSettings, this LaunchedEffect picks up the change
    // and the running scroll loop respects the new speed on its next
    // frame (no restart needed — the loop reads speedPxPerSec.value
    // every frame).
    LaunchedEffect(settings.autoScrollSpeed) {
        autoScrollController.setSpeed(settings.autoScrollSpeed.toFloat())
    }

    // TTS mutex: starting TTS while auto-scroll is active stops auto-
    // scroll. Without this, both try to drive the screen and you get
    // visual jitter as TTS auto-scrolls to the current paragraph
    // while our loop is also drifting downward.
    LaunchedEffect(ttsState) {
        if (ttsState == TTSState.PLAYING && isAutoScrollActive) {
            autoScrollController.stop()
        }
    }

    // Lifecycle: pause auto-scroll when the screen goes into the
    // background (user pressed home, screen turned off, etc).
    // We pause rather than stop so resuming the app resumes the
    // scroll where it left off.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, autoScrollController) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE &&
                autoScrollState == AutoScrollController.State.ACTIVE
            ) {
                autoScrollController.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Stop auto-scroll on screen exit. Without this, the coroutine
    // scope persists for one frame after navigation and the loop
    // can fire one final scrollBy on a recycled LazyListState.
    DisposableEffect(autoScrollController) {
        onDispose { autoScrollController.stop() }
    }

    // Chapter-end behavior. When the controller hits the bottom of the
    // current chapter, it fires this callback. We advance (if possible)
    // and tell the controller — which then gives the user a 2-second
    // pause at the top of the new chapter before resuming the scroll
    // loop. If we can't advance (last chapter), we just stop.
    val onAutoScrollChapterEnd: () -> Unit = {
        if (canGoNext) {
            onNextChapter()
            autoScrollController.onChapterAdvanced()
        } else {
            autoScrollController.stop()
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
                if (findActive) {
                    ReaderFindBar(
                        query = findQuery,
                        onQueryChange = { findQuery = it },
                        matchCount = findMatches.size,
                        activeIndex = safeFindIndex,
                        onPrev = {
                            if (findMatches.isNotEmpty()) {
                                val n = (safeFindIndex - 1 + findMatches.size) % findMatches.size
                                activeFindIndex = n
                                jumpToFindMatch(n)
                            }
                        },
                        onNext = {
                            if (findMatches.isNotEmpty()) {
                                val n = (safeFindIndex + 1) % findMatches.size
                                activeFindIndex = n
                                jumpToFindMatch(n)
                            }
                        },
                        onClose = {
                            findActive = false
                            findQuery = ""
                            activeFindIndex = 0
                        },
                        backgroundColor = colors.background,
                        contentColor = colors.text
                    )
                } else {
                    ReaderTopBar(
                        // Active chapter — with stitching the user may be
                        // several chapters past the anchor.
                        chapterTitle = activeSegment.title,
                        novelTitle = chapter.novelTitle,
                        chapterNumber = activeSegment.number,
                        totalChapters = chapter.totalChapters,
                        ttsState = ttsState,
                        estimatedMinutesLeft = dynamicMinutesLeft,
                        onBackClick = onBackClick,
                        onTTSClick = onToggleTTSControls,
                        onFindClick = {
                            findActive = true
                            isImmersive = false
                        },
                        isAutoScrollActive = isAutoScrollActive,
                        onAutoScrollClick = {
                            when (autoScrollState) {
                                AutoScrollController.State.IDLE,
                                AutoScrollController.State.CHAPTER_END ->
                                    autoScrollController.start(onAutoScrollChapterEnd)
                                AutoScrollController.State.ACTIVE,
                                AutoScrollController.State.PAUSED ->
                                    autoScrollController.stop()
                            }
                        },
                        backgroundColor = colors.background,
                        contentColor = colors.text
                    )
                }
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
                    searchQuery = if (findActive) findQuery else "",
                    activeSearchParagraph = activeFindMatch?.paragraphIndex ?: -1,
                    activeSearchRange = activeFindMatch?.range,
                    searchJumpRequest = pagedFindRequest,
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
                        // ── Auto-scroll: tap-to-pause/resume ────────────
                        // Only active while auto-scroll is running. When idle,
                        // this pointerInput is a no-op and taps fall through
                        // to text selection / native behavior. The `key` is
                        // `isAutoScrollActive` so the gesture handler re-installs
                        // when the state flips (otherwise the captured value
                        // would go stale).
                        //
                        // NOTE: detectTapGestures consumes single taps. If you
                        // want a tap on a *paragraph* to still behave as a tap
                        // (e.g. for selection), the SelectionContainer inside
                        // BookmarkableParagraph handles long-press → selection,
                        // which fires on a different gesture path and isn't
                        // affected by this tap detector.
                        // Keyed on the tap-zone layout too, so changing it in
                        // Quick Settings re-installs the handler immediately.
                        .pointerInput(isAutoScrollActive, settings.tapZoneLayout) {
                            if (isAutoScrollActive) {
                                detectTapGestures(
                                    onTap = { autoScrollController.togglePause() }
                                )
                            } else {
                                // ── Tap zones (scroll mode) ─────────────
                                // Geometry comes from the user's
                                // TapZoneLayout — one resolver shared with
                                // paged mode. Buttons inside list items
                                // consume their own taps before this
                                // detector sees them, so this only fires on
                                // plain text / empty space. Long-press
                                // selection is a separate gesture path and
                                // is unaffected.
                                detectTapGestures(
                                    onTap = { offset ->
                                        val viewport = listState.layoutInfo.viewportEndOffset -
                                                listState.layoutInfo.viewportStartOffset
                                        val jump = viewport * 0.8f
                                        val action = settings.tapZoneLayout.resolve(
                                            xFrac = offset.x / size.width,
                                            yFrac = offset.y / size.height
                                        )
                                        when (action) {
                                            TapAction.BACK ->
                                                autoScrollScope.launch {
                                                    listState.animateScrollBy(-jump)
                                                }
                                            TapAction.FORWARD ->
                                                autoScrollScope.launch {
                                                    listState.animateScrollBy(jump)
                                                }
                                            TapAction.MENU -> isImmersive = !isImmersive
                                        }
                                    }
                                )
                            }
                        }
                        .pointerInput(Unit) {
                            // ── Left-edge brightness gesture ────────
                            // Vertical drag starting in the leftmost
                            // 28dp adjusts window brightness (drag up =
                            // brighter). Consuming each change keeps
                            // the list from scrolling underneath; drags
                            // starting elsewhere are ignored entirely,
                            // so normal scrolling is unaffected.
                            val edgeWidth = 28.dp.toPx()
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (down.position.x > edgeWidth) return@awaitEachGesture
                                val activity = readerActivity ?: return@awaitEachGesture
                                var level = activity.window.attributes.screenBrightness
                                if (level < 0f) level = 0.5f  // -1 = "system" sentinel
                                drag(down.id) { change ->
                                    val delta = change.position.y - change.previousPosition.y
                                    level = (level - delta / size.height).coerceIn(0.02f, 1f)
                                    val lp = activity.window.attributes
                                    lp.screenBrightness = level
                                    activity.window.attributes = lp
                                    change.consume()
                                }
                            }
                        }
                        .padding(horizontal = settings.horizontalMargin.dp, vertical = 8.dp)
                ) {
                    segments.forEachIndexed { segIdx, seg ->
                        // Divider before every stitched chapter
                        if (segIdx > 0) {
                            item(key = "divider_${seg.chapterId}") {
                                StitchChapterDivider(
                                    title = seg.title,
                                    number = seg.number,
                                    colors = colors
                                )
                            }
                        }

                        itemsIndexed(
                            items = seg.paragraphs,
                            key = { index, _ -> "${seg.chapterId}_$index" }
                        ) { index, paragraph ->
                            val isActiveSeg = seg.chapterId == activeSegmentId
                            val isCurrentParagraph = ttsState == TTSState.PLAYING &&
                                    isActiveSeg && index == currentTTSParagraph

                            // In-book find: the active match's range only
                            // applies to the paragraph that contains it.
                            val activeSearchRange = activeFindMatch?.let { m ->
                                if (seg.startIndex + index == m.globalIndex) m.range else null
                            }

                            BookmarkableParagraph(
                                paragraph = paragraph,
                                index = index,
                                selectionKey = selectionKey,
                                isInLibrary = isInLibrary,
                                isCurrentTTSParagraph = isCurrentParagraph,
                                ttsManager = ttsManager,
                                colors = colors,
                                settings = settings,
                                searchQuery = if (findActive) findQuery else "",
                                activeSearchRange = activeSearchRange,
                                // Highlight overlays are loaded for the ACTIVE
                                // chapter; peeking segments render plain until
                                // the viewport crosses their divider.
                                highlights = if (isActiveSeg) {
                                    chapterHighlights.filter { it.paragraphIndex == index }
                                } else {
                                    emptyList()
                                },
                                onAddBookmark = { idx, text ->
                                    // Acting on a peeking segment makes it
                                    // active first, so the bookmark attaches
                                    // to the right chapter (the ViewModel's
                                    // switch is synchronous).
                                    if (seg.chapterId != activeSegmentId) {
                                        activeSegmentId = seg.chapterId
                                        onActiveChapterChanged(seg.chapterId)
                                    }
                                    onAddBookmark(idx, text)
                                    selectionKey++
                                },
                                onLookupWord = lookupWordAndClearSelection,
                                onHighlight = { selectedText ->
                                    if (seg.chapterId != activeSegmentId) {
                                        activeSegmentId = seg.chapterId
                                        onActiveChapterChanged(seg.chapterId)
                                    }
                                    onAddHighlight(index, selectedText, paragraph)
                                    selectionKey++
                                }
                            )
                        }
                    }

                    // ── Tail: what follows the last chapter in the window ──
                    when (stitchTail) {
                        StitchTailState.LOADING_MORE -> {
                            item(key = "stitch_loading") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = colors.secondaryText,
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                        }
                        else -> {
                            // WINDOW_FULL / FAILED / END_OF_BOOK — classic
                            // end-of-chapter navigation, describing the LAST
                            // chapter in the window. With nothing stitched
                            // this is identical to the pre-Phase-5 behavior.
                            item {
                                Spacer(modifier = Modifier.height(16.dp))
                                HorizontalDivider(color = colors.secondaryText.copy(alpha = 0.3f))
                                Spacer(modifier = Modifier.height(24.dp))
                            }

                            item {
                                val lastSeg = segments.last()
                                ChapterEndNavigation(
                                    chapter = chapter.copy(
                                        chapterId = lastSeg.chapterId,
                                        chapterTitle = lastSeg.title,
                                        chapterNumber = lastSeg.number,
                                        isLastChapter = stitchTail == StitchTailState.END_OF_BOOK,
                                        nextChapter = if (stitchTail == StitchTailState.END_OF_BOOK) {
                                            null
                                        } else {
                                            chapter.nextChapter
                                        }
                                    ),
                                    colors = colors,
                                    canGoPrevious = canGoPrevious,
                                    canGoNext = stitchTail != StitchTailState.END_OF_BOOK,
                                    onPreviousChapter = onPreviousChapter,
                                    onNextChapter = onNextChapter
                                )
                                Spacer(modifier = Modifier.height(120.dp))
                            }
                        }
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
                    chapterContent = activeSegment.content,
                    novelTitle = chapter.novelTitle,
                    chapterTitle = activeSegment.title,
                    canGoNext = canGoNext,
                    startFromParagraph = if (settings.readingMode == ReadingMode.SCROLL) {
                        (listState.firstVisibleItemIndex - activeSegment.startIndex)
                            .coerceIn(0, (activeSegment.paragraphs.size - 1).coerceAtLeast(0))
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
    searchQuery: String = "",
    activeSearchRange: IntRange? = null,
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
                        justify = settings.justifyText,
                        indent = settings.paragraphIndent,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                } else {
                    // In-book find occurrences for this paragraph.
                    // remember-ed: the scan reruns only when the text
                    // or query changes, not on every recomposition.
                    val searchRanges = remember(paragraph, searchQuery) {
                        MatchHighlight.findOccurrences(paragraph, searchQuery)
                    }

                    if (highlights.isNotEmpty() || searchRanges.isNotEmpty()) {
                        // Highlight overlays + search matches share one
                        // AnnotatedString. Search spans are added LAST so
                        // they win where the two overlap — a find match
                        // must stay visible inside a yellow highlight.
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
                            with(MatchHighlight) {
                                addSearchSpans(
                                    ranges = searchRanges,
                                    activeRange = activeSearchRange,
                                    textColor = colors.text,
                                    backgroundColor = colors.background
                                )
                            }
                        }
                        Text(
                            text = annotatedText,
                            color = colors.text,
                            fontSize = settings.fontSize.sp,
                            fontFamily = settings.font.toFontFamily(),
                            lineHeight = (settings.fontSize * settings.lineSpacing).sp,
                            textAlign = if (settings.justifyText) TextAlign.Justify else TextAlign.Start,
                            style = TextStyle(
                                textIndent = if (settings.paragraphIndent) {
                                    TextIndent(firstLine = (settings.fontSize * 1.5).sp)
                                } else TextIndent.None
                            ),
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                    } else {
                        Text(
                            text = paragraph,
                            color = colors.text,
                            fontSize = settings.fontSize.sp,
                            fontFamily = settings.font.toFontFamily(),
                            lineHeight = (settings.fontSize * settings.lineSpacing).sp,
                            textAlign = if (settings.justifyText) TextAlign.Justify else TextAlign.Start,
                            style = TextStyle(
                                textIndent = if (settings.paragraphIndent) {
                                    TextIndent(firstLine = (settings.fontSize * 1.5).sp)
                                } else TextIndent.None
                            ),
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                    }
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
    justify: Boolean = true,
    indent: Boolean = false,
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
        textAlign = if (justify) TextAlign.Justify else TextAlign.Start,
        style = TextStyle(
            textIndent = if (indent) TextIndent(firstLine = (fontSize * 1.5).sp)
            else TextIndent.None
        ),
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

// ─────────────────────────────────────────────────────────────────
// Phase 5: infinite-scroll (stitching) support types
// ─────────────────────────────────────────────────────────────────

private val whitespaceRegex = Regex("\\s+")

/**
 * One chapter's slice of the stitched LazyColumn. startIndex is where
 * this segment's FIRST PARAGRAPH sits in the global item index space
 * (dividers between chapters occupy one index each).
 */
internal data class ReaderSegment(
    val chapterId: String,
    val title: String,
    val number: Int,
    val paragraphs: List<String>,
    val content: String,
    val startIndex: Int
)

/**
 * Divider rendered between stitched chapters — a quiet visual seam
 * marking where one chapter ends and the next begins.
 */
@Composable
private fun StitchChapterDivider(
    title: String,
    number: Int,
    colors: ThemeColors
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HorizontalDivider(color = colors.secondaryText.copy(alpha = 0.3f))
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "Chapter $number",
            color = colors.secondaryText,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = title,
            color = colors.text,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))
        HorizontalDivider(color = colors.secondaryText.copy(alpha = 0.3f))
    }
}
