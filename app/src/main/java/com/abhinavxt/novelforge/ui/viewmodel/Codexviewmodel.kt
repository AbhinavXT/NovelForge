package com.abhinavxt.novelforge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.abhinavxt.novelforge.data.NovelRepository
import com.abhinavxt.novelforge.data.codex.CodexEngine
import com.abhinavxt.novelforge.data.database.CodexMention
import com.abhinavxt.novelforge.data.database.CodexNameEntity
import com.abhinavxt.novelforge.data.database.HighlightEntity
import com.abhinavxt.novelforge.util.Logger
import com.abhinavxt.novelforge.util.ParagraphSplitter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Character Codex for one novel.
 *
 * Names come from the cached heuristic scan (codex_names) as a
 * Flow; the spoiler gate and text filter are applied reactively on
 * top. Mention lookups per name are live FTS queries, spoiler-capped
 * at the reader's current chapter.
 */
class CodexViewModel(
    private val novelId: String,
    private val repository: NovelRepository
) : ViewModel() {

    // ── Spoiler gate ────────────────────────────────────────────
    // Ceiling = the chapter number the reader has reached. MAX_VALUE
    // means "no gate" (never read, or toggle off).
    private val _spoilerGuardEnabled = MutableStateFlow(true)
    val spoilerGuardEnabled: StateFlow<Boolean> = _spoilerGuardEnabled.asStateFlow()

    private val _readingCeiling = MutableStateFlow(Int.MAX_VALUE)
    val readingCeiling: StateFlow<Int> = _readingCeiling.asStateFlow()

    private val _filter = MutableStateFlow("")
    val filter: StateFlow<String> = _filter.asStateFlow()

    // ── Scan state ──────────────────────────────────────────────
    sealed interface ScanState {
        object Idle : ScanState
        data class Running(val scanned: Int, val total: Int) : ScanState
    }

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    /** True once we know whether this novel has ever been scanned. */
    private val _hasScanned = MutableStateFlow<Boolean?>(null)
    val hasScanned: StateFlow<Boolean?> = _hasScanned.asStateFlow()

    private var scanJob: Job? = null

    /** Highest downloaded chapter number — sparkline x-axis extent. */
    private val _maxChapterNumber = MutableStateFlow(0)
    val maxChapterNumber: StateFlow<Int> = _maxChapterNumber.asStateFlow()

    // ── Names list: cached scan × spoiler gate × filter ──────────
    val names: StateFlow<List<CodexNameEntity>> = combine(
        repository.getCodexNamesFlow(novelId),
        _spoilerGuardEnabled,
        _readingCeiling,
        _filter
    ) { all, guard, ceiling, filter ->
        all.asSequence()
            .filter { !guard || it.firstChapterNumber <= ceiling }
            .filter { filter.isBlank() || it.name.contains(filter, ignoreCase = true) }
            .toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Detail (bottom sheet) ───────────────────────────────────
    data class DetailState(
        val name: String,
        val entry: CodexNameEntity?,
        val loading: Boolean,
        val mentions: List<CodexMention> = emptyList(),
        val highlights: List<HighlightEntity> = emptyList()
    )

    private val _detail = MutableStateFlow<DetailState?>(null)
    val detail: StateFlow<DetailState?> = _detail.asStateFlow()

    init {
        viewModelScope.launch {
            // Reading ceiling from progress. currentChapterNumber
            // defaults to 0 on legacy rows — treat that as "unknown",
            // i.e. no gate, rather than hiding everything.
            val progress = repository.getReadingProgress(novelId)
            val ceiling = progress?.currentChapterNumber ?: 0
            _readingCeiling.value = if (ceiling > 0) ceiling else Int.MAX_VALUE
            _spoilerGuardEnabled.value = ceiling > 0

            _maxChapterNumber.value = repository.getChaptersOnce(novelId)
                .filter { it.isDownloaded }
                .maxOfOrNull { it.number } ?: 0

            _hasScanned.value = repository.getCodexScanInfo(novelId) != null
        }
    }

    fun setFilter(value: String) { _filter.value = value }

    fun toggleSpoilerGuard() {
        _spoilerGuardEnabled.value = !_spoilerGuardEnabled.value
    }

    /** Incremental scan — only chapters newer than the watermark. */
    fun startScan(rebuild: Boolean = false) {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            _scanState.value = ScanState.Running(0, 0)
            try {
                val onProgress = { p: CodexEngine.ScanProgress ->
                    _scanState.value = ScanState.Running(p.scanned, p.total)
                }
                if (rebuild) CodexEngine.rebuild(repository, novelId, onProgress)
                else CodexEngine.scan(repository, novelId, onProgress)
                _hasScanned.value = true
            } catch (e: Exception) {
                Logger.e("CodexViewModel", "Scan failed", e)
            } finally {
                _scanState.value = ScanState.Idle
            }
        }
    }

    fun selectName(entry: CodexNameEntity) {
        _detail.value = DetailState(name = entry.name, entry = entry, loading = true)
        viewModelScope.launch {
            val mentions = repository.getCodexMentions(
                novelId = novelId,
                name = entry.name,
                maxChapterNumber = if (_spoilerGuardEnabled.value) _readingCeiling.value else Int.MAX_VALUE
            )
            val highlights = repository.getHighlightsForNovelOnce(novelId)
                .filter { it.selectedText.contains(entry.name, ignoreCase = true) }
                .filter { !_spoilerGuardEnabled.value || it.chapterNumber <= _readingCeiling.value }
            // Only publish if this name is still the selected one.
            if (_detail.value?.name == entry.name) {
                _detail.value = DetailState(
                    name = entry.name,
                    entry = entry,
                    loading = false,
                    mentions = mentions,
                    highlights = highlights
                )
            }
        }
    }

    fun dismissDetail() { _detail.value = null }

    /**
     * Mention tap → paragraph index for the reader's deep-jump, using
     * the same split + first-match logic as library search.
     */
    suspend fun resolveParagraphIndex(chapterId: String, name: String): Int {
        val content = repository.getDownloadedChapterContent(chapterId) ?: return 0
        val paragraphs = ParagraphSplitter.split(content)
        return ParagraphSplitter.findFirstMatch(paragraphs, name)
    }

    companion object {
        fun provideFactory(
            novelId: String,
            repository: NovelRepository
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return CodexViewModel(novelId, repository) as T
                }
            }
        }
    }
}
