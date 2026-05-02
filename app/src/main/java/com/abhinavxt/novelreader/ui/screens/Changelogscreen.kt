package com.abhinavxt.novelreader.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Changelog screen — shows version history in reverse chronological order.
 * Navigated to from Settings > About > "Changelog" row.
 *
 * Each version entry has a version number, a date, and a list of changes.
 * The most recent version gets a "Latest" badge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogScreen(
    onBackClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Changelog") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(
                items = changelogEntries,
                key = { it.version }
            ) { entry ->
                ChangelogEntry(
                    entry = entry,
                    isLatest = entry == changelogEntries.first()
                )
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ChangelogEntry(
    entry: VersionEntry,
    isLatest: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        // Version header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = entry.version,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isLatest) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )

            if (isLatest) {
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.NewReleases,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Latest",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = entry.date,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Changes list
        entry.changes.forEach { change ->
            Row(
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
            ) {
                Text(
                    text = "·",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(16.dp)
                )
                Text(
                    text = change,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    }
}

// ═══════════════════════════════════════════════════════════════
// Changelog data — add new entries at the top
// ═══════════════════════════════════════════════════════════════

private data class VersionEntry(
    val version: String,
    val date: String,
    val changes: List<String>
)

private val changelogEntries = listOf(
    VersionEntry(
        version = "v1.7.0",
        date = "Apr 2026",
        changes = listOf(
            "Horizontal paged reading mode with swipe + tap navigation",
            "Auto-scroll mode — teleprompter-style continuous drift, tap to pause",
            "Quick settings bottom sheet (reading mode, fonts, themes, margins, transitions)",
            "Highlights & annotations — select text to highlight in 6 colors",
            "Page turn animations: slide, fade, page curl, none",
            "Volume key navigation for hands-free page turning",
            "New chapter update banner on novel detail screen",
            "Redesigned reading-stats share card",
            "Widget preview now renders correctly in the launcher picker",
            "Keep screen on toggle",
            "Configurable horizontal margins",
            "Configurable auto-scroll speed (20–200 px/sec)",
            "Fullscreen mode with dedicated button",
            "Minimal streak display on home screen"
        )
    ),
    VersionEntry(
        version = "v1.6.0",
        date = "Apr 2026",
        changes = listOf(
            "Pronunciation dictionary for TTS name corrections",
            "Reading stats dashboard with streaks and daily charts",
            "Chapter update checker with auto-download",
            "Offline filter and download/export range picker",
            "Library sorting (last read, title, chapters, added)",
            "Update badges on library cards",
            "Bookmarks and pronunciations included in backup",
            "6 new reader themes (Nord, Mocha, Dracula, AMOLED, Gruvbox, Catppuccin)",
            "Primordial Translation source added"
        )
    ),
    VersionEntry(
        version = "v1.5.0",
        date = "Mar 2026",
        changes = listOf(
            "M4B chaptered audiobook generation from exported audio",
            "Audio library with built-in player and speed control",
            "Per-chapter audio export with range selection",
            "Sleep timer for TTS (end of chapter, 15/30/45/60 min)",
            "Deep-link notifications for new chapter updates",
            "Bookmark system with notes and paragraph snippets"
        )
    ),
    VersionEntry(
        version = "v1.4.0",
        date = "Mar 2026",
        changes = listOf(
            "Sherpa-ONNX neural TTS engine (Piper, Kokoro, KittenTTS)",
            "Voice model downloader with per-model management",
            "Google TTS with 67+ voices across 24 languages",
            "TTS sentence highlighting and auto-scroll",
            "WPM-based reading time estimate per chapter"
        )
    ),
    VersionEntry(
        version = "v1.3.0",
        date = "Mar 2026",
        changes = listOf(
            "EPUB import for locally stored novels",
            "Chapter pre-fetching for instant navigation",
            "Inline dictionary lookup (6 languages)",
            "11 reader themes with one-tap selection",
            "8 reader fonts including OpenDyslexic"
        )
    ),
    VersionEntry(
        version = "v1.2.0",
        date = "Feb 2026",
        changes = listOf(
            "Backup and restore with full data export",
            "7 novel sources with search and browse",
            "Chapter download manager with bulk download",
            "Reading progress tracking with paragraph position"
        )
    ),
    VersionEntry(
        version = "v1.0.0",
        date = "Feb 2026",
        changes = listOf(
            "Initial release",
            "Novel browsing, search, and library management",
            "Offline reading with chapter downloads",
            "Basic reader with font size and theme controls"
        )
    )
)