package com.example.novelreader.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LibraryAdd
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.example.novelreader.data.DownloadManager
import com.example.novelreader.data.NovelDownloadState
import com.example.novelreader.data.NovelRepository
import com.example.novelreader.data.database.BookmarkEntity
import com.example.novelreader.data.model.Chapter
import com.example.novelreader.data.model.Novel
import com.example.novelreader.ui.viewmodel.NovelDetailUiState
import com.example.novelreader.ui.viewmodel.NovelDetailViewModel
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
    onBackClick: () -> Unit,
    onChapterClick: (chapterId: String, chapterUrl: String) -> Unit,
    viewModel: NovelDetailViewModel = viewModel(
        factory = NovelDetailViewModel.provideFactory(novelId, novelUrl, repository)
    )
) {
    val uiState by viewModel.uiState.collectAsState()
    val downloadState by downloadManager.novelDownloadState.collectAsState()
    val downloadingChapters by viewModel.downloadingChapters.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val bookmarkCount by viewModel.bookmarkCount.collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(
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
                    bookmarks = bookmarks,
                    bookmarkCount = bookmarkCount,
                    onToggleLibrary = { viewModel.toggleLibrary() },
                    onChapterClick = onChapterClick,
                    onDownloadChapter = { chapter -> viewModel.downloadChapter(chapter) },
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
                    modifier = Modifier.padding(paddingValues)
                )
            }
        }
    }
}

@Composable
private fun NovelDetailContent(
    novel: Novel,
    isInLibrary: Boolean,
    isLocalNovel: Boolean,
    downloadState: NovelDownloadState?,
    downloadingChapters: Set<String>,
    bookmarks: List<BookmarkEntity>,
    bookmarkCount: Int,
    onToggleLibrary: () -> Unit,
    onChapterClick: (chapterId: String, chapterUrl: String) -> Unit,
    onDownloadChapter: (Chapter) -> Unit,
    onDownloadAll: () -> Unit,
    onDeleteBookmark: (Long) -> Unit,
    onUpdateBookmarkNote: (Long, String?) -> Unit,
    onBookmarkClick: (BookmarkEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by rememberSaveable { mutableStateOf(0) }

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
                        if (downloadedCount > 0 && !isLocalNovel) {
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

                // Chapter search bar
                item {
                    ChapterSearchBar(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        resultCount = filteredChapters.size,
                        totalCount = novel.chapters.size
                    )
                    HorizontalDivider()
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
                            isLocalNovel = isLocalNovel,
                            onClick = { onChapterClick(chapter.id, chapter.url) },
                            onDownload = { onDownloadChapter(chapter) }
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
                if (!isLocalNovel) {
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
                    modifier = if (isLocalNovel) Modifier.fillMaxWidth() else Modifier.weight(1f)
                ) {
                    Text("Read")
                }
            }

            // Download button - only for online novels
            if (!isLocalNovel) {
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
private fun ChapterListItem(
    chapter: Chapter,
    isDownloading: Boolean,
    isLocalNovel: Boolean,
    onClick: () -> Unit,
    onDownload: () -> Unit
) {
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

            // Download status / button
            if (!isLocalNovel) {
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