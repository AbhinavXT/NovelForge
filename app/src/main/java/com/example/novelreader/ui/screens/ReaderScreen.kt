package com.example.novelreader.ui.screens

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
import com.example.novelreader.data.NovelRepository
import com.example.novelreader.data.TTSManager
import com.example.novelreader.data.TTSState
import com.example.novelreader.data.model.ReaderSettings
import com.example.novelreader.data.model.ReaderTheme
import com.example.novelreader.data.model.ReaderFont
import com.example.novelreader.ui.viewmodel.ReaderChapterData
import com.example.novelreader.ui.viewmodel.ReaderUiState
import com.example.novelreader.ui.viewmodel.ReaderViewModel
import android.speech.tts.Voice
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.text.font.Font
import com.example.novelreader.R
import kotlinx.coroutines.delay
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    novelId: String,
    chapterId: String,
    chapterUrl: String,
    novelUrl: String,
    repository: NovelRepository,
    ttsManager: TTSManager,
    onBackClick: () -> Unit,
    viewModel: ReaderViewModel = viewModel(
        factory = ReaderViewModel.provideFactory(novelId, chapterId, chapterUrl, novelUrl = novelUrl, repository)
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
            ttsManager.autoContinueIfNeeded(state.chapter.content) {
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
                canGoPrevious = viewModel.canGoPrevious(),
                canGoNext = viewModel.canGoNext(),
                onSaveParagraphIndex = { index ->
                    viewModel.saveParagraphIndex(index)
                }
            )
        }
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
    ttsSettings: com.example.novelreader.data.TTSSettings,
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
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onSaveParagraphIndex: (Int) -> Unit
) {
    val colors = getThemeColors(settings.theme)

    val listState = rememberLazyListState()

    var swipeOffset by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(chapter.chapterId) {
        if (chapter.savedParagraphIndex > 0) {
            delay(100)
            listState.scrollToItem(chapter.savedParagraphIndex)
        }
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

    Scaffold(
        topBar = {
            ReaderTopBar(
                chapterTitle = chapter.chapterTitle,
                novelTitle = chapter.novelTitle,
                chapterNumber = chapter.chapterNumber,
                totalChapters = chapter.totalChapters,
                ttsState = ttsState,
                onBackClick = onBackClick,
                onTTSClick = onToggleTTSControls,
                backgroundColor = colors.background,
                contentColor = colors.text
            )
        },
        bottomBar = {
            ReaderBottomBar(
                settings = settings,
                canGoPrevious = canGoPrevious,
                canGoNext = canGoNext,
                onPreviousClick = onPreviousChapter,
                onNextClick = onNextChapter,
                onIncreaseFontSize = onIncreaseFontSize,
                onDecreaseFontSize = onDecreaseFontSize,
                onCycleTheme = onCycleTheme,
                onCycleFont = onCycleFont,
                backgroundColor = colors.background,
                contentColor = colors.text
            )
        },
        containerColor = colors.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
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
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                itemsIndexed(
                    items = chapter.paragraphs,
                    key = { index, _ -> "${chapter.chapterId}_$index" }
                ) { index, paragraph ->
                    // Check if this paragraph contains the current TTS sentence
                    val isCurrentParagraph = ttsState == TTSState.PLAYING && index == currentTTSParagraph

                    if (isCurrentParagraph) {
                        // Highlight the current sentence within this paragraph
                        HighlightedParagraph(
                            paragraph = paragraph,
                            currentSentenceIndex = ttsManager.currentSentenceInParagraph.collectAsState().value,
                            textColor = colors.text,
                            highlightColor = colors.text.copy(alpha = 0.15f),
                            fontSize = settings.fontSize,
                            fontFamily = settings.font.toFontFamily(),
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                    } else {
                        Text(
                            text = paragraph,
                            color = colors.text,
                            fontSize = settings.fontSize.sp,
                            fontFamily = settings.font.toFontFamily(),
                            lineHeight = (settings.fontSize * 1.6).sp,
                            textAlign = TextAlign.Justify,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                    }
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
                    canGoNext = canGoNext,
                    startFromParagraph = listState.firstVisibleItemIndex,
                    onNextChapter = onNextChapter,
                    onNextChapterWithRetry = onNextChapterWithRetry,
                    onClose = {
                        ttsManager.stop()
                        onToggleTTSControls()
                    }
                )
            }
        }
    }
}

@SuppressLint("DefaultLocale")
@Composable
private fun TTSControlsPanel(
    ttsManager: TTSManager,
    ttsState: TTSState,
    currentSentence: Int,
    ttsSettings: com.example.novelreader.data.TTSSettings,
    chapterContent: String,
    canGoNext: Boolean,
    startFromParagraph: Int,
    onNextChapter: () -> Unit,
    onNextChapterWithRetry: () -> Unit,
    onClose: () -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }
    var showVoiceSelector by remember { mutableStateOf(false) }

    val availableVoices by ttsManager.availableVoices.collectAsState()
    val currentVoice by ttsManager.currentVoice.collectAsState()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Text-to-Speech",
                    style = MaterialTheme.typography.titleMedium
                )

                Row {
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings"
                        )
                    }

                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close"
                        )
                    }
                }
            }

            // Progress
            val totalSentences = ttsManager.getSentenceCount()
            if (totalSentences > 0) {
                Text(
                    text = "Sentence ${currentSentence + 1} of $totalSentences",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Settings panel (collapsible) - EXPANDED
            AnimatedVisibility(visible = showSettings) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    // Speed slider
                    SettingSlider(
                        label = "Speed",
                        value = ttsSettings.speed,
                        valueDisplay = String.format("%.1fx", ttsSettings.speed),
                        onValueChange = { ttsManager.setSpeed(it) },
                        valueRange = 0.5f..2.0f,
                        steps = 5
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Pitch slider
                    SettingSlider(
                        label = "Pitch",
                        value = ttsSettings.pitch,
                        valueDisplay = String.format("%.1f", ttsSettings.pitch),
                        onValueChange = { ttsManager.setPitch(it) },
                        valueRange = 0.5f..2.0f,
                        steps = 5
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Volume slider
                    SettingSlider(
                        label = "Volume",
                        value = ttsSettings.volume,
                        valueDisplay = "${(ttsSettings.volume * 100).toInt()}%",
                        onValueChange = { ttsManager.setVolume(it) },
                        valueRange = 0f..1f
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Sentence pause slider
                    SettingSlider(
                        label = "Sentence Pause",
                        value = ttsSettings.sentencePauseMs.toFloat(),
                        valueDisplay = "${ttsSettings.sentencePauseMs}ms",
                        onValueChange = { ttsManager.setSentencePause(it.toLong()) },
                        valueRange = 0f..2000f,
                        steps = 7
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Paragraph pause slider
                    SettingSlider(
                        label = "Paragraph Pause",
                        value = ttsSettings.paragraphPauseMs.toFloat(),
                        valueDisplay = "${ttsSettings.paragraphPauseMs}ms",
                        onValueChange = { ttsManager.setParagraphPause(it.toLong()) },
                        valueRange = 0f..3000f,
                        steps = 5
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Voice selector button
                    OutlinedButton(
                        onClick = { showVoiceSelector = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.RecordVoiceOver,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = currentVoice?.let { ttsManager.getVoiceDisplayName(it) }
                                ?: "Select Voice",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Playback controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Stop
                IconButton(
                    onClick = { ttsManager.stop() },
                    enabled = ttsState != TTSState.IDLE
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop",
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Previous sentence
                IconButton(
                    onClick = { ttsManager.skipToPrevious() },
                    enabled = ttsState == TTSState.PLAYING || ttsState == TTSState.PAUSED
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Play/Pause
                Surface(
                    onClick = {
                        when (ttsState) {
                            TTSState.PLAYING -> ttsManager.pause()
                            TTSState.PAUSED -> ttsManager.resume()
                            else -> {
                                ttsManager.speakText(chapterContent, startFromParagraph) {
                                    if (canGoNext) {
                                        onNextChapterWithRetry()
                                    }
                                }
                            }
                        }
                    },
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
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

                Spacer(modifier = Modifier.width(8.dp))

                // Next sentence
                IconButton(
                    onClick = { ttsManager.skipToNext() },
                    enabled = ttsState == TTSState.PLAYING || ttsState == TTSState.PAUSED
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Spacer(modifier = Modifier.size(28.dp))
            }

            // State indicator
            when (ttsState) {
                TTSState.LOADING -> {
                    Text(
                        text = "Loading...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        textAlign = TextAlign.Center
                    )
                }
                TTSState.ERROR -> {
                    Text(
                        text = "TTS Error - Check device settings",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        textAlign = TextAlign.Center
                    )
                }
                else -> {}
            }

            // Auto-continue indicator
            if (ttsState == TTSState.PLAYING || ttsState == TTSState.PAUSED) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "📖 Will auto-continue to next chapter",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    // Voice selector dialog
    if (showVoiceSelector) {
        VoiceSelectorDialog(
            voices = availableVoices,
            currentVoice = currentVoice,
            ttsManager = ttsManager,
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
    voices: List<Voice>,
    currentVoice: Voice?,
    ttsManager: TTSManager,
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
                    // Group voices by locale
                    val groupedVoices = voices.groupBy { it.locale.displayCountry.ifBlank { it.locale.country } }

                    groupedVoices.forEach { (country, countryVoices) ->
                        item {
                            Text(
                                text = country,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }

                        items(
                            items = countryVoices,
                            key = { it.name }
                        ) { voice ->
                            VoiceItem(
                                voice = voice,
                                isSelected = voice.name == currentVoice?.name,
                                displayName = ttsManager.getVoiceDisplayName(voice),
                                onClick = {
                                    ttsManager.setVoice(voice)
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
    voice: Voice,
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
                    text = if (voice.isNetworkConnectionRequired)
                        "Requires internet"
                    else
                        "Available offline",
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
    onCycleTheme: () -> Unit,
    onCycleFont: () -> Unit,
    backgroundColor: Color,
    contentColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
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

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
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

        TextButton(onClick = onCycleFont) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Aa",
                    fontSize = 14.sp,
                    fontFamily = settings.font.toFontFamily(),
                    color = contentColor
                )
                Text(
                    text = when (settings.font) {
                        ReaderFont.SANS_SERIF -> "Sans"
                        ReaderFont.SERIF -> "Serif"
                        ReaderFont.MONOSPACE -> "Mono"
                        ReaderFont.CURSIVE -> "Script"
                    },
                    fontSize = 9.sp,
                    color = contentColor.copy(alpha = 0.7f)
                )
            }
        }

        TextButton(onClick = onCycleTheme) {
            Text(
                text = when (settings.theme) {
                    ReaderTheme.LIGHT -> "☀️"
                    ReaderTheme.DARK -> "🌙"
                    ReaderTheme.SEPIA -> "📜"
                    ReaderTheme.GREY -> "🐘"
                    ReaderTheme.PAPER -> "📄"
                    ReaderTheme.NAVY -> "🌌"
                    ReaderTheme.SOLARIZED_LIGHT -> "🏜️"
                    ReaderTheme.SOLARIZED_DARK -> "🌃"
                },
                fontSize = 18.sp
            )
        }

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

fun getThemeColors(theme: ReaderTheme): ThemeColors {
    return when (theme) {
        ReaderTheme.LIGHT -> ThemeColors(
            background = Color.White,
            text = Color.Black,
            secondaryText = Color.Gray
        )
        ReaderTheme.DARK -> ThemeColors(
            background = Color(0xFF1A1A1A),
            text = Color(0xFFE0E0E0),
            secondaryText = Color(0xFF808080)
        )
        ReaderTheme.SEPIA -> ThemeColors(
            background = Color(0xFFF5E6C8),
            text = Color(0xFF5B4636),
            secondaryText = Color(0xFF8B7355)
        )
        ReaderTheme.GREY -> ThemeColors(
            background = Color(0xFF3C3F41),
            text = Color(0xFFE0E0E0),
            secondaryText = Color(0xFFB0B0B0)
        )
        ReaderTheme.PAPER -> ThemeColors(
            background = Color(0xFFF8F4EC),
            text = Color(0xFF2C2416),
            secondaryText = Color(0xFF5C5347),
            isPaper = true
        )
        ReaderTheme.NAVY -> ThemeColors(
            background = Color(0xFF001B2E),
            text = Color(0xFFCDE5FF),
            secondaryText = Color(0xFF8A9FAC)
        )
        ReaderTheme.SOLARIZED_LIGHT -> ThemeColors(
            background = Color(0xFFFDF6E3),
            text = Color(0xFF657B83),
            secondaryText = Color(0xFF93A1A1)
        )
        ReaderTheme.SOLARIZED_DARK -> ThemeColors(
            background = Color(0xFF002B36),
            text = Color(0xFF839496),
            secondaryText = Color(0xFF586E75)
        )
    }
}

fun ReaderFont.toFontFamily(): FontFamily {
    return when (this) {
        ReaderFont.SANS_SERIF -> FontFamily.SansSerif
        ReaderFont.SERIF -> FontFamily(Font(R.font.merriweather_regular))
        ReaderFont.MONOSPACE -> FontFamily(Font(R.font.jetbrains_mono_regular))
        ReaderFont.CURSIVE -> FontFamily(Font(R.font.dancing_script_regular))
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
        lineHeight = (fontSize * 1.6).sp,
        textAlign = TextAlign.Justify,
        modifier = modifier
    )
}