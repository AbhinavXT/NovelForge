package com.abhinavxt.novelreader.ui.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abhinavxt.novelreader.ui.viewmodel.StatsUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Strava-style reading share card.
 *
 * Design inspiration (the Strava activity-detail share card):
 *  • Near-black base (#0F1115), not pure black — richer on JPEG/Story
 *    compression and on AMOLED
 *  • ONE accent color (orange for Strava; here: your theme's primary).
 *    Used sparingly — on the hero number only and on the sparkline bars.
 *  • Heavy weight contrast: hero is extreme-bold, labels are tracked
 *    uppercase 10-12sp with low opacity
 *  • Hairline dividers (1dp, 8% white) separate sections
 *  • Data row is 4 columns with thin vertical dividers — no boxes, no
 *    rings, no decorations. This is the Strava "stat strip" pattern.
 *  • A mini bar-chart sparkline stands in for Strava's route polyline —
 *    it's the visual signature that makes the card feel "active."
 *
 * Layout approach — NO Arrangement.SpaceBetween:
 *  The previous card used SpaceBetween across three vertical blocks,
 *  which can overflow the 1920px canvas when font metrics round up.
 *  This one uses absolute spacing (explicit Spacer heights) so vertical
 *  math is deterministic and fits within 1920 with ~80px of bottom slack.
 *
 * Fixed render size: 1080x1920. Budgeted heights:
 *   top pad        64
 *   header block  ~180
 *   hero block    ~380
 *   divider+pad    56
 *   stat strip   ~130
 *   divider+pad    56
 *   sparkline    ~340
 *   divider+pad    56
 *   footer       ~90
 *   bottom slack  ~128
 *   TOTAL        ~1880  (fits 1920 with margin)
 */
@Composable
fun StatsShareCard(
    state: StatsUiState,
    displayName: String = "",
    modifier: Modifier = Modifier
) {
    // ONE accent. Derived from the app theme so different users get
    // subtle variation but the overall look stays consistent.
    val accent = MaterialTheme.colorScheme.primary

    // Light-on-dark colors. Fixed — the card doesn't mirror the user's
    // app theme so cards look consistent across users on social feeds.
    val bg = Color(0xFF0F1115)
    val text = Color.White
    val textDim = text.copy(alpha = 0.60f)
    val textFaint = text.copy(alpha = 0.35f)
    val divider = text.copy(alpha = 0.08f)

    val headerLabel = if (displayName.isNotBlank()) {
        displayName.uppercase(Locale.US)
    } else {
        "NOVELFORGE"
    }
    val dateStamp = SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date())

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bg)
            .padding(horizontal = 56.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ─── Top margin ───
            Spacer(Modifier.height(72.dp))

            // ─── HEADER: kicker + date ────────────────────────────────
            // Kicker is tracked-uppercase 11sp — classic editorial style
            // you see on every Strava/NYT/Apple share card. Immediately
            // tells the viewer "this is a data card from a specific app."
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: "NOVELFORGE" or user's name, wide-tracked
                Text(
                    text = headerLabel,
                    color = accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp,
                    style = MaterialTheme.typography.labelMedium
                )
                // Right: current date, same weight but dim
                Text(
                    text = dateStamp.uppercase(Locale.US),
                    color = textFaint,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 2.sp,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            Spacer(Modifier.height(20.dp))

            // Hairline under the header — the Strava-ish section marker.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(divider)
            )

            Spacer(Modifier.height(64.dp))

            // ─── HEADLINE LABEL ───
            // Small descriptor ABOVE the hero, not below. Lets the eye
            // read "this week's reading" → see the giant number → feel
            // the meaning. If the label goes below, the number reads as
            // "a number" before you know what it measures.
            Text(
                text = "THIS WEEK'S READING",
                color = textDim,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 3.sp,
                style = MaterialTheme.typography.labelMedium
            )

            Spacer(Modifier.height(8.dp))

            // ─── HERO: "12,450 words" ─────────────────────────────────
            // Reading words as the hero matches what users intuitively
            // think of as their reading "output" — same role kilometers
            // play on a Strava run card.
            //
            // We keep the number on one line up to "99.9K" by using the
            // K/M short forms. Even huge readers won't blow past the
            // canvas horizontally.
            val heroWords = max(state.thisWeekWords, state.todayWords)
            val showingToday = state.thisWeekWords < state.todayWords

            Row(
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = formatNumberStrava(heroWords),
                    color = text,
                    fontSize = 112.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 112.sp,
                    letterSpacing = (-4).sp,   // tight tracking = confident, premium
                    style = MaterialTheme.typography.displayLarge
                )
                Spacer(Modifier.width(16.dp))
                // "words" sits on the baseline next to the big number.
                // Smaller, colored in accent — the only other place the
                // accent appears above the sparkline.
                Text(
                    text = "words",
                    color = accent,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.SemiBold,
                    // Align to baseline with padding-bottom to match
                    modifier = Modifier.padding(bottom = 16.dp),
                    style = MaterialTheme.typography.headlineMedium
                )
            }

            Spacer(Modifier.height(12.dp))

            // Secondary context line — if we're showing today's number
            // because it's bigger than the week, say so explicitly.
            // Otherwise show the week range.
            Text(
                text = if (showingToday) {
                    "Today · ${state.todayChapters} chapters"
                } else {
                    "Past 7 days · ${state.thisWeekDaysActive}/7 days active"
                },
                color = textDim,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(Modifier.height(48.dp))

            // ─── STAT STRIP: 4 columns separated by hairlines ─────────
            // This is THE Strava pattern — no rings, no cards, just
            // 4 vertical stat columns with thin dividers between them.
            // Each column: tiny label on top, big number below.
            //
            // Four stats chosen so every user sees most of them non-zero:
            //   time · chapters · speed · streak
            StatStrip(
                items = listOf(
                    StatItem("TIME", formatTimeStrava(state.thisWeekTimeMs)),
                    StatItem("CHAPTERS", state.thisWeekChapters.toString()),
                    StatItem("SPEED", "${state.userWPM} wpm"),
                    StatItem(
                        "STREAK",
                        if (state.currentStreak > 0) {
                            "${state.currentStreak}d"
                        } else "—"
                    )
                ),
                textColor = text,
                labelColor = textDim,
                dividerColor = divider
            )

            Spacer(Modifier.height(56.dp))

            // ─── SPARKLINE: 14-day bar chart ───────────────────────────
            // Strava's equivalent is the route polyline. Ours is a tiny
            // bar chart of the last 14 days of reading. Same visual role:
            // a "pulse" that makes the card feel alive and specific to
            // this user's activity.
            if (state.dailyWordCounts.isNotEmpty()) {
                Text(
                    text = "LAST 14 DAYS",
                    color = textDim,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 3.sp,
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.height(16.dp))
                Sparkline(
                    data = state.dailyWordCounts.map { it.second },
                    accent = accent,
                    dimColor = text.copy(alpha = 0.12f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                )
                Spacer(Modifier.height(10.dp))
                // X-axis anchors — first and last date labels only.
                // A full axis would be cluttered; Strava does the same
                // "start/end only" on its pace charts.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = state.dailyWordCounts.firstOrNull()?.first ?: "",
                        color = textFaint,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = state.dailyWordCounts.lastOrNull()?.first ?: "",
                        color = textFaint,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Spacer(Modifier.height(56.dp))

            // Hairline before the footer, matching the header's hairline
            // — creates a clean "start" and "end" to the card.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(divider)
            )

            Spacer(Modifier.height(20.dp))

            // ─── FOOTER: all-time totals + attribution ─────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: all-time stats, one line, pipe-separated
                Column {
                    Text(
                        text = "ALL TIME",
                        color = textFaint,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.sp,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${formatNumberStrava(state.totalWordsRead)} " +
                                "words · ${state.totalChapters} chapters",
                        color = text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                // Right: tiny app mark
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accent),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "N",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }

            // No spacer here — we deliberately let the bottom pad be the
            // natural "empty" area below the footer. This is ~128px of
            // slack on a 1920px canvas, which is the safety margin that
            // guarantees no cropping even if font metrics round up.
        }
    }
}

