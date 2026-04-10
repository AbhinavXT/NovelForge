package com.abhinavxt.novelreader.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abhinavxt.novelreader.data.PronunciationManager
import com.abhinavxt.novelreader.data.database.PronunciationEntry
import com.abhinavxt.novelreader.ui.viewmodel.PronunciationViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PronunciationScreen(
    pronunciationManager: PronunciationManager,
    onBackClick: () -> Unit,
    viewModel: PronunciationViewModel = viewModel(
        factory = PronunciationViewModel.Factory(pronunciationManager)
    )
) {
    val entries by viewModel.entries.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<PronunciationEntry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pronunciation Dictionary") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add entry")
            }
        }
    ) { innerPadding ->
        if (entries.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "No pronunciation entries yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Add words that TTS mispronounces. For example:\nXiulan → shoo-lan\nQi → chee",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(entries, key = { it.id }) { entry ->
                    PronunciationEntryItem(
                        entry = entry,
                        onEdit = { editingEntry = entry },
                        onDelete = { viewModel.deleteEntry(entry.id) }
                    )
                }
            }
        }
    }

    // Add dialog
    if (showAddDialog) {
        PronunciationEntryDialog(
            title = "Add Pronunciation",
            initialWord = "",
            initialReplacement = "",
            onConfirm = { word, replacement ->
                viewModel.addEntry(word, replacement)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }

    // Edit dialog
    editingEntry?.let { entry ->
        PronunciationEntryDialog(
            title = "Edit Pronunciation",
            initialWord = entry.word,
            initialReplacement = entry.replacement,
            onConfirm = { word, replacement ->
                viewModel.updateEntry(entry.id, word, replacement)
                editingEntry = null
            },
            onDismiss = { editingEntry = null }
        )
    }
}

@Composable
private fun PronunciationEntryItem(
    entry: PronunciationEntry,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.word,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "→ ${entry.replacement}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Edit",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Entry") },
            text = { Text("Remove \"${entry.word}\" from the pronunciation dictionary?") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteConfirm = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun PronunciationEntryDialog(
    title: String,
    initialWord: String,
    initialReplacement: String,
    onConfirm: (word: String, replacement: String) -> Unit,
    onDismiss: () -> Unit
) {
    var word by remember { mutableStateOf(initialWord) }
    var replacement by remember { mutableStateOf(initialReplacement) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = word,
                    onValueChange = { word = it },
                    label = { Text("Word (as written)") },
                    placeholder = { Text("e.g. Xiulan") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    label = { Text("Speak as") },
                    placeholder = { Text("e.g. shoo-lan") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(word, replacement) },
                enabled = word.isNotBlank() && replacement.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}