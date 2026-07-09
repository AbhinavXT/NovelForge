package com.abhinavxt.novelforge.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abhinavxt.novelforge.data.NovelRepository
import com.abhinavxt.novelforge.data.database.CodexMention
import com.abhinavxt.novelforge.data.database.CodexNameEntity
import com.abhinavxt.novelforge.ui.viewmodel.CodexViewModel
import com.abhinavxt.novelforge.util.MatchHighlight
import kotlinx.coroutines.launch

/**
 * Character Codex — "who is this again?" for long webnovels.
 *
 * The list shows heuristically detected characters/places/factions
 * from downloaded chapters; the spoiler guard hides anyone the
 * reader hasn't met yet and caps every mention lookup at the current
 * reading position. Tapping a mention deep-jumps the reader to the
 * paragraph via the same mechanism as library search.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodexScreen(
    repository: NovelRepository,
    novelId: String,
    onBackClick: () -> Unit,
    onMentionClick: (chapterId: String, chapterUrl: String, paragraphIndex: Int) -> Unit,
    onGraphClick: () -> Unit = {},
    viewModel: CodexViewModel = viewModel(
        factory = CodexViewModel.provideFactory(novelId, repository)
    )
) {
    val names by viewModel.names.collectAsState()
    val scanState by viewModel.scanState.collectAsState()
    val hasScanned by viewModel.hasScanned.collectAsState()
    val spoilerGuard by viewModel.spoilerGuardEnabled.collectAsState()
    val ceiling by viewModel.readingCeiling.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val detail by viewModel.detail.collectAsState()
    val maxChapter by viewModel.maxChapterNumber.collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Codex") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Relationship graph — who appears with whom.
                    IconButton(onClick = onGraphClick) {
                        Icon(Icons.Default.Hub, contentDescription = "Relationship graph")
                    }
                    // Incremental: only chapters downloaded since the
                    // last scan are processed.
                    IconButton(
                        onClick = { viewModel.startScan() },
                        enabled = scanState is CodexViewModel.ScanState.Idle
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Rescan new chapters")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val scan = scanState) {
                is CodexViewModel.ScanState.Running -> {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            text = if (scan.total > 0) {
                                "Scanning chapter ${scan.scanned}/${scan.total}…"
                            } else "Preparing scan…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = if (scan.total > 0) scan.scanned.toFloat() / scan.total else 0f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                else -> Unit
            }

            when {
                // Never scanned and nothing running: invite.
                hasScanned == false && scanState is CodexViewModel.ScanState.Idle -> {
                    CodexEmptyState(
                        title = "Build the codex",
                        subtitle = "Claude-free, offline heuristics scan your downloaded chapters and find every recurring character, place, and faction.",
                        buttonLabel = "Scan downloaded chapters",
                        onButtonClick = { viewModel.startScan() }
                    )
                }

                hasScanned == true && names.isEmpty() && filter.isBlank() &&
                        scanState is CodexViewModel.ScanState.Idle -> {
                    CodexEmptyState(
                        title = "No entries yet",
                        subtitle = if (spoilerGuard) {
                            "Nothing found up to your reading position. Read further, download more chapters, or turn off the spoiler guard."
                        } else {
                            "No recurring names found in the downloaded chapters."
                        },
                        buttonLabel = "Rebuild from scratch",
                        onButtonClick = { viewModel.startScan(rebuild = true) }
                    )
                }

                else -> {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { viewModel.setFilter(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        placeholder = { Text("Filter names…") },
                        trailingIcon = {
                            if (filter.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setFilter("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear filter")
                                }
                            }
                        },
                        singleLine = true
                    )

                    if (ceiling != Int.MAX_VALUE) {
                        FilterChip(
                            selected = spoilerGuard,
                            onClick = { viewModel.toggleSpoilerGuard() },
                            label = {
                                Text(
                                    if (spoilerGuard) "Spoiler guard · up to Ch. $ceiling"
                                    else "Spoiler guard off"
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.VisibilityOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }

                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(names, key = { it.name }) { entry ->
                            CodexNameRow(
                                entry = entry,
                                onClick = { viewModel.selectName(entry) }
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Detail sheet ────────────────────────────────────────────
    detail?.let { d ->
        ModalBottomSheet(onDismissRequest = { viewModel.dismissDetail() }) {
            CodexDetailContent(
                detail = d,
                maxChapterNumber = if (spoilerGuard) minOf(ceiling, maxChapter) else maxChapter,
                onMentionClick = { mention ->
                    scope.launch {
                        val paragraph = viewModel.resolveParagraphIndex(mention.chapterId, d.name)
                        viewModel.dismissDetail()
                        onMentionClick(mention.chapterId, mention.chapterUrl, paragraph)
                    }
                }
            )
        }
    }
}

@Composable
private fun CodexEmptyState(
    title: String,
    subtitle: String,
    buttonLabel: String,
    onButtonClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Groups,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onButtonClick) { Text(buttonLabel) }
        }
    }
}

@Composable
private fun CodexNameRow(
    entry: CodexNameEntity,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${entry.occurrences} mentions · ${entry.chapterCount} chapters · since Ch. ${entry.firstChapterNumber}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CodexDetailContent(
    detail: CodexViewModel.DetailState,
    maxChapterNumber: Int,
    onMentionClick: (CodexMention) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = detail.name,
            style = MaterialTheme.typography.headlineSmall
        )
        detail.entry?.let { e ->
            Text(
                text = "First appears in Ch. ${e.firstChapterNumber} · ${e.occurrences} mentions across ${e.chapterCount} chapters",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (detail.loading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            return@Column
        }

        // ── Presence sparkline ──────────────────────────────────
        if (detail.mentions.isNotEmpty() && maxChapterNumber > 1) {
            MentionSparkline(
                mentions = detail.mentions,
                firstChapter = detail.entry?.firstChapterNumber ?: 1,
                maxChapterNumber = maxChapterNumber
            )
            Text(
                text = "Ch. 1 — Ch. $maxChapterNumber",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // ── Highlights mentioning this name ─────────────────────
        if (detail.highlights.isNotEmpty()) {
            Text(
                text = "Your highlights (${detail.highlights.size})",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        // ── Mentions ────────────────────────────────────────────
        Text(
            text = "Mentions (${detail.mentions.size}${if (detail.mentions.size >= 400) "+" else ""})",
            style = MaterialTheme.typography.titleSmall
        )
        // Bounded: a LazyColumn inside a bottom sheet must not ask for
        // infinite height; 420dp keeps the sheet usable on any phone.
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
        ) {
            items(detail.highlights.take(5), key = { "hl-${it.id}" }) { hl ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                ) {
                    Text(
                        text = "\u201C${hl.selectedText}\u201D",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Highlight · Ch. ${hl.chapterNumber}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (detail.highlights.isNotEmpty()) {
                item(key = "hl-div") { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
            }
            items(detail.mentions, key = { it.chapterId }) { mention ->
                CodexMentionRow(mention = mention, onClick = { onMentionClick(mention) })
            }
        }
    }
}

@Composable
private fun CodexMentionRow(
    mention: CodexMention,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = "Ch. ${mention.chapterNumber} · ${mention.chapterTitle}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        val annotated = remember(mention.snippet) {
            MatchHighlight.parseFtsSnippet(mention.snippet)
        }
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Presence sparkline: chapters 1..max bucketed into vertical bars,
 * bar height = number of mention-chapters in that bucket. Shows the
 * shape of a character's presence at a glance — "introduced early,
 * vanished for 400 chapters, back for the finale arc".
 */
