package com.abhinavxt.novelreader.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.abhinavxt.novelreader.data.NovelRepository
import com.abhinavxt.novelreader.data.model.NovelPreview
import com.abhinavxt.novelreader.ui.components.OfflineBanner
import com.abhinavxt.novelreader.ui.viewmodel.SearchUiState
import com.abhinavxt.novelreader.ui.viewmodel.SearchViewModel
import com.abhinavxt.novelreader.util.NetworkMonitor

@Composable
fun SearchScreen(
    repository: NovelRepository,
    onNovelClick: (NovelPreview) -> Unit,
    networkMonitor: NetworkMonitor,
    viewModel: SearchViewModel = viewModel(
        factory = SearchViewModel.provideFactory(repository)
    )
) {
    val uiState by viewModel.uiState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val popularNovels by viewModel.popularNovels.collectAsState()
    val selectedSource by viewModel.selectedSource.collectAsState()
    val allSourcesMode by viewModel.allSourcesMode.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current

    // ── Outer column ──
    // No horizontal padding so the OfflineBanner spans full-width.
    // The original 16dp horizontal padding moves to the inner column
    // that wraps the search/results content.
    Column(modifier = Modifier.fillMaxSize()) {
        // ★ Offline banner — full width, slides in/out with network state.
        OfflineBanner(monitor = networkMonitor)

        // Inner column: original content with the horizontal + vertical
        // padding that used to be on the root Column.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Source selection chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Global search across every source (Phase 7)
                item {
                    FilterChip(
                        selected = allSourcesMode,
                        onClick = { viewModel.selectAllSources() },
                        label = { Text("All sources") }
                    )
                }
                items(viewModel.availableSources) { source ->
                    FilterChip(
                        selected = !allSourcesMode && selectedSource.id == source.id,
                        onClick = { viewModel.selectSource(source) },
                        label = { Text(source.name) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.onSearchQueryChange(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        if (allSourcesMode) "Search all sources..."
                        else "Search on ${selectedSource.name}...",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
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
                                contentDescription = "Clear"
                            )
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        viewModel.searchNow()
                        keyboardController?.hide()
                    }
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Content based on state
            when (val state = uiState) {
                is SearchUiState.Initial -> {
                    if (allSourcesMode) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "Search across all ${viewModel.availableSources.size} sources",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Results are grouped by source",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    } else if (popularNovels.isNotEmpty()) {
                        Text(
                            text = "Popular on ${selectedSource.name}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        NovelList(
                            novels = popularNovels,
                            onNovelClick = onNovelClick
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Loading popular novels...")
                            }
                        }
                    }
                }

                is SearchUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is SearchUiState.Success -> {
                    if (state.novels.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "No novels found",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Try a different search term",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "${state.novels.size} results",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        NovelList(
                            novels = state.novels,
                            onNovelClick = onNovelClick
                        )
                    }
                }

                is SearchUiState.SuccessGrouped -> {
                    if (state.groups.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "No novels found",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "No source had results for this search",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    } else {
                        GroupedNovelList(
                            groups = state.groups,
                            onNovelClick = onNovelClick
                        )
                    }
                }

                is SearchUiState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
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
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NovelList(
    novels: List<NovelPreview>,
    onNovelClick: (NovelPreview) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = novels.distinctBy { it.id },
            key = { it.id }
        ) { novel ->
            NovelListItem(
                novel = novel,
                onClick = { onNovelClick(novel) }
            )
        }
    }
}

@Composable
private fun NovelListItem(
    novel: NovelPreview,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Cover image or placeholder
            if (novel.coverUrl != null) {
                AsyncImage(
                    model = novel.coverUrl,
                    contentDescription = "Cover of ${novel.title}",
                    modifier = Modifier.size(60.dp, 80.dp)
                )
            } else {
                Box(
                    modifier = Modifier.size(60.dp, 80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Book,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = novel.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = novel.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                // Show source badge
                Text(
                    text = novel.source,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = novel.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Global-search results: per-source header, then that source's novels
 * (Phase 7). One LazyColumn so scroll state spans all groups.
 */
@Composable
private fun GroupedNovelList(
    groups: List<com.abhinavxt.novelreader.ui.viewmodel.SourceResults>,
    onNovelClick: (NovelPreview) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        groups.forEach { group ->
            item(key = "header_${group.sourceId}") {
                Text(
                    text = "${group.sourceName} · ${group.novels.size}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                )
            }
            items(
                items = group.novels.distinctBy { it.id },
                key = { "${group.sourceId}_${it.id}" }
            ) { novel ->
                NovelListItem(
                    novel = novel,
                    onClick = { onNovelClick(novel) }
                )
            }
        }
    }
}
