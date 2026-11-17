package com.abhinavxt.novelforge.ui.components

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.abhinavxt.novelforge.ui.viewmodel.SyncViewModel

/** Settings -> Sync: the reader's own WebDAV folder. No NovelForge server is involved. */
@Composable
fun SyncSettingsSection(viewModel: SyncViewModel) {
    val s by viewModel.state.collectAsState()
    var showPassword by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var showWhat by remember { mutableStateOf(false) }
    // A background sync may have paused sync since this screen was built.
    LaunchedEffect(Unit) { viewModel.refreshStatus() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Keep your library, reading positions, bookmarks and highlights the same on your devices. " +
                "It uses a WebDAV folder you provide, such as Nextcloud or a NAS. There is no NovelForge server.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = s.serverUrl,
            onValueChange = viewModel::onServerUrlChange,
            label = { Text("Server address") },
            placeholder = { Text("https://cloud.example.com/remote.php/dav/files/NAME/") },
            supportingText = { Text(s.urlError ?: "Must start with https://") },
            isError = s.urlError != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = s.username,
            onValueChange = viewModel::onUsernameChange,
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = s.passwordInput,
            onValueChange = viewModel::onPasswordChange,
            label = { Text("Password") },
            placeholder = { if (s.passwordSaved) Text("Saved on this device") },
            supportingText = {
                Text(
                    if (s.passwordSaved) "Leave empty to keep the saved password. It is stored encrypted and left out of backups."
                    else "Stored encrypted on this phone and left out of backups."
                )
            },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (showPassword) "Hide password" else "Show password"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = s.deviceName,
            onValueChange = viewModel::onDeviceNameChange,
            label = { Text("Device name") },
            supportingText = { Text("Shown to your other devices.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (s.pinnedFingerprint != null) {
            Text(
                "Trusted certificate: ${s.pinnedFingerprint}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = viewModel::forgetCertificate) { Text("Stop trusting this certificate") }
        }

        s.message?.let {
            Text(
                it.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (it.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = viewModel::testConnection, enabled = !s.busy) { Text("Test connection") }
            Button(onClick = viewModel::save, enabled = !s.busy) { Text(if (s.connected) "Save" else "Save and sync") }
        }
        if (s.connected) {
            OutlinedButton(onClick = viewModel::syncNow, enabled = !s.busy) {
                Text(if (s.busy) "Syncing..." else "Sync now")
            }
            Text(
                if (s.lastSyncTime > 0L) {
                    "Last synced " + DateUtils.getRelativeTimeSpanString(s.lastSyncTime) +
                        if (s.lastResult.isNotBlank()) ". ${s.lastResult}" else ""
                } else if (s.lastResult.isNotBlank()) s.lastResult else "Not synced yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Sync automatically", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "When the app opens, after you stop reading, and every 12 hours.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = s.enabled, onCheckedChange = viewModel::setEnabled)
            }
        }

        if (s.pendingRemoteRemovals > 0 || s.pendingLocalRemovals > 0) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (s.pendingRemoteRemovals > 0) {
                        val n = s.pendingRemoteRemovals
                        Text(
                            "Sync is paused. Another device removed $n ${if (n == 1) "book" else "books"}. " +
                                "Remove them here too, or keep them?",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Row {
                            TextButton(onClick = viewModel::removeHere, enabled = !s.busy) { Text("Remove here") }
                            TextButton(onClick = viewModel::keepThem, enabled = !s.busy) { Text("Keep them") }
                        }
                    }
                    if (s.pendingLocalRemovals > 0) {
                        val n = s.pendingLocalRemovals
                        Text(
                            "Sync is paused. This device no longer has $n ${if (n == 1) "book" else "books"} " +
                                "that are in sync. Remove them from your other devices too? " +
                                "Sync stays paused until you answer.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        TextButton(onClick = viewModel::removeEverywhere, enabled = !s.busy) { Text("Remove everywhere") }
                    }
                }
            }
        }

        if (s.skewWarning) {
            Text(
                "The clock on one of your devices differs from the server's by more than 2 minutes. " +
                    "Set its date and time to automatic, or edits may be ordered wrongly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        s.skippedDevices.forEach {
            Text(
                "Skipped $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        TextButton(onClick = { showWhat = !showWhat }) {
            Text(if (showWhat) "Hide what is synced" else "What is synced")
        }
        if (showWhat) {
            Text(
                "Synced: library, reading positions, bookmarks, highlights, categories, per-book looks and reading stats.\n\n" +
                    "Not synced: chapter text, covers, book files, global reader settings and API keys. " +
                    "An imported book syncs only between devices that have the same file.\n\n" +
                    "Your server's owner can read the titles, highlights and notes stored there. " +
                    "It is not end-to-end encrypted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (s.connected) {
            TextButton(onClick = { confirmDisconnect = true }) {
                Text("Disconnect", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect sync?") },
            text = {
                Text(
                    "This removes the server address and password from this device and turns sync off. " +
                        "Nothing is deleted on the server or on your other devices."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDisconnect = false; viewModel.disconnect() }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } }
        )
    }

    s.certificatePrompt?.let { fp ->
        AlertDialog(
            onDismissRequest = viewModel::declineCertificate,
            title = { Text("Trust this certificate?") },
            text = {
                Text(
                    "The server's certificate is not signed by an authority this phone knows. " +
                        "If this is your own server, check that this fingerprint (SHA-256) matches it:\n\n$fp"
                )
            },
            confirmButton = { TextButton(onClick = viewModel::trustCertificate) { Text("Trust this certificate") } },
            dismissButton = { TextButton(onClick = viewModel::declineCertificate) { Text("Cancel") } }
        )
    }
}