@Composable
private fun MentionSparkline(
    mentions: List<CodexMention>,
    firstChapter: Int,
    maxChapterNumber: Int
) {
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val buckets = 48

    val counts = remember(mentions, maxChapterNumber) {
        val arr = IntArray(buckets)
        mentions.forEach { m ->
            val frac = (m.chapterNumber - 1).toFloat() / maxChapterNumber
            val idx = (frac * buckets).toInt().coerceIn(0, buckets - 1)
            arr[idx]++
        }
        arr
    }
    val maxCount = remember(counts) { (counts.maxOrNull() ?: 1).coerceAtLeast(1) }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
    ) {
        val barWidth = size.width / buckets
        for (i in 0 until buckets) {
            val h = if (counts[i] == 0) 0f else {
                // Minimum visible height for any presence.
                (size.height * counts[i] / maxCount).coerceAtLeast(3.dp.toPx())
            }
            drawRect(
                color = trackColor,
                topLeft = androidx.compose.ui.geometry.Offset(i * barWidth, size.height - 2.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(barWidth * 0.7f, 2.dp.toPx())
            )
            if (h > 0f) {
                drawRect(
                    color = barColor,
                    topLeft = androidx.compose.ui.geometry.Offset(i * barWidth, size.height - h),
                    size = androidx.compose.ui.geometry.Size(barWidth * 0.7f, h)
                )
            }
        }
    }
}
