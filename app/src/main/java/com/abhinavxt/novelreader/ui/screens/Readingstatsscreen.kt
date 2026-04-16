package com.abhinavxt.novelreader.ui.screens

import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abhinavxt.novelreader.data.NovelRepository
import com.abhinavxt.novelreader.data.ReadingStatsTracker
import com.abhinavxt.novelreader.ui.viewmodel.ReadingStatsViewModel
import com.abhinavxt.novelreader.ui.viewmodel.StatsUiState

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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            // ── Empty state ─────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("📖", fontSize = 48.sp)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "No reading data yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Start reading to see your stats here.\nTime and word counts are tracked each chapter.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            // ── Stats content ───────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                // ── 1. Hero card: streak + reading speed ────────
                HeroCard(state = state)

                // ── 2. Today's progress ─────────────────────────
                SectionLabel("Today")
                TodayRow(state = state)

                // ── 3. Daily activity chart (14 days) ───────────
                if (state.dailyWordCounts.isNotEmpty()) {
                    SectionLabel("Last 14 Days")
                    DailyChart(data = state.dailyWordCounts)
                }

                // ── 4. All-time stats ───────────────────────────
                SectionLabel("All Time")
                AllTimeGrid(state = state)

                // ── 5. Reading speed arc ────────────────────────
                SectionLabel("Reading Speed")
                SpeedCard(wpm = state.userWPM)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 1. Hero Card — streak + books read
// ═══════════════════════════════════════════════════════════════

@Composable
private fun HeroCard(state: StatsUiState) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            primary.copy(alpha = 0.15f),
                            tertiary.copy(alpha = 0.10f)
                        ),
                        start = Offset.Zero,
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                    ),
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Current streak
                HeroStat(
                    value = "${state.currentStreak}",
                    label = if (state.currentStreak == 1) "day streak" else "day streak",
                    color = primary
                )

                // Vertical divider
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(48.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                )

                // Best streak
                HeroStat(
                    value = "${state.longestStreak}",
                    label = "best streak",
                    color = tertiary
                )

                // Vertical divider
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(48.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                )

                // Books equivalent
                HeroStat(
                    value = if (state.booksEquivalent >= 1f) {
                        String.format("%.1f", state.booksEquivalent)
                    } else {
                        String.format("%.2f", state.booksEquivalent)
                    },
                    label = "books read",
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun HeroStat(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// 2. Today's progress — 3 compact stat pills
// ═══════════════════════════════════════════════════════════════

@Composable
private fun TodayRow(state: StatsUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StatPill(
            icon = Icons.Default.MenuBook,
            value = formatNumber(state.todayWords),
            label = "words",
            modifier = Modifier.weight(1f)
        )
        StatPill(
            icon = Icons.Default.Timer,
            value = formatTime(state.todayTimeMs),
            label = "reading",
            modifier = Modifier.weight(1f)
        )
        StatPill(
            icon = Icons.Default.Speed,
            value = state.todayChapters.toString(),
            label = if (state.todayChapters == 1) "chapter" else "chapters",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatPill(
    icon: ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 3. Daily chart — gradient bars with labels
// ═══════════════════════════════════════════════════════════════

@Composable
private fun DailyChart(data: List<Pair<String, Int>>) {
    val maxValue = data.maxOfOrNull { it.second } ?: 1
    val barColor = MaterialTheme.colorScheme.primary
    val barColorLight = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Max value label
            Text(
                text = "${formatNumber(maxValue)} words",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Bars
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                data.forEach { (_, words) ->
                    val fraction = if (maxValue > 0) words.toFloat() / maxValue else 0f
                    val barHeight = (fraction * 88f).coerceAtLeast(if (words > 0) 6f else 2f)

                    val animatedHeight by animateFloatAsState(
                        targetValue = barHeight,
                        animationSpec = tween(600, easing = EaseOutCubic),
                        label = "barAnim"
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(animatedHeight.dp)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(
                                if (words > 0) {
                                    Brush.verticalGradient(
                                        colors = listOf(barColor, barColorLight)
                                    )
                                } else {
                                    Brush.verticalGradient(
                                        colors = listOf(emptyColor, emptyColor)
                                    )
                                }
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Labels — show date labels only, every 2 days to keep axis readable.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                data.forEachIndexed { index, (label, _) ->
                    val isLast = index == data.size - 1
                    val showLabel = isLast || index % 2 == 0
                    Text(
                        text = if (showLabel) label else "",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 4. All-time grid — 2x2 cards
// ═══════════════════════════════════════════════════════════════

@Composable
private fun AllTimeGrid(state: StatsUiState) {
    // Row 1: words + time
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AllTimeCard(
            value = formatNumber(state.totalWordsRead),
            label = "Total Words",
            subtitle = "~${String.format("%.1f", state.booksEquivalent)} books",
            modifier = Modifier.weight(1f)
        )
        AllTimeCard(
            value = formatTime(state.totalReadingTimeMs),
            label = "Total Time",
            subtitle = "${formatNumber(state.totalChapters)} chapters",
            modifier = Modifier.weight(1f)
        )
    }

    Spacer(modifier = Modifier.height(2.dp))

    // Row 2: avg chapter time + WPM
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AllTimeCard(
            value = formatTime(state.avgChapterTimeMs),
            label = "Avg per Chapter",
            subtitle = "reading time",
            modifier = Modifier.weight(1f)
        )
        AllTimeCard(
            value = "${state.userWPM}",
            label = "Words/min",
            subtitle = getSpeedLabel(state.userWPM),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun AllTimeCard(
    value: String,
    label: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 5. Speed arc — semicircular gauge showing WPM
// ═══════════════════════════════════════════════════════════════

@Composable
private fun SpeedCard(wpm: Int) {
    // WPM range: 50–600. Map to 0..1 for the arc.
    val progress = ((wpm - 50f) / 550f).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(1000, easing = EaseOutCubic),
        label = "speedArc"
    )

    val arcColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Semicircular arc gauge
            Box(
                modifier = Modifier.size(160.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(140.dp)) {
                    val strokeWidth = 14.dp.toPx()
                    val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                    val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)

                    // Track (background arc) — 180° semicircle
                    drawArc(
                        color = trackColor,
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )

                    // Progress arc
                    drawArc(
                        color = arcColor,
                        startAngle = 180f,
                        sweepAngle = 180f * animatedProgress,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }

                // Center label
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 20.dp)
                ) {
                    Text(
                        text = "$wpm",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = arcColor
                    )
                    Text(
                        text = "WPM",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Speed label
            Text(
                text = getSpeedLabel(wpm),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(2.dp))

            // Scale labels
            Row(
                modifier = Modifier.fillMaxWidth(0.7f),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Slow", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline)
                Text("Average", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline)
                Text("Fast", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Helpers
// ═══════════════════════════════════════════════════════════════

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
}

/**
 * Human-readable speed category based on WPM.
 * Research: average adult reads ~250 WPM silently.
 */
private fun getSpeedLabel(wpm: Int): String {
    return when {
        wpm < 150 -> "Careful reader"
        wpm < 250 -> "Steady reader"
        wpm < 350 -> "Brisk reader"
        wpm < 450 -> "Speed reader"
        else -> "Lightning reader"
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
        totalMinutes < 1 -> "<1m"
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