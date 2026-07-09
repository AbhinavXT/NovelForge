package com.abhinavxt.novelforge.ui.screens

import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abhinavxt.novelforge.ui.viewmodel.AudioChapter
import com.abhinavxt.novelforge.ui.viewmodel.AudioNovel
import com.abhinavxt.novelforge.ui.viewmodel.AudioPlayerViewModel
import com.abhinavxt.novelforge.ui.viewmodel.PlaybackState

/**
 * Screen 1: Lists all novels that have exported audio chapters.
 * Tapping a novel navigates to the chapter list for that novel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioLibraryScreen(
    viewModel: AudioPlayerViewModel,
    onNovelClick: (String) -> Unit,  // folderName
    onBackClick: () -> Unit
) {
    val novels by viewModel.novels.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()

    // Refresh on entry
    LaunchedEffect(Unit) {
        viewModel.scanAudioFiles()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audio Library") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (novels.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.LibraryMusic,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No audio files yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Export chapters as audio from the novel detail screen",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(novels, key = { it.folderName }) { novel ->
                    AudioNovelCard(
                        novel = novel,
                        playbackState = playbackState,
                        onClick = { onNovelClick(novel.folderName) },
                        onDelete = { viewModel.deleteNovel(novel) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioNovelCard(
    novel: AudioNovel,
    playbackState: PlaybackState,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val isCurrentlyPlaying = playbackState.currentNovel?.folderName == novel.folderName
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Surface(
                color = if (isCurrentlyPlaying)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(
                    imageVector = if (isCurrentlyPlaying) Icons.Default.GraphicEq
                    else Icons.Default.AudioFile,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(12.dp)
                        .size(28.dp),
                    tint = if (isCurrentlyPlaying)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = novel.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${novel.chapters.size} chapter${if (novel.chapters.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                if (isCurrentlyPlaying && playbackState.currentChapter != null) {
                    Text(
                        text = "Playing: ${playbackState.currentChapter.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Delete button
            IconButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete novel audio",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Open",
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Audio") },
            text = {
                Text("Delete all ${novel.chapters.size} audio file${if (novel.chapters.size != 1) "s" else ""} for \"${novel.displayName}\"? This cannot be undone.")
            },
            confirmButton = {
                Button(onClick = {
                    onDelete()
                    showDeleteConfirm = false
                }) {
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

/**
 * Screen 2: Lists chapters for a specific novel.
 * Tapping a chapter navigates to the player screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioChapterListScreen(
    viewModel: AudioPlayerViewModel,
    novelFolderName: String,
    onChapterClick: (String, String) -> Unit,  // folderName, filePath
    onBackClick: () -> Unit
) {
    val novel = viewModel.getNovel(novelFolderName)
    val playbackState by viewModel.playbackState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Audio merger
    val audioMerger = remember { com.abhinavxt.novelforge.data.tts.AudioMerger(context) }
    val mergeState by audioMerger.state.collectAsState()
    var showMergeDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = novel?.displayName ?: "Audio Chapters",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (novel == null || novel.chapters.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No audio chapters found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Merge as Audiobook button
                if (novel.chapters.size > 1) {
                    item {
                        OutlinedButton(
                            onClick = { showMergeDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AudioFile,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Merge as Audiobook (${novel.chapters.size} chapters)")
                        }
                    }
                }

                items(novel.chapters, key = { it.filePath }) { chapter ->
                    AudioChapterItem(
                        chapter = chapter,
                        isPlaying = playbackState.currentChapter?.filePath == chapter.filePath
                                && playbackState.isPlaying,
                        onClick = { onChapterClick(novelFolderName, chapter.filePath) },
                        onPlayClick = { viewModel.play(chapter, novel) },
                        onDelete = { viewModel.deleteChapter(chapter) }
                    )
                }
            }
        }
    }

    // Merge confirmation + progress dialog
    if (showMergeDialog) {
        val currentMergeState = mergeState
        AlertDialog(
            onDismissRequest = {
                if (currentMergeState !is com.abhinavxt.novelforge.data.tts.AudioMerger.MergeState.Merging) {
                    showMergeDialog = false
                    audioMerger.reset()
                }
            },
            title = {
                Text(
                    when (currentMergeState) {
                        is com.abhinavxt.novelforge.data.tts.AudioMerger.MergeState.Idle -> "Merge as Audiobook"
                        is com.abhinavxt.novelforge.data.tts.AudioMerger.MergeState.Merging -> "Merging…"
                        is com.abhinavxt.novelforge.data.tts.AudioMerger.MergeState.Complete -> "Audiobook Created"
                        is com.abhinavxt.novelforge.data.tts.AudioMerger.MergeState.Error -> "Merge Failed"
                    }
                )
            },
            text = {
                Column {
                    when (currentMergeState) {
                        is com.abhinavxt.novelforge.data.tts.AudioMerger.MergeState.Idle -> {
                            Text("Combine ${novel?.chapters?.size ?: 0} chapter files into a single audiobook WAV.")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Saved alongside the chapter files in Music/NovelReader/.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        is com.abhinavxt.novelforge.data.tts.AudioMerger.MergeState.Merging -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Chapter ${currentMergeState.currentChapter}/${currentMergeState.totalChapters}")
                            }
                        }
                        is com.abhinavxt.novelforge.data.tts.AudioMerger.MergeState.Complete -> {
                            val mins = currentMergeState.totalDurationMs / 60_000
                            Text("Audiobook saved successfully.")
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Duration: ${mins}min",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        is com.abhinavxt.novelforge.data.tts.AudioMerger.MergeState.Error -> {
                            Text(currentMergeState.message, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {
                when (currentMergeState) {
                    is com.abhinavxt.novelforge.data.tts.AudioMerger.MergeState.Idle -> {
                        Button(onClick = {
                            scope.launch {
                                audioMerger.mergeChapters(novelFolderName)
                            }
                        }) { Text("Merge") }
                    }
                    is com.abhinavxt.novelforge.data.tts.AudioMerger.MergeState.Complete,
                    is com.abhinavxt.novelforge.data.tts.AudioMerger.MergeState.Error -> {
                        Button(onClick = {
                            showMergeDialog = false
                            audioMerger.reset()
                        }) { Text("Done") }
                    }
                    else -> {}
                }
            },
            dismissButton = {
                if (currentMergeState is com.abhinavxt.novelforge.data.tts.AudioMerger.MergeState.Idle) {
                    OutlinedButton(onClick = { showMergeDialog = false }) { Text("Cancel") }
                }
            }
        )
    }
}

@Composable
private fun AudioChapterItem(
    chapter: AudioChapter,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Music icon
            Icon(
                imageVector = if (isPlaying) Icons.Default.GraphicEq
                else Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (isPlaying) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chapter.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isPlaying) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
                val sizeMb = chapter.fileSizeBytes / (1024f * 1024f)
                Text(
                    text = "%.1f MB".format(sizeMb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            // Delete button
            IconButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Quick play button
            IconButton(onClick = onPlayClick) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Audio") },
            text = { Text("Delete \"${chapter.displayName}\"?") },
            confirmButton = {
                Button(onClick = {
                    onDelete()
                    showDeleteConfirm = false
                }) {
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