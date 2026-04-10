package com.abhinavxt.novelreader.ui.screens

import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abhinavxt.novelreader.data.BackupInfo
import com.abhinavxt.novelreader.data.BackupManager
import com.abhinavxt.novelreader.data.ColorScheme
import com.abhinavxt.novelreader.data.DictionaryLanguage
import com.abhinavxt.novelreader.data.ThemeMode
import com.abhinavxt.novelreader.data.ThemePreferences
import com.abhinavxt.novelreader.ui.theme.*
import com.abhinavxt.novelreader.ui.viewmodel.BackupUiState
import com.abhinavxt.novelreader.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    backupManager: BackupManager,
    themePreferences: ThemePreferences,
    onNavigateToPronunciation: () -> Unit = {},
    onNavigateToReadingStats: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(backupManager, themePreferences)
    )
) {
    val backupState by viewModel.backupState.collectAsState()
    val currentThemeMode by viewModel.themeMode.collectAsState()
    val currentColorScheme by viewModel.colorScheme.collectAsState()
    val currentDictionaryLanguage by viewModel.dictionaryLanguage.collectAsState()

    // File picker for creating backup
    val createBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.createBackup(it) }
    }

    // File picker for restoring backup
    val restoreBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.prepareRestore(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // ── Appearance Section ────────────────────────────────
            Text(
                text = "Appearance",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Theme Mode
            Text(
                text = "Theme",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThemeMode.entries.forEach { mode ->
                    val icon = when (mode) {
                        ThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                        ThemeMode.LIGHT -> Icons.Default.LightMode
                        ThemeMode.DARK -> Icons.Default.DarkMode
                    }
                    FilterChip(
                        selected = currentThemeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        label = { Text(mode.label) },
                        leadingIcon = {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Color Scheme
            Text(
                text = "Color",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ColorScheme.entries.forEach { scheme ->
                    val previewColor = when (scheme) {
                        ColorScheme.DYNAMIC -> MaterialTheme.colorScheme.primary
                        ColorScheme.PURPLE -> PreviewPurple
                        ColorScheme.BLUE -> PreviewBlue
                        ColorScheme.GREEN -> PreviewGreen
                        ColorScheme.TEAL -> PreviewTeal
                        ColorScheme.RED -> PreviewRed
                        ColorScheme.ORANGE -> PreviewOrange
                    }
                    val isSelected = currentColorScheme == scheme
                    // Hide Dynamic option on pre-Android 12 (not supported)
                    if (scheme != ColorScheme.DYNAMIC || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        ColorCircle(
                            color = previewColor,
                            label = scheme.label,
                            isSelected = isSelected,
                            onClick = { viewModel.setColorScheme(scheme) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Dictionary Language
            Text(
                text = "Dictionary Language",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Words you select in the reader will be looked up in this language",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Show first row (3 chips)
                DictionaryLanguage.entries.take(3).forEach { language ->
                    FilterChip(
                        selected = currentDictionaryLanguage == language,
                        onClick = { viewModel.setDictionaryLanguage(language) },
                        label = { Text(language.label) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Show second row (remaining chips)
                DictionaryLanguage.entries.drop(3).forEach { language ->
                    FilterChip(
                        selected = currentDictionaryLanguage == language,
                        onClick = { viewModel.setDictionaryLanguage(language) },
                        label = { Text(language.label) },
                        modifier = Modifier.weight(1f)
                    )
                }
                // Fill empty slots if needed
                repeat(3 - DictionaryLanguage.entries.drop(3).size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            // ── Backup & Restore Section ─────────────────────────
            Text(
                text = "Backup & Restore",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Save your library, reading progress, and settings to a file. Restore on any device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Backup Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudUpload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Create Backup",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Export library, progress & settings",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Button(
                        onClick = {
                            val filename = viewModel.generateBackupFilename()
                            createBackupLauncher.launch(filename)
                        },
                        enabled = backupState !is BackupUiState.Loading
                    ) {
                        Text("Backup")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Restore Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(40.dp)
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Restore Backup",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Import from backup file",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            restoreBackupLauncher.launch(arrayOf("application/json"))
                        },
                        enabled = backupState !is BackupUiState.Loading
                    ) {
                        Text("Restore")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Info Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "What's included in backup:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "• Library (all added novels)\n" +
                                    "• Reading progress & position\n" +
                                    "• Downloaded chapters (optional)\n" +
                                    "• Bookmarks & notes\n" +
                                    "• Pronunciation dictionary\n" +
                                    "• Reader settings (font, theme)\n" +
                                    "• TTS settings",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            // ── TTS Section ─────────────────────────────────────
            Text(
                text = "Text-to-Speech",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                onClick = onNavigateToPronunciation,
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Pronunciation Dictionary",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Fix mispronounced character names",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            // ── Updates Section ──────────────────────────────────
            Text(
                text = "Chapter Updates",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            val context = androidx.compose.ui.platform.LocalContext.current
            val updatePrefs = remember {
                context.getSharedPreferences(
                    com.abhinavxt.novelreader.worker.UpdateCheckerWorker.PREFS_NAME,
                    android.content.Context.MODE_PRIVATE
                )
            }
            var updateCheckerEnabled by remember {
                mutableStateOf(
                    updatePrefs.getBoolean(com.abhinavxt.novelreader.worker.UpdateCheckerWorker.PREF_ENABLED, false)
                )
            }
            var autoDownloadEnabled by remember {
                mutableStateOf(
                    updatePrefs.getBoolean(com.abhinavxt.novelreader.worker.UpdateCheckerWorker.PREF_AUTO_DOWNLOAD, false)
                )
            }
            var checkInterval by remember {
                mutableStateOf(
                    updatePrefs.getLong(com.abhinavxt.novelreader.worker.UpdateCheckerWorker.PREF_INTERVAL_HOURS, 12)
                )
            }

            // Update checker toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Check for new chapters",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Periodically check library novels for updates",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                androidx.compose.material3.Switch(
                    checked = updateCheckerEnabled,
                    onCheckedChange = { enabled ->
                        updateCheckerEnabled = enabled
                        updatePrefs.edit()
                            .putBoolean(com.abhinavxt.novelreader.worker.UpdateCheckerWorker.PREF_ENABLED, enabled)
                            .apply()
                        com.abhinavxt.novelreader.worker.UpdateCheckerWorker.schedule(
                            context, enabled, checkInterval
                        )
                    }
                )
            }

            // Interval picker (only shown when enabled)
            if (updateCheckerEnabled) {
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Check every",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(6L, 12L, 24L).forEach { hours ->
                        FilterChip(
                            selected = checkInterval == hours,
                            onClick = {
                                checkInterval = hours
                                updatePrefs.edit()
                                    .putLong(com.abhinavxt.novelreader.worker.UpdateCheckerWorker.PREF_INTERVAL_HOURS, hours)
                                    .apply()
                                com.abhinavxt.novelreader.worker.UpdateCheckerWorker.schedule(
                                    context, true, hours
                                )
                            },
                            label = { Text("${hours}h") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Auto-download toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-download new chapters",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Download chapters automatically when found",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = autoDownloadEnabled,
                        onCheckedChange = { enabled ->
                            autoDownloadEnabled = enabled
                            updatePrefs.edit()
                                .putBoolean(com.abhinavxt.novelreader.worker.UpdateCheckerWorker.PREF_AUTO_DOWNLOAD, enabled)
                                .apply()
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Check now button
                OutlinedButton(
                    onClick = {
                        com.abhinavxt.novelreader.worker.UpdateCheckerWorker.runNow(context)
                    }
                ) {
                    Text("Check Now")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            // ── Reading Stats Section ────────────────────────────
            Text(
                text = "Reading",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                onClick = onNavigateToReadingStats,
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Reading Stats",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Words read, time spent, streaks",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            // ── About Section ────────────────────────────────────
            Text(
                text = "About",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Novel Reader v1.6.0",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Read web novels offline with multi-engine neural TTS, audio export, and bookmarks.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "What's New in v1.6.0",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "• Pronunciation dictionary for TTS name corrections\n" +
                                "• Reading stats dashboard with streaks and daily charts\n" +
                                "• Chapter update checker with auto-download\n" +
                                "• Offline filter and download/export range picker\n" +
                                "• Library sorting (last read, title, chapters, added)\n" +
                                "• Update badges on library cards\n" +
                                "• Bookmarks and pronunciations included in backup\n" +
                                "• 6 new reader themes (Nord, Mocha, Dracula, AMOLED, Gruvbox, Catppuccin)\n" +
                                "• Primordial Translation source added",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Features",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "• 7 novel sources + EPUB import\n" +
                        "• On-device neural TTS (Piper, Kokoro, KittenTTS)\n" +
                        "• Google TTS with 67+ voices across 24 languages\n" +
                        "• Pronunciation dictionary for character names\n" +
                        "• Per-chapter audio export with range selection\n" +
                        "• Built-in audio player with speed control\n" +
                        "• Reading stats, streaks, and daily tracking\n" +
                        "• Bookmarks with notes and passage snippets\n" +
                        "• 14 reader themes including Nord, Mocha, Dracula\n" +
                        "• Scheduled chapter update checker\n" +
                        "• Dictionary lookup (6 languages)\n" +
                        "• Backup and restore with full data",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // Handle different states
    when (val state = backupState) {
        is BackupUiState.Loading -> {
            LoadingDialog()
        }
        is BackupUiState.BackupSuccess -> {
            SuccessDialog(
                title = "Backup Complete",
                message = state.message,
                onDismiss = { viewModel.dismissMessage() }
            )
        }
        is BackupUiState.RestoreSuccess -> {
            SuccessDialog(
                title = "Restore Complete",
                message = state.message,
                onDismiss = { viewModel.dismissMessage() }
            )
        }
        is BackupUiState.Error -> {
            ErrorDialog(
                message = state.message,
                onDismiss = { viewModel.dismissMessage() }
            )
        }
        is BackupUiState.ConfirmRestore -> {
            RestoreConfirmDialog(
                info = state.info,
                onConfirm = { includeDownloads ->
                    viewModel.confirmRestore(state.uri, includeDownloads)
                },
                onCancel = { viewModel.cancelRestore() }
            )
        }
        is BackupUiState.Idle -> { /* Do nothing */ }
    }
}

// ── Color circle picker ──────────────────────────────────────────

@Composable
private fun ColorCircle(
    color: Color,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(color)
                .then(
                    if (isSelected) Modifier.border(
                        width = 3.dp,
                        color = MaterialTheme.colorScheme.onBackground,
                        shape = CircleShape
                    ) else Modifier
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── Dialogs (unchanged) ──────────────────────────────────────────

@Composable
private fun LoadingDialog() {
    AlertDialog(
        onDismissRequest = { },
        confirmButton = { },
        icon = {
            CircularProgressIndicator(modifier = Modifier.size(48.dp))
        },
        title = {
            Text("Please wait...")
        },
        text = {
            Text("This may take a moment for large libraries.")
        }
    )
}

@Composable
private fun SuccessDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("OK")
            }
        },
        icon = {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
        },
        title = { Text(title) },
        text = { Text(message) }
    )
}

@Composable
private fun ErrorDialog(
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("OK")
            }
        },
        icon = {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp)
            )
        },
        title = { Text("Error") },
        text = { Text(message) }
    )
}

@Composable
private fun RestoreConfirmDialog(
    info: BackupInfo,
    onConfirm: (includeDownloads: Boolean) -> Unit,
    onCancel: () -> Unit
) {
    var includeDownloads by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            Button(onClick = { onConfirm(includeDownloads) }) {
                Text("Restore")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        },
        icon = {
            Icon(
                imageVector = Icons.Default.Restore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(48.dp)
            )
        },
        title = { Text("Restore Backup?") },
        text = {
            Column {
                Text("This will restore from the backup file.")

                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        InfoRow("Novels", "${info.novelCount}")
                        InfoRow("Chapters", "${info.chapterCount}")
                        InfoRow("Downloaded", "${info.downloadedChapterCount}")
                        if (info.bookmarkCount > 0) {
                            InfoRow("Bookmarks", "${info.bookmarkCount}")
                        }
                        if (info.pronunciationCount > 0) {
                            InfoRow("Pronunciations", "${info.pronunciationCount}")
                        }
                        InfoRow("Created", info.createdAt)
                        InfoRow("Size", info.sizeFormatted)
                    }
                }

                if (info.downloadedChapterCount > 0) {
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = includeDownloads,
                            onCheckedChange = { includeDownloads = it }
                        )
                        Text(
                            text = "Include downloaded chapters",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Existing data will be merged. Duplicates will be overwritten.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}