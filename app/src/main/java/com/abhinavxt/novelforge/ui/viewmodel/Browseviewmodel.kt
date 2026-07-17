package com.abhinavxt.novelforge.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.abhinavxt.novelforge.data.model.NovelPreview
import com.abhinavxt.novelforge.data.source.BrowseFilters
import com.abhinavxt.novelforge.data.source.BrowseSource
import com.abhinavxt.novelforge.data.source.Source
import com.abhinavxt.novelforge.data.source.SourceManager
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Catalog browsing across sources. Every source is browsable: BrowseSource
 * implementors get the full filter surface (category / sort / genre-tag);
 * plain sources fall back to getPopular(page) with no filters.
 *
 * Paging is accumulate-and-append with 1-based pages; an empty page marks the
 * catalog exhausted.
 */
class BrowseViewModel(
    appContext: Context
) : ViewModel() {

    private val prefs = appContext.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val availableSources: List<Source> = SourceManager.getAllSources()

    // ── Source selection (last browsed source persisted) ─────────
    private val _selectedSource = MutableStateFlow(restoreLastSource())
    val selectedSource: StateFlow<Source> = _selectedSource.asStateFlow()

    // Pinned/recents share the search picker's keys so pins stay in sync.
    private val _pinnedSourceIds = MutableStateFlow(
        prefs.getStringSet(PREF_PINNED, emptySet())!!.toSet()
    )
    val pinnedSourceIds: StateFlow<Set<String>> = _pinnedSourceIds.asStateFlow()

    private val _recentSourceIds = MutableStateFlow(
        prefs.getString(PREF_RECENTS, "")!!.split(',').filter { it.isNotBlank() }
    )
    val recentSourceIds: StateFlow<List<String>> = _recentSourceIds.asStateFlow()

    // ── Filter state ─────────────────────────────────────────────
    /** Filters offered by the current source (empty for plain sources). */
    private val _filters = MutableStateFlow(filtersOf(_selectedSource.value))
    val filters: StateFlow<BrowseFilters> = _filters.asStateFlow()

    /** Selected filter values; null = provider default. */
    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory.asStateFlow()

    private val _selectedOrderBy = MutableStateFlow<String?>(null)
    val selectedOrderBy: StateFlow<String?> = _selectedOrderBy.asStateFlow()

    private val _selectedTag = MutableStateFlow<String?>(null)
    val selectedTag: StateFlow<String?> = _selectedTag.asStateFlow()

    // ── Paging state ─────────────────────────────────────────────
    data class CatalogState(
        val items: List<NovelPreview> = emptyList(),
        val isLoadingInitial: Boolean = false,
        val isLoadingMore: Boolean = false,
        val endReached: Boolean = false,
        /** Non-null only when the FIRST page failed (nothing to show). */
        val error: String? = null,
    )

    private val _catalog = MutableStateFlow(CatalogState())
    val catalog: StateFlow<CatalogState> = _catalog.asStateFlow()

    private var page = 0
    private var loadJob: Job? = null

    init {
        reload()
    }

    // ── Actions ──────────────────────────────────────────────────

    fun selectSource(source: Source) {
        if (source.id == _selectedSource.value.id) return
        _selectedSource.value = source
        prefs.edit().putString(PREF_LAST_SOURCE, source.id).apply()
        recordRecent(source.id)
        // Filter values are provider-specific — reset on source change.
        _filters.value = filtersOf(source)
        _selectedCategory.value = null
        _selectedOrderBy.value = null
        _selectedTag.value = null
        reload()
    }

    fun togglePin(sourceId: String) {
        val updated = _pinnedSourceIds.value.toMutableSet().apply {
            if (!add(sourceId)) remove(sourceId)
        }
        _pinnedSourceIds.value = updated
        prefs.edit().putStringSet(PREF_PINNED, updated).apply()
    }

    fun selectCategory(value: String?) = applyFilter { _selectedCategory.value = value }
    fun selectOrderBy(value: String?) = applyFilter { _selectedOrderBy.value = value }
    fun selectTag(value: String?) = applyFilter { _selectedTag.value = value }

    private inline fun applyFilter(update: () -> Unit) {
        update()
        reload()
    }

    fun reload() {
        loadJob?.cancel()
        page = 0
        _catalog.value = CatalogState(isLoadingInitial = true)
        loadNextPage()
    }

    /** Called by the grid when the user nears the bottom. */
    fun loadMore() {
        val state = _catalog.value
        if (state.isLoadingInitial || state.isLoadingMore || state.endReached) return
        _catalog.value = state.copy(isLoadingMore = true)
        loadNextPage()
    }

    private fun loadNextPage() {
        val source = _selectedSource.value
        val nextPage = page + 1
        loadJob = viewModelScope.launch {
            try {
                val newItems = fetchPage(source, nextPage)
                page = nextPage
                val existing = _catalog.value.items
                val existingIds = existing.mapTo(HashSet()) { it.id }
                // Sites sometimes repeat entries across pages; dedupe by id.
                val appended = newItems.filter { it.id !in existingIds }
                _catalog.value = CatalogState(
                    items = existing + appended,
                    // Empty page OR fully-duplicate page = exhausted. Without
                    // the duplicate check, sites that clamp out-of-range pages
                    // to their last page would loop forever.
                    endReached = newItems.isEmpty() || appended.isEmpty(),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("BrowseVM", "[${source.id}] page $nextPage failed: ${e.message}")
                val existing = _catalog.value.items
                _catalog.value = CatalogState(
                    items = existing,
                    endReached = existing.isNotEmpty(), // stop paging after a mid-scroll failure
                    error = if (existing.isEmpty())
                        (e.message ?: "Failed to load catalog") else null,
                )
            }
        }
    }

    private suspend fun fetchPage(source: Source, page: Int): List<NovelPreview> {
        return if (source is BrowseSource && source.canBrowse) {
            source.browse(
                page = page,
                category = _selectedCategory.value,
                orderBy = _selectedOrderBy.value,
                tag = _selectedTag.value,
            )
        } else {
            source.getPopular(page)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    private fun filtersOf(source: Source): BrowseFilters =
        if (source is BrowseSource && source.canBrowse) source.filters else BrowseFilters()

    private fun restoreLastSource(): Source {
        val lastId = prefs.getString(PREF_LAST_SOURCE, null)
        return SourceManager.getAllSources().let { sources ->
            sources.find { it.id == lastId } ?: sources.first()
        }
    }

    private fun recordRecent(sourceId: String) {
        val updated = (listOf(sourceId) + _recentSourceIds.value.filter { it != sourceId })
            .take(MAX_RECENTS)
        _recentSourceIds.value = updated
        prefs.edit().putString(PREF_RECENTS, updated.joinToString(",")).apply()
    }

    companion object {
        // Same file as SearchViewModel's picker prefs — pins/recents shared.
        private const val PREFS_NAME = "source_picker_prefs"
        private const val PREF_PINNED = "pinned_source_ids"
        private const val PREF_RECENTS = "recent_source_ids"
        private const val PREF_LAST_SOURCE = "browse_last_source"
        private const val MAX_RECENTS = 5

        fun provideFactory(appContext: Context): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return BrowseViewModel(appContext.applicationContext) as T
                }
            }
        }
    }
}