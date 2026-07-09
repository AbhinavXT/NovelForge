package com.abhinavxt.novelforge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// ─────────────────────────────────────────────────────────────────
// Split out of the original NovelDetailScreen.kt (Phase 3 refactor).
// Same package, pure move — no behavior change. Declarations used
// across files were promoted private → internal.
// ─────────────────────────────────────────────────────────────────

@Composable
internal fun DownloadRangeDialog(
    totalChapters: Int,
    onConfirm: (from: Int, to: Int) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Download Chapters",
    confirmLabel: String = "Download",
    description: String = "Download a range of chapters (1–$totalChapters). Already downloaded chapters are skipped."
) {
    var fromText by remember { mutableStateOf("1") }
    var toText by remember { mutableStateOf(totalChapters.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = fromText,
                        onValueChange = { fromText = it.filter { c -> c.isDigit() } },
                        label = { Text("From") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Text("to")
                    OutlinedTextField(
                        value = toText,
                        onValueChange = { toText = it.filter { c -> c.isDigit() } },
                        label = { Text("To") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                val from = (fromText.toIntOrNull() ?: 1).coerceIn(1, totalChapters)
                val to = (toText.toIntOrNull() ?: totalChapters).coerceIn(from, totalChapters)
                val count = to - from + 1
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "$count chapter${if (count != 1) "s" else ""} selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val from = (fromText.toIntOrNull() ?: 1).coerceIn(1, totalChapters)
                    val to = (toText.toIntOrNull() ?: totalChapters).coerceIn(from, totalChapters)
                    onConfirm(from, to)
                }
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
