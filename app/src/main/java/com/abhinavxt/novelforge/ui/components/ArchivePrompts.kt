package com.abhinavxt.novelforge.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.abhinavxt.novelforge.data.epub.LocalBookArchive.ArchiveResult
import com.abhinavxt.novelforge.data.epub.LocalBookArchive.RestoreResult

/**
 * Why an archived book could not come back on its own, in terms of what the
 * user can do about it. Shared by the reader and the library so the same
 * situation is explained the same way wherever it is met.
 */
sealed interface ArchiveProblem {
    /** No file was ever recorded — imported before files were remembered. */
    data object NoFile : ArchiveProblem

    /** The recorded file is gone, moved, or unreadable. */
    data class FileMissing(val detail: String) : ArchiveProblem

    /** The file reads, but not as the chapters that were archived. */
    data class TextChanged(val fileChapters: Int, val libraryChapters: Int) : ArchiveProblem

    val title: String
        get() = when (this) {
            NoFile -> "Where is this book's file?"
            is FileMissing -> "Can't open this book's file"
            is TextChanged -> "This file has changed"
        }

    val message: String
        get() = when (this) {
            NoFile ->
                "The text of this book was set aside to save space, and the app " +
                        "doesn't know which file it came from. Point to the file " +
                        "to bring it back."
            is FileMissing ->
                "The text of this book was set aside to save space, but its file " +
                        "can't be opened: $detail. Point to the file to bring the " +
                        "book back."
            is TextChanged ->
                "This file doesn't match the book you archived" +
                        (if (fileChapters != libraryChapters) {
                            " (it has $fileChapters chapters, the book had $libraryChapters)"
                        } else "") +
                        ". It may have been edited, or be a different edition. " +
                        "Restoring it anyway can move your bookmarks, highlights " +
                        "and reading position."
        }

    /** Only a changed file can be accepted as is; the others need a file first. */
    val canRestoreAnyway: Boolean get() = this is TextChanged
}

/** The problem a restore ran into, or null if it needs no user decision. */
fun RestoreResult.toProblem(): ArchiveProblem? = when (this) {
    RestoreResult.NeedsFile -> ArchiveProblem.NoFile
    is RestoreResult.FileUnavailable -> ArchiveProblem.FileMissing(message)
    is RestoreResult.DifferentText -> ArchiveProblem.TextChanged(fileChapters, libraryChapters)
    else -> null
}

/** One line for a toast or snackbar after an archive attempt. */
fun ArchiveResult.describe(title: String): String = when (this) {
    is ArchiveResult.Archived ->
        "Archived \"$title\" — about ${formatSize(freedBytes)} freed"
    ArchiveResult.AlreadyArchived -> "\"$title\" is already archived"
    ArchiveResult.NotLocal -> "Only imported books can be archived"
    ArchiveResult.NoFile ->
        "Can't archive \"$title\": the app doesn't know which file it came " +
                "from. Import the file again to link it, then archive."
    is ArchiveResult.FileUnavailable ->
        "Can't archive \"$title\": $message"
    ArchiveResult.FileChanged ->
        "Can't archive \"$title\": its file no longer matches the book, " +
                "so it couldn't be restored exactly"
    ArchiveResult.NotFound -> "Book not found"
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/**
 * The explanation and its actions, without a container — the reader shows it
 * full screen in place of the chapter, the library inside a dialog.
 */
@Composable
fun ArchiveProblemContent(
    problem: ArchiveProblem,
    onLocateFile: () -> Unit,
    onRestoreAnyway: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = problem.title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Text(
            text = problem.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Button(onClick = onLocateFile, modifier = Modifier.fillMaxWidth()) {
            Text("Locate file")
        }
        if (problem.canRestoreAnyway) {
            OutlinedButton(onClick = onRestoreAnyway, modifier = Modifier.fillMaxWidth()) {
                Text("Restore anyway")
            }
        }
    }
}

@Composable
fun ArchiveProblemDialog(
    problem: ArchiveProblem,
    onLocateFile: () -> Unit,
    onRestoreAnyway: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            ArchiveProblemContent(
                problem = problem,
                onLocateFile = onLocateFile,
                onRestoreAnyway = onRestoreAnyway
            )
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } }
    )
}
