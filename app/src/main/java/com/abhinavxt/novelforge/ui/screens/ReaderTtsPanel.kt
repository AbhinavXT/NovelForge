package com.abhinavxt.novelforge.ui.screens

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abhinavxt.novelforge.data.TTSManager
import com.abhinavxt.novelforge.data.TTSState
import com.abhinavxt.novelforge.data.SleepTimerMode
import com.abhinavxt.novelforge.data.tts.VoiceInfo
import androidx.compose.material.icons.filled.Download

// ─────────────────────────────────────────────────────────────────
// Split out of the original ReaderScreen.kt (Phase 3 refactor).
// Same package, pure move — no behavior change. Declarations used
// across files were promoted private → internal.
// ─────────────────────────────────────────────────────────────────

@SuppressLint("DefaultLocale")
@Composable
internal fun TTSControlsPanel(
    ttsManager: TTSManager,
    ttsState: TTSState,
    currentSentence: Int,
    ttsSettings: com.abhinavxt.novelforge.data.TTSSettings,
    chapterContent: String,
    novelTitle: String,
    chapterTitle: String,
    canGoNext: Boolean,
    startFromParagraph: Int,
    onNextChapter: () -> Unit,
    onNextChapterWithRetry: () -> Unit,
    onShowModelDownload: () -> Unit,
    onClose: () -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }
    var showVoiceSelector by remember { mutableStateOf(false) }
    var showSleepMenu by remember { mutableStateOf(false) }

    val availableVoices by ttsManager.availableVoices.collectAsState()
    val currentVoice by ttsManager.currentVoice.collectAsState()
    val sleepMode by ttsManager.sleepTimerMode.collectAsState()
    val sleepRemainingMs by ttsManager.sleepTimerRemainingMs.collectAsState()

    val totalSentences = ttsManager.getSentenceCount()
    val progress = if (totalSentences > 0) (currentSentence + 1).toFloat() / totalSentences else 0f

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 12.dp,
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 16.dp, start = 20.dp, end = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Drag handle
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        RoundedCornerShape(2.dp)
                    )
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Chapter info + close
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = chapterTitle,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (totalSentences > 0) {
                        Text(
                            text = "${currentSentence + 1} / $totalSentences",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Settings gear
                IconButton(
                    onClick = { showSettings = !showSettings },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        modifier = Modifier.size(20.dp),
                        tint = if (showSettings) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Sleep timer button + dropdown
                Box {
                    IconButton(
                        onClick = { showSleepMenu = !showSleepMenu },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (sleepMode != SleepTimerMode.NONE)
                                Icons.Default.Timer else Icons.Default.TimerOff,
                            contentDescription = "Sleep timer",
                            modifier = Modifier.size(20.dp),
                            tint = if (sleepMode != SleepTimerMode.NONE)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = showSleepMenu,
                        onDismissRequest = { showSleepMenu = false }
                    ) {
                        SleepTimerMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = mode.label,
                                        color = if (mode == sleepMode)
                                            MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = {
                                    ttsManager.setSleepTimer(mode)
                                    showSleepMenu = false
                                }
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Sleep timer status — shown when a timer is active
            if (sleepMode != SleepTimerMode.NONE) {
                val timerText = if (sleepMode == SleepTimerMode.END_OF_CHAPTER) {
                    "⏱ Stopping at end of chapter"
                } else if (sleepRemainingMs > 0) {
                    val mins = (sleepRemainingMs / 60_000).toInt()
                    val secs = ((sleepRemainingMs % 60_000) / 1000).toInt()
                    "⏱ Sleep in ${mins}:${String.format("%02d", secs)}"
                } else {
                    "⏱ ${sleepMode.label}"
                }
                Text(
                    text = timerText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }

            // Progress bar
            if (totalSentences > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Playback controls — centered, clean
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Stop
                IconButton(
                    onClick = { ttsManager.stop() },
                    enabled = ttsState != TTSState.IDLE,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop",
                        modifier = Modifier.size(22.dp),
                        tint = if (ttsState != TTSState.IDLE)
                            MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Previous sentence
                IconButton(
                    onClick = { ttsManager.skipToPrevious() },
                    enabled = ttsState == TTSState.PLAYING || ttsState == TTSState.PAUSED,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Play/Pause — larger prominent button
                Surface(
                    onClick = {
                        when (ttsState) {
                            TTSState.PLAYING -> ttsManager.pause()
                            TTSState.PAUSED -> ttsManager.resume()
                            else -> {
                                ttsManager.speakText(
                                    text = chapterContent,
                                    startFromParagraph = startFromParagraph,
                                    novelTitle = novelTitle,
                                    chapterTitle = chapterTitle
                                ) {
                                    if (canGoNext) {
                                        onNextChapterWithRetry()
                                    }
                                }
                            }
                        }
                    },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(60.dp),
                    shadowElevation = 4.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (ttsState == TTSState.LOADING) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                imageVector = if (ttsState == TTSState.PLAYING)
                                    Icons.Default.Pause
                                else
                                    Icons.Default.PlayArrow,
                                contentDescription = if (ttsState == TTSState.PLAYING) "Pause" else "Play",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Next sentence
                IconButton(
                    onClick = { ttsManager.skipToNext() },
                    enabled = ttsState == TTSState.PLAYING || ttsState == TTSState.PAUSED,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Spacer to balance stop button
                Spacer(modifier = Modifier.size(40.dp))
            }

            // Voice name pill
            if (currentVoice != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    onClick = { showVoiceSelector = true },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.RecordVoiceOver,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = currentVoice?.displayName ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Error state
            if (ttsState == TTSState.ERROR) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "TTS Error — check device settings",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }

            // Settings panel (collapsible)
            AnimatedVisibility(visible = showSettings) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    HorizontalDivider(
                        modifier = Modifier.padding(bottom = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    SettingSlider(
                        label = "Speed",
                        value = ttsSettings.speed,
                        valueDisplay = String.format("%.1fx", ttsSettings.speed),
                        onValueChange = { ttsManager.setSpeed(it) },
                        valueRange = 0.5f..2.0f,
                        steps = 5
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    SettingSlider(
                        label = "Pitch",
                        value = ttsSettings.pitch,
                        valueDisplay = String.format("%.1f", ttsSettings.pitch),
                        onValueChange = { ttsManager.setPitch(it) },
                        valueRange = 0.5f..2.0f,
                        steps = 5
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    SettingSlider(
                        label = "Volume",
                        value = ttsSettings.volume,
                        valueDisplay = "${(ttsSettings.volume * 100).toInt()}%",
                        onValueChange = { ttsManager.setVolume(it) },
                        valueRange = 0f..1f
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    SettingSlider(
                        label = "Sentence Gap",
                        value = ttsSettings.sentencePauseMs.toFloat(),
                        valueDisplay = "${ttsSettings.sentencePauseMs}ms",
                        onValueChange = { ttsManager.setSentencePause(it.toLong()) },
                        valueRange = 0f..2000f,
                        steps = 7
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    SettingSlider(
                        label = "Paragraph Gap",
                        value = ttsSettings.paragraphPauseMs.toFloat(),
                        valueDisplay = "${ttsSettings.paragraphPauseMs}ms",
                        onValueChange = { ttsManager.setParagraphPause(it.toLong()) },
                        valueRange = 0f..3000f,
                        steps = 5
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Download neural voices
                    TextButton(
                        onClick = onShowModelDownload,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Get Neural Voices")
                    }
                }
            }
        }
    }

    // Voice selector dialog
    if (showVoiceSelector) {
        VoiceSelectorDialog(
            voices = availableVoices,
            currentVoice = currentVoice,
            onVoiceSelected = { ttsManager.setVoice(it) },
            onDismiss = { showVoiceSelector = false }
        )
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    valueDisplay: String,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = valueDisplay,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun VoiceSelectorDialog(
    voices: List<VoiceInfo>,
    currentVoice: VoiceInfo?,
    onVoiceSelected: (VoiceInfo) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Voice") },
        text = {
            if (voices.isEmpty()) {
                Text("No voices available. Please check your device TTS settings.")
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                ) {
                    // Group voices by engine
                    val groupedVoices: Map<String, List<VoiceInfo>> =
                        voices.groupBy { it.engineId }

                    groupedVoices.forEach { (engine: String, engineVoices: List<VoiceInfo>) ->
                        item {
                            Text(
                                text = engine.ifBlank { "Voices" },
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }

                        items(
                            items = engineVoices,
                            key = { it.id }
                        ) { voice: VoiceInfo ->
                            VoiceItem(
                                voice = voice,
                                isSelected = voice.id == currentVoice?.id,
                                displayName = voice.displayName,
                                onClick = {
                                    onVoiceSelected(voice)
                                    onDismiss()
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun VoiceItem(
    voice: VoiceInfo,
    isSelected: Boolean,
    displayName: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer
        else
            Color.Transparent,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = if (voice.isDownloaded)
                        "Available offline"
                    else
                        "Not downloaded",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

