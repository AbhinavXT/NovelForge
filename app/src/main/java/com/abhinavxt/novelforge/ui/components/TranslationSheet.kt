package com.abhinavxt.novelforge.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abhinavxt.novelforge.data.database.GlossaryTermEntity
import com.abhinavxt.novelforge.data.translation.TranslationConfig
import com.abhinavxt.novelforge.data.translation.TranslationEngine
import com.abhinavxt.novelforge.data.translation.TranslationManager
import kotlinx.coroutines.launch

/**
 * One book's translation: the switch, translating what's downloaded, the glossary, and undoing it all.
 * [onChanged] runs after anything that changes the text on screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationSheet(
    novelId: String,
    manager: TranslationManager,
    config: TranslationConfig,
    onChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val on by manager.observeTranslateOn(novelId).collectAsState(initial = false)
    val glossary by manager.observeGlossary(novelId).collectAsState(initial = emptyList())
    val bulk by manager.bulk.collectAsState()
    val progress = bulk?.takeIf { it.novelId == novelId }
    // A finished "translate downloaded chapters" run changed text and titles.
    var bulkWasRunning by remember { mutableStateOf(false) }
    LaunchedEffect(progress?.running) {
        if (progress?.running == true) bulkWasRunning = true
        else if (bulkWasRunning) {
            bulkWasRunning = false
            onChanged()
        }
    }
    var untranslated by remember { mutableIntStateOf(0) }
    var showGlossary by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }

    LaunchedEffect(novelId, progress?.done, progress?.running) {
        untranslated = manager.untranslatedChapters(novelId).size
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Translation", style = MaterialTheme.typography.titleLarge)
            val engine = when (config.engine) {
                TranslationEngine.AI -> "${config.provider.label} · ${config.model.ifBlank { "no model set" }}"
                TranslationEngine.ON_DEVICE -> "On-device"
            }
            Text(
                "Into ${config.targetLanguageName} with $engine. Change it in Settings → Translation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Translate this book", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Each chapter is translated when you open it, then kept, so it's only paid for once. " +
                            "Works for raw chapters and for machine-translated English.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = on, onCheckedChange = { checked ->
                    scope.launch {
                        manager.setTranslateOn(novelId, checked)
                        onChanged()
                    }
                })
            }

            if (progress?.running == true) {
                Text("Translating ${progress.done} of ${progress.total} chapters…", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { if (progress.total == 0) 0f else progress.done.toFloat() / progress.total },
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = manager::stopBulk) { Text("Stop") }
            } else {
                OutlinedButton(
                    onClick = { manager.translateStoredChapters(novelId) },
                    enabled = untranslated > 0,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (untranslated > 0) "Translate $untranslated downloaded chapters" else "Downloaded chapters are translated")
                }
                progress?.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = false, onClick = { showGlossary = true })
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Glossary", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (glossary.isEmpty()) "Names and terms fill in as chapters are translated"
                        else "${glossary.size} names and terms, always translated the same way",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            TextButton(onClick = { confirmRestore = true }) {
                Text("Restore original text", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showGlossary) {
        GlossarySheet(
            terms = glossary,
            onSave = { scope.launch { manager.saveTerm(it.copy(novelId = novelId)) } },
            onDelete = { id -> scope.launch { manager.deleteTerm(id) } },
            onDismiss = { showGlossary = false }
        )
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Restore original text?") },
            text = { Text("Translated chapters go back to their original text and the book stops translating. The glossary is kept. Translating again will be paid for again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    scope.launch {
                        manager.stopBulk()
                        manager.setTranslateOn(novelId, false)
                        manager.restoreBook(novelId)
                        untranslated = manager.untranslatedChapters(novelId).size
                        onChanged()
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancel") } }
        )
    }
}

/** The book's glossary: tap a term to edit it, or add one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlossarySheet(
    terms: List<GlossaryTermEntity>,
    onSave: (GlossaryTermEntity) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var editing by remember { mutableStateOf<GlossaryTermEntity?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Glossary", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { editing = GlossaryTermEntity(novelId = "", source = "", target = "") }) {
                Icon(Icons.Default.Add, contentDescription = "Add term")
            }
        }
        Text(
            "Used for chapters translated from now on. Fix a name here, then restore and translate again to fix chapters already done.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Spacer(Modifier.height(8.dp))
        if (terms.isEmpty()) {
            Text(
                "None yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp)
            )
        } else {
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                items(terms, key = { it.id }) { term ->
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = false, onClick = { editing = term })
                                .padding(start = 24.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("${term.source} → ${term.target}", style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (term.note.isNotBlank()) {
                                    Text(term.note, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            IconButton(onClick = { onDelete(term.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 24.dp))
                    }
                }
            }
        }
    }

    editing?.let { term ->
        var source by remember(term) { mutableStateOf(term.source) }
        var target by remember(term) { mutableStateOf(term.target) }
        var note by remember(term) { mutableStateOf(term.note) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (term.id == 0L) "Add term" else "Edit term") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(source, { source = it }, label = { Text("In the original") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(target, { target = it }, label = { Text("Translate as") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(note, { note = it }, label = { Text("Note, e.g. female, she/her") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSave(term.copy(source = source, target = target, note = note))
                        editing = null
                    },
                    enabled = source.isNotBlank() && target.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } }
        )
    }
}
