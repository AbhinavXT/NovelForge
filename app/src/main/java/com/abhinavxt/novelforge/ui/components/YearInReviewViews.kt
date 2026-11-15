package com.abhinavxt.novelforge.ui.components

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.abhinavxt.novelforge.data.YearInReview
import com.abhinavxt.novelforge.ui.theme.LocalEinkMode
import com.abhinavxt.novelforge.ui.viewmodel.YearBook
import com.abhinavxt.novelforge.ui.viewmodel.YearReviewUiState
import com.abhinavxt.novelforge.util.StatsShareExporter
import kotlinx.coroutines.launch
import java.io.File
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

// ═══════════════════════════════════════════════════════════════
// Card on the stats screen
// ═══════════════════════════════════════════════════════════════

/**
 * Headline numbers for one year, with arrows between the years that have
 * reading in them. The arrows are the only year picker, so a year with
 * nothing in it can never be selected.
 */
@Composable
fun YearInReviewCard(
    state: YearReviewUiState,
    onSelectYear: (Int) -> Unit,
    onView: () -> Unit,
    modifier: Modifier = Modifier
) {
    val review = state.review ?: return
    val index = state.years.indexOf(review.year)
    val eink = LocalEinkMode.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (eink) Color.White else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = if (eink) BorderStroke(1.dp, Color.Black) else null
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Year in review", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                YearArrows(state.years, index, review.year, onSelectYear)
            }

            if (review.isEmpty) {
                Text(
                    "No reading in ${review.year} yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    CardStat(yirDuration(review.totalTimeMs), "read")
                    CardStat(yirCount(review.totalWords), "words")
                    CardStat(review.chapters.toString(), "chapters")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onView) { Text("View") }
                }
            }
        }
    }
}

