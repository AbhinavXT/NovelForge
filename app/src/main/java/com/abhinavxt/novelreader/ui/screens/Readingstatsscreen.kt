package com.abhinavxt.novelreader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abhinavxt.novelreader.data.NovelRepository
import com.abhinavxt.novelreader.data.ReadingStatsTracker
import com.abhinavxt.novelreader.ui.viewmodel.ReadingStatsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingStatsScreen(
    readingStatsTracker: ReadingStatsTracker,
    repository: NovelRepository,
    onBackClick: () -> Unit,
    viewModel: ReadingStatsViewModel = viewModel(
        factory = ReadingStatsViewModel.Factory(readingStatsTracker, repository)
    )
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reading Stats") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (state.totalChapters == 0) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "No reading data yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Start reading to see your stats here. Time and word counts are tracked each time you read a chapter.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Streak card
                StreakCard(
                    currentStreak = state.currentStreak,
                    longestStreak = state.longestStreak
                )

                // Today's stats
                Text(
                    text = "Today",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatCard(
                        label = "Words",
                        value = formatNumber(state.todayWords),
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        label = "Time",
                        value = formatTime(state.todayTimeMs),
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        label = "Chapters",
                        value = state.todayChapters.toString(),
                        modifier = Modifier.weight(1f)
                    )
                }

                // Daily word count chart
                if (state.dailyWordCounts.isNotEmpty()) {
                    Text(
                        text = "Last 14 Days",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    DailyChart(data = state.dailyWordCounts)
                }

                // All-time stats
                Text(
                    text = "All Time",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatCard(
                        label = "Words",
                        value = formatNumber(state.totalWordsRead),
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        label = "Time",
                        value = formatTime(state.totalReadingTimeMs),
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        label = "Chapters",
                        value = state.totalChapters.toString(),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun StreakCard(currentStreak: Int, longestStreak: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$currentStreak",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = if (currentStreak == 1) "day streak" else "day streak",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$longestStreak",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "best streak",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Simple bar chart using basic Compose drawing.
 * Each bar represents one day's word count.
 */
@Composable
private fun DailyChart(data: List<Pair<String, Int>>) {
    val maxValue = data.maxOfOrNull { it.second } ?: 1
    val barColor = MaterialTheme.colorScheme.primary
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Bars
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                data.forEach { (_, words) ->
                    val fraction = if (maxValue > 0) words.toFloat() / maxValue else 0f
                    val barHeight = (fraction * 100).coerceAtLeast(if (words > 0) 4f else 2f)

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(barHeight.dp)
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(if (words > 0) barColor else emptyColor)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Labels (show every other to avoid crowding)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                data.forEachIndexed { index, (label, _) ->
                    val showLabel = index == 0 || index == data.size - 1 || index % 3 == 0
                    Text(
                        text = if (showLabel) label else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

private fun formatNumber(n: Int): String {
    return when {
        n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format("%.1fK", n / 1_000.0)
        else -> n.toString()
    }
}

private fun formatTime(ms: Long): String {
    val totalMinutes = ms / 60_000
    return when {
        totalMinutes < 60 -> "${totalMinutes}m"
        totalMinutes < 1440 -> {
            val h = totalMinutes / 60
            val m = totalMinutes % 60
            if (m > 0) "${h}h ${m}m" else "${h}h"
        }
        else -> {
            val d = totalMinutes / 1440
            val h = (totalMinutes % 1440) / 60
            if (h > 0) "${d}d ${h}h" else "${d}d"
        }
    }
}