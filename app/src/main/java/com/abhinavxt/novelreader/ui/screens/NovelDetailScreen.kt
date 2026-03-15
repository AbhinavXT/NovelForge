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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.abhinavxt.novelreader.AppConfig
import com.abhinavxt.novelreader.data.DownloadManager
import com.abhinavxt.novelreader.ui.components.ModelDownloadDialog
import com.abhinavxt.novelreader.data.NovelDownloadState
import com.abhinavxt.novelreader.data.NovelRepository
import com.abhinavxt.novelreader.data.database.BookmarkEntity
import com.abhinavxt.novelreader.data.model.Chapter
import com.abhinavxt.novelreader.data.model.Novel
import com.abhinavxt.novelreader.ui.viewmodel.NovelDetailUiState
import com.abhinavxt.novelreader.ui.viewmodel.NovelDetailViewModel
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
    onBackClick: () -> Unit,
    onChapterClick: (chapterId: String, chapterUrl: String) -> Unit,
    viewModel: NovelDetailViewModel = viewModel(
        factory = NovelDetailViewModel.provideFactory(
            novelId, novelUrl, repository, ttsManager,
            androidx.compose.ui.platform.LocalContext.current.applicationContext
        )
    )
) {
    val uiState by viewModel.uiState.collectAsState()
    val downloadState by downloadManager.novelDownloadState.collectAsState()
    val downloadingChapters by viewModel.downloadingChapters.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val bookmarkCount by viewModel.bookmarkCount.collectAsState()
    val exportState by viewModel.exportState.collectAsState()
    val exportingChapters by viewModel.exportingChapters.collectAsState()
    val audioExportedChapters by viewModel.audioExportedChapters.collectAsState()
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
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
        when (val state = uiState) {
            is NovelDetailUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
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
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
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
                    downloadingChapters = downloadingChapters,
                    exportingChapters = exportingChapters,
                    audioExportedChapters = audioExportedChapters,
                    exportState = exportState,
                    bookmarks = bookmarks,
                    bookmarkCount = bookmarkCount,
                    onToggleLibrary = { viewModel.toggleLibrary() },
                    onChapterClick = onChapterClick,
                    onDownloadChapter = { chapter -> viewModel.downloadChapter(chapter) },
                    onExportChapterAudio = { chapter -> viewModel.exportChapterAudio(chapter) },
                    onExportAllAudio = { viewModel.exportAllChaptersAudio() },
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
                    modifier = Modifier.padding(paddingValues)
                )
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
    onToggleLibrary: () -> Unit,
    onChapterClick: (chapterId: String, chapterUrl: String) -> Unit,
    onDownloadChapter: (Chapter) -> Unit,
    onExportChapterAudio: (Chapter) -> Unit,
    onExportAllAudio: () -> Unit,
    onCancelExport: () -> Unit,
    availableVoices: List<com.abhinavxt.novelreader.data.tts.VoiceInfo>,
    currentVoice: com.abhinavxt.novelreader.data.tts.VoiceInfo?,
    onVoiceSelected: (com.abhinavxt.novelreader.data.tts.VoiceInfo) -> Unit,
    onDownloadAll: () -> Unit,
    onDeleteBookmark: (Long) -> Unit,
    onUpdateBookmarkNote: (Long, String?) -> Unit,
    onBookmarkClick: (BookmarkEntity) -> Unit,
    onShowModelDownload: () -> Unit,
    currentReadingChapterId: String? = null,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Auto-scroll to current reading chapter on first load
    var hasScrolledToReading by remember { mutableStateOf(false) }
    LaunchedEffect(novel.chapters, currentReadingChapterId) {
        if (!hasScrolledToReading && currentReadingChapterId != null && searchQuery.isBlank()) {
            val chapterIndex = novel.chapters.indexOfFirst { it.id == currentReadingChapterId }
            if (chapterIndex >= 0) {
                // Offset: header, description, tabRow, downloadBadge, voicePicker, exportRow, searchBar = 7
                val scrollTarget = 7 + chapterIndex
                listState.scrollToItem(scrollTarget)
                hasScrolledToReading = true
            }
        }
    }

    // Filter chapters based on search query
    val filteredChapters = remember(novel.chapters, searchQuery) {
        if (searchQuery.isBlank()) {
            novel.chapters
        } else {
            val query = searchQuery.trim()
            val chapterNumber = query.toIntOrNull()

            novel.chapters.filter { chapter ->
                if (chapterNumber != null) {
                    chapter.number == chapterNumber ||
                            chapter.number.toString().contains(query)
                } else {
                    chapter.title.contains(query, ignoreCase = true) ||
                            chapter.number.toString().contains(query)
                }
            }
        }
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
                onToggleLibrary = onToggleLibrary,
                onStartReading = {
                    novel.chapters.firstOrNull()?.let { chapter ->
                        onChapterClick(chapter.id, chapter.url)
                    }
                },
                onDownloadAll = onDownloadAll
            )
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

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(modifier = Modifier.weight(1f))

                        // Show downloaded count badge
                        val downloadedCount = novel.chapters.count { it.isDownloaded }
                        if (downloadedCount > 0 && !isLocalNovel && AppConfig.ONLINE_SOURCES_ENABLED) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    text = "$downloadedCount downloaded",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }

                // Audio voice picker
                item {
                    AudioVoicePicker(
                        availableVoices = availableVoices,
                        currentVoice = currentVoice,
                        onVoiceSelected = onVoiceSelected,
                        onShowModelDownload = onShowModelDownload
                    )
                }

                // Export All as Audio button
                item {
                    val exportedCount = audioExportedChapters.size
                    val totalCount = novel.chapters.size
                    val isAnyExporting = exportingChapters.isNotEmpty()

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (exportedCount > 0) {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    text = "$exportedCount/$totalCount audio exported",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        if (isAnyExporting) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Exporting...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                TextButton(onClick = onCancelExport) {
                                    Text("Cancel")
                                }
                            }
                        } else if (exportedCount < totalCount) {
                            TextButton(onClick = onExportAllAudio) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (exportedCount == 0) "Export All as Audio"
                                    else "Export Remaining (${ totalCount - exportedCount })"
                                )
                            }
                        }
                    }
                }

                // Chapter search bar — sticky so it stays visible while scrolling
                stickyHeader {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        ChapterSearchBar(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            resultCount = filteredChapters.size,
                            totalCount = novel.chapters.size
                        )
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
        placeholder = { Text("Search chapters or enter number...") },
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
    onToggleLibrary: () -> Unit,
    onStartReading: () -> Unit,
    onDownloadAll: () -> Unit
) {
    // Handle both URL and local file path for cover
    val imageModel = remember(novel.coverUrl) {
        when {
            novel.coverUrl == null -> null
            novel.coverUrl.startsWith("/") -> File(novel.coverUrl)
            else -> novel.coverUrl
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // Cover image
        if (imageModel != null) {
            AsyncImage(
                model = imageModel,
                contentDescription = "Cover of ${novel.title}",
                modifier = Modifier.size(120.dp, 160.dp),
                contentScale = ContentScale.Crop
            )
        } else {
            Surface(
                modifier = Modifier.size(120.dp, 160.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Book,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = novel.title,
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = novel.author,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.outline
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Status badge row
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isLocalNovel) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small
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
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = novel.status,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Library and Read buttons
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
                    modifier = if (isLocalNovel || !AppConfig.ONLINE_SOURCES_ENABLED) Modifier.fillMaxWidth() else Modifier.weight(1f)
                ) {
                    Text("Read")
                }
            }

            // Download button - only for online novels when sources enabled
            if (!isLocalNovel && AppConfig.ONLINE_SOURCES_ENABLED) {
                Spacer(modifier = Modifier.height(8.dp))

                val isDownloading = downloadState?.novelId == novel.id && downloadState.isDownloading
                val isThisNovelDownloading = downloadState?.novelId == novel.id

                OutlinedButton(
                    onClick = onDownloadAll,
                    enabled = !isDownloading && novel.chapters.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Downloading ${downloadState?.downloadedChapters}/${downloadState?.totalChapters}")
                    } else if (isThisNovelDownloading && downloadState?.downloadedChapters == downloadState?.totalChapters) {
                        Icon(
                            imageVector = Icons.Default.DownloadDone,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Downloaded")
                    } else {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Download All (${novel.chapters.size})")
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