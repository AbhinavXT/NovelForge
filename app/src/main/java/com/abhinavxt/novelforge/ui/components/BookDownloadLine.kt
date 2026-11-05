package com.abhinavxt.novelforge.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abhinavxt.novelforge.worker.BookDownloadState

/** One line under a book saying where its background download has got to. */
@Composable
fun BookDownloadLine(state: BookDownloadState) {
    val (icon, text, isError) = when (state) {
        BookDownloadState.Queued -> Triple(Icons.Default.Schedule, "Waiting to download", false)
        is BookDownloadState.Running -> Triple(
            null,
            state.total?.let { "Downloading ${formatBytes(state.bytes)} of ${formatBytes(it)}" }
                ?: if (state.bytes > 0) "Downloading ${formatBytes(state.bytes)}" else "Downloading",
            false
        )
        is BookDownloadState.Imported -> Triple(Icons.Default.CheckCircle, "In your library · tap to open", false)
        is BookDownloadState.AlreadyInLibrary -> Triple(Icons.Default.CheckCircle, "Already in your library", false)
        is BookDownloadState.Failed -> Triple(Icons.Default.ErrorOutline, "Couldn't download · tap to retry", true)
    }
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        } else {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
