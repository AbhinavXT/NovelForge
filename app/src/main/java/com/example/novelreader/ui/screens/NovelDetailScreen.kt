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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.novelreader.data.DownloadManager
import com.example.novelreader.data.NovelDownloadState
import com.example.novelreader.data.NovelRepository
import com.example.novelreader.data.model.Chapter
import com.example.novelreader.data.model.Novel
import com.example.novelreader.ui.viewmodel.NovelDetailUiState
import com.example.novelreader.ui.viewmodel.NovelDetailViewModel
import kotlinx.coroutines.launch
import java.io.File

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
    onToggleLibrary: () -> Unit,
    onChapterClick: (chapterId: String, chapterUrl: String) -> Unit,
    onDownloadChapter: (Chapter) -> Unit,
    onDownloadAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }

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

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Chapters (${novel.chapters.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )

                // Show downloaded count
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