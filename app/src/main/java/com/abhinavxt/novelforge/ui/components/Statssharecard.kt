package com.abhinavxt.novelforge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abhinavxt.novelforge.ui.viewmodel.StatsUiState
import java.util.Locale
import kotlin.math.max

/**
 * Reading stats share card — Maya-style stacked stats.
 *
 * ── Design direction ──
 *
 * Modeled on the Maya reading tracker's share overlay: three stacked
 * stat blocks (small label above, huge extra-bold value below), an
 * orange open-book icon, and a bold tracked wordmark at the bottom.
 * Everything center-aligned on one vertical axis.
 *
 * Stat mapping (Maya's Length / Pace / Time → reading equivalents):
 *   Words → lifetime words read     ("1.2M words")
 *   Pace  → personal reading speed  ("312 wpm")
 *   Time  → lifetime reading time   ("10hr 42m")
 *
 * Maya overlays transparent text on the user's video; we render a
 * bitmap, so the card keeps a near-black background — white type and
 * the orange icon read exactly like the reference on it, and it
 * JPEG/PNG-compresses cleanly.
 *
 * ── Hardening (carried over) ──
 * The exporter locks density to 3.0 on a 1080×1920 canvas (360×640 dp).
 * Every text that can grow horizontally has maxLines=1 + softWrap=false
 * so extreme values truncate instead of wrapping into vertical columns.
 */
@Composable
fun StatsShareCard(
    state: StatsUiState,
    displayName: String = "",
    modifier: Modifier = Modifier
) {
    val bg = Color(0xFF0B0D10)
    val label = Color.White.copy(alpha = 0.80f)
    val value = Color.White
    // Maya's book orange
    val accent = Color(0xFFF4611C)

    // Hero values. Fall back to today's numbers so a brand-new user
    // never shares a card full of zeros.
    val words = max(state.totalWordsRead, state.todayWords)
    val timeMs = max(state.totalReadingTimeMs, state.todayTimeMs)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bg)
            .padding(horizontal = 32.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Centering strategy: equal weighted voids above and below
            // keep the stat stack in the upper-middle band, like the
            // reference (which sits above the subject's head).
            Spacer(Modifier.weight(0.9f))

            StackedStat(
                label = "Words",
                value = "${formatNumber(words)} words",
                labelColor = label,
                valueColor = value
            )

            Spacer(Modifier.height(44.dp))

            StackedStat(
                label = "Pace",
                value = "${state.userWPM} wpm",
                labelColor = label,
                valueColor = value
            )

            Spacer(Modifier.height(44.dp))

            StackedStat(
                label = "Time",
                value = formatDuration(timeMs),
                labelColor = label,
                valueColor = value
            )

            Spacer(Modifier.height(56.dp))

            // ── Orange open book ──
            Icon(
                imageVector = Icons.Outlined.AutoStories,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(96.dp)
            )

            Spacer(Modifier.height(20.dp))

            // ── Wordmark — bold, tracked, like "MAYA" ──
            Text(
                text = displayName.ifBlank { "NOVELFORGE" }
                    .uppercase(Locale.getDefault()),
                color = value,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 5.sp,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.weight(1.1f))
        }
    }
}

/**
 * One Maya-style stat block: small semi-bold label on top, huge
 * extra-bold value beneath, both centered.
 */
@Composable
private fun StackedStat(
    label: String,
    value: String,
    labelColor: Color,
    valueColor: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = labelColor,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = value,
            color = valueColor,
            fontSize = 46.sp,
            lineHeight = 50.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.5).sp,
            maxLines = 1,
            softWrap = false
        )
    }
}

// ─── Formatting ──────────────────────────────────────────────────────

/**
 * K/M short-form. Below 1000 keeps full digits ("847" reads more
 * grounded than "0.8K"); one decimal below 10K/10M, none above.
 */
private fun formatNumber(n: Int): String = when {
    n >= 10_000_000 -> "${n / 1_000_000}M"
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 10_000 -> "${n / 1000}K"
    n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0)
    else -> n.toString()
}

/**
 * "10hr 42m" for hour-scale totals, "42m 10s" under an hour — mirrors
 * the reference card's "10hr 0m 45s" without three units of noise on
 * multi-hundred-hour lifetime totals.
 */
private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "${hours}hr ${minutes}m" else "${minutes}m ${seconds}s"
}
