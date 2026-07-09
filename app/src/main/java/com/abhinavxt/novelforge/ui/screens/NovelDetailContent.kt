package com.abhinavxt.novelforge.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.abhinavxt.novelforge.data.tts.M4BAudiobookBuilder
import com.abhinavxt.novelforge.AppConfig
import com.abhinavxt.novelforge.data.NovelDownloadState
import com.abhinavxt.novelforge.data.database.BookmarkEntity
import com.abhinavxt.novelforge.data.model.Chapter
import com.abhinavxt.novelforge.data.model.Novel

// ─────────────────────────────────────────────────────────────────
// Split out of the original NovelDetailScreen.kt (Phase 3 refactor).
// Same package, pure move — no behavior change. Declarations used
// across files were promoted private → internal.
// ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NovelDetailContent(
    novel: Novel,
    isInLibrary: Boolean,
    onSetReadingPosition: (Chapter) -> Unit = {},
    isLocalNovel: Boolean,
    downloadState: NovelDownloadState?,
    downloadingChapters: Set<String>,
    exportingChapters: Set<String>,
    audioExportedChapters: Set<String>,
    exportState: com.abhinavxt.novelforge.data.tts.AudioExporter.ExportState,
    bookmarks: List<BookmarkEntity>,
    bookmarkCount: Int,
    highlights: List<com.abhinavxt.novelforge.data.database.HighlightEntity> = emptyList(),
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
    availableVoices: List<com.abhinavxt.novelforge.data.tts.VoiceInfo>,
    currentVoice: com.abhinavxt.novelforge.data.tts.VoiceInfo?,
    onVoiceSelected: (com.abhinavxt.novelforge.data.tts.VoiceInfo) -> Unit,
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


    // ── "Start reading from here" (long-press a chapter) ────────
    var startFromChapter by remember { mutableStateOf<Chapter?>(null) }
    startFromChapter?.let { target ->
        AlertDialog(
            onDismissRequest = { startFromChapter = null },
            title = { Text("Start reading here?") },
            text = {
                Text(
                    "Sets your reading position to Chapter ${target.number}. " +
                            "Continue Reading will pick up from this chapter."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSetReadingPosition(target)
                        startFromChapter = null
                    }
                ) { Text("Set position") }
            },
            dismissButton = {
                TextButton(onClick = { startFromChapter = null }) { Text("Cancel") }
            }
        )
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
                            onLongPress = { startFromChapter = chapter },
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

