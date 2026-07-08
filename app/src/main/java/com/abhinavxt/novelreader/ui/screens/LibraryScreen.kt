package com.abhinavxt.novelreader.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.abhinavxt.novelreader.util.AnnotationExporter
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.abhinavxt.novelreader.data.LibraryViewMode
import com.abhinavxt.novelreader.data.database.CategoryEntity
import com.abhinavxt.novelreader.data.NovelRepository
import com.abhinavxt.novelreader.data.ThemePreferences
import com.abhinavxt.novelreader.data.model.Novel
import com.abhinavxt.novelreader.ui.components.NovelCover
import com.abhinavxt.novelreader.ui.components.novelCoverShared
import com.abhinavxt.novelreader.ui.components.OfflineBanner
import com.abhinavxt.novelreader.ui.viewmodel.ImportState
import com.abhinavxt.novelreader.ui.viewmodel.LibraryFilter
import com.abhinavxt.novelreader.ui.viewmodel.LibrarySort
import com.abhinavxt.novelreader.ui.viewmodel.LibraryViewModel
import com.abhinavxt.novelreader.util.NetworkMonitor
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    repository: NovelRepository,
    themePreferences: ThemePreferences,
    onNovelClick: (String) -> Unit,
    networkMonitor: NetworkMonitor,
    viewModel: LibraryViewModel = viewModel(
        factory = LibraryViewModel.provideFactory(repository, LocalContext.current)
    )
) {
    val novels by viewModel.libraryNovels.collectAsState()
    val importState by viewModel.importState.collectAsState()
    val currentFilter by viewModel.currentFilter.collectAsState()
    val currentSort by viewModel.currentSort.collectAsState()
    val novelsWithUpdates by viewModel.novelsWithUpdates.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val viewMode by themePreferences.libraryViewMode.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val selectedCategoryId by viewModel.selectedCategoryId.collectAsState()
    val novelCategoryMap by viewModel.novelCategoryMap.collectAsState()

    // ── Screen-level dialog state (Phase 6) ──────────────────────
    // Long-pressing a novel (list or grid) opens the options dialog;
    // from there the user can edit categories or remove the novel.
    var optionsNovel by remember { mutableStateOf<Novel?>(null) }
    var categoriesNovel by remember { mutableStateOf<Novel?>(null) }
    var removeNovel by remember { mutableStateOf<Novel?>(null) }
    var showManageCategories by remember { mutableStateOf(false) }

    val exportScope = rememberCoroutineScope()
    val exportContext = LocalContext.current

    optionsNovel?.let { novel ->
        NovelOptionsDialog(
            novel = novel,
            onEditCategories = {
                optionsNovel = null
                categoriesNovel = novel
            },
            onExportAnnotations = {
                optionsNovel = null
                exportScope.launch {
                    val result = AnnotationExporter.exportAndShare(
                        context = exportContext,
                        repository = repository,
                        novelId = novel.id,
                        novelTitle = novel.title
                    )
                    if (result is AnnotationExporter.ExportResult.Empty) {
                        Toast.makeText(
                            exportContext,
                            "No highlights or notes in \"${novel.title}\" yet",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else if (result is AnnotationExporter.ExportResult.Error) {
                        Toast.makeText(exportContext, result.message, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onRemove = {
                optionsNovel = null
                removeNovel = novel
            },
            onDismiss = { optionsNovel = null }
        )
    }

    categoriesNovel?.let { novel ->
        CategoryAssignDialog(
            novel = novel,
            categories = categories,
            initialSelected = novelCategoryMap[novel.id] ?: emptySet(),
            onSave = { ids ->
                viewModel.setNovelCategories(novel.id, ids)
                categoriesNovel = null
            },
            onManageCategories = {
                categoriesNovel = null
                showManageCategories = true
            },
            onDismiss = { categoriesNovel = null }
        )
    }

    removeNovel?.let { novel ->
        AlertDialog(
            onDismissRequest = { removeNovel = null },
            title = { Text("Remove from library?") },
            text = { Text("\"${novel.title}\" will be removed from your library.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeFromLibrary(novel.id)
                        removeNovel = null
                    }
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { removeNovel = null }) { Text("Cancel") }
            }
        )
    }

    if (showManageCategories) {
        ManageCategoriesDialog(
            categories = categories,
            onCreate = { name -> viewModel.createCategory(name) },
            onDelete = { id -> viewModel.deleteCategory(id) },
            onDismiss = { showManageCategories = false }
        )
    }

    // File picker launcher for EPUB files
    val epubPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importEpub(it) }
    }

    // Snackbar for import results
    val snackbarHostState = remember { SnackbarHostState() }

    // Refresh downloads when screen is shown
    LaunchedEffect(Unit) {
        viewModel.refreshDownloads()
    }

    LaunchedEffect(importState) {
        when (val state = importState) {
            is ImportState.Success -> {
                snackbarHostState.showSnackbar(
                    message = "Imported \"${state.title}\" (${state.chapterCount} chapters)",
                    duration = SnackbarDuration.Short
                )
                viewModel.clearImportState()
            }
            is ImportState.Error -> {
                snackbarHostState.showSnackbar(
                    message = state.message,
                    duration = SnackbarDuration.Long
                )
                viewModel.clearImportState()
            }
            else -> {}
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    epubPickerLauncher.launch(arrayOf("application/epub+zip"))
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Import EPUB"
                )
            }
        }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // ── Outer column: NO horizontal padding, so OfflineBanner can
            //    span full-width edge-to-edge. Holds two children: the
            //    banner, and the inner padded content column. ──
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // ★ Offline banner — slides in/out with network state,
                //   always sits at the very top of the Library content.
                OfflineBanner(monitor = networkMonitor)

                // ── Inner column: the original padded content block,
                //    unchanged from before. Using .weight(1f) so the
                //    LazyColumn inside can still fill remaining vertical
                //    space when the banner is visible OR hidden. ──
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                ) {
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "My Library",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        IconButton(
                            onClick = {
                                themePreferences.setLibraryViewMode(
                                    if (viewMode == LibraryViewMode.LIST) LibraryViewMode.GRID
                                    else LibraryViewMode.LIST
                                )
                            }
                        ) {
                            Icon(
                                // Show the mode you'd SWITCH TO, not the current one
                                imageVector = if (viewMode == LibraryViewMode.LIST) {
                                    Icons.Default.GridView
                                } else {
                                    Icons.AutoMirrored.Filled.ViewList
                                },
                                contentDescription = if (viewMode == LibraryViewMode.LIST) {
                                    "Switch to grid view"
                                } else {
                                    "Switch to list view"
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // ── In-library search ────────────────────────
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.onSearchQueryChange(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search your library...") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search"
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Clear search"
                                    )
                                }
                            }
                        },
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // ── Category chips (Phase 6) ─────────────────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (categories.isNotEmpty()) {
                            FilterChip(
                                selected = selectedCategoryId == null,
                                onClick = { viewModel.setCategoryFilter(null) },
                                label = { Text("All") }
                            )
                            categories.forEach { category ->
                                FilterChip(
                                    selected = selectedCategoryId == category.id,
                                    onClick = {
                                        // Tap the selected chip again to
                                        // deselect back to All
                                        viewModel.setCategoryFilter(
                                            if (selectedCategoryId == category.id) null
                                            else category.id
                                        )
                                    },
                                    label = { Text(category.name) }
                                )
                            }
                        }
                        AssistChip(
                            onClick = { showManageCategories = true },
                            label = { Text(if (categories.isEmpty()) "Add categories" else "Edit") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                                )
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Filter Chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = currentFilter == LibraryFilter.ALL,
                            onClick = { viewModel.setFilter(LibraryFilter.ALL) },
                            label = { Text("All") },
                            leadingIcon = if (currentFilter == LibraryFilter.ALL) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Book,
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize)
                                    )
                                }
                            } else null
                        )

                        FilterChip(
                            selected = currentFilter == LibraryFilter.DOWNLOADED,
                            onClick = { viewModel.setFilter(LibraryFilter.DOWNLOADED) },
                            label = { Text("Downloaded") },
                            leadingIcon = if (currentFilter == LibraryFilter.DOWNLOADED) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize)
                                    )
                                }
                            } else null
                        )

                        FilterChip(
                            selected = currentFilter == LibraryFilter.READING,
                            onClick = { viewModel.setFilter(LibraryFilter.READING) },
                            label = { Text("Reading") },
                            leadingIcon = if (currentFilter == LibraryFilter.READING) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.MenuBook,
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize)
                                    )
                                }
                            } else null
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Sort row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Sort:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LibrarySort.values().forEach { sort ->
                            FilterChip(
                                selected = currentSort == sort,
                                onClick = { viewModel.setSort(sort) },
                                label = {
                                    Text(
                                        sort.displayName,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                modifier = Modifier.height(28.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (novels.isEmpty()) {
                        // Empty state — varies by search/filter
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            if (searchQuery.isNotBlank()) {
                                EmptyFilterState(
                                    icon = Icons.Default.Search,
                                    title = "No matches",
                                    subtitle = "Nothing in your library matches \"${searchQuery.trim()}\""
                                )
                            } else when (currentFilter) {
                                LibraryFilter.ALL -> {
                                    EmptyLibraryState(
                                        onImportClick = {
                                            epubPickerLauncher.launch(
                                                arrayOf("application/epub+zip")
                                            )
                                        }
                                    )
                                }
                                LibraryFilter.DOWNLOADED -> {
                                    EmptyFilterState(
                                        icon = Icons.Default.Download,
                                        title = "No downloaded novels",
                                        subtitle = "Download chapters from a novel to read offline"
                                    )
                                }
                                LibraryFilter.READING -> {
                                    EmptyFilterState(
                                        icon = Icons.Default.MenuBook,
                                        title = "No novels in progress",
                                        subtitle = "Start reading a novel to see it here"
                                    )
                                }
                            }
                        }
                    } else if (viewMode == LibraryViewMode.GRID) {
                        // ── Grid view ────────────────────────────
                        // Covers-first browsing. No inline delete button;
                        // long-press a cover to remove (confirm dialog in
                        // LibraryGridItem).
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 100.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(vertical = 8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            gridItems(
                                items = novels,
                                key = { it.id }
                            ) { novel ->
                                LibraryGridItem(
                                    novel = novel,
                                    hasUpdate = novel.id in novelsWithUpdates,
                                    onClick = {
                                        viewModel.clearUpdateBadge(novel.id)
                                        onNovelClick(novel.id)
                                    },
                                    onLongPress = { optionsNovel = novel }
                                )
                            }

                            // Bottom spacing for FAB — spans the full row
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Spacer(modifier = Modifier.height(72.dp))
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(
                                items = novels,
                                key = { it.id }
                            ) { novel ->
                                LibraryNovelItem(
                                    novel = novel,
                                    hasUpdate = novel.id in novelsWithUpdates,
                                    onClick = {
                                        viewModel.clearUpdateBadge(novel.id)
                                        onNovelClick(novel.id)
                                    },
                                    onLongPress = { optionsNovel = novel },
                                    onRemove = { removeNovel = novel }
                                )
                            }

                            // Bottom spacing for FAB
                            item {
                                Spacer(modifier = Modifier.height(72.dp))
                            }
                        }
                    }
                }
            }

            // Loading overlay when importing — sits on top of everything
            // in the PullToRefreshBox, including the banner.
            if (importState is ImportState.Importing) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Importing EPUB...",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibraryState(
    onImportClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Book,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Your library is empty",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Browse novels online or import EPUB files",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedButton(onClick = onImportClick) {
            Icon(
                imageVector = Icons.Default.FileOpen,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Import EPUB")
        }
    }
}

@Composable
private fun EmptyFilterState(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

// ── Grid cell: cover + title, update badge ──────────────────────
// Long-press opens the screen-level options dialog (edit categories /
// remove) — the dialog itself is hoisted so list and grid share it.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryGridItem(
    novel: Novel,
    hasUpdate: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    Column(
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongPress
        ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            NovelCover(
                coverUrl = novel.coverUrl,
                modifier = Modifier.novelCoverShared(novel.id),
                width = 100.dp,
                height = 140.dp
            )
            if (hasUpdate) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .align(Alignment.TopEnd)
                        .background(
                            color = MaterialTheme.colorScheme.error,
                            shape = androidx.compose.foundation.shape.CircleShape
                        )
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = novel.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryNovelItem(
    novel: Novel,
    hasUpdate: Boolean = false,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
    onRemove: () -> Unit
) {
    // Handle both URL and local file path for cover
    val imageModel = remember(novel.coverUrl) {
        when {
            novel.coverUrl == null -> null
            novel.coverUrl.startsWith("/") -> File(novel.coverUrl)  // Local file
            else -> novel.coverUrl                                  // URL
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Cover image or placeholder with update badge
            Box(modifier = Modifier.novelCoverShared(novel.id)) {
                if (imageModel != null) {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = "Cover of ${novel.title}",
                        modifier = Modifier.size(48.dp, 64.dp),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Surface(
                        modifier = Modifier.size(48.dp, 64.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Book,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Update badge
                if (hasUpdate) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .align(Alignment.TopEnd)
                            .background(
                                color = MaterialTheme.colorScheme.error,
                                shape = androidx.compose.foundation.shape.CircleShape
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Novel info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = novel.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = novel.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Show source with "Local" badge for imported EPUBs
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (novel.source == "local") {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                text = "Local",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    } else {
                        Text(
                            text = novel.source,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            // Remove button
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Remove from library",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
// ─────────────────────────────────────────────────────────────────
// Phase 6: category dialogs
// ─────────────────────────────────────────────────────────────────

/**
 * Long-press options for a novel: edit categories or remove.
 */
@Composable
private fun NovelOptionsDialog(
    novel: Novel,
    onEditCategories: () -> Unit,
    onExportAnnotations: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = novel.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column {
                TextButton(
                    onClick = onEditCategories,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Edit categories") }
                TextButton(
                    onClick = onExportAnnotations,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Export highlights & notes") }
                TextButton(
                    onClick = onRemove,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Remove from library",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Checkbox picker assigning a novel to categories. Selection is local
 * until Save so cancel discards cleanly.
 */
@Composable
private fun CategoryAssignDialog(
    novel: Novel,
    categories: List<CategoryEntity>,
    initialSelected: Set<Long>,
    onSave: (Set<Long>) -> Unit,
    onManageCategories: () -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember(novel.id) { mutableStateOf(initialSelected) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set categories") },
        text = {
            Column {
                if (categories.isEmpty()) {
                    Text(
                        text = "No categories yet. Create one first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    categories.forEach { category ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (category.id in selected) {
                                        selected - category.id
                                    } else {
                                        selected + category.id
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = category.id in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + category.id
                                    else selected - category.id
                                }
                            )
                            Text(
                                text = category.name,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
                TextButton(onClick = onManageCategories) {
                    Text("Manage categories…")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(selected) },
                enabled = categories.isNotEmpty()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Create and delete categories. Deleting a category removes its
 * assignments but never touches the novels themselves.
 */
@Composable
private fun ManageCategoriesDialog(
    categories: List<CategoryEntity>,
    onCreate: (String) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Categories") },
        text = {
            Column {
                categories.forEach { category ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = category.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(onClick = { onDelete(category.id) }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete ${category.name}",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                if (categories.isEmpty()) {
                    Text(
                        text = "No categories yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("New category") },
                        singleLine = true
                    )
                    TextButton(
                        onClick = {
                            onCreate(newName)
                            newName = ""
                        },
                        enabled = newName.isNotBlank()
                    ) { Text("Add") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}
