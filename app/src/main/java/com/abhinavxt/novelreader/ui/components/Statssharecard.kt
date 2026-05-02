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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * Reading stats share card — serene redesign.
 *
 * ── Design direction ──
 *
 * The previous version was a Strava-style card: high contrast, theme-
 * derived accent, four metric strip + sparkline. Energetic. This version
 * is the opposite vibe: a quiet reading-room card. The visual goals,
 * roughly in priority order:
 *
 *  1. *Calm.* Generous whitespace. No boxed metric chips, no shouting
 *     accent. Numbers float on the surface separated by hairlines.
 *  2. *Hero is the only loud thing.* The total/weekly word count is
 *     the one heavy element; everything else recedes.
 *  3. *No iconography.* No fire-streak icon, no ascending bars, no
 *     decorative chrome. Type and hairlines do all the work.
 *  4. *Activity grid as the visual anchor.* The 14-day grid is the
 *     only graphic on the card. Lower contrast than a typical heatmap —
 *     accent maxes out at 70% alpha rather than full saturation.
 *
 * ── Why we hard-code the accent instead of using MaterialTheme ──
 *
 * The previous card used MaterialTheme.colorScheme.primary so different
 * reader themes produced subtly different cards. That's fine for an
 * energetic Strava look, but for "serene" we need to control the accent
 * tightly — some of the app's 11 reader themes have saturated primaries
 * (hot pink, neon green, etc.) that would scream on a near-black canvas
 * no matter how we styled the layout. So we pin one slate-blue accent
 * and use it everywhere. Cards look consistent across users on social
 * feeds, which is also what most polished sharing surfaces (Strava,
 * Spotify Wrapped, Letterboxd) do.
 *
 * ── Layout budget at 1080×1920 (locked density 3.0 → 360×640 dp) ──
 *   top pad        96
 *   header        ~70   (kicker + date, single row)
 *   hairline+gap   80
 *   hero block   ~360   (number + caption)
 *   hairline+gap  100
 *   stat row     ~140   (3 stats, no boxes)
 *   hairline+gap  100
 *   activity     ~340   (14 days as 7×2 grid + caption)
 *   bottom pad   ~624   (a lot — that's the serenity)
 *   wordmark      ~50   (anchored to bottom)
 *
 *   The deliberately-large bottom void is the design move. Most share
 *   cards cram every pixel; this one breathes.
 *
 * ── Hardening (carried over from v2) ──
 *
 * Renderer locks LocalDensity (see StatsShareExporter). All Text that
 * contributes to horizontal pressure has `maxLines = 1` + `softWrap =
 * false` so over-long values truncate cleanly instead of wrapping into
 * vertical character columns.
 */
@Composable
fun StatsShareCard(
    state: StatsUiState,
    @Suppress("UNUSED_PARAMETER") displayName: String = "",
    modifier: Modifier = Modifier
) {
    // ── Palette ──
    // Background slightly darker than the previous #0F1115 — lifts
    // content a fraction more on AMOLED and JPEG-compresses cleaner
    // (less banding around hairlines).
    val bg = Color(0xFF0B0D10)

    // Text ramps. Five tiers of alpha, descending — every text element
    // picks one of these. No ad-hoc alphas elsewhere in the file.
    val text = Color.White
    val textHigh = text.copy(alpha = 0.92f)   // hero number
    val textMid = text.copy(alpha = 0.55f)    // captions, kicker
    val textLow = text.copy(alpha = 0.32f)    // dates, axis labels
    val textFaint = text.copy(alpha = 0.16f)  // wordmark, dim grid cells
    val hairline = text.copy(alpha = 0.06f)   // dividers

    // Pinned accent — desaturated slate-blue. Reads calm on near-black,
    // survives JPEG compression, doesn't fight any reader theme since
    // it's not derived from one.
    val accent = Color(0xFF7E8CA8)

    // Header label. Kept as a constant — the displayName param is
    // accepted for API compatibility with the existing call site but
    // ignored, because user-name personalization fights the "consistent
    // across users" goal stated above.
    val headerLabel = "NOVELFORGE"
    val dateStamp = SimpleDateFormat("MMMM yyyy", Locale.US)
        .format(Date())
        .uppercase(Locale.US)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bg)
            // 56dp horizontal padding @ density 3.0 = 168 px each side.
            // Inner content area = 1080 - 336 = 744 px. Comfortable for
            // the 96sp hero number and three flat stats.
            .padding(horizontal = 56.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ─── Top void ───
            // Larger top spacer than the previous version (96 vs 72).
            // Pushing the kicker down makes the card feel less crowded.
            Spacer(Modifier.height(96.dp))

            // ─── HEADER: kicker + date, on the same baseline ───────────
            // The previous card put these in a SpaceBetween row. Same
            // here — but both are now drawn at the same low-emphasis
            // weight (no accent color on the kicker). Identical visual
            // weight on left and right reads as calm/symmetric;
            // asymmetric weight reads as "branded".
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = headerLabel,
                    color = textMid,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 4.sp,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = dateStamp,
                    color = textLow,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 3.sp,
                    maxLines = 1,
                    softWrap = false
                )
            }

            Spacer(Modifier.height(40.dp))
            Hairline(hairline)
            Spacer(Modifier.height(64.dp))

            // ─── HERO: total words read ────────────────────────────────
            // Switched the headline metric from "this week's words" to
            // "total words read" — total reads as a quieter, longer-arc
            // number. Weekly numbers are about momentum; cumulative
            // numbers are about a relationship with reading. The latter
            // fits the serene brief.
            //
            // We still fall back to today's count if the user has no
            // total yet (brand-new user) so the hero never shows "0".
            val heroWords = max(state.totalWordsRead, state.todayWords)

            Text(
                text = formatNumber(heroWords),
                color = textHigh,
                fontSize = 96.sp,
                lineHeight = 96.sp,
                fontWeight = FontWeight.Light,   // light weight — calm hero
                letterSpacing = (-3).sp,
                maxLines = 1,
                softWrap = false,
                style = MaterialTheme.typography.displayLarge
            )

            Spacer(Modifier.height(20.dp))

            // Caption under the hero. Tracked uppercase, low contrast.
            // "WORDS READ" instead of "TOTAL WORDS READ" — shorter,
            // less prescriptive. The hero's size already implies "this
            // is the big number".
            Text(
                text = "WORDS READ",
                color = textMid,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 4.sp,
                maxLines = 1,
                softWrap = false
            )

            Spacer(Modifier.height(72.dp))
            Hairline(hairline)
            Spacer(Modifier.height(56.dp))

            // ─── STAT ROW: three flat stats ────────────────────────────
            // No boxes, no chips. Three columns separated by thin
            // vertical hairlines. Number on top (28sp Light), label
            // underneath (10sp tracked uppercase).
            //
            // Three stats chosen to span time horizons:
            //   - WPM:       a *capability* (their reading speed)
            //   - Streak:    a *current state* (today's habit)
            //   - Chapters:  a *cumulative output* (lifetime work)
            // No "best streak" because StatsUiState only carries
            // currentStreak, and inventing fields is scope creep.
            FlatStatRow(
                items = listOf(
                    FlatStat(
                        label = "WPM",
                        value = state.userWPM.toString()
                    ),
                    FlatStat(
                        label = "STREAK",
                        value = if (state.currentStreak > 0) {
                            "${state.currentStreak}d"
                        } else "—"
                    ),
                    FlatStat(
                        label = "CHAPTERS",
                        value = formatNumber(state.totalChapters)
                    )
                ),
                valueColor = textHigh,
                labelColor = textMid,
                dividerColor = hairline
            )

            Spacer(Modifier.height(72.dp))
            Hairline(hairline)
            Spacer(Modifier.height(56.dp))

            // ─── ACTIVITY GRID: 14 days as 7×2 cells ───────────────────
            // The visual anchor of the card. Lume's design used a 7×2
            // grid; we keep that shape. Intensity = today's value /
            // max(any day in window), mapped to alpha 0.16 → 0.70 on
            // the accent. Capping at 0.70 (instead of going to full
            // saturation) is what makes this calm — heatmaps usually
            // pop their hottest cells; ours stays muted.
            //
            // We render even when dailyWordCounts is empty (brand-new
            // user) — the grid just shows 14 dim cells, which still
            // reads as "this is where your reading will go".
            Text(
                text = "LAST 14 DAYS",
                color = textMid,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 3.sp,
                maxLines = 1,
                softWrap = false
            )
            Spacer(Modifier.height(24.dp))

            ActivityGrid(
                values = state.dailyWordCounts.map { it.second },
                accent = accent,
                dimColor = textFaint,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            )

            // ─── Bottom void + wordmark ────────────────────────────────
            // weight(1f) gives the wordmark all remaining vertical space
            // as breathing room — that's the serene move. Whatever space
            // the rest of the card didn't use ends up as silence above
            // the wordmark. On a 1920px canvas this typically lands at
            // ~500-600px of empty space.
            Spacer(Modifier.weight(1f))

            // Wordmark at the bottom, very faint. Replaces the previous
            // card's logo block + all-time totals row. Saying nothing
            // is also a design choice.
            Text(
                text = "NOVELFORGE",
                color = textFaint,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 6.sp,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 64.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

// ─── Hairline ────────────────────────────────────────────────────────

/**
 * 1dp horizontal divider. Pulled out as its own composable because the
 * card uses three of them and inlining the Box+Modifier chain three
 * times was noisy. Single-purpose, no parameters beyond color.
 */
@Composable
private fun Hairline(color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color)
    )
}

