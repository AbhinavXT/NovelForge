package com.abhinavxt.novelforge.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abhinavxt.novelforge.data.StarDict
import com.abhinavxt.novelforge.data.StarDictException
import com.abhinavxt.novelforge.data.StarDictManager
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Settings section: import and remove StarDict dictionaries. */
@Composable
fun OfflineDictionariesSection(manager: StarDictManager) {
    val dictionaries by manager.dictionaries.collectAsState()
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<StarDict?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = true
        error = null
        scope.launch {
            try {
                manager.import(uris)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("Dictionary", "Import failed", e)
                error = (e as? StarDictException)?.message ?: "Couldn't read that dictionary."
            } finally {
                importing = false
            }
        }
    }

    Text(
        text = "Offline Dictionaries",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "Words are looked up here first, then online in the language above. " +
                "Add a StarDict dictionary as one .zip or .tar.gz, or pick its " +
                ".ifo, .idx and .dict files together.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )

    dictionaries.forEach { dictionary ->
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = dictionary.info.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "%,d words".format(dictionary.info.wordCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            IconButton(onClick = { toDelete = dictionary }) {
                Icon(Icons.Default.Delete, contentDescription = "Remove ${dictionary.info.name}")
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(
        onClick = { picker.launch(arrayOf("*/*")) },
        enabled = !importing,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (importing) "Adding…" else "Add dictionary")
    }
    error?.let {
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }

    toDelete?.let { dictionary ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Remove ${dictionary.info.name}?") },
            text = { Text("You'll need the original file to add it again.") },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    scope.launch { manager.delete(dictionary) }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } }
        )
    }
}