// ─── Stat-strip sub-composable ──────────────────────────────────────

private data class StatItem(val label: String, val value: String)

/**
 * A horizontal row of stats with thin vertical dividers between them.
 * No background, no padding around each stat — pure data. This is the
 * visual anchor that says "this is a data card," lifted directly from
 * Strava's activity detail strip.
 */
@Composable
private fun StatStrip(
    items: List<StatItem>,
    textColor: Color,
    labelColor: Color,
    dividerColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        items.forEachIndexed { i, item ->
            // Each stat gets equal width via weight(1f). Can't use
            // Row(horizontalArrangement = SpaceEvenly) because we need
            // the dividers to sit at specific offsets.
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = item.label,
                    color = labelColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = item.value,
                    color = textColor,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                    style = MaterialTheme.typography.headlineMedium
                )
            }
            // Vertical divider between stats, but not after the last one.
            if (i != items.lastIndex) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(60.dp)
                        .background(dividerColor)
                )
            }
        }
    }
}

// ─── Sparkline ──────────────────────────────────────────────────────

/**
 * Simple bar sparkline — N evenly-spaced vertical bars, scaled to the
 * max value in the data set. Zero-days draw a very short "floor" bar
 * (2dp) so the chart doesn't have visual holes; this matches Strava's
 * pace chart which shows a faint tick for zero-effort days.
 */
