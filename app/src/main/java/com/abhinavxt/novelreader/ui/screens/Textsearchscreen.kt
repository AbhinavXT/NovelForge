package com.abhinavxt.novelreader.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abhinavxt.novelreader.data.NovelRepository
import com.abhinavxt.novelreader.data.database.ChapterSearchResult
import com.abhinavxt.novelreader.ui.components.NovelCover
import com.abhinavxt.novelreader.ui.viewmodel.NovelSearchGroup
import com.abhinavxt.novelreader.ui.viewmodel.TextSearchUiState
import com.abhinavxt.novelreader.ui.viewmodel.TextSearchViewModel
import kotlinx.coroutines.launch

/**
 * Library-wide full-text search over downloaded chapter content.
 * Results are grouped by novel; tapping a hit opens the reader at
 * the first paragraph containing the match.
 *
 * Snippets come from FTS4 snippet() with \u0001 / \u0002 wrapped
 * around matched terms — [SnippetText] converts those markers into
 * bold spans.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextSearchScreen(
    repository: NovelRepository,
    onBackClick: () -> Unit,
    onHitClick: (novelId: String, chapterId: String, chapterUrl: String, paragraphIndex: Int) -> Unit,
    viewModel: TextSearchViewModel = viewModel(
        factory = TextSearchViewModel.provideFactory(repository)
    )
) {
    val query by viewModel.query.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    // Pop the keyboard immediately — the whole screen is the field.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search in books") },
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
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.onQueryChange(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .focusRequester(focusRequester),
                placeholder = { Text("Search downloaded chapters…") },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear search"
                            )
                        }
                    }
                },
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            when (val state = uiState) {
                is TextSearchUiState.Idle -> CenteredHint(
                    title = "Search inside your downloads",
                    subtitle = "Find a name, a scene, a line — across every downloaded chapter in your library."
                )

                is TextSearchUiState.Searching -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                is TextSearchUiState.Empty -> CenteredHint(
                    title = "No matches for \"${state.query}\"",
                    subtitle = "Only downloaded chapters are searchable. Download more chapters to widen the net."
                )

                is TextSearchUiState.Success -> ResultList(
                    groups = state.groups,
                    totalHits = state.totalHits,
                    query = state.query,
                    onHitClick = { hit ->
                        scope.launch {
                            val paragraph = viewModel.resolveParagraphIndex(hit.chapterId)
                            onHitClick(hit.novelId, hit.chapterId, hit.chapterUrl, paragraph)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun CenteredHint(title: String, subtitle: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.ManageSearch,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ResultList(
    groups: List<NovelSearchGroup>,
    totalHits: Int,
    query: String,
    onHitClick: (ChapterSearchResult) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "summary") {
            Text(
                text = "$totalHits match${if (totalHits == 1) "" else "es"} in ${groups.size} book${if (groups.size == 1) "" else "s"}" +
                        if (totalHits >= 200) " (showing first 200)" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        groups.forEach { group ->
            item(key = "novel-${group.novelId}") {
                NovelGroupHeader(group)
            }
            items(group.hits, key = { "hit-${it.chapterId}" }) { hit ->
                HitRow(hit = hit, onClick = { onHitClick(hit) })
            }
            item(key = "div-${group.novelId}") {
                HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun NovelGroupHeader(group: NovelSearchGroup) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NovelCover(
            coverUrl = group.coverUrl,
            width = 36.dp,
            height = 50.dp
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = group.novelTitle,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${group.hits.size} match${if (group.hits.size == 1) "" else "es"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HitRow(
    hit: ChapterSearchResult,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = "Ch. ${hit.chapterNumber} · ${hit.chapterTitle}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        SnippetText(hit.snippet)
    }
}

/**
 * Renders an FTS4 snippet, converting the \u0001…\u0002 match
 * markers into bold spans. remember()-ed so the parse runs once per
 * snippet, not per recomposition.
 */
@Composable
private fun SnippetText(snippet: String) {
    val annotated = remember(snippet) { parseSnippet(snippet) }
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis
    )
}

private fun parseSnippet(snippet: String): AnnotatedString = buildAnnotatedString {
    var bold = false
    val plain = StringBuilder()

    fun flush() {
        if (plain.isEmpty()) return
        if (bold) {
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            append(plain.toString())
            pop()
        } else {
            append(plain.toString())
        }
        plain.clear()
    }

    for (ch in snippet) {
        when (ch) {
            '\u0001' -> { flush(); bold = true }
            '\u0002' -> { flush(); bold = false }
            else -> plain.append(ch)
        }
    }
    flush()
}
