package com.abhinavxt.novelforge.ui.screens

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abhinavxt.novelforge.data.BackupInfo
import com.abhinavxt.novelforge.data.BackupManager
import com.abhinavxt.novelforge.worker.AutoBackupWorker
import com.abhinavxt.novelforge.data.AppFont
import com.abhinavxt.novelforge.data.AppTheme
import com.abhinavxt.novelforge.data.ColorScheme
import com.abhinavxt.novelforge.data.DictionaryLanguage
import com.abhinavxt.novelforge.data.ThemeMode
import com.abhinavxt.novelforge.data.ThemePreferences
import com.abhinavxt.novelforge.ui.theme.*
import com.abhinavxt.novelforge.ui.viewmodel.BackupUiState
import com.abhinavxt.novelforge.ui.viewmodel.SettingsViewModel
import com.abhinavxt.novelforge.BuildConfig
import com.abhinavxt.novelforge.data.UpdateChecker
import com.abhinavxt.novelforge.ui.components.UpdateStatusRow
import com.abhinavxt.novelforge.ui.components.openUpdateUrlInBrowser
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    backupManager: BackupManager,
    themePreferences: ThemePreferences,
    onNavigateToPronunciation: () -> Unit = {},
    onNavigateToReadingStats: () -> Unit = {},
    onNavigateToChangelog: () -> Unit = {},
    updateChecker: UpdateChecker,
    viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(backupManager, themePreferences)
    )
) {
    val backupState by viewModel.backupState.collectAsState()
    val currentThemeMode by viewModel.themeMode.collectAsState()
    val currentColorScheme by viewModel.colorScheme.collectAsState()
    val currentAppFont by viewModel.appFont.collectAsState()
    val currentCustomPrimary by viewModel.customPrimaryColor.collectAsState()
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

    val updateStatus by updateChecker.status.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    UpdateStatusRow(
        status = updateStatus,
        currentVersion = BuildConfig.VERSION_NAME,
        onCheckNow = {
            scope.launch {
                updateChecker.check(force = true)
            }
        },
        onOpenDownload = { url ->
            openUpdateUrlInBrowser(context, url)
        }
    )

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

            // ── Compact appearance rows ──────────────────────────
            // Full pickers moved into bottom sheets (same interaction
            // language as the reader's QuickSettingsSheet). Selection
            // applies immediately, so the app re-themes live behind
            // the open sheet.
            val currentAppTheme by viewModel.appTheme.collectAsState()
            val currentCustomAppBackground by viewModel.customAppBackground.collectAsState()
            var showThemeSheet by remember { mutableStateOf(false) }
            var showColorSheet by remember { mutableStateOf(false) }
            var showFontSheet by remember { mutableStateOf(false) }

            AppearanceRow(
                title = "Theme",
                value = currentAppTheme.label +
                        if (currentAppTheme == AppTheme.DEFAULT) " · ${currentThemeMode.label}" else "",
                onClick = { showThemeSheet = true },
                leading = {
                    ThemePreviewDot(
                        theme = currentAppTheme,
                        customBackground = currentCustomAppBackground
                    )
                }
            )

            // Accent applies to the Default theme only — palettes
            // carry their own canonical accents.
            if (currentAppTheme == AppTheme.DEFAULT) {
                AppearanceRow(
                    title = "Accent color",
                    value = currentColorScheme.label,
                    onClick = { showColorSheet = true },
                    leading = {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(
                                    if (currentColorScheme == ColorScheme.CUSTOM)
                                        Color(currentCustomPrimary)
                                    else MaterialTheme.colorScheme.primary
                                )
                        )
                    }
                )
            }

            AppearanceRow(
                title = "Font",
                value = currentAppFont.label,
                onClick = { showFontSheet = true },
                leading = {
                    Text(
                        text = "Ag",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = if (currentAppFont == AppFont.DEFAULT) HeadingFont
                        else currentAppFont.toFontFamily(),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            )

            if (showThemeSheet) {
                ThemePickerSheet(
                    currentTheme = currentAppTheme,
                    currentMode = currentThemeMode,
                    customBackground = currentCustomAppBackground,
                    customAccent = currentCustomPrimary,
                    onThemeSelected = { viewModel.setAppTheme(it) },
                    onModeSelected = { viewModel.setThemeMode(it) },
                    onBackgroundPicked = { viewModel.setCustomAppBackground(it) },
                    onAccentPicked = { viewModel.setCustomPrimaryColor(it) },
                    onDismiss = { showThemeSheet = false }
                )
            }
            if (showColorSheet) {
                AccentPickerSheet(
                    currentScheme = currentColorScheme,
                    customSeed = currentCustomPrimary,
                    onSchemeSelected = { viewModel.setColorScheme(it) },
                    onSeedPicked = { viewModel.setCustomPrimaryColor(it) },
                    onDismiss = { showColorSheet = false }
                )
            }
            if (showFontSheet) {
                FontPickerSheet(
                    currentFont = currentAppFont,
                    onFontSelected = { viewModel.setAppFont(it) },
                    onDismiss = { showFontSheet = false }
                )
            }

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

            // ── Automatic backups (Phase 7) ──────────────────────
            // UI state mirrors AutoBackupWorker's prefs; the worker
            // companion owns persistence AND (re)scheduling.
            var autoBackupEnabled by remember {
                mutableStateOf(AutoBackupWorker.isEnabled(context))
            }
            var autoBackupFolder by remember {
                mutableStateOf(AutoBackupWorker.getFolderUri(context))
            }
            val folderPickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (uri != null) {
                    // Persist access across reboots — without this the
                    // worker loses the folder after the device restarts
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    AutoBackupWorker.setFolderUri(context, uri.toString())
                    autoBackupFolder = uri.toString()
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Automatic daily backups",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (autoBackupFolder != null) {
                                    val last = AutoBackupWorker.getLastBackupTime(context)
                                    if (last > 0L) {
                                        "Keeps the 5 newest · last: " +
                                                android.text.format.DateUtils.getRelativeTimeSpanString(last)
                                    } else {
                                        "Keeps the 5 newest backups"
                                    }
                                } else {
                                    "Choose a folder to enable"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoBackupEnabled,
                            onCheckedChange = { checked ->
                                if (checked && autoBackupFolder == null) {
                                    // Need a destination first
                                    folderPickerLauncher.launch(null)
                                } else {
                                    AutoBackupWorker.setEnabled(context, checked)
                                    autoBackupEnabled = checked
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(onClick = { folderPickerLauncher.launch(null) }) {
                        Text(
                            text = if (autoBackupFolder == null) "Choose backup folder"
                            else "Change backup folder"
                        )
                    }
                }
            }

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
                    com.abhinavxt.novelforge.worker.UpdateCheckerWorker.PREFS_NAME,
                    android.content.Context.MODE_PRIVATE
                )
            }
            var updateCheckerEnabled by remember {
                mutableStateOf(
                    updatePrefs.getBoolean(com.abhinavxt.novelforge.worker.UpdateCheckerWorker.PREF_ENABLED, false)
                )
            }
            var autoDownloadEnabled by remember {
                mutableStateOf(
                    updatePrefs.getBoolean(com.abhinavxt.novelforge.worker.UpdateCheckerWorker.PREF_AUTO_DOWNLOAD, false)
                )
            }
            var checkInterval by remember {
                mutableStateOf(
                    updatePrefs.getLong(com.abhinavxt.novelforge.worker.UpdateCheckerWorker.PREF_INTERVAL_HOURS, 12)
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
                            .putBoolean(com.abhinavxt.novelforge.worker.UpdateCheckerWorker.PREF_ENABLED, enabled)
                            .apply()
                        com.abhinavxt.novelforge.worker.UpdateCheckerWorker.schedule(
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
                                    .putLong(com.abhinavxt.novelforge.worker.UpdateCheckerWorker.PREF_INTERVAL_HOURS, hours)
                                    .apply()
                                com.abhinavxt.novelforge.worker.UpdateCheckerWorker.schedule(
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
                                .putBoolean(com.abhinavxt.novelforge.worker.UpdateCheckerWorker.PREF_AUTO_DOWNLOAD, enabled)
                                .apply()
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Check now button
                OutlinedButton(
                    onClick = {
                        com.abhinavxt.novelforge.worker.UpdateCheckerWorker.runNow(context)
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
                text = "Novel Forge v1.8.0",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Read web novels offline with neural TTS, highlights, audio export, and bookmarks.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Changelog navigation card — tapping opens the full version history screen
            Card(
                onClick = onNavigateToChangelog,
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
                            text = "Changelog",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Version history and release notes",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
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

// ── Compact appearance preference row ────────────────────────────
// Leading preview + title + current value + chevron. One line per
// setting; the full picker lives in a bottom sheet.

@Composable
private fun AppearanceRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    leading: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(32.dp),
                contentAlignment = Alignment.Center
            ) { leading() }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ThemePreviewDot(theme: AppTheme, customBackground: Long) {
    val preview = appThemePreviewColors(theme)
    val bg: Color
    val text: Color
    when {
        preview != null -> { bg = preview.first; text = preview.second }
        theme == AppTheme.CUSTOM -> {
            bg = Color(customBackground)
            text = if (bg.luminance() < 0.5f) Color(0xFFE3E0DA) else Color(0xFF24211D)
        }
        else -> {
            bg = MaterialTheme.colorScheme.background
            text = MaterialTheme.colorScheme.primary
        }
    }
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text("A", color = text, fontSize = 12.sp, fontFamily = FontFamily.Serif)
    }
}

// ── Picker bottom sheets ─────────────────────────────────────────
// LazyColumn inside ModalBottomSheet, per the QuickSettingsSheet
// precedent — nested scrolling stays correct and long content
// remains reachable. Selections apply immediately (live preview);
// swipe down or tap scrim to dismiss.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemePickerSheet(
    currentTheme: AppTheme,
    currentMode: ThemeMode,
    customBackground: Long,
    customAccent: Long,
    onThemeSelected: (AppTheme) -> Unit,
    onModeSelected: (ThemeMode) -> Unit,
    onBackgroundPicked: (Long) -> Unit,
    onAccentPicked: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            item {
                Text(
                    text = "App Theme",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            // Mode chips — Default theme only
            if (currentTheme == AppTheme.DEFAULT) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = currentMode == mode,
                                onClick = { onModeSelected(mode) },
                                label = { Text(mode.label) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Light",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                AppThemeRow(
                    themes = listOf(
                        AppTheme.DEFAULT, AppTheme.PAPER,
                        AppTheme.SEPIA, AppTheme.SOLARIZED_LIGHT
                    ),
                    currentTheme = currentTheme,
                    customBackground = customBackground,
                    onSelect = onThemeSelected
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            item {
                Text(
                    text = "Dark",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            val darkThemes = listOf(
                AppTheme.DARK, AppTheme.AMOLED, AppTheme.NORD, AppTheme.DRACULA,
                AppTheme.GRUVBOX, AppTheme.CATPPUCCIN, AppTheme.NAVY, AppTheme.GREY
            )
            items(darkThemes.chunked(4)) { rowThemes ->
                AppThemeRow(
                    themes = rowThemes,
                    currentTheme = currentTheme,
                    customBackground = customBackground,
                    onSelect = onThemeSelected
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            item {
                AppThemeRow(
                    themes = listOf(AppTheme.CUSTOM),
                    currentTheme = currentTheme,
                    customBackground = customBackground,
                    onSelect = onThemeSelected
                )
            }

            if (currentTheme == AppTheme.CUSTOM) {
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    SwatchRow(
                        label = "Background",
                        swatches = appBackgroundSwatches,
                        selected = customBackground,
                        onPick = onBackgroundPicked
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SwatchRow(
                        label = "Accent",
                        swatches = customSeedSwatches,
                        selected = customAccent,
                        onPick = onAccentPicked
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccentPickerSheet(
    currentScheme: ColorScheme,
    customSeed: Long,
    onSchemeSelected: (ColorScheme) -> Unit,
    onSeedPicked: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            item {
                Text(
                    text = "Accent Color",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            val visibleSchemes = ColorScheme.entries.filter { scheme ->
                scheme != ColorScheme.DYNAMIC ||
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            }
            items(visibleSchemes.chunked(4)) { rowSchemes ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowSchemes.forEach { scheme ->
                        val previewColor = when (scheme) {
                            ColorScheme.DYNAMIC -> MaterialTheme.colorScheme.primary
                            ColorScheme.PURPLE -> PreviewPurple
                            ColorScheme.BLUE -> PreviewBlue
                            ColorScheme.GREEN -> PreviewGreen
                            ColorScheme.TEAL -> PreviewTeal
                            ColorScheme.RED -> PreviewRed
                            ColorScheme.ORANGE -> PreviewOrange
                            ColorScheme.PINK -> PreviewPink
                            ColorScheme.INDIGO -> PreviewIndigo
                            ColorScheme.CYAN -> PreviewCyan
                            ColorScheme.AMBER -> PreviewAmber
                            ColorScheme.CUSTOM -> Color(customSeed)
                        }
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            ColorCircle(
                                color = previewColor,
                                label = scheme.label,
                                isSelected = currentScheme == scheme,
                                onClick = { onSchemeSelected(scheme) }
                            )
                        }
                    }
                    repeat(4 - rowSchemes.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
            if (currentScheme == ColorScheme.CUSTOM) {
                item {
                    SwatchRow(
                        label = "Custom color",
                        swatches = customSeedSwatches,
                        selected = customSeed,
                        onPick = onSeedPicked
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FontPickerSheet(
    currentFont: AppFont,
    onFontSelected: (AppFont) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            item {
                Text(
                    text = "App Font",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "Applies across the whole app. The reader keeps its own font setting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            items(AppFont.entries, key = { it.name }) { font ->
                AppFontRow(
                    font = font,
                    isSelected = font == currentFont,
                    onClick = { onFontSelected(font) }
                )
            }
        }
    }
}

// ── App theme grid pieces ────────────────────────────────────────
// Reader-style theme circles: palette background disc with an "A"
// in the palette's text color, label underneath. DEFAULT previews
// with the live Material scheme; CUSTOM with the picked background.

@Composable
private fun AppThemeRow(
    themes: List<AppTheme>,
    currentTheme: AppTheme,
    customBackground: Long,
    onSelect: (AppTheme) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        themes.forEach { theme ->
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                AppThemeCircle(
                    theme = theme,
                    isSelected = theme == currentTheme,
                    customBackground = customBackground,
                    onClick = { onSelect(theme) }
                )
            }
        }
        repeat(4 - themes.size) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun AppThemeCircle(
    theme: AppTheme,
    isSelected: Boolean,
    customBackground: Long,
    onClick: () -> Unit
) {
    val preview = appThemePreviewColors(theme)
    val bg: Color
    val text: Color
    when {
        preview != null -> { bg = preview.first; text = preview.second }
        theme == AppTheme.CUSTOM -> {
            bg = Color(customBackground)
            text = if (bg.luminance() < 0.5f) Color(0xFFE3E0DA) else Color(0xFF24211D)
        }
        else -> { // DEFAULT — live scheme
            bg = MaterialTheme.colorScheme.background
            text = MaterialTheme.colorScheme.primary
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(bg)
                .then(
                    if (isSelected)
                        Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    else
                        Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text("A", color = text, fontSize = 16.sp, fontFamily = FontFamily.Serif)
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = theme.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ── Shared swatch row (backgrounds / accent seeds) ───────────────

@Composable
private fun SwatchRow(
    label: String,
    swatches: List<Long>,
    selected: Long,
    onPick: (Long) -> Unit
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline
    )
    Spacer(modifier = Modifier.height(6.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        swatches.forEach { colorLong ->
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(colorLong))
                    .border(
                        width = if (selected == colorLong) 3.dp else 1.dp,
                        color = if (selected == colorLong)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        shape = CircleShape
                    )
                    .clickable { onPick(colorLong) }
            )
        }
    }
}

// ── App font row ─────────────────────────────────────────────────
// Same shape as the reader's FontRow in QuickSettingsSheet: name +
// pangram preview rendered in the font itself, check when selected.

@Composable
private fun AppFontRow(
    font: AppFont,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    // DEFAULT previews with the heading font so the row visually
    // matches what the user will actually see in titles.
    val previewFamily = if (font == AppFont.DEFAULT) HeadingFont else font.toFontFamily()
    Surface(
        onClick = onClick,
        color = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        else
            Color.Transparent,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = font.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = previewFamily,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "The quick brown fox jumps over the lazy dog",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = previewFamily,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1
                )
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
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
                        if (info.readingStatsCount > 0) {
                            InfoRow("Reading Sessions", "${info.readingStatsCount}")
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