package com.abhinavxt.novelreader.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import com.abhinavxt.novelreader.data.tts.M4BAudiobookBuilder
import com.abhinavxt.novelreader.ui.components.NovelCover
import androidx.compose.ui.platform.LocalContext
import com.abhinavxt.novelreader.AppConfig
import com.abhinavxt.novelreader.data.DownloadManager
import com.abhinavxt.novelreader.data.DownloadStatus
import com.abhinavxt.novelreader.ui.components.ModelDownloadDialog
import com.abhinavxt.novelreader.ui.components.OfflineBanner
import com.abhinavxt.novelreader.data.NovelDownloadState
import com.abhinavxt.novelreader.data.NovelRepository
import com.abhinavxt.novelreader.data.database.BookmarkEntity
import com.abhinavxt.novelreader.data.model.Chapter
import com.abhinavxt.novelreader.data.model.Novel
import com.abhinavxt.novelreader.ui.viewmodel.NovelDetailUiState
import com.abhinavxt.novelreader.ui.viewmodel.NovelDetailViewModel
import com.abhinavxt.novelreader.util.NetworkMonitor
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelDetailScreen(
    novelId: String,
    novelUrl: String,
    repository: NovelRepository,
    downloadManager: DownloadManager,
    ttsManager: com.abhinavxt.novelreader.data.TTSManager,
    readingStatsTracker: com.abhinavxt.novelreader.data.ReadingStatsTracker? = null,
    networkMonitor: NetworkMonitor,
    onBackClick: () -> Unit,
    onChapterClick: (chapterId: String, chapterUrl: String) -> Unit,
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
            is com.abhinavxt.novelreader.data.tts.AudioExporter.ExportState.Completed -> {
                snackbarHostState.showSnackbar("Audio saved: ${state.filePath}")
                viewModel.audioExporter.resetState()
            }
            is com.abhinavxt.novelreader.data.tts.AudioExporter.ExportState.Error -> {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NovelDetailContent(
    novel: Novel,
    isInLibrary: Boolean,
    isLocalNovel: Boolean,
    downloadState: NovelDownloadState?,
    downloadingChapters: Set<String>,
    exportingChapters: Set<String>,
    audioExportedChapters: Set<String>,
    exportState: com.abhinavxt.novelreader.data.tts.AudioExporter.ExportState,
    bookmarks: List<BookmarkEntity>,
    bookmarkCount: Int,
    highlights: List<com.abhinavxt.novelreader.data.database.HighlightEntity> = emptyList(),
    highlightCount: Int = 0,
    newChapterCount: Int = 0,
    onMarkUpdateSeen: () -> Unit = {},
    onDeleteHighlight: (Long) -> Unit = {},
    onUpdateHighlightNote: (Long, String?) -> Unit = { _, _ -> },
    onToggleLibrary: () -> Unit,
    onChapterClick: (chapterId: String, chapterUrl: String) -> Unit,
    onDownloadChapter: (Chapter) -> Unit,
    onExportChapterAudio: (Chapter) -> Unit,
    onExportAllAudio: () -> Unit,
    onExportRangeAudio: (fromChapter: Int, toChapter: Int) -> Unit,
    onCancelExport: () -> Unit,
    availableVoices: List<com.abhinavxt.novelreader.data.tts.VoiceInfo>,
    currentVoice: com.abhinavxt.novelreader.data.tts.VoiceInfo?,
    onVoiceSelected: (com.abhinavxt.novelreader.data.tts.VoiceInfo) -> Unit,
    onDownloadAll: () -> Unit,
    onDownloadRange: (fromChapter: Int, toChapter: Int) -> Unit,
    onDeleteBookmark: (Long) -> Unit,
    onUpdateBookmarkNote: (Long, String?) -> Unit,
    onBookmarkClick: (BookmarkEntity) -> Unit,
    onShowModelDownload: () -> Unit,
    currentReadingChapterId: String? = null,
    estimatedTimeToFinish: String? = null,
    m4bBuildState: M4BAudiobookBuilder.BuildState = M4BAudiobookBuilder.BuildState.Idle,
    onGenerateM4B: () -> Unit = {},
    onCancelM4B: () -> Unit = {},
    onResetM4B: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var showDownloadedOnly by remember { mutableStateOf(false) }
    var showDownloadRangeDialog by remember { mutableStateOf(false) }
    var showExportRangeDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Auto-scroll to current reading chapter on first load
    var hasScrolledToReading by remember { mutableStateOf(false) }
    LaunchedEffect(novel.chapters, currentReadingChapterId) {
        if (!hasScrolledToReading && currentReadingChapterId != null && searchQuery.isBlank()) {
            val chapterIndex = novel.chapters.indexOfFirst { it.id == currentReadingChapterId }
            if (chapterIndex >= 0) {
                // Offset: header, description, tabRow, statusCard, stickyHeader(voice+search+filter) = 5
                val scrollTarget = 5 + chapterIndex
                listState.scrollToItem(scrollTarget)
                hasScrolledToReading = true
            }
        }
    }

    // Filter chapters based on search query and download filter
    val filteredChapters = remember(novel.chapters, searchQuery, showDownloadedOnly) {
        var result = novel.chapters

        // Apply downloaded-only filter
        if (showDownloadedOnly) {
            result = result.filter { it.isDownloaded }
        }

        // Apply search filter
        if (searchQuery.isNotBlank()) {
            val query = searchQuery.trim()
            val chapterNumber = query.toIntOrNull()

            result = result.filter { chapter ->
                if (chapterNumber != null) {
                    chapter.number == chapterNumber ||
                            chapter.number.toString().contains(query)
                } else {
                    chapter.title.contains(query, ignoreCase = true) ||
                            chapter.number.toString().contains(query)
                }
            }
        }

        result
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize()
    ) {
        // Novel header (cover, title, buttons) — unchanged
        item {
            NovelHeader(
                novel = novel,
                isInLibrary = isInLibrary,
                isLocalNovel = isLocalNovel,
                downloadState = downloadState,
                estimatedTimeToFinish = estimatedTimeToFinish,
                onToggleLibrary = onToggleLibrary,
                onStartReading = {
                    novel.chapters.firstOrNull()?.let { chapter ->
                        onChapterClick(chapter.id, chapter.url)
                    }
                },
                onDownloadAll = onDownloadAll
            )
        }

        // ── NEW: Update changelog banner ────────────────────────
        // Shows when UpdateCheckerWorker found new chapters since
        // the user last viewed this novel. Auto-dismisses after 5 seconds.
        if (newChapterCount > 0) {
            item {
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(5000)
                    onMarkUpdateSeen()
                }

                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "$newChapterCount new chapter${if (newChapterCount > 1) "s" else ""} available!",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "Since you last checked",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }

        item {
            NovelDescription(description = novel.description)
        }

        // ===== TAB ROW: Chapters / Bookmarks =====
        item {
            TabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Chapters (${novel.chapters.size})") },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.List,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )

                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Bookmarks") },
                    icon = {
                        // Show a badge with count when there are bookmarks
                        if (bookmarkCount > 0) {
                            BadgedBox(
                                badge = {
                                    Badge { Text("$bookmarkCount") }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bookmark,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Default.BookmarkBorder,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                )
            }
        }

        // ===== TAB CONTENT =====
        when (selectedTab) {
            0 -> {
                // ===== CHAPTERS TAB =====

                // Compact status + actions card
                item {
                    val downloadedCount = novel.chapters.count { it.isDownloaded }
                    val exportedCount = audioExportedChapters.size
                    val totalCount = novel.chapters.size
                    val isAnyExporting = exportingChapters.isNotEmpty()

                    if ((downloadedCount > 0 || exportedCount > 0 || !isLocalNovel) && AppConfig.ONLINE_SOURCES_ENABLED) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            ),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Stats row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (downloadedCount > 0) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.DownloadDone,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "$downloadedCount/$totalCount",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    if (exportedCount > 0) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.MusicNote,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.tertiary
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "$exportedCount/$totalCount",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.tertiary
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.weight(1f))

                                    // Export actions
                                    if (isAnyExporting) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Exporting…",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                        TextButton(
                                            onClick = onCancelExport,
                                            modifier = Modifier.height(28.dp),
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                        ) {
                                            Text("Cancel", style = MaterialTheme.typography.labelSmall)
                                        }
                                    } else if (exportedCount < totalCount) {
                                        TextButton(
                                            onClick = onExportAllAudio,
                                            modifier = Modifier.height(28.dp),
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.GraphicEq,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = if (exportedCount == 0) "Export" else "Export (${totalCount - exportedCount})",
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                        TextButton(
                                            onClick = { showExportRangeDialog = true },
                                            modifier = Modifier.height(28.dp),
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                        ) {
                                            Text("Range", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // M4B Audiobook generation — appears when all chapters are exported
                val allChaptersExported = audioExportedChapters.size == novel.chapters.size
                        && novel.chapters.isNotEmpty()
                if (allChaptersExported) {
                    item {
                        OutlinedButton(
                            onClick = {
                                when (m4bBuildState) {
                                    is M4BAudiobookBuilder.BuildState.Building -> onCancelM4B()
                                    is M4BAudiobookBuilder.BuildState.Complete -> onResetM4B()
                                    is M4BAudiobookBuilder.BuildState.Error -> {
                                        onResetM4B()
                                        onGenerateM4B()
                                    }
                                    else -> onGenerateM4B()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.GraphicEq,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            when (m4bBuildState) {
                                is M4BAudiobookBuilder.BuildState.Idle ->
                                    Text("Generate M4B Audiobook")
                                is M4BAudiobookBuilder.BuildState.Building -> {
                                    val s = m4bBuildState as M4BAudiobookBuilder.BuildState.Building
                                    Text("${s.phase} ${s.currentChapter}/${s.totalChapters} — Tap to Cancel")
                                }
                                is M4BAudiobookBuilder.BuildState.Complete ->
                                    Text("Audiobook ready! Tap to reset")
                                is M4BAudiobookBuilder.BuildState.Error -> {
                                    val s = m4bBuildState as M4BAudiobookBuilder.BuildState.Error
                                    Text("Error — Tap to retry")
                                }
                            }
                        }

                        // Progress indicator during build
                        if (m4bBuildState is M4BAudiobookBuilder.BuildState.Building) {
                            val buildState = m4bBuildState as M4BAudiobookBuilder.BuildState.Building
                            LinearProgressIndicator(
                                progress = { buildState.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                            )
                            Text(
                                text = buildState.phase,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                            )
                        }

                        // Show file path on completion
                        if (m4bBuildState is M4BAudiobookBuilder.BuildState.Complete) {
                            val completeState = m4bBuildState as M4BAudiobookBuilder.BuildState.Complete
                            Text(
                                text = "Saved: ${completeState.filePath}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                            )
                        }

                        // Show error message
                        if (m4bBuildState is M4BAudiobookBuilder.BuildState.Error) {
                            val errorState = m4bBuildState as M4BAudiobookBuilder.BuildState.Error
                            Text(
                                text = errorState.message,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                // Voice picker + search bar + filters — sticky
                stickyHeader {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        // Voice picker row
                        AudioVoicePicker(
                            availableVoices = availableVoices,
                            currentVoice = currentVoice,
                            onVoiceSelected = onVoiceSelected,
                            onShowModelDownload = onShowModelDownload
                        )

                        // Search + filter in one block
                        ChapterSearchBar(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            resultCount = filteredChapters.size,
                            totalCount = novel.chapters.size
                        )

                        // Filter row
                        val downloadedCount = novel.chapters.count { it.isDownloaded }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = !showDownloadedOnly,
                                onClick = { showDownloadedOnly = false },
                                label = { Text("All", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(28.dp)
                            )
                            if (downloadedCount > 0) {
                                FilterChip(
                                    selected = showDownloadedOnly,
                                    onClick = { showDownloadedOnly = true },
                                    label = { Text("Offline ($downloadedCount)", style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.height(28.dp)
                                )
                            }

                            if (!isLocalNovel && AppConfig.ONLINE_SOURCES_ENABLED) {
                                Spacer(modifier = Modifier.weight(1f))
                                TextButton(
                                    onClick = { showDownloadRangeDialog = true },
                                    modifier = Modifier.height(28.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Range", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        HorizontalDivider()
                    }
                }

                if (filteredChapters.isEmpty() && searchQuery.isNotBlank()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No chapters found for \"$searchQuery\"",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                } else {
                    items(
                        items = filteredChapters,
                        key = { it.id }
                    ) { chapter ->
                        ChapterListItem(
                            chapter = chapter,
                            isDownloading = chapter.id in downloadingChapters,
                            isExporting = chapter.id in exportingChapters,
                            isAudioExported = chapter.id in audioExportedChapters,
                            exportState = exportState,
                            isLocalNovel = isLocalNovel,
                            onClick = { onChapterClick(chapter.id, chapter.url) },
                            onDownload = { onDownloadChapter(chapter) },
                            onExportAudio = { onExportChapterAudio(chapter) },
                            onCancelExport = onCancelExport
                        )
                    }
                }
            }

            1 -> {
                // ===== BOOKMARKS TAB =====

                if (bookmarks.isEmpty()) {
                    // Empty state with helpful instructions
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.BookmarkBorder,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.outline
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "No bookmarks yet",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Long-press any paragraph while reading to bookmark it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                } else {
                    items(
                        items = bookmarks,
                        key = { it.id }
                    ) { bookmark ->
                        BookmarkListItem(
                            bookmark = bookmark,
                            onClick = { onBookmarkClick(bookmark) },
                            onDelete = { onDeleteBookmark(bookmark.id) },
                            onEditNote = { note -> onUpdateBookmarkNote(bookmark.id, note) }
                        )
                    }
                }
            }
        }
    }

    // Download Range Dialog
    if (showDownloadRangeDialog) {
        DownloadRangeDialog(
            totalChapters = novel.chapters.size,
            onConfirm = { from, to ->
                onDownloadRange(from, to)
                showDownloadRangeDialog = false
            },
            onDismiss = { showDownloadRangeDialog = false }
        )
    }

    // Export Range Dialog
    if (showExportRangeDialog) {
        DownloadRangeDialog(
            totalChapters = novel.chapters.size,
            title = "Export Chapters as Audio",
            confirmLabel = "Export",
            description = "Export a range of chapters as audio files. Already exported chapters are skipped.",
            onConfirm = { from, to ->
                onExportRangeAudio(from, to)
                showExportRangeDialog = false
            },
            onDismiss = { showExportRangeDialog = false }
        )
    }
}

@Composable
private fun ChapterSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    resultCount: Int,
    totalCount: Int
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text("Search chapters or enter number") },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search"
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear search"
                    )
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        supportingText = if (query.isNotBlank()) {
            { Text("$resultCount of $totalCount chapters") }
        } else null
    )
}

@Composable
private fun NovelHeader(
    novel: Novel,
    isInLibrary: Boolean,
    isLocalNovel: Boolean,
    downloadState: NovelDownloadState?,
    estimatedTimeToFinish: String? = null,
    onToggleLibrary: () -> Unit,
    onStartReading: () -> Unit,
    onDownloadAll: () -> Unit
) {
    // Resolve cover URL vs local file path
    val imageModel = remember(novel.coverUrl) {
        when {
            novel.coverUrl == null -> null
            novel.coverUrl.startsWith("/") -> File(novel.coverUrl)
            else -> novel.coverUrl
        }
    }

    android.util.Log.d("NovelDetail", "NovelHeader: title=${novel.title}, coverUrl=${novel.coverUrl}, imageModel=$imageModel")

    val surfaceColor = MaterialTheme.colorScheme.surface

    // ── Cinematic header: blurred backdrop + gradient scrim + cover ──
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 280.dp)
    ) {
        // Layer 1: Blurred cover as atmospheric backdrop
        if (imageModel != null) {
            val referer = if (novel.coverUrl != null && !novel.coverUrl.startsWith("/")) {
                try {
                    val uri = android.net.Uri.parse(novel.coverUrl)
                    "${uri.scheme}://${uri.host}"
                } catch (_: Exception) { null }
            } else null

            android.util.Log.d("NovelDetail", "Backdrop: coverUrl=${novel.coverUrl}, referer=$referer, imageModel=$imageModel")

            val backdropRequest = ImageRequest.Builder(LocalContext.current)
                .data(imageModel)
                .crossfade(400)
                .listener(
                    onStart = { android.util.Log.d("NovelDetail", "Backdrop Coil START: ${novel.coverUrl}") },
                    onSuccess = { _, _ -> android.util.Log.d("NovelDetail", "Backdrop Coil SUCCESS: ${novel.coverUrl}") },
                    onError = { _, result -> android.util.Log.e("NovelDetail", "Backdrop Coil ERROR: ${novel.coverUrl} — ${result.throwable}") }
                )
            if (referer != null) {
                backdropRequest.addHeader("Referer", referer)
            }
            AsyncImage(
                model = backdropRequest.build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.35f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .blur(25.dp)
            )
        } else {
            // Gradient fallback when no cover exists
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                surfaceColor
                            )
                        )
                    )
            )
        }

        // Layer 2: Gradient scrim — fades backdrop into surface color
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            surfaceColor.copy(alpha = 0.6f),
                            surfaceColor
                        ),
                        startY = 0f,
                        endY = Float.POSITIVE_INFINITY
                    )
                )
        )

        // Layer 3: Actual content — cover + info overlaid on backdrop
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 60.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                // Cover image with rounded corners and shadow
                NovelCover(
                    coverUrl = novel.coverUrl,
                    width = 110.dp,
                    height = 160.dp
                )

                Spacer(modifier = Modifier.width(16.dp))

                // Title, author, status badges
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 24.dp)
                ) {
                    Text(
                        text = novel.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = novel.author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Status badges
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isLocalNovel) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "Local",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }

                        Surface(
                            color = if (novel.status.equals("Completed", ignoreCase = true))
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = novel.status,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        // Chapter count badge
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "${novel.chapters.size} ch",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Reading time estimate badge — shows "~12 hrs" based on user WPM
                        if (estimatedTimeToFinish != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = estimatedTimeToFinish,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Action buttons ──────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (!isLocalNovel && AppConfig.ONLINE_SOURCES_ENABLED) {
                    OutlinedButton(
                        onClick = onToggleLibrary,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (isInLibrary)
                                Icons.Default.CheckCircle
                            else
                                Icons.Default.LibraryAdd,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isInLibrary) "Added" else "Add")
                    }
                }

                Button(
                    onClick = onStartReading,
                    enabled = novel.chapters.isNotEmpty(),
                    modifier = if (isLocalNovel || !AppConfig.ONLINE_SOURCES_ENABLED)
                        Modifier.fillMaxWidth() else Modifier.weight(1f)
                ) {
                    Text("Read")
                }
            }

            // Download button — only for online novels
            if (!isLocalNovel && AppConfig.ONLINE_SOURCES_ENABLED) {
                Spacer(modifier = Modifier.height(8.dp))

                val isDownloading = downloadState?.novelId == novel.id && downloadState.isDownloading
                val downloadedCount = novel.chapters.count { it.isDownloaded }
                val totalCount = novel.chapters.size
                val allDownloaded = downloadedCount == totalCount && totalCount > 0

                OutlinedButton(
                    onClick = onDownloadAll,
                    enabled = !isDownloading && !allDownloaded && novel.chapters.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Downloading ${downloadState?.downloadedChapters}/${downloadState?.totalChapters}")
                    } else if (allDownloaded) {
                        Icon(
                            imageVector = Icons.Default.DownloadDone,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("All Downloaded")
                    } else if (downloadedCount > 0) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Download Remaining (${totalCount - downloadedCount})")
                    } else {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Download All ($totalCount)")
                    }
                }
            }
        }
    }
}

