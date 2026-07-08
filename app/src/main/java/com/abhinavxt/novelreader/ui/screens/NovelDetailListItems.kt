package com.abhinavxt.novelreader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import com.abhinavxt.novelreader.data.tts.M4BAudiobookBuilder
import com.abhinavxt.novelreader.ui.components.NovelCover
import androidx.compose.ui.platform.LocalContext
import com.abhinavxt.novelreader.AppConfig
import com.abhinavxt.novelreader.data.DownloadManager
import com.abhinavxt.novelreader.data.DownloadStatus
import com.abhinavxt.novelreader.ui.components.ModelDownloadDialog
import com.abhinavxt.novelreader.ui.components.OfflineBanner
import com.abhinavxt.novelreader.data.NovelDownloadState
import com.abhinavxt.novelreader.data.NovelRepository
import com.abhinavxt.novelreader.data.database.BookmarkEntity
import com.abhinavxt.novelreader.data.model.Chapter
import com.abhinavxt.novelreader.data.model.Novel
import com.abhinavxt.novelreader.ui.viewmodel.NovelDetailUiState
import com.abhinavxt.novelreader.ui.viewmodel.NovelDetailViewModel
import com.abhinavxt.novelreader.util.NetworkMonitor
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────────────────────────────────────────────
// Split out of the original NovelDetailScreen.kt (Phase 3 refactor).
// Same package, pure move — no behavior change. Declarations used
// across files were promoted private → internal.
// ─────────────────────────────────────────────────────────────────

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun ChapterListItem(
    chapter: Chapter,
    isDownloading: Boolean,
    isExporting: Boolean,
    isAudioExported: Boolean,
    exportState: com.abhinavxt.novelreader.data.tts.AudioExporter.ExportState,
    isLocalNovel: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
    onDownload: () -> Unit,
    onExportAudio: () -> Unit,
    onCancelExport: () -> Unit
) {
    // Get export progress for this specific chapter
    val exportProgress = if (isExporting && exportState is com.abhinavxt.novelreader.data.tts.AudioExporter.ExportState.Exporting
        && exportState.chapterId == chapter.id) {
        exportState.progress
    } else null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Chapter number badge
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = "${chapter.number}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Chapter title
            Text(
                text = chapter.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Audio export button
            if (isExporting) {
                // Show progress or cancel
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(32.dp)
                ) {
                    CircularProgressIndicator(
                        progress = { exportProgress ?: 0f },
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    // Tap to cancel
                    IconButton(
                        onClick = onCancelExport,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel export",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            } else if (isAudioExported) {
                // Already exported — show completed icon
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = "Audio exported",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.tertiary
                )
            } else {
                IconButton(
                    onClick = onExportAudio,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = "Export as audio",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Download status / button — only when online sources enabled
            if (!isLocalNovel && AppConfig.ONLINE_SOURCES_ENABLED) {
                when {
                    isDownloading -> {
                        // Downloading spinner
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    chapter.isDownloaded -> {
                        // Downloaded indicator
                        Icon(
                            imageVector = Icons.Default.DownloadDone,
                            contentDescription = "Downloaded",
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    else -> {
                        // Download button
                        IconButton(
                            onClick = onDownload,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Download chapter",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BookmarkListItem(
    bookmark: BookmarkEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onEditNote: (String?) -> Unit
) {
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Top row: chapter info + action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Chapter badge
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = "Ch. ${bookmark.chapterNumber}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Chapter title
                Text(
                    text = bookmark.chapterTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Edit note button
                IconButton(
                    onClick = { showEditDialog = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit note",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }

                // Delete button
                IconButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete bookmark",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Text snippet preview — helps users recognize which passage they bookmarked
            Text(
                text = "\"${bookmark.textSnippet}\"",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Show the user's note if present
            if (!bookmark.note.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "\uD83D\uDCDD ${bookmark.note}",  // 📝 emoji
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Timestamp showing when the bookmark was created
            Text(
                text = formatBookmarkDate(bookmark.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }

    // Edit note dialog
    if (showEditDialog) {
        EditBookmarkNoteDialog(
            currentNote = bookmark.note,
            onDismiss = { showEditDialog = false },
            onSave = { note ->
                onEditNote(note)
                showEditDialog = false
            }
        )
    }

    // Delete confirmation dialog
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Bookmark") },
            text = { Text("Remove this bookmark from Ch. ${bookmark.chapterNumber}?") },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete()
                        showDeleteConfirm = false
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun EditBookmarkNoteDialog(
    currentNote: String?,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit
) {
    var noteText by remember { mutableStateOf(currentNote ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bookmark Note") },
        text = {
            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Add a note about this passage...") },
                maxLines = 4
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(noteText.ifBlank { null })
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Format timestamp into a readable date string.
 */
private fun formatBookmarkDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

