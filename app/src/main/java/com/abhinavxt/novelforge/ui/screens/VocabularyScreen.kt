package com.abhinavxt.novelforge.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abhinavxt.novelforge.data.NovelRepository
import com.abhinavxt.novelforge.data.database.VocabularyEntity
import kotlinx.coroutines.launch

/** Words saved from the Define sheet, newest first, and a flashcard review of the ones due. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabularyScreen(repository: NovelRepository, onBackClick: () -> Unit) {
    val words by remember { repository.observeVocabulary() }.collectAsState(initial = null)
    // Ids still to review this session, front first; null when not reviewing. Saveable so rotation keeps the place.
    var queue by rememberSaveable { mutableStateOf<List<Long>?>(null) }
    // Counts answers, so each card (even a missed one shown again straight away) starts hidden.
    var turn by rememberSaveable { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val due = words.orEmpty().filter { it.dueAt <= System.currentTimeMillis() }.sortedBy { it.dueAt }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (queue != null) "Review" else "Vocabulary") },
                navigationIcon = {
                    IconButton(onClick = { if (queue != null) queue = null else onBackClick() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Go back")
                    }
                },
                actions = {
                    if (queue == null && due.isNotEmpty()) {
                        TextButton(onClick = { queue = due.map { it.id } }) { Text("Review (${due.size})") }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        val list = words
        val reviewing = queue
        when {
            list == null -> Unit
            reviewing != null -> ReviewPane(
                card = reviewing.firstOrNull()?.let { id -> list.firstOrNull { it.id == id } },
                left = reviewing.size,
                turn = turn,
                modifier = Modifier.fillMaxSize().padding(padding),
                onAnswer = { card, knewIt ->
                    // Missed cards come round again at the end of this session.
                    queue = reviewing.drop(1) + if (knewIt) emptyList() else listOf(card.id)
                    turn++
                    scope.launch { repository.reviewWord(card, knewIt) }
                },
                onSkipGone = { queue = reviewing.drop(1) },
                onDone = { queue = null }
            )
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "No words yet. Select a word while reading, tap Define, then Save word.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp)
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(list, key = { it.id }) { word ->
                    WordCard(word, onDelete = {
                        scope.launch {
                            repository.deleteWord(word.id)
                            val undo = snackbar.showSnackbar("Removed “${word.word}”", actionLabel = "Undo")
                            if (undo == SnackbarResult.ActionPerformed) repository.insertVocabularyForRestore(listOf(word))
                        }
                    })
                }
            }
        }
    }
}

@Composable
private fun WordCard(word: VocabularyEntity, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, top = 14.dp, bottom = 14.dp)) {
            Column(Modifier.weight(1f)) {
                Text(word.word, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(word.definition, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                word.sentence?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("“$it”", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                }
                Source(word)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Remove ${word.word}")
            }
        }
    }
}

@Composable
private fun Source(word: VocabularyEntity) {
    val where = listOfNotNull(word.novelTitle?.takeIf { it.isNotBlank() }, word.chapterNumber?.let { "Ch. $it" })
    if (where.isEmpty()) return
    Spacer(Modifier.height(6.dp))
    Text(
        where.joinToString(" · "),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** One flashcard: the word and its sentence; the meaning on request; then Again or Got it. */
@Composable
private fun ReviewPane(
    card: VocabularyEntity?,
    left: Int,
    turn: Int,
    modifier: Modifier,
    onAnswer: (VocabularyEntity, Boolean) -> Unit,
    onSkipGone: () -> Unit,
    onDone: () -> Unit
) {
    if (card == null && left > 0) {
        // Deleted elsewhere mid-review.
        LaunchedEffect(left) { onSkipGone() }
        return
    }
    var revealed by rememberSaveable(turn) { mutableStateOf(false) }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (card == null) {
            Text("All caught up", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Words you knew come back in a few days; the gap grows each time you know them.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onDone) { Text("Back to the list") }
            return@Column
        }
        Text("$left left", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Text(card.word, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        card.sentence?.let {
            Spacer(Modifier.height(12.dp))
            Text("“$it”", fontStyle = FontStyle.Italic, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(24.dp))
        if (!revealed) {
            Button(onClick = { revealed = true }) { Text("Show meaning") }
        } else {
            Text(card.definition, textAlign = TextAlign.Center)
            Source(card)
            Spacer(Modifier.height(24.dp))
            Row {
                OutlinedButton(onClick = { onAnswer(card, false) }) { Text("Again") }
                Spacer(Modifier.width(16.dp))
                Button(onClick = { onAnswer(card, true) }) { Text("Got it") }
            }
        }
    }
}