// ─── Flat stat row ───────────────────────────────────────────────────

private data class FlatStat(val label: String, val value: String)

/**
 * Three-column flat stat row. Number on top (Light weight, 28sp),
 * label below (Medium weight, 10sp tracked uppercase). Vertical
 * hairlines between columns. No background, no padding, no chips.
 *
 * The previous card put labels above values — I flipped it because
 * with a Light-weight value the number reads first, label second,
 * which matches the natural eye-flow on a calm composition. With
 * Bold values you want the label first as a "primer"; with Light
 * values the number is already low-key enough.
 */
@Composable
private fun FlatStatRow(
    items: List<FlatStat>,
    valueColor: Color,
    labelColor: Color,
    dividerColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        items.forEachIndexed { i, item ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = item.value,
                    color = valueColor,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = (-0.5).sp,
                    maxLines = 1,
                    softWrap = false
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = item.label,
                    color = labelColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 3.sp,
                    maxLines = 1,
                    softWrap = false
                )
            }
            if (i != items.lastIndex) {
                // Vertical hairline between columns. Height matches
                // roughly the height of the value+spacer+label stack.
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(72.dp)
                        .background(dividerColor)
                )
            }
        }
    }
}

// ─── Activity grid ───────────────────────────────────────────────────

/**
 * 7×2 reading-activity grid. Each cell's alpha is a function of that
 * day's word count divided by the max in the window. Cells are squares
 * with rounded corners, separated by uniform gaps.
 *
 * Why 7×2 and not 14×1: a wide single row gets visually thin (~50px
 * cells on a 744px content area) and reads more like a chart axis than
 * a cohesive block. 7×2 lands cells at ~95×95 px each, big enough to
 * register as deliberate squares.
 *
 * Intensity ramp:
 *   value == 0          → flat dim alpha (just shows the cell exists)
 *   value > 0           → lerp from 0.20 → 0.70 alpha on the accent
 *
 * Capping at 0.70 alpha (not 1.0) is the calm move. A typical heatmap
 * goes full-saturation on max days — pretty but loud. Ours never lets
 * any single cell scream.
 */