/** Older year on the left, newer on the right; [years] is newest first. */
@Composable
private fun YearArrows(years: List<Int>, index: Int, year: Int, onSelectYear: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { onSelectYear(years[index + 1]) },
            enabled = index in 0 until years.lastIndex
        ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous year") }
        Text(year.toString(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        IconButton(
            onClick = { onSelectYear(years[index - 1]) },
            enabled = index > 0
        ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next year") }
    }
}

@Composable
private fun CardStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ═══════════════════════════════════════════════════════════════
// Full-screen page
// ═══════════════════════════════════════════════════════════════

/**
 * Drawn in place of the stats screen rather than as a dialog: a dialog
 * brings its own window animation, which e-ink panels ghost on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YearInReviewPage(
    state: YearReviewUiState,
    onSelectYear: (Int) -> Unit,
    onBack: () -> Unit
) {
    val review = state.review ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sharing by remember { mutableStateOf(false) }
    val eink = LocalEinkMode.current
    val index = state.years.indexOf(review.year)
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Year in review") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // An empty year has nothing worth sharing.
                    if (!review.isEmpty) {
                        IconButton(
                            enabled = !sharing,
                            onClick = {
                                sharing = true
                                scope.launch {
                                    val file = StatsShareExporter.renderAndSave(context) {
                                        YearInReviewShareCard(review, state.books)
                                    }
                                    if (file != null) {
                                        StatsShareExporter.share(
                                            context, file,
                                            shareText = "My ${review.year} in books, on NovelForge"
                                        )
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Couldn't create share image — try again",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    sharing = false
                                }
                            }
                        ) { Icon(Icons.Default.Share, contentDescription = "Share year in review") }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                YearArrows(state.years, index, review.year, onSelectYear)
            }

            if (review.isEmpty) {
                Text(
                    "No reading in ${review.year} yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp)
                )
                return@Column
            }

            // Headline
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    yirDuration(review.totalTimeMs),
                    fontSize = 56.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (eink) Color.Black else MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
                Text(
                    "of reading in ${review.year}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text("By month", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            MonthBars(
                values = review.monthTimeMs,
                barColor = if (eink) Color.Black else MaterialTheme.colorScheme.primary,
                emptyColor = if (eink) Color.Black else MaterialTheme.colorScheme.outlineVariant,
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().height(150.dp)
            )

            if (state.books.isNotEmpty()) {
                Text("Most read", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                state.books.forEachIndexed { i, book -> TopBookRow(i + 1, book) }
            }

            val busiestDay = review.busiestDay
            val tiles = buildList {
                add("Words" to yirCount(review.totalWords))
                add("Chapters" to review.chapters.toString())
                add("Days read" to review.daysRead.toString())
                add("Longest streak" to "${review.longestStreak} ${if (review.longestStreak == 1) "day" else "days"}")
                review.busiestMonth?.let { add("Busiest month" to monthName(it)) }
                if (busiestDay != null) add("Busiest day" to "${yirDay(busiestDay)} · ${yirDuration(busiestDay.timeMs)}")
                add("Books read" to review.booksRead.toString())
            }
            tiles.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { (label, value) -> StatTile(label, value, Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    val eink = LocalEinkMode.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (eink) Color.White else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = if (eink) BorderStroke(1.dp, Color.Black) else null
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TopBookRow(rank: Int, book: YearBook) {
    val model = remember(book.coverUrl) {
        val url = book.coverUrl
        when {
            url == null -> null
            url.startsWith("/") -> File(url)   // local copy
            else -> url
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$rank", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.width(28.dp)
        )
        Box(
            Modifier
                .size(48.dp, 68.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (model != null) {
                AsyncImage(
                    model = model,
                    contentDescription = "Cover of ${book.title}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                book.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Text(
                "${yirDuration(book.timeMs)} · ${book.chapters} ${if (book.chapters == 1) "chapter" else "chapters"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Twelve bars, January to December, drawn by hand. Solid fills and no
 * animation, so it reads the same on an e-ink panel.
 */
@Composable
fun MonthBars(
    values: List<Long>,
    barColor: Color,
    emptyColor: Color,
    labelColor: Color,
    modifier: Modifier = Modifier
) {
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Column(modifier) {
        Canvas(Modifier.weight(1f).fillMaxWidth()) {
            val slot = size.width / 12f
            val barW = slot * 0.62f
            values.forEachIndexed { i, v ->
                val x = i * slot + (slot - barW) / 2f
                if (v <= 0L) {
                    // A hairline keeps empty months visible as months.
                    drawRect(emptyColor, Offset(x, size.height - 2f), Size(barW, 2f))
                } else {
                    val h = (size.height * v / max).coerceAtLeast(4f)
                    drawRoundRect(barColor, Offset(x, size.height - h), Size(barW, h), CornerRadius(3f, 3f))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            for (m in 1..12) {
                Text(
                    Month.of(m).getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Share image (1080x1920, rendered at density 3.0 -> 360x640 dp)
// ═══════════════════════════════════════════════════════════════

/**
 * Same near-black canvas, orange book and tracked wordmark as
 * [StatsShareCard]. Covers are left off on purpose: the offscreen render
 * captures one frame and image loading would race it, so titles stand in.
 */
@Composable
fun YearInReviewShareCard(review: YearInReview, books: List<YearBook>) {
    val bg = Color(0xFF0B0D10)
    val label = Color.White.copy(alpha = 0.80f)
    val accent = Color(0xFFF4611C)

    Box(Modifier.fillMaxSize().background(bg).padding(horizontal = 32.dp)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(0.8f))
            Text(
                "My ${review.year} in books", color = label, fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false
            )
            Spacer(Modifier.height(6.dp))
            Text(
                yirDuration(review.totalTimeMs), color = Color.White, fontSize = 56.sp, lineHeight = 60.sp,
                fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp, maxLines = 1, softWrap = false
            )
            Spacer(Modifier.height(28.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ShareStat(yirCount(review.totalWords), "words", label)
                ShareStat(review.chapters.toString(), "chapters", label)
                ShareStat(review.daysRead.toString(), "days", label)
            }

            Spacer(Modifier.height(32.dp))
            MonthBars(
                values = review.monthTimeMs,
                barColor = accent,
                emptyColor = Color.White.copy(alpha = 0.25f),
                labelColor = label,
                modifier = Modifier.fillMaxWidth().height(110.dp)
            )

            if (books.isNotEmpty()) {
                Spacer(Modifier.height(32.dp))
                Text("Most read", color = label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                books.forEachIndexed { i, b ->
                    Text(
                        "${i + 1}. ${b.title}", color = Color.White, fontSize = 18.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    )
                }
            }

            Spacer(Modifier.height(36.dp))
            Icon(Icons.Outlined.AutoStories, contentDescription = null, tint = accent, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(10.dp))
            Text(
                "NOVELFORGE", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold,
                letterSpacing = 4.sp, maxLines = 1, softWrap = false
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ShareStat(value: String, label: String, labelColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, softWrap = false)
        Text(label, color = labelColor, fontSize = 14.sp, maxLines = 1, softWrap = false)
    }
}

// ─── Formatting ──────────────────────────────────────────────────────

/** "142h 30m" for a year's worth of reading; minutes alone below an hour. */
private fun yirDuration(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    // A book read for under a minute still counts; "0m" reads as if it didn't.
    return when {
        h > 0 -> "${h}h ${m}m"
        m == 0L && ms > 0 -> "<1m"
        else -> "${m}m"
    }
}

private fun yirCount(n: Int): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0)
    else -> n.toString()
}

private fun monthName(month: Int): String =
    Month.of(month).getDisplayName(TextStyle.FULL, Locale.getDefault())

private fun yirDay(day: YearInReview.BusyDay): String =
    "${monthName(day.date.monthValue).take(3)} ${day.date.dayOfMonth}"
