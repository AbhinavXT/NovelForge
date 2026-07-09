package com.abhinavxt.novelforge.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abhinavxt.novelforge.data.NovelRepository
import com.abhinavxt.novelforge.ui.viewmodel.CodexGraphViewModel
import com.abhinavxt.novelforge.ui.viewmodel.CodexGraphViewModel.GraphUiState
import kotlinx.coroutines.launch
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Character relationship graph — nodes are top codex names sized by
 * mentions, edges are "appeared together in N chapters", positions
 * from a force layout. Tap one node to select it (its edges light
 * up), tap a second to see every chapter they share, tap a shared
 * chapter to deep-jump the reader there. Pinch to zoom, drag to pan.
 *
 * Spoiler-capped like everything codex: only chapters up to the
 * reading position feed the edges, so the graph grows as you read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodexGraphScreen(
    repository: NovelRepository,
    novelId: String,
    onBackClick: () -> Unit,
    onChapterClick: (chapterId: String, chapterUrl: String, paragraphIndex: Int) -> Unit,
    viewModel: CodexGraphViewModel = viewModel(
        factory = CodexGraphViewModel.provideFactory(novelId, repository)
    )
) {
    val uiState by viewModel.uiState.collectAsState()
    val selected by viewModel.selectedNode.collectAsState()
    val pairDetail by viewModel.pairDetail.collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Relationship Graph") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val state = uiState) {
                is GraphUiState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                is GraphUiState.Empty -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = state.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is GraphUiState.Ready -> GraphCanvas(
                    state = state,
                    selected = selected,
                    onNodeTap = { viewModel.onNodeTap(it) },
                    onBackgroundTap = { viewModel.clearSelection() }
                )
            }

            // Hint / selection chip overlay
            (uiState as? GraphUiState.Ready)?.let { state ->
                val hint = when {
                    selected >= 0 ->
                        "${state.nodes[selected].name} — tap another character to see their shared chapters"
                    else -> "Tap a character, then a second one · pinch to zoom" +
                            if (state.ceiling != Int.MAX_VALUE) " · up to Ch. ${state.ceiling}" else ""
                }
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 20.dp),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                    tonalElevation = 2.dp
                ) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }

    // ── Shared-chapters sheet ───────────────────────────────────
    pairDetail?.let { pair ->
        ModalBottomSheet(onDismissRequest = { viewModel.dismissPair() }) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = "${pair.nameA} × ${pair.nameB}",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "Together in ${pair.shared.size} chapter${if (pair.shared.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    items(pair.shared, key = { it.chapterId }) { ch ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        val paragraph = viewModel.resolveParagraphIndex(
                                            ch.chapterId, pair.nameA, pair.nameB
                                        )
                                        viewModel.dismissPair()
                                        onChapterClick(ch.chapterId, ch.url, paragraph)
                                    }
                                }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(
                                text = "Ch. ${ch.number} · ${ch.title}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun GraphCanvas(
    state: GraphUiState.Ready,
    selected: Int,
    onNodeTap: (Int) -> Unit,
    onBackgroundTap: () -> Unit
) {
    // Manual zoom/pan: screenPos = contentPos * zoom + pan. Drawing
    // and tap hit-testing share the SAME transform, so there is no
    // coordinate-space ambiguity, and node/label sizes stay constant
    // while zoom spreads the layout — better readability than
    // scaling the whole layer.
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        zoom = (zoom * zoomChange).coerceIn(0.6f, 4f)
        pan += panChange
    }

    val nodeColor = MaterialTheme.colorScheme.primary
    val nodeContainer = MaterialTheme.colorScheme.primaryContainer
    val nodeSelectedColor = MaterialTheme.colorScheme.tertiary
    val nodeSelectedContainer = MaterialTheme.colorScheme.tertiaryContainer
    val edgeColor = MaterialTheme.colorScheme.primary
    val edgeActiveColor = MaterialTheme.colorScheme.tertiary
    val labelColor = MaterialTheme.colorScheme.onSurface
    val labelChipColor = MaterialTheme.colorScheme.surfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    // Node radii in px, log-scaled by mentions so the protagonist
    // doesn't eclipse the canvas.
    val radii = remember(state.nodes, density) {
        val maxOcc = state.nodes.maxOf { it.occurrences }.coerceAtLeast(1)
        state.nodes.map { n ->
            with(density) {
                val t = ln(1.0 + n.occurrences) / ln(1.0 + maxOcc)
                (8 + 12 * t).dp.toPx()
            }
        }
    }

    // Neighbor set of the selected node, for highlight dimming.
    val neighbors = remember(selected, state.edges) {
        if (selected < 0) emptySet()
        else state.edges.mapNotNull { e ->
            when (selected) {
                e.a -> e.b
                e.b -> e.a
                else -> null
            }
        }.toSet()
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .transformable(transformState)
            .pointerInput(state, radii) {
                detectTapGestures { offset ->
                    // Hit-test in screen space against the same
                    // transform used for drawing (zoom/pan read at
                    // tap time — lambda captures the vars, and vars
                    // are State-backed so values are current).
                    var best = -1
                    var bestDist = Float.MAX_VALUE
                    state.nodes.forEachIndexed { i, node ->
                        val nx = node.x * size.width * zoom + pan.x
                        val ny = node.y * size.height * zoom + pan.y
                        val dx = offset.x - nx
                        val dy = offset.y - ny
                        val dist = sqrt(dx * dx + dy * dy)
                        val hitRadius = maxOf(radii[i] * 1.6f, 48f)
                        if (dist < hitRadius && dist < bestDist) {
                            best = i
                            bestDist = dist
                        }
                    }
                    if (best >= 0) onNodeTap(best) else onBackgroundTap()
                }
            }
    ) {
        fun screen(i: Int) = Offset(
            state.nodes[i].x * size.width * zoom + pan.x,
            state.nodes[i].y * size.height * zoom + pan.y
        )

        // ── Edges ───────────────────────────────────────────────
        // Primary-tinted, weight-driven alpha — `outline` gray was
        // invisible on AMOLED. Width in dp so it survives density.
        for (e in state.edges) {
            val touchesSelection = selected >= 0 && (e.a == selected || e.b == selected)
            val t = e.weight.toFloat() / state.maxWeight
            val w = (1.2f + 4.5f * t).dp.toPx()
            val (color, alpha) = when {
                selected < 0 -> edgeColor to (0.30f + 0.50f * t)
                touchesSelection -> edgeActiveColor to 0.95f
                else -> edgeColor to 0.08f
            }
            drawLine(
                color = color.copy(alpha = alpha),
                start = screen(e.a),
                end = screen(e.b),
                strokeWidth = w
            )
        }

        // ── Nodes + labels ──────────────────────────────────────
        state.nodes.forEachIndexed { i, node ->
            val pos = screen(i)
            val dimmed = selected >= 0 && i != selected && i !in neighbors
            val alpha = if (dimmed) 0.25f else 1f

            // Selection halo
            if (i == selected) {
                drawCircle(
                    color = nodeSelectedColor.copy(alpha = 0.25f),
                    radius = radii[i] + 8.dp.toPx(),
                    center = pos
                )
            }
            // Filled disc + ring: container fill reads as a surface,
            // the ring gives it definition against edges behind it.
            drawCircle(
                color = (if (i == selected) nodeSelectedContainer else nodeContainer)
                    .copy(alpha = alpha),
                radius = radii[i],
                center = pos
            )
            drawCircle(
                color = (if (i == selected) nodeSelectedColor else nodeColor)
                    .copy(alpha = alpha),
                radius = radii[i],
                center = pos,
                style = Stroke(width = 2.dp.toPx())
            )

            // Label on a pill chip so it stays readable over edges.
            val label = if (node.name.length > 14) node.name.take(13) + "…" else node.name
            val measured = textMeasurer.measure(
                text = label,
                style = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Medium
                )
            )
            val pad = 4.dp.toPx()
            val chipTop = pos.y + radii[i] + 4.dp.toPx()
            drawRoundRect(
                color = labelChipColor.copy(alpha = if (dimmed) 0.3f else 0.75f),
                topLeft = Offset(pos.x - measured.size.width / 2f - pad, chipTop),
                size = androidx.compose.ui.geometry.Size(
                    measured.size.width + pad * 2,
                    measured.size.height + pad
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx())
            )
            drawText(
                textLayoutResult = measured,
                color = labelColor.copy(alpha = if (dimmed) 0.4f else 1f),
                topLeft = Offset(
                    pos.x - measured.size.width / 2f,
                    chipTop + pad / 2
                )
            )
        }
    }
}
