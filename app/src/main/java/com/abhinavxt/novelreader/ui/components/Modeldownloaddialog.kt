package com.abhinavxt.novelreader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.abhinavxt.novelreader.data.tts.DownloadState
import com.abhinavxt.novelreader.data.tts.ModelFamily
import com.abhinavxt.novelreader.data.tts.TTSModelInfo
import com.abhinavxt.novelreader.data.tts.TTSModelManager
import kotlinx.coroutines.launch

/**
 * Dialog that shows available TTS models for download.
 *
 * Usage in ReaderScreen:
 * ```
 * var showModelDialog by remember { mutableStateOf(false) }
 *
 * if (showModelDialog) {
 *     ModelDownloadDialog(
 *         modelManager = ttsManager.modelManager,
 *         onDismiss = { showModelDialog = false },
 *         onModelDownloaded = {
 *             ttsManager.refreshVoiceList()
 *         }
 *     )
 * }
 * ```
 */
@Composable
fun ModelDownloadDialog(
    modelManager: TTSModelManager,
    onDismiss: () -> Unit,
    onModelDownloaded: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val downloadStates by modelManager.downloadProgress.collectAsState()

    // Build catalog grouped by language
    val catalogByLanguage = remember(downloadStates) {
        modelManager.getModelCatalogByLanguage()
    }

    // Language filter
    var selectedLanguage by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Voice Models",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${catalogByLanguage.values.sumOf { it.size }} voices in ${catalogByLanguage.size} languages",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                // Language filter chips
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = selectedLanguage == null,
                            onClick = { selectedLanguage = null },
                            label = { Text("All") }
                        )
                    }
                    items(catalogByLanguage.keys.toList()) { langCode ->
                        FilterChip(
                            selected = selectedLanguage == langCode,
                            onClick = {
                                selectedLanguage = if (selectedLanguage == langCode) null else langCode
                            },
                            label = {
                                Text(
                                    TTSModelManager.getLanguageDisplayName(langCode).substringBefore(" ("),
                                    maxLines = 1
                                )
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()

                // Model list grouped by language
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val languagesToShow = if (selectedLanguage != null) {
                        catalogByLanguage.filterKeys { it == selectedLanguage }
                    } else {
                        catalogByLanguage
                    }

                    languagesToShow.forEach { (langCode, models) ->
                        item(key = "header_$langCode") {
                            Text(
                                text = TTSModelManager.getLanguageDisplayName(langCode),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                        }

                        items(models, key = { it.id }) { model ->
                            val state = downloadStates[model.id] ?: if (model.isDownloaded) {
                                DownloadState.Completed
                            } else {
                                DownloadState.NotDownloaded
                            }

                            ModelCard(
                                model = model,
                                downloadState = state,
                                onDownload = {
                                    scope.launch {
                                        val success = modelManager.downloadModel(model.id)
                                        if (success) {
                                            onModelDownloaded()
                                        }
                                    }
                                },
                                onDelete = {
                                    modelManager.deleteModel(model.id)
                                    onModelDownloaded()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: TTSModelInfo,
    downloadState: DownloadState,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Title row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${model.family.displayName} · ${model.sizeDisplay}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Action button
                when (downloadState) {
                    is DownloadState.NotDownloaded -> {
                        FilledTonalButton(
                            onClick = onDownload,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = "Download",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Download", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    is DownloadState.Downloading -> {
                        // Show progress
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { downloadState.progress },
                                modifier = Modifier.size(36.dp),
                                strokeWidth = 3.dp
                            )
                            Text(
                                text = "${(downloadState.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }

                    is DownloadState.Extracting -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Extracting…", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    is DownloadState.Completed -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Downloaded",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "Ready",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = onDelete,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    is DownloadState.Error -> {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "Failed",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            TextButton(
                                onClick = onDownload,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text("Retry", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            // Description
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = model.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Progress bar for downloading state
            if (downloadState is DownloadState.Downloading) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { downloadState.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}