@Composable
private fun Sparkline(
    data: List<Int>,
    accent: Color,
    dimColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (data.isEmpty()) return@Canvas

        val canvasWidth = size.width
        val canvasHeight = size.height
        val barCount = data.size
        // Gap is ~30% of bar-slot width. Gives visual separation without
        // making the chart look sparse.
        val slotWidth = canvasWidth / barCount
        val barWidth = slotWidth * 0.70f
        val barGap = slotWidth * 0.30f

        val maxValue = data.max().coerceAtLeast(1)
        val floor = 2.dp.toPx()  // minimum visible height for zero-days

        data.forEachIndexed { i, value ->
            val normalized = value.toFloat() / maxValue
            val barHeight = (canvasHeight * normalized).coerceAtLeast(floor)
            val x = i * slotWidth + barGap / 2f
            val y = canvasHeight - barHeight

            drawRoundRect(
                // Zero-value days get the dim color (they're just the floor
                // tick). Everything else gets the accent.
                color = if (value == 0) dimColor else accent,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                    barWidth / 4f, barWidth / 4f
                )
            )
        }
    }
}

// ─── Formatting (private — doesn't collide with stats screen helpers) ──

/**
 * Strava-style number formatting: K/M with one decimal below 10K/10M,
 * no decimal above. "12.4K" reads well at 112sp; "12.4M" is rare but
 * we handle it anyway. Full digits below 1000 — "847" is more satisfying
 * than "0.8K" in the hero spot.
 */
private fun formatNumberStrava(n: Int): String = when {
    n >= 10_000_000 -> "${n / 1_000_000}M"
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 10_000 -> "${n / 1000}K"
    n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0)
    else -> n.toString()
}

/**
 * "2h 14m" / "45m" / "<1m" style. Same shape as your existing helper
 * but private here to avoid cross-file collision.
 */
private fun formatTimeStrava(ms: Long): String {
    val totalMin = ms / 60_000
    return when {
        totalMin < 1 -> "<1m"
        totalMin < 60 -> "${totalMin}m"
        totalMin < 1440 -> {
            val h = totalMin / 60
            val m = totalMin % 60
            if (m > 0) "${h}h ${m}m" else "${h}h"
        }
        else -> {
            val d = totalMin / 1440
            val h = (totalMin % 1440) / 60
            if (h > 0) "${d}d ${h}h" else "${d}d"
        }
    }
}