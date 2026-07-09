package com.abhinavxt.novelforge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.abhinavxt.novelforge.data.NovelRepository
import com.abhinavxt.novelforge.util.Logger
import com.abhinavxt.novelforge.util.ParagraphSplitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Character relationship graph for one novel.
 *
 * Built entirely from existing infrastructure — no new scan, no new
 * tables: the top codex names each get a lightweight FTS query for
 * their mention-chapter set, and an edge between two names is the
 * size of the intersection ("appeared together in N chapters").
 * Because those queries are spoiler-capped at the reading position,
 * the graph only ever shows relationships you've read — it literally
 * grows as you read.
 *
 * Positions come from a small Fruchterman–Reingold force layout,
 * validated for cluster separation and NaN-safety before porting.
 * Deterministic circle init → the same book renders the same shape
 * every time.
 */
class CodexGraphViewModel(
    private val novelId: String,
    private val repository: NovelRepository
) : ViewModel() {

    companion object {
        private const val MAX_NODES = 25
        private const val MIN_NODE_OCCURRENCES = 5
        /** Sharing fewer chapters than this isn't a relationship. */
        private const val MIN_EDGE_WEIGHT = 2
        /** Hairball guard: keep only the strongest edges. */
        private const val MAX_EDGES = 60
        private const val LAYOUT_ITERATIONS = 180

        fun provideFactory(
            novelId: String,
            repository: NovelRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return CodexGraphViewModel(novelId, repository) as T
            }
        }
    }

    data class GraphNode(
        val name: String,
        val occurrences: Int,
        val chapterSet: Set<Int>,
        /** Layout position in unit space [0,1]². */
        val x: Float,
        val y: Float
    )

    data class GraphEdge(val a: Int, val b: Int, val weight: Int)

    sealed interface GraphUiState {
        object Loading : GraphUiState
        /** Not enough codex data — scan first, or read further. */
        data class Empty(val reason: String) : GraphUiState
        data class Ready(
            val nodes: List<GraphNode>,
            val edges: List<GraphEdge>,
            val maxWeight: Int,
            val ceiling: Int
        ) : GraphUiState
    }

    private val _uiState = MutableStateFlow<GraphUiState>(GraphUiState.Loading)
    val uiState: StateFlow<GraphUiState> = _uiState.asStateFlow()

    /** Selected node index, or -1. Second selection opens the pair sheet. */
    private val _selectedNode = MutableStateFlow(-1)
    val selectedNode: StateFlow<Int> = _selectedNode.asStateFlow()

    // ── Pair detail (bottom sheet) ──────────────────────────────
    data class SharedChapter(
        val chapterId: String,
        val number: Int,
        val title: String,
        val url: String
    )

    data class PairDetail(
        val nameA: String,
        val nameB: String,
        val shared: List<SharedChapter>
    )

    private val _pairDetail = MutableStateFlow<PairDetail?>(null)
    val pairDetail: StateFlow<PairDetail?> = _pairDetail.asStateFlow()

    private var spoilerCeiling = Int.MAX_VALUE

    init {
        buildGraph()
    }

    private fun buildGraph() {
        viewModelScope.launch {
            _uiState.value = GraphUiState.Loading
            try {
                _uiState.value = withContext(Dispatchers.Default) { compute() }
            } catch (e: Exception) {
                Logger.e("CodexGraphViewModel", "Graph build failed", e)
                _uiState.value = GraphUiState.Empty("Couldn't build the graph")
            }
        }
    }

    private suspend fun compute(): GraphUiState {
        val progress = repository.getReadingProgress(novelId)
        val ceiling = progress?.currentChapterNumber ?: 0
        spoilerCeiling = if (ceiling > 0) ceiling else Int.MAX_VALUE

        val topNames = repository.getCodexNamesOnce(novelId)
            .filter { it.occurrences >= MIN_NODE_OCCURRENCES }
            .filter { spoilerCeiling == Int.MAX_VALUE || it.firstChapterNumber <= spoilerCeiling }
            .sortedByDescending { it.occurrences }
            .take(MAX_NODES)

        if (topNames.size < 2) {
            return GraphUiState.Empty(
                if (topNames.isEmpty()) "Build the codex first — the graph is drawn from its entries."
                else "Need at least two characters up to your reading position."
            )
        }

        // One lightweight FTS query per name, in parallel.
        val chapterSets: List<Set<Int>> = coroutineScope {
            topNames.map { entry ->
                async {
                    repository.getCodexMentionChapterNumbers(
                        novelId, entry.name, spoilerCeiling
                    ).toSet()
                }
            }.map { it.await() }
        }

        // Co-occurrence: edge weight = shared chapter count.
        val edges = mutableListOf<GraphEdge>()
        for (i in topNames.indices) {
            for (j in i + 1 until topNames.size) {
                val w = countIntersection(chapterSets[i], chapterSets[j])
                if (w >= MIN_EDGE_WEIGHT) edges += GraphEdge(i, j, w)
            }
        }
        val kept = edges.sortedByDescending { it.weight }.take(MAX_EDGES)

        val positions = forceLayout(topNames.size, kept)
        val nodes = topNames.mapIndexed { i, entry ->
            GraphNode(
                name = entry.name,
                occurrences = entry.occurrences,
                chapterSet = chapterSets[i],
                x = positions[i].first,
                y = positions[i].second
            )
        }
        return GraphUiState.Ready(
            nodes = nodes,
            edges = kept,
            maxWeight = kept.maxOfOrNull { it.weight } ?: 1,
            ceiling = spoilerCeiling
        )
    }

    private fun countIntersection(a: Set<Int>, b: Set<Int>): Int {
        val (small, large) = if (a.size <= b.size) a to b else b to a
        var n = 0
        for (v in small) if (v in large) n++
        return n
    }

    // ── Selection ───────────────────────────────────────────────

    /**
     * First tap selects a node; tapping a second node opens the
     * pair sheet for the two; tapping the selected node (or empty
     * space, handled in the screen) clears.
     */
    fun onNodeTap(index: Int) {
        val state = _uiState.value as? GraphUiState.Ready ?: return
        val current = _selectedNode.value
        when {
            current == -1 -> _selectedNode.value = index
            current == index -> _selectedNode.value = -1
            else -> {
                openPair(state, current, index)
                _selectedNode.value = -1
            }
        }
    }

    fun clearSelection() { _selectedNode.value = -1 }

    private fun openPair(state: GraphUiState.Ready, a: Int, b: Int) {
        val nodeA = state.nodes[a]
        val nodeB = state.nodes[b]
        val sharedNumbers = nodeA.chapterSet.intersect(nodeB.chapterSet).sorted()
        viewModelScope.launch {
            // Map chapter numbers → metadata for the sheet rows.
            val byNumber = repository.getChaptersOnce(novelId).associateBy { it.number }
            val shared = sharedNumbers.mapNotNull { n ->
                byNumber[n]?.let { ch ->
                    SharedChapter(chapterId = ch.id, number = n, title = ch.title, url = ch.url)
                }
            }
            _pairDetail.value = PairDetail(nodeA.name, nodeB.name, shared)
        }
    }

    fun dismissPair() { _pairDetail.value = null }

    /** Deep-jump target: first paragraph in the chapter naming either character. */
    suspend fun resolveParagraphIndex(chapterId: String, nameA: String, nameB: String): Int {
        val content = repository.getDownloadedChapterContent(chapterId) ?: return 0
        val paragraphs = ParagraphSplitter.split(content)
        // Prefer a paragraph containing both; fall back to A alone.
        val both = paragraphs.indexOfFirst {
            it.contains(nameA, ignoreCase = true) && it.contains(nameB, ignoreCase = true)
        }
        if (both >= 0) return both
        return ParagraphSplitter.findFirstMatch(paragraphs, nameA)
    }

    // ── Force layout (validated port) ───────────────────────────

    private fun forceLayout(n: Int, edges: List<GraphEdge>): List<Pair<Float, Float>> {
        if (n == 1) return listOf(0.5f to 0.5f)

        val px = DoubleArray(n); val py = DoubleArray(n)
        for (i in 0 until n) {
            val ang = 2.0 * Math.PI * i / n
            px[i] = 0.5 + 0.35 * cos(ang)
            py[i] = 0.5 + 0.35 * sin(ang)
        }
        // v2 tuning (validated): larger k = more personal space;
        // log-weight + sqrt(degree)-damped attraction stops the
        // heavily-connected core from collapsing into a single blob.
        val k = 1.2 / sqrt(n.toDouble())
        val maxW = (edges.maxOfOrNull { it.weight } ?: 1).toDouble()
        val degree = IntArray(n)
        for (e in edges) { degree[e.a]++; degree[e.b]++ }
        var temp = 0.12
        val dx = DoubleArray(n); val dy = DoubleArray(n)
        // Deterministic jitter source for coincident nodes.
        var seed = 1234567L
        fun jitter(): Double {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return ((seed ushr 33).toDouble() / (1L shl 31).toDouble() - 0.5) * 1e-3
        }

        repeat(LAYOUT_ITERATIONS) {
            java.util.Arrays.fill(dx, 0.0)
            java.util.Arrays.fill(dy, 0.0)
            // Repulsion, all pairs. n ≤ 25 → ≤ 300 pairs/iteration.
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    var ddx = px[i] - px[j]
                    var ddy = py[i] - py[j]
                    var d = sqrt(ddx * ddx + ddy * ddy)
                    if (d < 1e-4) { ddx = jitter(); ddy = jitter(); d = 1e-3 }
                    val f = k * k / d
                    dx[i] += ddx / d * f; dy[i] += ddy / d * f
                    dx[j] -= ddx / d * f; dy[j] -= ddy / d * f
                }
            }
            // Attraction along edges. Log-scaled weight (a 200-chapter
            // bond shouldn't fuse two nodes) and per-node degree
            // damping (a hub pulled by 15 edges otherwise implodes).
            for (e in edges) {
                val ddx = px[e.a] - px[e.b]
                val ddy = py[e.a] - py[e.b]
                val d = sqrt(ddx * ddx + ddy * ddy)
                if (d < 1e-4) continue
                val wf = 0.15 + 0.35 * kotlin.math.ln(1.0 + e.weight) / kotlin.math.ln(1.0 + maxW)
                val f = d * d / k * wf
                val fa = f / maxOf(1.0, sqrt(degree[e.a].toDouble()))
                val fb = f / maxOf(1.0, sqrt(degree[e.b].toDouble()))
                dx[e.a] -= ddx / d * fa; dy[e.a] -= ddy / d * fa
                dx[e.b] += ddx / d * fb; dy[e.b] += ddy / d * fb
            }
            // Gravity + clamped step.
            for (i in 0 until n) {
                dx[i] += (0.5 - px[i]) * 0.06
                dy[i] += (0.5 - py[i]) * 0.06
                val dl = sqrt(dx[i] * dx[i] + dy[i] * dy[i])
                if (dl > 0) {
                    val step = min(dl, temp)
                    px[i] += dx[i] / dl * step
                    py[i] += dy[i] / dl * step
                }
            }
            temp *= 0.97
        }

        // Normalize into a padded unit box.
        val minX = px.min(); val maxX = px.max()
        val minY = py.min(); val maxY = py.max()
        val sx = (maxX - minX).takeIf { it > 1e-9 } ?: 1.0
        val sy = (maxY - minY).takeIf { it > 1e-9 } ?: 1.0
        for (i in 0 until n) {
            px[i] = (px[i] - minX) / sx * 0.88 + 0.06
            py[i] = (py[i] - minY) / sy * 0.88 + 0.06
        }

        // Collision separation post-pass: hard floor on pairwise
        // distance so nodes (and their labels) can never overlap,
        // whatever the force phase converged to. 0.085 normalized
        // ≈ 90+px between centers on a phone.
        val minSep = 0.085
        run {
            repeat(60) {
                var moved = false
                for (i in 0 until n) {
                    for (j in i + 1 until n) {
                        var ddx = px[i] - px[j]
                        var ddy = py[i] - py[j]
                        var d = sqrt(ddx * ddx + ddy * ddy)
                        if (d < 1e-5) { ddx = jitter(); ddy = jitter(); d = 1e-4 }
                        if (d < minSep) {
                            val push = (minSep - d) / 2
                            px[i] += ddx / d * push; py[i] += ddy / d * push
                            px[j] -= ddx / d * push; py[j] -= ddy / d * push
                            moved = true
                        }
                    }
                }
                if (!moved) return@run
            }
        }
        for (i in 0 until n) {
            px[i] = px[i].coerceIn(0.03, 0.97)
            py[i] = py[i].coerceIn(0.03, 0.97)
        }
        return (0 until n).map { i -> px[i].toFloat() to py[i].toFloat() }
    }
}
