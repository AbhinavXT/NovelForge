package com.abhinavxt.novelforge.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abhinavxt.novelforge.data.NovelRepository
import com.abhinavxt.novelforge.data.TTSManager
import com.abhinavxt.novelforge.data.ThemePreferences
import com.abhinavxt.novelforge.ui.viewmodel.ReaderUiState
import com.abhinavxt.novelforge.ui.viewmodel.ReaderViewModel
import com.abhinavxt.novelforge.ui.components.ModelDownloadDialog
import com.abhinavxt.novelforge.data.ChapterPrefetcher

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
    statsTracker: com.abhinavxt.novelforge.data.ReadingStatsTracker? = null,
    chapterPrefetcher: ChapterPrefetcher? = null,
    // Full-text search deep-jump; -1 = normal progress restore.
    targetParagraph: Int = -1,
    onBackClick: () -> Unit,
    viewModel: ReaderViewModel = viewModel(
        factory = ReaderViewModel.provideFactory(novelId, chapterId, chapterUrl, novelUrl = novelUrl, repository, themePreferences, statsTracker, chapterPrefetcher, targetParagraph)
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

