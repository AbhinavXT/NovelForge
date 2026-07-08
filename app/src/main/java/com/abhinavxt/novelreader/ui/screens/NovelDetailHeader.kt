package com.abhinavxt.novelreader.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.ExperimentalFoundationApi
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
import com.abhinavxt.novelreader.ui.components.novelCoverShared
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
internal fun NovelHeader(
    novel: Novel,
    isInLibrary: Boolean,
    isLocalNovel: Boolean,
    downloadState: NovelDownloadState?,
    estimatedTimeToFinish: String? = null,
    onToggleLibrary: () -> Unit,
    onStartReading: () -> Unit,
    onDownloadAll: () -> Unit
) {
    // Resolve cover URL vs local file path
    val imageModel = remember(novel.coverUrl) {
        when {
            novel.coverUrl == null -> null
            novel.coverUrl.startsWith("/") -> File(novel.coverUrl)
            else -> novel.coverUrl
        }
    }

    android.util.Log.d("NovelDetail", "NovelHeader: title=${novel.title}, coverUrl=${novel.coverUrl}, imageModel=$imageModel")

    val surfaceColor = MaterialTheme.colorScheme.surface

    // ── Cinematic header: blurred backdrop + gradient scrim + cover ──
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 280.dp)
    ) {
        // Layer 1: Blurred cover as atmospheric backdrop
        if (imageModel != null) {
            val referer = if (novel.coverUrl != null && !novel.coverUrl.startsWith("/")) {
                try {
                    val uri = android.net.Uri.parse(novel.coverUrl)
                    "${uri.scheme}://${uri.host}"
                } catch (_: Exception) { null }
            } else null

            android.util.Log.d("NovelDetail", "Backdrop: coverUrl=${novel.coverUrl}, referer=$referer, imageModel=$imageModel")

            val backdropRequest = ImageRequest.Builder(LocalContext.current)
                .data(imageModel)
                .crossfade(400)
                .listener(
                    onStart = { android.util.Log.d("NovelDetail", "Backdrop Coil START: ${novel.coverUrl}") },
                    onSuccess = { _, _ -> android.util.Log.d("NovelDetail", "Backdrop Coil SUCCESS: ${novel.coverUrl}") },
                    onError = { _, result -> android.util.Log.e("NovelDetail", "Backdrop Coil ERROR: ${novel.coverUrl} — ${result.throwable}") }
                )
            if (referer != null) {
                backdropRequest.addHeader("Referer", referer)
            }
            AsyncImage(
                model = backdropRequest.build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.35f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .blur(25.dp)
            )
        } else {
            // Gradient fallback when no cover exists
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                surfaceColor
                            )
                        )
                    )
            )
        }

        // Layer 2: Gradient scrim — fades backdrop into surface color
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            surfaceColor.copy(alpha = 0.6f),
                            surfaceColor
                        ),
                        startY = 0f,
                        endY = Float.POSITIVE_INFINITY
                    )
                )
        )

        // Layer 3: Actual content — cover + info overlaid on backdrop
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 60.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                // Cover image with rounded corners and shadow
                NovelCover(
                    coverUrl = novel.coverUrl,
                    modifier = Modifier.novelCoverShared(novel.id),
                    width = 110.dp,
                    height = 160.dp
                )

                Spacer(modifier = Modifier.width(16.dp))

                // Title, author, status badges
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 24.dp)
                ) {
                    Text(
                        text = novel.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = novel.author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Status badges
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isLocalNovel) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "Local",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }

                        Surface(
                            color = if (novel.status.equals("Completed", ignoreCase = true))
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = novel.status,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        // Chapter count badge
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "${novel.chapters.size} ch",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Reading time estimate badge — shows "~12 hrs" based on user WPM
                        if (estimatedTimeToFinish != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = estimatedTimeToFinish,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Action buttons ──────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (!isLocalNovel && AppConfig.ONLINE_SOURCES_ENABLED) {
                    OutlinedButton(
                        onClick = onToggleLibrary,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (isInLibrary)
                                Icons.Default.CheckCircle
                            else
                                Icons.Default.LibraryAdd,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isInLibrary) "Added" else "Add")
                    }
                }

                Button(
                    onClick = onStartReading,
                    enabled = novel.chapters.isNotEmpty(),
                    modifier = if (isLocalNovel || !AppConfig.ONLINE_SOURCES_ENABLED)
                        Modifier.fillMaxWidth() else Modifier.weight(1f)
                ) {
                    Text("Read")
                }
            }

            // Download button — only for online novels
            if (!isLocalNovel && AppConfig.ONLINE_SOURCES_ENABLED) {
                Spacer(modifier = Modifier.height(8.dp))

                val isDownloading = downloadState?.novelId == novel.id && downloadState.isDownloading
                val downloadedCount = novel.chapters.count { it.isDownloaded }
                val totalCount = novel.chapters.size
                val allDownloaded = downloadedCount == totalCount && totalCount > 0

                OutlinedButton(
                    onClick = onDownloadAll,
                    enabled = !isDownloading && !allDownloaded && novel.chapters.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Downloading ${downloadState?.downloadedChapters}/${downloadState?.totalChapters}")
                    } else if (allDownloaded) {
                        Icon(
                            imageVector = Icons.Default.DownloadDone,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("All Downloaded")
                    } else if (downloadedCount > 0) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Download Remaining (${totalCount - downloadedCount})")
                    } else {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Download All ($totalCount)")
                    }
                }
            }
        }
    }
}

