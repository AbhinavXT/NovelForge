package com.abhinavxt.novelforge.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abhinavxt.novelforge.data.NovelRepository
import com.abhinavxt.novelforge.data.model.Novel
import com.abhinavxt.novelforge.ui.viewmodel.MigrationState
import com.abhinavxt.novelforge.ui.viewmodel.MigrationViewModel

/** Moves a library novel to another source: search, pick, check what will happen, move. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrateSourceDialog(
    novel: Novel,
    repository: NovelRepository,
    onDismiss: () -> Unit
) {
    val vm: MigrationViewModel = viewModel(
        key = "migrate-${novel.id}",
        factory = MigrationViewModel.Factory(repository, novel.id, novel.title)
    )
    val state by vm.state.collectAsState()
    var query by rememberSaveable { mutableStateOf(novel.title) }
    val busy = state is MigrationState.Migrating

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Move to another source") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss, enabled = !busy) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                )
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                when (val s = state) {
                    MigrationState.Searching, is MigrationState.Results -> {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("Search for this book") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        if (s is MigrationState.Results) ResultsList(s, vm::pick) else Centered { CircularProgressIndicator() }
                    }
                    MigrationState.LoadingTarget, MigrationState.Migrating -> Centered { CircularProgressIndicator() }
                    is MigrationState.Preview -> PreviewPanel(novel, s, onBack = { vm.search(query) }, onMove = { vm.migrate(s.target) })
                    is MigrationState.Done -> Centered {
                        Text(
                            buildString {
                                append("Moved. Your place, ${s.result.movedBookmarks} bookmarks and ")
                                append("${s.result.movedHighlights} highlights came along.")
                                if (s.result.bookmarksToNearest > 0) {
                                    append(" ${s.result.bookmarksToNearest} bookmarks were in chapters the new source ")
                                    append("doesn't have, so they now open at the nearest chapter.")
                                }
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onDismiss) { Text("Done") }
                    }
                    is MigrationState.Failed -> Centered {
                        Text(s.message, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(16.dp))
                        TextButton(onClick = { vm.search(query) }) { Text("Back to results") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultsList(
    results: MigrationState.Results,
    onPick: (com.abhinavxt.novelforge.data.model.NovelPreview, String) -> Unit
) {
    if (results.groups.isEmpty()) {
        Centered { Text("No other source has a match. Try a shorter title.") }
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        results.groups.forEach { group ->
            item(key = "h-${group.sourceId}") {
                Text(
                    group.sourceName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
            }
            items(group.novels, key = { "${group.sourceId}-${it.id}" }) { preview ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(preview, group.sourceName) }
                        .padding(vertical = 10.dp)
                ) {
                    Text(preview.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (preview.author.isNotBlank()) {
                        Text(preview.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewPanel(novel: Novel, p: MigrationState.Preview, onBack: () -> Unit, onMove: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(p.target.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("${novel.source} → ${p.sourceName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${p.target.chapters.size} chapters there. ${p.matchedChapters} of your ${p.oldChapters} match by number.")
        if (p.progressFrom != null && p.progressTo != null) {
            Text(
                if (p.progressFrom == p.progressTo) "You'll stay on chapter ${p.progressTo}."
                else "Chapter ${p.progressFrom} isn't there; you'll continue from chapter ${p.progressTo}."
            )
        }
        Text(
            "Bookmarks, highlights, categories, stats and status move with the book. " +
                "Downloaded chapters don't: download them again from the new source. " +
                "The codex will rebuild from the new text.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (p.matchedChapters * 2 < p.oldChapters) {
            Text(
                "Fewer than half the chapters match. This may be a different book or a different numbering.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onBack) { Text("Back") }
            Button(onClick = onMove) { Text("Move") }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}
