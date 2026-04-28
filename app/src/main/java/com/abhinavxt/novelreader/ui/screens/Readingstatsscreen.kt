package com.abhinavxt.novelreader.ui.screens

import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abhinavxt.novelreader.data.NovelRepository
import com.abhinavxt.novelreader.data.ReadingStatsTracker
import com.abhinavxt.novelreader.ui.viewmodel.Achievement
import com.abhinavxt.novelreader.ui.viewmodel.ReadingSession
import com.abhinavxt.novelreader.ui.viewmodel.ReadingStatsViewModel
import com.abhinavxt.novelreader.ui.viewmodel.StatsUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.widget.Toast
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.abhinavxt.novelreader.ui.components.StatsShareCard
import com.abhinavxt.novelreader.util.StatsShareExporter

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
    var shareRequested by remember { mutableStateOf(false) }
    var isSharing by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(shareRequested) {
        if (!shareRequested) return@LaunchedEffect

        // Guard against double-taps. The offscreen render temporarily attaches
        // a View to the WindowManager; two simultaneous renders would both work,
        // but we might as well be tidy and cheap on window manager churn.
        if (isSharing) {
            shareRequested = false
            return@LaunchedEffect
        }
        isSharing = true

        // LaunchedEffect's body is already a suspend lambda — renderAndSave
        // is a suspend function and can be called directly.
        val file = StatsShareExporter.renderAndSave(context) {
            // The offscreen composition inherits MaterialTheme from the caller,
            // so theme colors resolve correctly inside the share card.
            StatsShareCard(
                state = state,
                displayName = ""   // TODO: plumb from SettingsViewModel if you store a display name
            )
        }

        if (file != null) {
            StatsShareExporter.share(
                context = context,
                file = file,
                shareText = "${formatNumber(state.thisWeekWords)} words this week " +
                        "on NovelForge 📖"
            )
        } else {
            Toast.makeText(
                context,
                "Couldn't create share image — try again",
                Toast.LENGTH_SHORT
            ).show()
        }

        isSharing = false
        shareRequested = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reading Stats") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    // Share icon — only enabled once stats have loaded and the user
                    // has actually read something. Sharing a "0 words this week" card
                    // is embarrassing; disabling the button avoids that failure mode.
                    val canShare = !state.isLoading && state.totalChapters > 0
                    IconButton(
                        onClick = { shareRequested = true },
                        enabled = canShare
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Share reading stats"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        } else if (state.totalChapters == 0) {
            EmptyState(Modifier.fillMaxSize().padding(innerPadding))
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(Modifier.height(4.dp))

                // 1. Weekly summary hero
                WeeklyHero(state)

                // 2. Today's progress
                SectionLabel("Today")
                TodayRow(state)

                // 3. Reading heatmap (last 12 weeks)
                if (state.heatmapData.isNotEmpty()) {
                    SectionLabel("Activity")
                    ReadingHeatmap(state.heatmapData)
                }

                // 4. Personal records
                SectionLabel("Personal Records")
                PersonalRecords(state)

                // 5. Recent activity feed
                if (state.recentSessions.isNotEmpty()) {
                    SectionLabel("Recent Sessions")
                    ActivityFeed(state.recentSessions.take(8))
                }

                // 6. Achievements
                SectionLabel("Achievements")
                AchievementsGrid(state.achievements)

                // 7. Daily chart
                if (state.dailyReadingTime.isNotEmpty()) {
                    SectionLabel("Reading Time (14 days)")
                    DailyChart(state.dailyReadingTime, "min")
                }

                // 8. Speed gauge
                SectionLabel("Reading Speed")
                SpeedCard(state.userWPM)

                // 9. All-time totals
                SectionLabel("All Time")
                AllTimeGrid(state)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 1. Weekly Hero — Strava-style "This Week" summary
// ═══════════════════════════════════════════════════════════════

@Composable
private fun WeeklyHero(state: StatsUiState) {
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
                        colors = listOf(primary.copy(alpha = 0.12f), tertiary.copy(alpha = 0.08f)),
                        start = Offset.Zero,
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                    ),
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(20.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("This Week", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    // Days active indicator
                    Text(
                        "${state.thisWeekDaysActive}/7 days",
                        style = MaterialTheme.typography.labelMedium,
                        color = primary
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Three stat columns with comparison arrows
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    WeekStat("Words", formatNumber(state.thisWeekWords), state.thisWeekWords, state.lastWeekWords)
                    WeekStat("Time", formatTime(state.thisWeekTimeMs), state.thisWeekTimeMs.toInt(), state.lastWeekTimeMs.toInt())
                    WeekStat("Chapters", state.thisWeekChapters.toString(), state.thisWeekChapters, state.lastWeekChapters)
                }
            }
        }
    }
}

