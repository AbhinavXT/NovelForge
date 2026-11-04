package com.abhinavxt.novelforge.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abhinavxt.novelforge.data.LibraryStorage
import com.abhinavxt.novelforge.data.LibraryStorage.State
import com.abhinavxt.novelforge.data.database.DatabaseCompactor
import com.abhinavxt.novelforge.data.epub.LocalBookArchive.BulkArchiveSummary

/**
 * Settings → Storage: how big the database is, and the two library-wide
 * jobs that make it smaller. The jobs live in [LibraryStorage], so leaving
 * this screen mid-run and coming back shows the run still going.
 */
@Composable
fun LibraryStorageSection(storage: LibraryStorage) {
    val state by storage.state.collectAsState()
    val usage by storage.usage.collectAsState()
    var confirmArchiveAll by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { storage.refreshUsage() }

    Text(
        text = "Storage",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "Imported books keep a copy of their text in the app. Archiving " +
                "drops that copy and reads the text from the book's file again " +
                "when you open it. Progress, bookmarks and highlights stay.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(16.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = usage?.let { "Library database: ${formatSize(it.fileBytes)}" }
                    ?: "Library database",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            usage?.takeIf { it.reclaimableBytes >= MIN_WORTH_MENTIONING }?.let {
                Text(
                    text = "At least ${formatSize(it.reclaimableBytes)} can be " +
                            "reclaimed by compacting",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            when (val s = state) {
                State.Idle -> Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { confirmArchiveAll = true }) {
                        Text("Archive all imported books")
                    }
                    TextButton(onClick = { storage.compact() }) {
                        Text("Compact")
                    }
                }

                is State.Archiving -> {
                    Text(
                        text = if (s.total == 0) "Finding imported books…"
                        else "Archiving ${s.done + 1} of ${s.total}…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (s.total > 0) {
                        LinearProgressIndicator(
                            progress = { s.done.toFloat() / s.total },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        text = "You can leave this screen; it keeps going.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    TextButton(onClick = { storage.stop() }) { Text("Stop") }
                }

                State.Compacting -> {
                    Text(
                        text = "Compacting the database… On a large library " +
                                "this can take a minute or two.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                is State.Finished -> {
                    Text(
                        text = describeStorageRun(s.archive, s.compaction),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = { storage.acknowledge() }) { Text("OK") }
                }
            }
        }
    }

    if (confirmArchiveAll) {
        AlertDialog(
            onDismissRequest = { confirmArchiveAll = false },
            title = { Text("Archive all imported books?") },
            text = {
                Text(
                    "Each book is checked against its file first. Books whose " +
                            "file is missing or has changed are left as they are.\n\n" +
                            "Archived books open a little slower the first time, " +
                            "and search won't find their text until they've been " +
                            "opened again. Books from online sources aren't affected."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmArchiveAll = false
                    storage.archiveAllAndCompact()
                }) { Text("Archive all") }
            },
            dismissButton = {
                TextButton(onClick = { confirmArchiveAll = false }) { Text("Cancel") }
            }
        )
    }
}

/** Below this, "can be reclaimed" is noise: every database has a few free pages. */
private const val MIN_WORTH_MENTIONING = 1_048_576L

internal fun describeStorageRun(
    archive: BulkArchiveSummary?,
    compaction: DatabaseCompactor.Result
): String = buildList {
    archive?.let { a ->
        add(
            when (a.archived) {
                0 -> "No books needed archiving."
                else -> "Archived ${books(a.archived)}."
            }
        )
        if (a.skipped > 0) {
            val reasons = buildList {
                if (a.unavailable > 0) add("${a.unavailable} with a missing or unreadable file")
                if (a.changed > 0) add("${a.changed} whose file changed since import")
                if (a.noFile > 0) add("${a.noFile} with no file on record")
            }
            add("Left ${books(a.skipped)} as they were: ${reasons.joinToString(", ")}.")
            if (a.noFile > 0) {
                add("Importing a book's file again links it, so it can be archived.")
            }
        }
        if (a.stopped) add("Stopped early. Run it again to carry on where it left off.")
    }
    when (compaction) {
        is DatabaseCompactor.Result.Compacted -> {
            add(
                if (compaction.freedBytes >= MIN_WORTH_MENTIONING) {
                    "The database is ${formatSize(compaction.freedBytes)} smaller, " +
                            "now ${formatSize(compaction.afterBytes)}."
                } else {
                    "The database was already compact (${formatSize(compaction.afterBytes)})."
                }
            )
            compaction.vacuumSkipped?.let { add("Only partly compacted: $it.") }
        }
        is DatabaseCompactor.Result.Failed ->
            add("Couldn't compact the database (${compaction.message}). Nothing was lost.")
    }
}.joinToString(" ")

private fun books(n: Int) = if (n == 1) "1 book" else "$n books"
