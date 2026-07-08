package com.abhinavxt.novelreader.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.abhinavxt.novelreader.data.NovelRepository
import com.abhinavxt.novelreader.data.database.ChapterSearchResult
import com.abhinavxt.novelreader.util.Logger
import com.abhinavxt.novelreader.util.ParagraphSplitter
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * One novel's group of full-text search hits. Results arrive from
 * the DAO ordered by (novelTitle, chapterNumber), so grouping is a
 * simple adjacency fold.
 */
data class NovelSearchGroup(
    val novelId: String,
    val novelTitle: String,
    val coverUrl: String?,
    val hits: List<ChapterSearchResult>
)

sealed interface TextSearchUiState {
    /** Nothing typed yet — show the "search your downloads" hint. */
    object Idle : TextSearchUiState
    object Searching : TextSearchUiState
    /** Query ran but matched nothing. */
    data class Empty(val query: String) : TextSearchUiState
    data class Success(
        val query: String,
        val groups: List<NovelSearchGroup>,
        val totalHits: Int
    ) : TextSearchUiState
}

/**
 * Library-wide full-text search over downloaded chapter content.
 * The FTS index lives in Room (chapters_fts); this ViewModel just
 * debounces keystrokes, runs the query, groups results by novel,
 * and resolves a tapped hit into a paragraph index for the reader's
 * deep-jump.
 */
class TextSearchViewModel(
    private val repository: NovelRepository
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _uiState = MutableStateFlow<TextSearchUiState>(TextSearchUiState.Idle)
    val uiState: StateFlow<TextSearchUiState> = _uiState.asStateFlow()

    init {
        observeQuery()
    }

    fun onQueryChange(value: String) {
        _query.value = value
        // Immediate feedback for the two cheap cases; the debounced
        // collector below handles the actual search.
        if (value.isBlank()) {
            _uiState.value = TextSearchUiState.Idle
        } else {
            _uiState.value = TextSearchUiState.Searching
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeQuery() {
        viewModelScope.launch {
            _query
                .debounce(300)
                .collectLatest { q ->
                    val trimmed = q.trim()
                    if (trimmed.length < 2) {
                        // Single characters match half the library and
                        // churn the FTS index for no useful result.
                        if (trimmed.isEmpty()) _uiState.value = TextSearchUiState.Idle
                        return@collectLatest
                    }

                    val results = repository.searchDownloadedContent(trimmed)

                    // collectLatest cancels this block when a newer
                    // query arrives, so stale results never land.
                    _uiState.value = if (results.isEmpty()) {
                        TextSearchUiState.Empty(trimmed)
                    } else {
                        TextSearchUiState.Success(
                            query = trimmed,
                            groups = groupByNovel(results),
                            totalHits = results.size
                        )
                    }
                }
        }
    }

    private fun groupByNovel(results: List<ChapterSearchResult>): List<NovelSearchGroup> {
        val groups = mutableListOf<NovelSearchGroup>()
        var current = mutableListOf<ChapterSearchResult>()

        for (hit in results) {
            if (current.isNotEmpty() && current.first().novelId != hit.novelId) {
                groups += current.toGroup()
                current = mutableListOf()
            }
            current += hit
        }
        if (current.isNotEmpty()) groups += current.toGroup()
        return groups
    }

    private fun List<ChapterSearchResult>.toGroup() = NovelSearchGroup(
        novelId = first().novelId,
        novelTitle = first().novelTitle,
        coverUrl = first().coverUrl,
        hits = this
    )

    /**
     * Turn a tapped hit into a paragraph index using the exact same
     * split the reader uses. DB read + split of one chapter — a few
     * ms, but still off the click handler via the caller's coroutine.
     * Returns 0 (top of chapter) on any miss so navigation always
     * proceeds.
     */
    suspend fun resolveParagraphIndex(chapterId: String): Int {
        val content = repository.getDownloadedChapterContent(chapterId)
        if (content == null) {
            Logger.w("Search hit for $chapterId has no local content — jumping to top")
            return 0
        }
        val paragraphs = ParagraphSplitter.split(content)
        return ParagraphSplitter.findFirstMatch(paragraphs, _query.value)
    }

    companion object {
        fun provideFactory(repository: NovelRepository): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    return TextSearchViewModel(repository) as T
                }
            }
        }
    }
}