@Composable
private fun NovelDescription(description: String) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = "Synopsis",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = description.ifEmpty { "No description available." },
            style = MaterialTheme.typography.bodyMedium
        )
    }
    HorizontalDivider()
}

@Composable
private fun AudioVoicePicker(
    availableVoices: List<com.abhinavxt.novelreader.data.tts.VoiceInfo>,
    currentVoice: com.abhinavxt.novelreader.data.tts.VoiceInfo?,
    onVoiceSelected: (com.abhinavxt.novelreader.data.tts.VoiceInfo) -> Unit,
    onShowModelDownload: () -> Unit
) {
    var showVoiceDialog by remember { mutableStateOf(false) }

    // Show current voice name in chip
    val displayVoiceName = remember(currentVoice) {
        currentVoice?.displayName ?: "Select voice"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.GraphicEq,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Audio voice:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.width(8.dp))

        Surface(
            onClick = { showVoiceDialog = true },
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.small
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayVoiceName,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Change voice",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }

    if (showVoiceDialog) {
        VoiceSelectorDialog(
            voices = availableVoices,
            currentVoice = currentVoice,
            onVoiceSelected = onVoiceSelected,
            onDismiss = { showVoiceDialog = false },
            onShowModelDownload = onShowModelDownload
        )
    }
}

@Composable
private fun VoiceSelectorDialog(
    voices: List<com.abhinavxt.novelreader.data.tts.VoiceInfo>,
    currentVoice: com.abhinavxt.novelreader.data.tts.VoiceInfo?,
    onVoiceSelected: (com.abhinavxt.novelreader.data.tts.VoiceInfo) -> Unit,
    onDismiss: () -> Unit,
    onShowModelDownload: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Audio Voice") },
        text = {
            if (voices.isEmpty()) {
                Text("No voices available. Check device TTS settings or download neural voices from the reader screen.")
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                ) {
                    val groupedVoices = voices.groupBy { it.engineId }

                    groupedVoices.forEach { (engine, engineVoices) ->
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
                        ) { voice ->
                            Surface(
                                onClick = {
                                    onVoiceSelected(voice)
                                    onDismiss()
                                },
                                color = if (voice.id == currentVoice?.id)
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
                                    Text(
                                        text = voice.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (voice.id == currentVoice?.id) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Download neural voices button
                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        TextButton(
                            onClick = {
                                onDismiss()
                                onShowModelDownload()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Get Neural Voices (Piper, Kokoro\u2026)")
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
private fun ChapterListItem(
    chapter: Chapter,
    isDownloading: Boolean,
    isExporting: Boolean,
    isAudioExported: Boolean,
    exportState: com.abhinavxt.novelreader.data.tts.AudioExporter.ExportState,
    isLocalNovel: Boolean,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onExportAudio: () -> Unit,
    onCancelExport: () -> Unit
) {
    // Get export progress for this specific chapter
    val exportProgress = if (isExporting && exportState is com.abhinavxt.novelreader.data.tts.AudioExporter.ExportState.Exporting
        && exportState.chapterId == chapter.id) {
        exportState.progress
    } else null

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Chapter number badge
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = "${chapter.number}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Chapter title
            Text(
                text = chapter.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Audio export button
            if (isExporting) {
                // Show progress or cancel
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(32.dp)
                ) {
                    CircularProgressIndicator(
                        progress = { exportProgress ?: 0f },
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    // Tap to cancel
                    IconButton(
                        onClick = onCancelExport,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel export",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            } else if (isAudioExported) {
                // Already exported — show completed icon
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = "Audio exported",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.tertiary
                )
            } else {
                IconButton(
                    onClick = onExportAudio,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = "Export as audio",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Download status / button — only when online sources enabled
            if (!isLocalNovel && AppConfig.ONLINE_SOURCES_ENABLED) {
                when {
                    isDownloading -> {
                        // Downloading spinner
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    chapter.isDownloaded -> {
                        // Downloaded indicator
                        Icon(
                            imageVector = Icons.Default.DownloadDone,
                            contentDescription = "Downloaded",
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    else -> {
                        // Download button
                        IconButton(
                            onClick = onDownload,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Download chapter",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarkListItem(
    bookmark: BookmarkEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onEditNote: (String?) -> Unit
) {
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Top row: chapter info + action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Chapter badge
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = "Ch. ${bookmark.chapterNumber}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Chapter title
                Text(
                    text = bookmark.chapterTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Edit note button
                IconButton(
                    onClick = { showEditDialog = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit note",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }

                // Delete button
                IconButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete bookmark",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Text snippet preview — helps users recognize which passage they bookmarked
            Text(
                text = "\"${bookmark.textSnippet}\"",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Show the user's note if present
            if (!bookmark.note.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "\uD83D\uDCDD ${bookmark.note}",  // 📝 emoji
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Timestamp showing when the bookmark was created
            Text(
                text = formatBookmarkDate(bookmark.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }

    // Edit note dialog
    if (showEditDialog) {
        EditBookmarkNoteDialog(
            currentNote = bookmark.note,
            onDismiss = { showEditDialog = false },
            onSave = { note ->
                onEditNote(note)
                showEditDialog = false
            }
        )
    }

    // Delete confirmation dialog
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Bookmark") },
            text = { Text("Remove this bookmark from Ch. ${bookmark.chapterNumber}?") },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete()
                        showDeleteConfirm = false
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun EditBookmarkNoteDialog(
    currentNote: String?,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit
) {
    var noteText by remember { mutableStateOf(currentNote ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bookmark Note") },
        text = {
            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Add a note about this passage...") },
                maxLines = 4
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(noteText.ifBlank { null })
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Format timestamp into a readable date string.
 */
private fun formatBookmarkDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

@Composable
private fun DownloadRangeDialog(
    totalChapters: Int,
    onConfirm: (from: Int, to: Int) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Download Chapters",
    confirmLabel: String = "Download",
    description: String = "Download a range of chapters (1–$totalChapters). Already downloaded chapters are skipped."
) {
    var fromText by remember { mutableStateOf("1") }
    var toText by remember { mutableStateOf(totalChapters.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = fromText,
                        onValueChange = { fromText = it.filter { c -> c.isDigit() } },
                        label = { Text("From") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Text("to")
                    OutlinedTextField(
                        value = toText,
                        onValueChange = { toText = it.filter { c -> c.isDigit() } },
                        label = { Text("To") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                val from = (fromText.toIntOrNull() ?: 1).coerceIn(1, totalChapters)
                val to = (toText.toIntOrNull() ?: totalChapters).coerceIn(from, totalChapters)
                val count = to - from + 1
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "$count chapter${if (count != 1) "s" else ""} selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val from = (fromText.toIntOrNull() ?: 1).coerceIn(1, totalChapters)
                    val to = (toText.toIntOrNull() ?: totalChapters).coerceIn(from, totalChapters)
                    onConfirm(from, to)
                }
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}