package com.abhinavxt.novelforge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.abhinavxt.novelforge.data.NovelRepository
import com.abhinavxt.novelforge.data.model.NovelPreview
import com.abhinavxt.novelforge.data.source.Source
import com.abhinavxt.novelforge.data.source.SourceManager
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One source's slice of a global (all-sources) search. */
data class SourceResults(
    val sourceName: String,
    val sourceId: String,
    val novels: List<NovelPreview>
)

sealed interface SearchUiState {
    object Initial : SearchUiState
    object Loading : SearchUiState
    data class Success(val novels: List<NovelPreview>) : SearchUiState

    /** Global search results, grouped by source (Phase 7). */
    data class SuccessGrouped(val groups: List<SourceResults>) : SearchUiState

    data class Error(val message: String) : SearchUiState
}

class SearchViewModel(
    private val repository: NovelRepository,
    appContext: android.content.Context
) : ViewModel() {

    // Application context only (provided by the factory) — safe to hold.
    private val prefs = appContext.applicationContext
        .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Initial)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private var searchJob: Job? = null
    private var popularJob: Job? = null  // Track popular novels loading job

    private val _popularNovels = MutableStateFlow<List<NovelPreview>>(emptyList())
    val popularNovels: StateFlow<List<NovelPreview>> = _popularNovels.asStateFlow()

    // Source selection
    private val _selectedSource = MutableStateFlow(SourceManager.getDefaultSource())
    val selectedSource: StateFlow<Source> = _selectedSource.asStateFlow()

    val availableSources: List<Source> = SourceManager.getAllSources()

    // ── Source picker: pinned + recently used (persisted) ────────
    // Stored ids are filtered against availableSources at read time, so
    // entries for removed sources are silently dropped.
    private val _pinnedSourceIds = MutableStateFlow(
        prefs.getStringSet(PREF_PINNED, emptySet())!!.toSet()
    )
    val pinnedSourceIds: StateFlow<Set<String>> = _pinnedSourceIds.asStateFlow()

    private val _recentSourceIds = MutableStateFlow(loadRecents())
    val recentSourceIds: StateFlow<List<String>> = _recentSourceIds.asStateFlow()

    fun togglePin(sourceId: String) {
        val updated = _pinnedSourceIds.value.toMutableSet().apply {
            if (!add(sourceId)) remove(sourceId)
        }
        _pinnedSourceIds.value = updated
        prefs.edit().putStringSet(PREF_PINNED, updated).apply()
    }

    private fun loadRecents(): List<String> =
        prefs.getString(PREF_RECENTS, "")!!
            .split(',')
            .filter { it.isNotBlank() }

    private fun recordRecent(sourceId: String) {
        val updated = (listOf(sourceId) + _recentSourceIds.value.filter { it != sourceId })
            .take(MAX_RECENTS)
        _recentSourceIds.value = updated
        prefs.edit().putString(PREF_RECENTS, updated.joinToString(",")).apply()
    }

    // ── Global search mode (Phase 7) ─────────────────────────────
    // When true, searches fan out to every source concurrently and
    // results render grouped by source.
    private val _allSourcesMode = MutableStateFlow(false)
    val allSourcesMode: StateFlow<Boolean> = _allSourcesMode.asStateFlow()

    fun selectAllSources() {
        if (_allSourcesMode.value) return
        searchJob?.cancel()
        popularJob?.cancel()
        _allSourcesMode.value = true
        _searchQuery.value = ""
        _uiState.value = SearchUiState.Initial
        _popularNovels.value = emptyList()
    }

    init {
        loadPopularNovels()
    }

    fun selectSource(source: Source) {
        // Track recency even for a re-select — "used" is what matters.
        recordRecent(source.id)

        // Re-selecting the same source is a no-op — unless we're leaving
        // all-sources mode, where it's a real mode switch.
        if (!_allSourcesMode.value && _selectedSource.value.id == source.id) return
        _allSourcesMode.value = false

        // Cancel any ongoing requests
        searchJob?.cancel()
        popularJob?.cancel()

        _selectedSource.value = source
        _searchQuery.value = ""
        _uiState.value = SearchUiState.Initial
        _popularNovels.value = emptyList()
        loadPopularNovels()
    }

    private fun loadPopularNovels() {
        // Cancel previous popular novels request
        popularJob?.cancel()

        popularJob = viewModelScope.launch {
            // Capture the source at the start of the request
            val requestSource = _selectedSource.value

            try {
                val novels = repository.getPopularNovels(source = requestSource)

                // Only update if source hasn't changed during the request
                if (_selectedSource.value.id == requestSource.id) {
                    _popularNovels.value = novels
                }
            } catch (e: Exception) {
                Logger.e("Error", e)
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query

        searchJob?.cancel()

        if (query.isBlank()) {
            _uiState.value = SearchUiState.Initial
            return
        }

        searchJob = viewModelScope.launch {
            delay(500)
            performSearch(query)
        }
    }

    fun searchNow() {
        val query = _searchQuery.value
        if (query.isBlank()) return

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            performSearch(query)
        }
    }

    private suspend fun performSearch(query: String) {
        if (_allSourcesMode.value) {
            performGlobalSearch(query)
            return
        }

        _uiState.value = SearchUiState.Loading

        // Capture the source at the start of the request
        val requestSource = _selectedSource.value

        try {
            val results = repository.searchNovels(query, source = requestSource)

            // Only update if source hasn't changed during the request
            if (_selectedSource.value.id == requestSource.id) {
                _uiState.value = if (results.isEmpty()) {
                    SearchUiState.Success(emptyList())
                } else {
                    SearchUiState.Success(results)
                }
            }
        } catch (e: Exception) {
            // Only update if source hasn't changed
            if (_selectedSource.value.id == requestSource.id) {
                _uiState.value = SearchUiState.Error(
                    e.message ?: "Search failed. Please check your internet connection."
                )
            }
        }
    }

    /**
     * Fan-out search across every source concurrently (Phase 7).
     * Per-source isolation: one dead site can't kill the whole search —
     * failures and timeouts (12s) just drop that source's group.
     */
    private suspend fun performGlobalSearch(query: String) {
        _uiState.value = SearchUiState.Loading

        try {
            val sources = availableSources
            val results = supervisorScope {
                sources.map { source ->
                    async {
                        try {
                            withTimeoutOrNull(12_000) {
                                repository.searchNovels(query, source = source)
                            } ?: emptyList()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Logger.e("Global search failed for ${source.name}", e)
                            emptyList()
                        }
                    }
                }.awaitAll()
            }

            // Ignore stale results if the user left all-sources mode
            if (!_allSourcesMode.value) return

            val groups = sources.zip(results)
                .filter { (_, novels) -> novels.isNotEmpty() }
                .map { (source, novels) ->
                    SourceResults(
                        sourceName = source.name,
                        sourceId = source.id,
                        novels = novels
                    )
                }
            _uiState.value = SearchUiState.SuccessGrouped(groups)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (_allSourcesMode.value) {
                _uiState.value = SearchUiState.Error(
                    e.message ?: "Search failed. Please check your internet connection."
                )
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "source_picker_prefs"
        private const val PREF_PINNED = "pinned_source_ids"
        private const val PREF_RECENTS = "recent_source_ids"
        private const val MAX_RECENTS = 5

        fun provideFactory(
            repository: NovelRepository,
            appContext: android.content.Context
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SearchViewModel(repository, appContext.applicationContext) as T
                }
            }
        }
    }
}