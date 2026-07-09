package com.abhinavxt.novelforge.ui.components

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.abhinavxt.novelforge.data.UpdateChecker

/**
 * Settings row that shows current update status.
 *
 * Behaviour:
 *  - Idle / UpToDate → shows "You're on version X — check for updates"
 *  - Available       → shows a highlighted pill with "Update available: vX"
 *                      + a red dot indicator
 *  - Failed          → shows the error briefly; tap to retry
 *  - Disabled        → row is hidden
 *
 * The row itself is tappable: tap = open the download URL in a browser
 * (for Available state) or trigger a manual check (for other states).
 */
@Composable
fun UpdateStatusRow(
    status: UpdateChecker.Status,
    onCheckNow: () -> Unit,
    onOpenDownload: (url: String) -> Unit,
    currentVersion: String,
    modifier: Modifier = Modifier
) {
    // Disabled → render nothing. Play Store builds will typically set
    // UpdateConfig(enabled = false).
    if (status is UpdateChecker.Status.Disabled) return

    val accent = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error

    // Compute the displayed label + dot color once.
    val (label, sublabel, dotColor) = when (status) {
        is UpdateChecker.Status.Available -> Triple(
            "Update available",
            "v${status.info.latestVersion} · tap to download",
            accent
        )
        is UpdateChecker.Status.UpToDate -> Triple(
            "App is up to date",
            "You're on v$currentVersion",
            null
        )
        is UpdateChecker.Status.Failed -> Triple(
            "Couldn't check for updates",
            status.message,
            errorColor
        )
        UpdateChecker.Status.Idle -> Triple(
            "Check for updates",
            "You're on v$currentVersion",
            null
        )
        UpdateChecker.Status.Disabled -> return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                if (status is UpdateChecker.Status.Available) {
                    onOpenDownload(status.info.downloadUrl)
                } else {
                    onCheckNow()
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.SystemUpdate,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge
                )
                if (dotColor != null) {
                    Spacer(Modifier.size(8.dp))
                    // Small colored dot — the "notification indicator" on
                    // the settings row. Red for errors, accent for updates.
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                }
            }
            Text(
                text = sublabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Full-screen-ish dialog shown on first detection of an update.
 * Non-recurring: once dismissed for a given version, we won't show it
 * again for that version (but will for newer ones).
 *
 * [onDismiss] should call UpdateChecker.dismiss(version) so the dismissal
 * persists across sessions.
 */
@Composable
fun UpdateAvailableDialog(
    info: UpdateChecker.UpdateInfo,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Update available",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            // Scrollable column so long release notes don't break the
            // dialog's height on short devices.
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    VersionBadge(
                        version = info.currentVersion,
                        label = "current",
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("→", style = MaterialTheme.typography.titleLarge)
                    VersionBadge(
                        version = info.latestVersion,
                        label = "new",
                        color = MaterialTheme.colorScheme.primary,
                        textColor = MaterialTheme.colorScheme.onPrimary
                    )
                }
                if (info.releaseNotes.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "What's new",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    // Release notes are in Markdown on GitHub; we're
                    // displaying them as plain text. For a fancier display
                    // you'd run this through a markdown renderer, but
                    // plain text reads fine for typical changelog notes
                    // and avoids pulling in a markdown dependency.
                    Text(
                        text = info.releaseNotes,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onUpdate) { Text("Download") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Later") }
        }
    )
}

@Composable
private fun VersionBadge(
    version: String,
    label: String,
    color: Color,
    textColor: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .background(color)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = "v$version",
                style = MaterialTheme.typography.titleMedium,
                color = textColor
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Helper: open the APK download URL in the user's browser. Users then
 * install it themselves.
 *
 * We deliberately do NOT try to download + install the APK directly —
 * that requires REQUEST_INSTALL_PACKAGES permission on Android 8+ and
 * dealing with PackageInstaller, which is a significant security and
 * complexity burden for a sideload update flow. Browser download → user
 * tap → Android's built-in package installer is the safe path.
 */
fun openUpdateUrlInBrowser(context: android.content.Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        // Device has no browser installed — uncommon, but handle it.
        android.widget.Toast.makeText(
            context,
            "No browser available to open the update link.",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}