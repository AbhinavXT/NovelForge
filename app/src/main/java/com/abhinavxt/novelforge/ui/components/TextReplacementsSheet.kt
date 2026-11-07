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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abhinavxt.novelforge.data.database.TextReplacementEntity

/**
 * The reader's text replacement rules: this book's, then those for every
 * book. Tap one to edit it, or add a new one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextReplacementsSheet(
    novelId: String,
    rules: List<TextReplacementEntity>,
    onSave: (id: Long?, novelId: String?, find: String, replace: String) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    // null = closed; id null inside = adding
    var editing by remember { mutableStateOf<TextReplacementEntity?>(null) }
    val bookRules = rules.filter { it.novelId == novelId }
    val allRules = rules.filter { it.novelId == null }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Text replacements",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                editing = TextReplacementEntity(id = 0, novelId = novelId, find = "", replace = "")
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add replacement")
            }
        }
        Text(
            "Changes what the reader shows and what TTS reads. The book itself isn't changed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Spacer(Modifier.height(8.dp))

        if (rules.isEmpty()) {
            Text(
                "None yet. Select text while reading and choose Replace, or tap + above.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp)
            )
        } else {
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                if (bookRules.isNotEmpty()) {
                    item { SectionLabel("This book") }
                    items(bookRules, key = { it.id }) { rule ->
                        RuleRow(rule, onClick = { editing = rule }, onDelete = { onDelete(rule.id) })
                    }
                }
                if (allRules.isNotEmpty()) {
                    item { SectionLabel("All books") }
                    items(allRules, key = { it.id }) { rule ->
                        RuleRow(rule, onClick = { editing = rule }, onDelete = { onDelete(rule.id) })
                    }
                }
            }
        }
    }

    editing?.let { rule ->
        TextReplacementDialog(
            title = if (rule.id == 0L) "Add replacement" else "Edit replacement",
            initialFind = rule.find,
            initialReplace = rule.replace,
            initialThisBookOnly = rule.novelId != null,
            onSave = { find, replace, thisBookOnly ->
                onSave(rule.id.takeIf { it != 0L }, if (thisBookOnly) novelId else null, find, replace)
                editing = null
            },
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun RuleRow(rule: TextReplacementEntity, onClick: () -> Unit, onDelete: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = false, onClick = onClick)
                .padding(start = 24.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(rule.find, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val removed = rule.replace.isEmpty()
                Text(
                    if (removed) "removed" else "→ ${rule.replace}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = if (removed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 24.dp))
    }
}

/**
 * Add or edit one rule. Used by the sheet and by Replace on a text selection.
 * An empty replacement removes the text.
 */
@Composable
fun TextReplacementDialog(
    title: String,
    initialFind: String,
    initialReplace: String = "",
    initialThisBookOnly: Boolean = true,
    onSave: (find: String, replace: String, thisBookOnly: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var find by remember { mutableStateOf(initialFind) }
    var replace by remember { mutableStateOf(initialReplace) }
    var thisBookOnly by remember { mutableStateOf(initialThisBookOnly) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = find,
                    onValueChange = { find = it },
                    label = { Text("Find") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = replace,
                    onValueChange = { replace = it },
                    label = { Text("Replace with") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    if (replace.isBlank() && find.isNotBlank()) "\"${find.trim()}\" will be removed."
                    else "Whole words, any case. Leave empty to remove the text.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ScopeOption("This book", selected = thisBookOnly) { thisBookOnly = true }
                ScopeOption("All books", selected = !thisBookOnly) { thisBookOnly = false }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(find, replace, thisBookOnly) },
                enabled = find.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ScopeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
