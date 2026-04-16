package com.abhinavxt.novelreader.ui.screens

import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abhinavxt.novelreader.data.NovelRepository
import com.abhinavxt.novelreader.data.model.Novel
import com.abhinavxt.novelreader.ui.components.NovelCover
import com.abhinavxt.novelreader.ui.viewmodel.ContinueReadingData
import com.abhinavxt.novelreader.ui.viewmodel.HomeViewModel
import com.abhinavxt.novelreader.ui.viewmodel.ReadingActivityData
import java.util.Calendar

@Composable
fun HomeScreen(
    repository: NovelRepository,
    readingStatsTracker: com.abhinavxt.novelreader.data.ReadingStatsTracker? = null,
    onBrowseClick: () -> Unit,
    onNovelClick: (novelId: String) -> Unit,
    onContinueReading: (novelId: String, chapterId: String, chapterUrl: String, novelUrl: String) -> Unit = { novelId, _, _, _ -> onNovelClick(novelId) },
    viewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.provideFactory(repository, readingStatsTracker)
    )
) {
    val libraryNovels by viewModel.libraryNovels.collectAsState()
    val continueReadingList by viewModel.continueReadingList.collectAsState()
    val readingActivity by viewModel.readingActivity.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 16.dp)
    ) {
        // ── Greeting header ─────────────────────────────────────
        val greeting = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 0..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
        Text(
            text = greeting,
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Text(
            text = if (libraryNovels.isNotEmpty()) {
                val count = libraryNovels.size
                "$count ${if (count == 1) "novel" else "novels"} in your library"
            } else "Start building your library",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )

        // ── Reading activity — compact inline ─────────────────
        // Minimal single-line display: streak + today's stats.
        // No card, no flame emoji — just clean info text.
        if (readingActivity.currentStreak > 0 || readingActivity.todayMinutes > 0) {
            val parts = mutableListOf<String>()
            if (readingActivity.currentStreak > 0) {
                parts.add("${readingActivity.currentStreak}-day streak")
            }
            if (readingActivity.todayMinutes > 0) {
                parts.add("${readingActivity.todayMinutes} min today")
            }
            if (readingActivity.todayChapters > 0) {
                parts.add("${readingActivity.todayChapters} ch today")
            }
            Text(
                text = parts.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── Continue Reading — horizontal scroll ────────────────
        if (continueReadingList.isNotEmpty()) {
            Text(
                text = "Continue Reading",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                items(
                    items = continueReadingList,
                    key = { it.novel.id }
                ) { data ->
                    ContinueReadingCard(
                        data = data,
                        onClick = {
                            onContinueReading(
                                data.novel.id,
                                data.chapterId,
                                data.chapterUrl,
                                data.novelUrl
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // ── Library grid ────────────────────────────────────────
        if (libraryNovels.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Your Library",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "${libraryNovels.size} novels",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Adaptive grid: 3 columns on phone, more on tablet
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(
                    items = libraryNovels,
                    key = { it.id }
                ) { novel ->
                    LibraryNovelItem(
                        novel = novel,
                        onClick = { onNovelClick(novel.id) }
                    )
                }
            }

        } else {
            // ── Empty state ─────────────────────────────────────
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Text(
                        text = "📚",
                        fontSize = 56.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Your library awaits",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Browse novels or import an EPUB to get started",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(onClick = onBrowseClick) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Browse Novels")
                    }
                }
            }
        }
    }
}

// ── Continue Reading card ────────────────────────────────────────
// Compact card for horizontal scroll — cover + title + progress
@Composable
private fun ContinueReadingCard(
    data: ContinueReadingData,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(260.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            // Cover with progress overlay
            val progress = if (data.totalChapters > 0)
                data.currentChapter.toFloat() / data.totalChapters.toFloat()
            else 0f

            NovelCover(
                coverUrl = data.novel.coverUrl,
                width = 56.dp,
                height = 78.dp,
                progress = progress
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = data.novel.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = data.chapterTitle.ifEmpty { "Chapter ${data.currentChapter}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Animated progress bar
                val animatedProgress by animateFloatAsState(
                    targetValue = progress,
                    animationSpec = tween(durationMillis = 800, easing = EaseOutCubic),
                    label = "continueReadingProgress"
                )
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth(),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )

                Spacer(modifier = Modifier.height(3.dp))

                Text(
                    text = "${data.currentChapter} / ${data.totalChapters}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

// ── Library grid item ────────────────────────────────────────────
// Cover with title underneath — uses NovelCover for consistency
@Composable
private fun LibraryNovelItem(
    novel: Novel,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        NovelCover(
            coverUrl = novel.coverUrl,
            width = 100.dp,
            height = 140.dp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = novel.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}