@Composable
internal fun NovelDescription(description: String) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = "Synopsis",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = description.ifEmpty { "No description available." },
            style = MaterialTheme.typography.bodyMedium
        )
    }
    HorizontalDivider()
}

@Composable
internal fun AudioVoicePicker(
    availableVoices: List<com.abhinavxt.novelreader.data.tts.VoiceInfo>,
    currentVoice: com.abhinavxt.novelreader.data.tts.VoiceInfo?,
    onVoiceSelected: (com.abhinavxt.novelreader.data.tts.VoiceInfo) -> Unit,
    onShowModelDownload: () -> Unit
) {
    var showVoiceDialog by remember { mutableStateOf(false) }

    // Show current voice name in chip
    val displayVoiceName = remember(currentVoice) {
        currentVoice?.displayName ?: "Select voice"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.GraphicEq,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Audio voice:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.width(8.dp))

        Surface(
            onClick = { showVoiceDialog = true },
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.small
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayVoiceName,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Change voice",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }

    if (showVoiceDialog) {
        VoiceSelectorDialog(
            voices = availableVoices,
            currentVoice = currentVoice,
            onVoiceSelected = onVoiceSelected,
            onDismiss = { showVoiceDialog = false },
            onShowModelDownload = onShowModelDownload
        )
    }
}

@Composable
private fun VoiceSelectorDialog(
    voices: List<com.abhinavxt.novelreader.data.tts.VoiceInfo>,
    currentVoice: com.abhinavxt.novelreader.data.tts.VoiceInfo?,
    onVoiceSelected: (com.abhinavxt.novelreader.data.tts.VoiceInfo) -> Unit,
    onDismiss: () -> Unit,
    onShowModelDownload: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Audio Voice") },
        text = {
            if (voices.isEmpty()) {
                Text("No voices available. Check device TTS settings or download neural voices from the reader screen.")
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                ) {
                    val groupedVoices = voices.groupBy { it.engineId }

                    groupedVoices.forEach { (engine, engineVoices) ->
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
                        ) { voice ->
                            Surface(
                                onClick = {
                                    onVoiceSelected(voice)
                                    onDismiss()
                                },
                                color = if (voice.id == currentVoice?.id)
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
                                    Text(
                                        text = voice.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (voice.id == currentVoice?.id) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Download neural voices button
                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        TextButton(
                            onClick = {
                                onDismiss()
                                onShowModelDownload()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Get Neural Voices (Piper, Kokoro\u2026)")
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

