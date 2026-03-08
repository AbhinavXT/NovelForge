package com.example.novelreader.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.novelreader.ui.viewmodel.AudioPlayerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPlayerScreen(
    viewModel: AudioPlayerViewModel,
    novelFolderName: String,
    chapterFilePath: String,
    onBackClick: () -> Unit
) {
    val playbackState by viewModel.playbackState.collectAsState()
    val novel = viewModel.getNovel(novelFolderName)

    // Auto-play on entry if not already playing this chapter
    LaunchedEffect(chapterFilePath) {
        val chapter = novel?.chapters?.find { it.filePath == chapterFilePath }
        if (chapter != null && playbackState.currentChapter?.filePath != chapterFilePath) {
            viewModel.play(chapter, novel)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = novel?.displayName ?: "Audio Player",
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // ── Album art / icon area ──
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.size(180.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // ── Chapter title ──
            Text(
                text = playbackState.currentChapter?.displayName ?: "Select a chapter",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Novel name subtitle
            Text(
                text = novel?.displayName ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(32.dp))

            // ── Seek bar ──
            SeekBar(
                positionMs = playbackState.positionMs,
                durationMs = playbackState.durationMs,
                onSeek = { viewModel.seekTo(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Playback controls ──
            PlaybackControls(
                isPlaying = playbackState.isPlaying,
                isLoading = playbackState.isLoading,
                hasNext = run {
                    val idx = novel?.chapters?.indexOfFirst {
                        it.filePath == playbackState.currentChapter?.filePath
                    } ?: -1
                    idx >= 0 && idx < (novel?.chapters?.size ?: 0) - 1
                },
                hasPrevious = run {
                    val idx = novel?.chapters?.indexOfFirst {
                        it.filePath == playbackState.currentChapter?.filePath
                    } ?: -1
                    idx > 0
                },
                onPlayPause = { viewModel.togglePlayPause() },
                onSkipForward = { viewModel.skipForward() },
                onSkipBackward = { viewModel.skipBackward() },
                onNext = { viewModel.playNextChapter() },
                onPrevious = { viewModel.playPreviousChapter() }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Speed control ──
            SpeedControl(
                currentSpeed = playbackState.speed,
                onSpeedChange = { viewModel.setSpeed(it) }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ── Seek bar ─────────────────────────────────────────────────────

@Composable
private fun SeekBar(
    positionMs: Int,
    durationMs: Int,
    onSeek: (Int) -> Unit
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = if (isDragging) dragPosition
            else if (durationMs > 0) positionMs.toFloat() / durationMs
            else 0f,
            onValueChange = {
                isDragging = true
                dragPosition = it
            },
            onValueChangeFinished = {
                isDragging = false
                onSeek((dragPosition * durationMs).toInt())
            },
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatTime(if (isDragging) (dragPosition * durationMs).toInt() else positionMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Text(
                text = formatTime(durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

// ── Playback controls ────────────────────────────────────────────

@Composable
private fun PlaybackControls(
    isPlaying: Boolean,
    isLoading: Boolean,
    hasNext: Boolean,
    hasPrevious: Boolean,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onSkipBackward: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Previous chapter
        IconButton(
            onClick = onPrevious,
            enabled = hasPrevious
        ) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = "Previous chapter",
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Skip back 10s
        IconButton(onClick = onSkipBackward) {
            Icon(
                imageVector = Icons.Default.Replay10,
                contentDescription = "Back 10s",
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Play/Pause (large button)
        FilledIconButton(
            onClick = onPlayPause,
            modifier = Modifier.size(64.dp),
            shape = CircleShape
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause
                    else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Skip forward 10s
        IconButton(onClick = onSkipForward) {
            Icon(
                imageVector = Icons.Default.Forward10,
                contentDescription = "Forward 10s",
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Next chapter
        IconButton(
            onClick = onNext,
            enabled = hasNext
        ) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = "Next chapter",
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

// ── Speed control ────────────────────────────────────────────────

@Composable
private fun SpeedControl(
    currentSpeed: Float,
    onSpeedChange: (Float) -> Unit
) {
    val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "Playback Speed",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Two rows of 3 chips
        for (rowSpeeds in speeds.chunked(3)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                rowSpeeds.forEach { speed ->
                    val label = if (speed % 1.0f == 0f) "${speed.toInt()}x" else "${speed}x"
                    FilterChip(
                        selected = currentSpeed == speed,
                        onClick = { onSpeedChange(speed) },
                        label = {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        }
                    )
                }
            }
        }
    }
}

// ── Utilities ────────────────────────────────────────────────────

private fun formatTime(ms: Int): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}