package com.abhinavxt.novelforge.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyListItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.abhinavxt.novelforge.data.model.NovelPreview
import com.abhinavxt.novelforge.data.source.FilterOption
import com.abhinavxt.novelforge.data.source.health.SourceHealthStore
import com.abhinavxt.novelforge.ui.components.SourcePickerSheet
import com.abhinavxt.novelforge.ui.viewmodel.BrowseViewModel

/**
 * Per-source catalog browsing with category / sort / genre filters (where the
 * source supports them — see BrowseSource). Plain sources show their popular
 * list unfiltered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    onBackClick: () -> Unit,
    onNovelClick: (NovelPreview) -> Unit,
    viewModel: BrowseViewModel = viewModel(
        factory = BrowseViewModel.provideFactory(
            LocalContext.current.applicationContext
        )
    )
) {
    val selectedSource by viewModel.selectedSource.collectAsState()
    val filters by viewModel.filters.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val selectedOrderBy by viewModel.selectedOrderBy.collectAsState()
    val selectedTag by viewModel.selectedTag.collectAsState()
    val catalog by viewModel.catalog.collectAsState()
    val pinnedIds by viewModel.pinnedSourceIds.collectAsState()
    val recentIds by viewModel.recentSourceIds.collectAsState()
    val sourceHealth by SourceHealthStore.health.collectAsState()

    var showSourcePicker by rememberSaveable { mutableStateOf(false) }
    // Which filter sheet is open: null / "category" / "orderBy" / "tag"
    var openFilter by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Browse") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // ── Source + filter chip row ─────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                FilterChip(
                    selected = true,
                    onClick = { showSourcePicker = true },
                    label = {
                        Text(
                            text = selectedSource.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    trailingIcon = {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose source")
                    }
                )
                if (filters.categories.isNotEmpty()) {
                    FilterDropChip(
                        placeholder = "Category",
                        options = filters.categories,
                        selectedValue = selectedCategory,
                        onClick = { openFilter = "category" }
                    )
                }
                if (filters.orderBys.isNotEmpty()) {
                    FilterDropChip(
                        placeholder = "Sort",
                        options = filters.orderBys,
                        selectedValue = selectedOrderBy,
                        onClick = { openFilter = "orderBy" }
                    )
                }
                if (filters.tags.isNotEmpty()) {
                    FilterDropChip(
                        placeholder = "Genre",
                        options = filters.tags,
                        selectedValue = selectedTag,
                        onClick = { openFilter = "tag" }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Catalog grid ─────────────────────────────────────
            when {
                catalog.isLoadingInitial -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                catalog.error != null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = catalog.error ?: "Failed to load",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = { viewModel.reload() }) { Text("Retry") }
                    }
                }

                catalog.items.isEmpty() && catalog.endReached -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Nothing here — try different filters",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                else -> {
                    val gridState = rememberLazyGridState()

                    // Infinite scroll: fire when within 6 items of the end.
                    val shouldLoadMore by remember {
                        derivedStateOf {
                            val info = gridState.layoutInfo
                            val lastVisible =
                                info.visibleItemsInfo.lastOrNull()?.index ?: 0
                            lastVisible >= info.totalItemsCount - 6
                        }
                    }
                    LaunchedEffect(shouldLoadMore, catalog.items.size) {
                        if (shouldLoadMore) viewModel.loadMore()
                    }

                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = 105.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(
                            start = 16.dp, end = 16.dp, bottom = 16.dp
                        ),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(catalog.items, key = { it.id }) { novel ->
                            NovelGridCard(novel = novel, onClick = { onNovelClick(novel) })
                        }
                        if (catalog.isLoadingMore) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.height(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSourcePicker) {
        SourcePickerSheet(
            sources = viewModel.availableSources,
            selectedSourceId = selectedSource.id,
            pinnedIds = pinnedIds,
            recentIds = recentIds,
            health = sourceHealth,
            onSelect = { source ->
                viewModel.selectSource(source)
                showSourcePicker = false
            },
            onTogglePin = { viewModel.togglePin(it) },
            onDismiss = { showSourcePicker = false }
        )
    }

    // Generic single-select sheet for whichever filter chip was tapped.
    val filterSheet: Triple<String, List<FilterOption>, String?>? = when (openFilter) {
        "category" -> Triple("Category", filters.categories, selectedCategory)
        "orderBy" -> Triple("Sort", filters.orderBys, selectedOrderBy)
        "tag" -> Triple("Genre", filters.tags, selectedTag)
        else -> null
    }
    if (filterSheet != null) {
        FilterOptionSheet(
            title = filterSheet.first,
            options = filterSheet.second,
            selectedValue = filterSheet.third,
            onSelect = { value ->
                when (openFilter) {
                    "category" -> viewModel.selectCategory(value)
                    "orderBy" -> viewModel.selectOrderBy(value)
                    "tag" -> viewModel.selectTag(value)
                }
                openFilter = null
            },
            onDismiss = { openFilter = null }
        )
    }
}

@Composable
private fun FilterDropChip(
    placeholder: String,
    options: List<FilterOption>,
    selectedValue: String?,
    onClick: () -> Unit,
) {
    val label = options.find { it.value == selectedValue }?.label ?: placeholder
    FilterChip(
        selected = selectedValue != null,
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = {
            Icon(Icons.Default.ArrowDropDown, contentDescription = placeholder)
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterOptionSheet(
    title: String,
    options: List<FilterOption>,
    selectedValue: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            item(key = "__header") {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            item(key = "__default") {
                FilterOptionRow(
                    label = "Default",
                    selected = selectedValue == null,
                    onClick = { onSelect(null) }
                )
            }
            lazyListItems(
                options, key = { it.value }
            ) { option ->
                FilterOptionRow(
                    label = option.label,
                    selected = option.value == selectedValue,
                    onClick = { onSelect(option.value) }
                )
            }
        }
    }
}

@Composable
private fun FilterOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun NovelGridCard(novel: NovelPreview, onClick: () -> Unit) {
    Card(modifier = Modifier.clickable(onClick = onClick)) {
        Column {
            AsyncImage(
                model = novel.coverUrl,
                contentDescription = novel.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
            )
            Text(
                text = novel.title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(6.dp)
            )
        }
    }
}