@Composable
private fun WeekStat(label: String, value: String, current: Int, previous: Int) {
    val diff = current - previous
    val color = when {
        diff > 0 -> Color(0xFF22C55E)   // Green — up from last week
        diff < 0 -> Color(0xFFEF4444)   // Red — down from last week
        else -> MaterialTheme.colorScheme.outline
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Spacer(Modifier.height(4.dp))
        // Comparison indicator
        if (diff != 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (diff > 0) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                    contentDescription = null, tint = color, modifier = Modifier.size(16.dp)
                )
                val pct = if (previous > 0) {
                    "${((diff.toFloat() / previous) * 100).toInt().let { if (it > 0) "+$it" else "$it" }}%"
                } else if (diff > 0) "+∞" else ""
                Text(pct, style = MaterialTheme.typography.labelSmall, color = color, fontSize = 10.sp)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 2. Today's progress pills
// ═══════════════════════════════════════════════════════════════

@Composable
private fun TodayRow(state: StatsUiState) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatPill(Icons.Default.MenuBook, formatNumber(state.todayWords), "words", Modifier.weight(1f))
        StatPill(Icons.Default.Timer, formatTime(state.todayTimeMs), "reading", Modifier.weight(1f))
        StatPill(Icons.Default.Speed, state.todayChapters.toString(),
            if (state.todayChapters == 1) "chapter" else "chapters", Modifier.weight(1f))
    }
}

@Composable
private fun StatPill(icon: ImageVector, value: String, label: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 3. Reading Heatmap — GitHub-style contribution grid
// ═══════════════════════════════════════════════════════════════

@Composable
private fun ReadingHeatmap(data: List<Int>) {
    // data is 84 entries (12 weeks × 7 days), oldest first
    val maxMinutes = data.maxOrNull()?.coerceAtLeast(1) ?: 1
    val primary = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)

    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp)) {
            // Legend
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("12 weeks", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline)
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Less", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.outline)
                    listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { level ->
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp))
                            .background(if (level == 0f) empty else primary.copy(alpha = 0.2f + level * 0.8f)))
                    }
                    Text("More", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.outline)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Grid: 7 rows (Mon-Sun) × 12 columns (weeks)
            val weeks = 12
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (week in 0 until weeks) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (day in 0 until 7) {
                            val index = week * 7 + day
                            val minutes = data.getOrElse(index) { 0 }
                            val intensity = if (minutes == 0) 0f
                            else (minutes.toFloat() / maxMinutes).coerceIn(0.15f, 1f)
                            Box(
                                Modifier.fillMaxWidth().height(10.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(if (minutes == 0) empty else primary.copy(alpha = intensity))
                            )
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 4. Personal Records
// ═══════════════════════════════════════════════════════════════

@Composable
private fun PersonalRecords(state: StatsUiState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PRCard("Best Day", formatNumber(state.bestDayWords) + " words",
            state.bestDayDate, Modifier.weight(1f))
        PRCard("Longest Session", formatTime(state.longestSessionMs),
            "${formatNumber(state.mostWordsInSession)} words", Modifier.weight(1f))
    }
    Spacer(Modifier.height(2.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PRCard("Best Streak", "${state.longestStreak} days",
            "Current: ${state.currentStreak}", Modifier.weight(1f))
        PRCard("Novels Read", "${state.distinctNovelsRead}",
            "~${String.format("%.1f", state.booksEquivalent)} books worth", Modifier.weight(1f))
    }
}

@Composable
private fun PRCard(title: String, value: String, subtitle: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 5. Activity Feed — like Strava recent activities
// ═══════════════════════════════════════════════════════════════

@Composable
private fun ActivityFeed(sessions: List<ReadingSession>) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(12.dp)) {
            sessions.forEachIndexed { index, session ->
                ActivityItem(session)
                if (index < sessions.size - 1) {
                    Spacer(Modifier.height(1.dp).fillMaxWidth()
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)))
                }
            }
        }
    }
}

