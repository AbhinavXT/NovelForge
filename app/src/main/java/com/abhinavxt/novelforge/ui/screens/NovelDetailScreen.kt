package com.abhinavxt.novelforge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abhinavxt.novelforge.data.DownloadManager
import com.abhinavxt.novelforge.data.DownloadStatus
import com.abhinavxt.novelforge.ui.components.ModelDownloadDialog
import com.abhinavxt.novelforge.ui.components.OfflineBanner
import com.abhinavxt.novelforge.data.NovelRepository
import com.abhinavxt.novelforge.ui.viewmodel.NovelDetailUiState
import com.abhinavxt.novelforge.ui.viewmodel.NovelDetailViewModel
import com.abhinavxt.novelforge.util.NetworkMonitor
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelDetailScreen(
    novelId: String,
    novelUrl: String,
    repository: NovelRepository,
    downloadManager: DownloadManager,
    ttsManager: com.abhinavxt.novelforge.data.TTSManager,
    readingStatsTracker: com.abhinavxt.novelforge.data.ReadingStatsTracker? = null,
    networkMonitor: NetworkMonitor,
    onBackClick: () -> Unit,
    onChapterClick: (chapterId: String, chapterUrl: String) -> Unit,
    onCodexClick: () -> Unit = {},
    viewModel: NovelDetailViewModel = viewModel(
        factory = NovelDetailViewModel.provideFactory(
            novelId, novelUrl, repository, ttsManager,
            androidx.compose.ui.platform.LocalContext.current.applicationContext,
            readingStatsTracker
        )
    )
) {
    val uiState by viewModel.uiState.collectAsState()
    val estimatedTimeToFinish by viewModel.estimatedTimeToFinish.collectAsState()
    val downloadState by downloadManager.novelDownloadState.collectAsState()
    val downloadingChapters by viewModel.downloadingChapters.collectAsState()
    val activeDownloads by downloadManager.activeDownloads.collectAsState()

    // Merge: show spinner for chapters being downloaded individually OR via bulk
    val allDownloadingChapters = remember(downloadingChapters, activeDownloads) {
        val bulkDownloading = activeDownloads
            .filter { it.value.status == DownloadStatus.DOWNLOADING }
            .keys
        downloadingChapters + bulkDownloading
    }
    val bookmarks by viewModel.bookmarks.collectAsState()
    val bookmarkCount by viewModel.bookmarkCount.collectAsState()
    val highlights by viewModel.highlights.collectAsState()
    val highlightCount by viewModel.highlightCount.collectAsState()
    val newChapterCount by viewModel.newChapterCount.collectAsState()
    val exportState by viewModel.exportState.collectAsState()
    val exportingChapters by viewModel.exportingChapters.collectAsState()
    val audioExportedChapters by viewModel.audioExportedChapters.collectAsState()
    val m4bBuildState by viewModel.m4bBuildState.collectAsState()
    val availableVoices by ttsManager.availableVoices.collectAsState()
    val currentVoice by ttsManager.currentVoice.collectAsState()
    val currentReadingChapterId by viewModel.currentReadingChapterId.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showModelDownloadDialog by remember { mutableStateOf(false) }

    // Show snackbar for export completion/error
    LaunchedEffect(exportState) {
        when (val state = exportState) {
            is com.abhinavxt.novelforge.data.tts.AudioExporter.ExportState.Completed -> {
                snackbarHostState.showSnackbar("Audio saved: ${state.filePath}")
                viewModel.audioExporter.resetState()
            }
            is com.abhinavxt.novelforge.data.tts.AudioExporter.ExportState.Error -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.audioExporter.resetState()
            }
            else -> {}
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            // Top bar stays clean — back button + title only. The offline
            // banner does NOT belong inside this row; it renders as a
            // full-width bar directly below the top bar instead.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                    .padding(start = 4.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Go back"
                    )
                }

                when (val state = uiState) {
                    is NovelDetailUiState.Success -> Text(
                        text = state.novel.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    else -> Text(
                        text = "Loading...",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                // Character Codex — who's who, spoiler-safe
                IconButton(onClick = onCodexClick) {
                    Icon(
                        imageVector = Icons.Default.Groups,
                        contentDescription = "Character codex"
                    )
                }
            }
        }
    ) { paddingValues ->
        // ── Outer Column ──
        // Wraps the banner + state switch so we apply paddingValues here
        // exactly once. The three branches below (Loading / Error / Success)
        // must NOT re-apply paddingValues or we'd double-pad.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // ★ Offline banner — full-width, sits right under the top bar,
            //   slides in/out as connectivity changes.
            OfflineBanner(monitor = networkMonitor)

            when (val state = uiState) {
                is NovelDetailUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),  // NOTE: no .padding(paddingValues)
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Loading novel details...")
                        }
                    }
                }

                is NovelDetailUiState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),  // NOTE: no .padding(paddingValues)
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Error",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = state.message,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }

                is NovelDetailUiState.Success -> {
                    NovelDetailContent(
                        novel = state.novel,
                        isInLibrary = state.isInLibrary,
                        onSetReadingPosition = { chapter ->
                            viewModel.startReadingFrom(chapter)
                        },
                        isLocalNovel = state.isLocalNovel,
                        downloadState = downloadState,
                        downloadingChapters = allDownloadingChapters,
                        exportingChapters = exportingChapters,
                        audioExportedChapters = audioExportedChapters,
                        exportState = exportState,
                        bookmarks = bookmarks,
                        bookmarkCount = bookmarkCount,
                        highlights = highlights,
                        highlightCount = highlightCount,
                        newChapterCount = newChapterCount,
                        onMarkUpdateSeen = { viewModel.markUpdateSeen() },
                        onDeleteHighlight = { id -> viewModel.deleteHighlight(id) },
                        onUpdateHighlightNote = { id, note -> viewModel.updateHighlightNote(id, note) },
                        onToggleLibrary = { viewModel.toggleLibrary() },
                        onChapterClick = onChapterClick,
                        onDownloadChapter = { chapter -> viewModel.downloadChapter(chapter) },
                        onExportChapterAudio = { chapter -> viewModel.exportChapterAudio(chapter) },
                        onExportAllAudio = { viewModel.exportAllChaptersAudio() },
                        onExportRangeAudio = { from, to ->
                            viewModel.exportChapterRangeAudio(from, to)
                        },
                        onCancelExport = { viewModel.cancelAudioExport() },
                        availableVoices = availableVoices,
                        currentVoice = currentVoice,
                        onVoiceSelected = { voice -> ttsManager.setVoice(voice) },
                        onDownloadAll = {
                            scope.launch {
                                downloadManager.downloadAllChapters(
                                    novelId = novelId,
                                    chapters = state.novel.chapters
                                )
                                viewModel.refreshDownloadStatus()
                            }
                        },
                        onDownloadRange = { from, to ->
                            scope.launch {
                                val chaptersInRange = state.novel.chapters.filter {
                                    it.number in from..to && !it.isDownloaded
                                }
                                downloadManager.downloadAllChapters(
                                    novelId = novelId,
                                    chapters = chaptersInRange
                                )
                                viewModel.refreshDownloadStatus()
                            }
                        },
                        onDeleteBookmark = { bookmarkId -> viewModel.deleteBookmark(bookmarkId) },
                        onUpdateBookmarkNote = { id, note -> viewModel.updateBookmarkNote(id, note) },
                        onBookmarkClick = { bookmark ->
                            // Navigate to the bookmarked chapter using the stored URL
                            onChapterClick(bookmark.chapterId, bookmark.chapterUrl)
                        },
                        onShowModelDownload = { showModelDownloadDialog = true },
                        currentReadingChapterId = currentReadingChapterId,
                        estimatedTimeToFinish = estimatedTimeToFinish,
                        m4bBuildState = m4bBuildState,
                        onGenerateM4B = { viewModel.generateM4BAudiobook() },
                        onCancelM4B = { viewModel.cancelM4BBuild() },
                        onResetM4B = { viewModel.resetM4BState() }
                        // NOTE: no `modifier = Modifier.padding(paddingValues)` here
                        // either — the outer Column already handled it.
                    )
                }
            }
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

