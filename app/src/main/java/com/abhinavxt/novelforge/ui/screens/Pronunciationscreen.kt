package com.abhinavxt.novelforge.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abhinavxt.novelforge.data.PronunciationIO
import com.abhinavxt.novelforge.data.PronunciationManager
import com.abhinavxt.novelforge.data.database.PronunciationEntry
import com.abhinavxt.novelforge.ui.viewmodel.PronunciationViewModel

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
    val importPreview by viewModel.importPreview.collectAsState()
    val transientMessage by viewModel.transientMessage.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<PronunciationEntry?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // ── SAF launchers ────────────────────────────────────────────────
    //
    // OpenDocument: user picks a file to import. We restrict to JSON
    // mime-types, but file pickers on many OEMs ignore the filter — we
    // fall back to filename suffix + content validation in the parser, so
    // this is purely a UX hint.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        // Null = user cancelled the picker; do nothing.
        uri?.let { viewModel.previewImport(context, it) }
    }

    // CreateDocument: user picks WHERE to save. We pre-fill a filename
    // based on today's date. The mime type string controls what extension
    // the picker will suggest — "application/json" is the most portable.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            // We don't have a "title" UI input yet — use a simple default.
            // Later this can be expanded to ask the user for a title before
            // launching the picker. Kept minimal for this ship.
            viewModel.exportToUri(
                context = context,
                uri = it,
                title = "My Pronunciation Dictionary"
            )
        }
    }

    // ── Transient snackbar messages ─────────────────────────────────
    LaunchedEffect(transientMessage) {
        transientMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearTransientMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Pronunciation Dictionary") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Import — always enabled. User can import into an empty dict.
                    IconButton(
                        onClick = {
                            // Hint the picker toward JSON; some pickers also honor
                            // the second mime ("*/*") as a looser fallback so users
                            // can still pick a .npd.json file even if it's marked
                            // as text/plain by their file manager.
                            importLauncher.launch(
                                arrayOf("application/json", "text/plain", "*/*")
                            )
                        }
                    ) {
                        Icon(
                            Icons.Default.FileUpload,
                            contentDescription = "Import dictionary"
                        )
                    }

                    // Export — disabled if there's nothing to export. Using the
                    // opacity trick via enabled + tint keeps it visible-but-dimmed
                    // so users understand the button exists, just isn't usable yet.
                    IconButton(
                        onClick = {
                            exportLauncher.launch(viewModel.suggestedExportFilename())
                        },
                        enabled = entries.isNotEmpty()
                    ) {
                        Icon(
                            Icons.Default.FileDownload,
                            contentDescription = "Export dictionary"
                        )
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
                    text = "Add words that TTS mispronounces. For example:\n" +
                            "Xiulan → shoo-lan\nQi → chee",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Tap the import icon above to load a shared dictionary.",
                    style = MaterialTheme.typography.bodySmall,
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

    // ── Import preview dialog ───────────────────────────────────────
    //
    // Shown when the user picks a file to import. Two states:
    //   - Ready: show summary + conflict strategy picker + Import button
    //   - Failed: show the error and a Dismiss button
    when (val state = importPreview) {
        is PronunciationViewModel.ImportPreviewState.Ready -> {
            ImportPreviewDialog(
                state = state,
                onConfirm = { strategy -> viewModel.commitImport(strategy) },
                onDismiss = { viewModel.cancelImport() }
            )
        }
        is PronunciationViewModel.ImportPreviewState.Failed -> {
            AlertDialog(
                onDismissRequest = { viewModel.cancelImport() },
                title = { Text("Import failed") },
                text = { Text(state.message) },
                confirmButton = {
                    TextButton(onClick = { viewModel.cancelImport() }) {
                        Text("OK")
                    }
                }
            )
        }
        null -> { /* no preview active */ }
    }
}

// ── NEW: import preview dialog ──────────────────────────────────────
//
// Shows the dictionary metadata, entry count, duplicate count, and lets the
// user pick a conflict-resolution strategy before committing. If there are
// zero duplicates we auto-select SKIP_DUPLICATES and hide the radio buttons
// to cut clicks for the common case.

@Composable
private fun ImportPreviewDialog(
    state: PronunciationViewModel.ImportPreviewState.Ready,
    onConfirm: (PronunciationIO.ConflictStrategy) -> Unit,
    onDismiss: () -> Unit
) {
    val dict = state.dictionary
    val hasDuplicates = state.duplicateCount > 0

    // Default strategy: safe one (skip). User can change it if duplicates exist.
    var strategy by remember {
        mutableStateOf(PronunciationIO.ConflictStrategy.SKIP_DUPLICATES)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Dictionary") },
        text = {
            Column {
                // Title + optional description from the file metadata.
                Text(
                    text = dict.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (dict.description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = dict.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Counts row: total entries + duplicate count.
                Text(
                    text = "${dict.entries.size} entries in file",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (hasDuplicates) {
                    Text(
                        text = "${state.duplicateCount} already in your dictionary",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                // Conflict strategy picker — only shown when there are actual
                // conflicts to resolve. Keeps the dialog simple for the common
                // case where imports are entirely new words.
                if (hasDuplicates) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "For duplicates:",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(Modifier.height(4.dp))

                    StrategyOption(
                        label = "Skip — keep my current entries",
                        selected = strategy ==
                                PronunciationIO.ConflictStrategy.SKIP_DUPLICATES,
                        onSelect = {
                            strategy = PronunciationIO.ConflictStrategy.SKIP_DUPLICATES
                        }
                    )
                    StrategyOption(
                        label = "Replace — use the imported pronunciations",
                        selected = strategy ==
                                PronunciationIO.ConflictStrategy.REPLACE,
                        onSelect = {
                            strategy = PronunciationIO.ConflictStrategy.REPLACE
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(strategy) }) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun StrategyOption(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

// ── Existing composables below (unchanged) ──────────────────────────

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