@Composable
private fun ActivityItem(session: ReadingSession) {
    val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    val timeStr = dateFormat.format(Date(session.completedAt))

    // Extract a readable novel name from novelId (remove source prefix, replace hyphens)
    val novelName = session.novelId
        .substringAfter("_")
        .replace("-", " ")
        .replace("~", "/")
        .replaceFirstChar { it.uppercase() }

    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        // Activity type icon circle
        Box(Modifier.size(36.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MenuBook, null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(novelName, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(timeStr, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline)
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(formatTime(session.readingTimeMs),
                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            Text("${formatNumber(session.wordsRead)}w · ${session.wpm} wpm",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline, fontSize = 10.sp)
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 6. Achievements — badge grid with progress
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AchievementsGrid(achievements: List<Achievement>) {
    val earned = achievements.filter { it.earned }
    val inProgress = achievements.filter { !it.earned && it.progress > 0f }
    val locked = achievements.filter { !it.earned && it.progress == 0f }

    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp)) {
            // Earned count
            Text("${earned.size}/${achievements.size} earned",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(12.dp))

            // Earned badges
            if (earned.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    earned.forEach { AchievementBadge(it) }
                }
                Spacer(Modifier.height(12.dp))
            }

            // In-progress badges
            if (inProgress.isNotEmpty()) {
                Text("In Progress", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))
                inProgress.forEach { badge ->
                    AchievementProgressRow(badge)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun AchievementBadge(achievement: Achievement) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("✓", fontWeight = FontWeight.Bold, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(achievement.title, style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun AchievementProgressRow(achievement: Achievement) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(achievement.title, style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium)
            Text(achievement.description, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline, fontSize = 10.sp)
        }
        Spacer(Modifier.width(12.dp))
        // Mini progress bar
        Box(Modifier.width(60.dp)) {
            LinearProgressIndicator(
                progress = { achievement.progress },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text("${(achievement.progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline, fontSize = 10.sp)
    }
}

// ═══════════════════════════════════════════════════════════════
// 7. Daily Chart
// ═══════════════════════════════════════════════════════════════

@Composable
private fun DailyChart(data: List<Pair<String, Int>>, unit: String) {
    val maxValue = data.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    val barColor = MaterialTheme.colorScheme.primary
    val barLight = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)

    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("${formatNumber(maxValue)} $unit",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
            Spacer(Modifier.height(4.dp))

            Row(Modifier.fillMaxWidth().height(100.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.Bottom) {
                data.forEach { (_, value) ->
                    val fraction = value.toFloat() / maxValue
                    val h = (fraction * 88f).coerceAtLeast(if (value > 0) 6f else 2f)
                    val animH by animateFloatAsState(h, tween(600, easing = EaseOutCubic), label = "bar")
                    Box(Modifier.weight(1f).height(animH.dp)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(if (value > 0) Brush.verticalGradient(listOf(barColor, barLight))
                        else Brush.verticalGradient(listOf(emptyColor, emptyColor))))
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                data.forEachIndexed { index, (label, _) ->
                    val show = index == data.size - 1 || index % 2 == 0
                    Text(if (show) label else "", style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 8. Speed Gauge
// ═══════════════════════════════════════════════════════════════

@Composable
private fun SpeedCard(wpm: Int) {
    val progress = ((wpm - 50f) / 550f).coerceIn(0f, 1f)
    val animProg by animateFloatAsState(progress, tween(1000, easing = EaseOutCubic), label = "arc")
    val arcColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(120.dp)) {
                    val sw = 12.dp.toPx()
                    val arcSz = Size(size.width - sw, size.height - sw)
                    val tl = Offset(sw / 2, sw / 2)
                    drawArc(trackColor, 180f, 180f, false, tl, arcSz,
                        style = Stroke(sw, cap = StrokeCap.Round))
                    drawArc(arcColor, 180f, 180f * animProg, false, tl, arcSz,
                        style = Stroke(sw, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 16.dp)) {
                    Text("$wpm", style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold, color = arcColor)
                    Text("WPM", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(getSpeedLabel(wpm), style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth(0.7f), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("Slow", "Average", "Fast").forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// 9. All-time stats grid
// ═══════════════════════════════════════════════════════════════

@Composable
private fun AllTimeGrid(state: StatsUiState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AllTimeCard(formatNumber(state.totalWordsRead), "Total Words",
            "~${String.format("%.1f", state.booksEquivalent)} books", Modifier.weight(1f))
        AllTimeCard(formatTime(state.totalReadingTimeMs), "Total Time",
            "${state.totalChapters} chapters", Modifier.weight(1f))
    }
    Spacer(Modifier.height(2.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AllTimeCard(formatTime(state.avgChapterTimeMs), "Avg/Chapter",
            "reading time", Modifier.weight(1f))
        AllTimeCard("${state.userWPM}", "Words/min",
            getSpeedLabel(state.userWPM), Modifier.weight(1f))
    }
}

@Composable
private fun AllTimeCard(value: String, label: String, sub: String, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(sub, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline)
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Helpers
// ═══════════════════════════════════════════════════════════════

@Composable
private fun EmptyState(modifier: Modifier) {
    Column(modifier.padding(32.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("📖", fontSize = 48.sp)
        Spacer(Modifier.height(12.dp))
        Text("No reading data yet", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text("Start reading to see your stats here.\nTime and words are tracked each chapter.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

private fun getSpeedLabel(wpm: Int): String = when {
    wpm < 150 -> "Careful reader"
    wpm < 250 -> "Steady reader"
    wpm < 350 -> "Brisk reader"
    wpm < 450 -> "Speed reader"
    else -> "Lightning reader"
}

private fun formatNumber(n: Int): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format("%.1fK", n / 1_000.0)
    else -> n.toString()
}

private fun formatTime(ms: Long): String {
    val totalMin = ms / 60_000
    return when {
        totalMin < 1 -> "<1m"
        totalMin < 60 -> "${totalMin}m"
        totalMin < 1440 -> {
            val h = totalMin / 60; val m = totalMin % 60
            if (m > 0) "${h}h ${m}m" else "${h}h"
        }
        else -> {
            val d = totalMin / 1440; val h = (totalMin % 1440) / 60
            if (h > 0) "${d}d ${h}h" else "${d}d"
        }
    }
}