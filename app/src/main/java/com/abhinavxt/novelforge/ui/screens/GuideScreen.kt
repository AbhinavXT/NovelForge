package com.abhinavxt.novelforge.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Where each feature lives. Each line names the control a reader has to find. */
private val sections = listOf(
    "Find novels" to "The Search tab searches all your sources at once, or one you pick. Tap a result to see the book, then add it to your library.",
    "Add sources" to "Settings → Source plugins installs more sources from the official repository, or a plugin file of your own.",
    "Your own books" to "Library → + → Import book files takes EPUB, text and Markdown files. The same menu saves a web page, searches Anna's Archive and browses book catalogs.",
    "Read offline" to "On a book's page, Download saves every chapter to your phone.",
    "Read aloud" to "In the reader, the headphones button reads the chapter aloud. With a multi-speaker voice, Character voices in that panel gives each character their own voice.",
    "Audiobooks" to "On a book's page, Export turns chapters into audio files. Once every chapter is exported, join them into one M4B audiobook.",
    "Character codex" to "The people button on a book's page lists who's who, hiding anyone past your chapter. Select a name while reading and tap Define to see their card.",
    "Translate" to "Pick an engine in Settings → Translation, then use the translate button on a library book's page. Each book keeps a glossary so names stay the same.",
    "Search, highlights, vocabulary" to "The Library's top bar searches inside your downloaded books, lists every highlight, and opens your saved words as flashcards. Save a word from Define in the reader.",
    "Stats and goals" to "Settings → Reading Stats shows your reading time and streaks, and sets a daily goal.",
    "Privacy" to "Settings → Privacy has an app lock and incognito mode, which keeps reading out of history and stats.",
    "E-ink screens" to "Settings → Appearance → E-ink mode turns off animations and switches to black on white.",
    "Backups" to "Settings → Backup & Restore saves your library, progress and settings to a file you can restore on any phone.",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(onBackClick: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Guide") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            items(sections) { (title, text) ->
                Column(Modifier.padding(vertical = 10.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
