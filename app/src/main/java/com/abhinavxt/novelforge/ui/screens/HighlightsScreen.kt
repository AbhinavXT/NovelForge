package com.abhinavxt.novelforge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abhinavxt.novelforge.data.NovelRepository
import com.abhinavxt.novelforge.data.database.HighlightWithBook

/** Opens the reader at a highlight: novelId, chapterId, chapterUrl, paragraph. */
typealias OpenHighlight = (String, String, String, Int) -> Unit

/** Every highlight in the library, newest first, filterable by book. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightsScreen(
    repository: NovelRepository,
    onBackClick: () -> Unit,
    onOpen: OpenHighlight
) {
    val all by remember { repository.getAllHighlightsWithBook() }.collectAsState(initial = null)
    var book by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Highlights") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Go back")
                    }
                }
            )
        }
    ) { padding ->
        val list = all
        when {
            list == null -> Unit
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "No highlights yet. Select text while reading and tap Highlight.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp)
                )
            }
            else -> {
                val books = list.map { it.highlight.novelId to it.novelTitle }.distinct()
                val shown = if (book == null) list else list.filter { it.highlight.novelId == book }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (books.size > 1) item {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(selected = book == null, onClick = { book = null }, label = { Text("All books") })
                            books.forEach { (id, title) ->
                                FilterChip(
                                    selected = book == id,
                                    onClick = { book = id },
                                    label = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                )
                            }
                        }
                    }
                    items(shown, key = { it.highlight.id }) { HighlightCard(it, onOpen) }
                }
            }
        }
    }
}

/** One highlight: its colour, the quote, the note, and where it is. Tapping opens the reader there. */
@Composable
fun HighlightCard(item: HighlightWithBook, onOpen: OpenHighlight, modifier: Modifier = Modifier) {
    val h = item.highlight
    val url = item.chapterUrl
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (url != null) Modifier.clickable { onOpen(h.novelId, h.chapterId, url, h.paragraphIndex) } else Modifier)
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Spacer(Modifier.width(4.dp).fillMaxHeight().background(getHighlightColor(h.color).copy(alpha = 1f)))
            Column(Modifier.padding(14.dp)) {
                Text("“${h.selectedText}”", style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic)
                if (!h.note.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(h.note, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "${item.novelTitle} · Ch. ${h.chapterNumber}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Today's highlight for the Home card: a different one each day, the same one all day. */
fun highlightOfTheDay(list: List<HighlightWithBook>, epochDay: Long): HighlightWithBook? =
    if (list.isEmpty()) null else list[Math.floorMod(epochDay, list.size)]