@Composable
private fun ActivityGrid(
    values: List<Int>,
    accent: Color,
    dimColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val cols = 7
        val rows = 2
        val totalCells = cols * rows

        // Pad the input list to exactly 14 cells. If the user has fewer
        // than 14 days of history, we render zeros for the missing days
        // — same shape, same composition, never a partial grid.
        val padded = (values + List(totalCells) { 0 }).take(totalCells)

        // Cell math. Gap between cells is ~12% of cell width — wide
        // enough to read as separate cells, narrow enough to hold the
        // grid as a unit.
        val gapRatio = 0.12f
        val totalGapWidth = (cols - 1) * gapRatio
        val cellSize = size.width / (cols + totalGapWidth)
        val gap = cellSize * gapRatio

        // Vertical: rows fit inside size.height. Compute row spacing
        // independently from horizontal so the grid doesn't squash
        // into the wrong aspect ratio if height is different from
        // (rows * cellSize + (rows-1) * gap).
        val gridRenderHeight = rows * cellSize + (rows - 1) * gap
        val verticalOffset = (size.height - gridRenderHeight) / 2f

        // Find the max for normalization. coerceAtLeast(1) prevents
        // divide-by-zero when every day is zero.
        val maxValue = padded.max().coerceAtLeast(1)

        padded.forEachIndexed { i, value ->
            val col = i % cols
            val row = i / cols

            val x = col * (cellSize + gap)
            val y = verticalOffset + row * (cellSize + gap)

            // Color resolution. Zero days get the flat dim color; non-
            // zero days get accent at an alpha lerped between 0.20 and
            // 0.70 based on intensity. The 0.20 floor ensures even a
            // tiny reading day is clearly distinguishable from a zero
            // day — important for the "you read every day" feeling.
            val cellColor = if (value == 0) {
                dimColor
            } else {
                val intensity = value.toFloat() / maxValue
                val alpha = 0.20f + intensity * 0.50f  // 0.20 → 0.70
                accent.copy(alpha = alpha)
            }

            drawRoundRect(
                color = cellColor,
                topLeft = Offset(x, y),
                size = Size(cellSize, cellSize),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                    cellSize * 0.18f, cellSize * 0.18f
                )
            )
        }
    }
}

// ─── Number formatting ──────────────────────────────────────────────

/**
 * K/M short-form for big numbers. Below 10K we keep one decimal
 * ("4.2K") because losing the precision feels arbitrary at that scale.
 * Above 10K we drop it ("42K") because the decimal becomes noise.
 *
 * Below 1000 we keep full digits. "847" reads more grounded than
 * "0.8K" in the hero spot.
 */
private fun formatNumber(n: Int): String = when {
    n >= 10_000_000 -> "${n / 1_000_000}M"
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 10_000 -> "${n / 1000}K"
    n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0)
    else -> n.toString()
}