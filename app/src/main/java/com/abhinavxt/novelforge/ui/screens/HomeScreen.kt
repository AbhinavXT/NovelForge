package com.abhinavxt.novelforge.ui.screens

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abhinavxt.novelforge.data.NovelRepository
import com.abhinavxt.novelforge.data.model.Novel
import com.abhinavxt.novelforge.ui.components.NovelCover
import com.abhinavxt.novelforge.ui.components.novelCoverShared
import com.abhinavxt.novelforge.ui.viewmodel.ContinueReadingData
import com.abhinavxt.novelforge.ui.viewmodel.HomeViewModel
import java.util.Calendar
import com.abhinavxt.novelforge.ui.components.OfflineBanner
import com.abhinavxt.novelforge.util.NetworkMonitor

/**
 * Home = dashboard (Phase 4 rework).
 *
 * Previously Home showed the FULL library grid, duplicating the
 * Library tab with a different layout — two canonical views of the
 * same collection. Home is now a dashboard:
 *
 *   greeting + streak line
 *   Continue Reading rail
 *   Recently Updated rail   (novels with unseen new chapters)
 *   From Your Library rail  (10 most recent covers + "See all")
 *
 * The Library tab is the single canonical collection view, with
 * search and a grid/list toggle.
 */
@Composable
fun HomeScreen(
    repository: NovelRepository,
    readingStatsTracker: com.abhinavxt.novelforge.data.ReadingStatsTracker? = null,
    onBrowseClick: () -> Unit,
    onNovelClick: (novelId: String) -> Unit,
    onContinueReading: (novelId: String, chapterId: String, chapterUrl: String, novelUrl: String) -> Unit = { novelId, _, _, _ -> onNovelClick(novelId) },
    onSeeLibrary: () -> Unit = {},
    onSeeUpdates: () -> Unit = {},
    networkMonitor: NetworkMonitor,
    viewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.provideFactory(
            repository,
            readingStatsTracker,
            LocalContext.current.applicationContext
        )
    )
) {
    val libraryNovels by viewModel.libraryNovels.collectAsState()
    val continueReadingList by viewModel.continueReadingList.collectAsState()
    val readingActivity by viewModel.readingActivity.collectAsState()
    val recentlyUpdated by viewModel.recentlyUpdated.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 16.dp)
    ) {
        OfflineBanner(monitor = networkMonitor)

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

        if (libraryNovels.isEmpty()) {
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
        } else {
            // ── Dashboard sections — scrollable column of rails ──
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(modifier = Modifier.height(20.dp))

                // ── Continue Reading ────────────────────────────
                if (continueReadingList.isNotEmpty()) {
                    SectionHeader(title = "Continue Reading")
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

                // ── Recently Updated ────────────────────────────
                // Only shown when the update checker found new chapters
                // the user hasn't opened yet. Tapping navigates to the
                // detail screen (which clears the badge itself).
                if (recentlyUpdated.isNotEmpty()) {
                    SectionHeader(
                        title = "Recently Updated",
                        action = "See all",
                        onAction = onSeeUpdates
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp)
                    ) {
                        items(
                            items = recentlyUpdated,
                            key = { it.id }
                        ) { novel ->
                            CoverRailItem(
                                novel = novel,
                                showNewChip = true,
                                onClick = { onNovelClick(novel.id) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // ── From Your Library ───────────────────────────
                // 3×3 grid of the newest novels (getAllNovels() is
                // ORDER BY lastUpdatedAt DESC, so take(9) = 9 latest).
                // Static chunked Rows, not LazyVerticalGrid — a lazy
                // grid can't be nested inside this verticalScroll
                // Column (infinite-height constraint), and 9 items
                // don't need laziness. Overflow lives behind "See all"
                // → the Library tab, the canonical collection view.
                SectionHeader(
                    title = "From Your Library",
                    action = "See all",
                    onAction = onSeeLibrary
                )
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    libraryNovels.take(9).chunked(3).forEach { rowNovels ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            rowNovels.forEach { novel ->
                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.TopCenter
                                ) {
                                    CoverRailItem(
                                        novel = novel,
                                        onClick = { onNovelClick(novel.id) }
                                    )
                                }
                            }
                            // Keep cell widths stable on a short last row
                            repeat(3 - rowNovels.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

// ── Section header with optional trailing action ─────────────────
@Composable
private fun SectionHeader(
    title: String,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium
        )
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(
                    text = action,
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

// ── Cover rail item: cover + title, optional "New" chip ──────────
@Composable
private fun CoverRailItem(
    novel: Novel,
    showNewChip: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(100.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            NovelCover(
                coverUrl = novel.coverUrl,
                modifier = Modifier.novelCoverShared(novel.id),
                width = 100.dp,
                height = 140.dp
            )
            if (showNewChip) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                ) {
                    Text(
                        text = "New",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }
        }
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
                modifier = Modifier.novelCoverShared(data.novel.id),
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