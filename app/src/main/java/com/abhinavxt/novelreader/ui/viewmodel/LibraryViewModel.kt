package com.abhinavxt.novelreader.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.abhinavxt.novelreader.data.NovelRepository
import com.abhinavxt.novelreader.data.database.CategoryEntity
import com.abhinavxt.novelreader.data.database.ReadingProgressEntity
import com.abhinavxt.novelreader.data.epub.EpubImporter
import com.abhinavxt.novelreader.data.model.Novel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ImportState {
    object Idle : ImportState
    object Importing : ImportState
    data class Success(val title: String, val chapterCount: Int) : ImportState
    data class Error(val message: String) : ImportState
}

enum class LibraryFilter {
    ALL,
    DOWNLOADED,
    READING
}

enum class LibrarySort(val displayName: String) {
    LAST_READ("Last Read"),
    RECENTLY_ADDED("Recently Added"),
    TITLE("Title"),
    CHAPTER_COUNT("Chapters")
}

class LibraryViewModel(
    private val repository: NovelRepository,
    private val epubImporter: EpubImporter? = null,
    private val appContext: Context? = null
) : ViewModel() {

    private val _allNovels = MutableStateFlow<List<Novel>>(emptyList())

    private val _libraryNovels = MutableStateFlow<List<Novel>>(emptyList())
    val libraryNovels: StateFlow<List<Novel>> = _libraryNovels.asStateFlow()

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    private val _currentFilter = MutableStateFlow(LibraryFilter.ALL)
    val currentFilter: StateFlow<LibraryFilter> = _currentFilter.asStateFlow()

    private val _currentSort = MutableStateFlow(LibrarySort.LAST_READ)
    val currentSort: StateFlow<LibrarySort> = _currentSort.asStateFlow()

    // ── In-library search ────────────────────────────────────────
    // Filters the ALREADY-LOADED library list in memory — no DB or
    // network involved, so no debounce needed; every keystroke just
    // re-runs applyFilterAndSort() over an in-memory list.
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // ── Categories (Phase 6) ─────────────────────────────────────
    private val _categories = MutableStateFlow<List<CategoryEntity>>(emptyList())
    val categories: StateFlow<List<CategoryEntity>> = _categories.asStateFlow()

    // novelId → set of category ids, folded from the cross-ref table
    private val _novelCategoryMap = MutableStateFlow<Map<String, Set<Long>>>(emptyMap())
    val novelCategoryMap: StateFlow<Map<String, Set<Long>>> = _novelCategoryMap.asStateFlow()

    // null = "All" (no category filter)
    private val _selectedCategoryId = MutableStateFlow<Long?>(null)
    val selectedCategoryId: StateFlow<Long?> = _selectedCategoryId.asStateFlow()

    // Track which novels have downloads
    private val _novelsWithDownloads = MutableStateFlow<Set<String>>(emptySet())

    // Track which novels are being read + their progress
    private val _novelsBeingRead = MutableStateFlow<Set<String>>(emptySet())
    private val _readingProgressMap = MutableStateFlow<Map<String, ReadingProgressEntity>>(emptyMap())

    // Track novels with new chapters (set by UpdateCheckerWorker via lastUpdatedAt)
    private val _novelsWithUpdates = MutableStateFlow<Set<String>>(emptySet())
    val novelsWithUpdates: StateFlow<Set<String>> = _novelsWithUpdates.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        loadLibrary()
        loadNovelsWithDownloads()
        loadNovelsBeingRead()
        loadUpdateBadges()
        loadCategories()
    }

    private fun loadCategories() {
        viewModelScope.launch {
            repository.getCategories().collect { cats ->
                _categories.value = cats
                // If the selected category was deleted, fall back to All
                if (_selectedCategoryId.value != null &&
                    cats.none { it.id == _selectedCategoryId.value }
                ) {
                    _selectedCategoryId.value = null
                }
                applyFilterAndSort()
            }
        }
        viewModelScope.launch {
            repository.getCategoryAssignments().collect { refs ->
                _novelCategoryMap.value = refs
                    .groupBy({ it.novelId }, { it.categoryId })
                    .mapValues { (_, ids) -> ids.toSet() }
                applyFilterAndSort()
            }
        }
    }

    /**
     * Pull-to-refresh: reload library, downloads, reading progress, and update badges.
     * Brief delay ensures the refresh indicator is visible.
     */
    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            loadNovelsWithDownloads()
            loadNovelsBeingRead()
            loadUpdateBadges()
            kotlinx.coroutines.delay(400) // brief delay so indicator is visible
            _isRefreshing.value = false
        }
    }

    private fun loadUpdateBadges() {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences(
            com.abhinavxt.novelreader.worker.UpdateCheckerWorker.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val ids = prefs.getStringSet(
            com.abhinavxt.novelreader.worker.UpdateCheckerWorker.PREF_UPDATED_NOVEL_IDS,
            emptySet()
        ) ?: emptySet()
        _novelsWithUpdates.value = ids
    }

    private fun loadLibrary() {
        viewModelScope.launch {
            repository.getLibraryNovels().collect { novels ->
                _allNovels.value = novels
                applyFilterAndSort()
            }
        }
    }

    private fun loadNovelsWithDownloads() {
        viewModelScope.launch {
            val downloadInfos = repository.getNovelsWithDownloads()
            _novelsWithDownloads.value = downloadInfos.map { it.novelId }.toSet()
            applyFilterAndSort()
        }
    }

    private fun loadNovelsBeingRead() {
        viewModelScope.launch {
            repository.getAllReadingProgress().collect { progressList ->
                _novelsBeingRead.value = progressList.map { it.novelId }.toSet()
                _readingProgressMap.value = progressList.associateBy { it.novelId }
                applyFilterAndSort()
            }
        }
    }

    private fun applyFilterAndSort() {
        val novels = _allNovels.value
        val filter = _currentFilter.value
        val sort = _currentSort.value
        val withDownloads = _novelsWithDownloads.value
        val beingRead = _novelsBeingRead.value
        val progressMap = _readingProgressMap.value
        val query = _searchQuery.value.trim()

        // Search — title or author, case-insensitive
        val searched = if (query.isEmpty()) {
            novels
        } else {
            novels.filter {
                it.title.contains(query, ignoreCase = true) ||
                        it.author.contains(query, ignoreCase = true)
            }
        }

        // Category filter (composes with search and status filters)
        val categoryId = _selectedCategoryId.value
        val categorized = if (categoryId == null) {
            searched
        } else {
            val map = _novelCategoryMap.value
            searched.filter { categoryId in (map[it.id] ?: emptySet()) }
        }

        // Filter
        val filtered = when (filter) {
            LibraryFilter.ALL -> categorized
            LibraryFilter.DOWNLOADED -> categorized.filter { it.id in withDownloads }
            LibraryFilter.READING -> categorized.filter { it.id in beingRead }
        }

        // Sort
        _libraryNovels.value = when (sort) {
            LibrarySort.LAST_READ -> {
                filtered.sortedByDescending { novel ->
                    progressMap[novel.id]?.lastReadAt ?: 0L
                }
            }
            LibrarySort.RECENTLY_ADDED -> filtered // Already sorted by lastUpdatedAt DESC from DAO
            LibrarySort.TITLE -> filtered.sortedBy { it.title.lowercase() }
            LibrarySort.CHAPTER_COUNT -> filtered.sortedByDescending { it.chapters.size }
        }
    }

    fun setFilter(filter: LibraryFilter) {
        _currentFilter.value = filter
        applyFilterAndSort()
    }

    fun setSort(sort: LibrarySort) {
        _currentSort.value = sort
        applyFilterAndSort()
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        applyFilterAndSort()
    }

    // ── Category actions (Phase 6) ───────────────────────────────

    fun setCategoryFilter(categoryId: Long?) {
        _selectedCategoryId.value = categoryId
        applyFilterAndSort()
    }

    fun createCategory(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        // Silently ignore case-insensitive duplicates
        if (_categories.value.any { it.name.equals(trimmed, ignoreCase = true) }) return
        viewModelScope.launch {
            repository.createCategory(trimmed)
        }
    }

    fun deleteCategory(categoryId: Long) {
        viewModelScope.launch {
            repository.deleteCategory(categoryId)
        }
    }

    fun setNovelCategories(novelId: String, categoryIds: Set<Long>) {
        viewModelScope.launch {
            repository.setNovelCategories(novelId, categoryIds)
        }
    }

    fun refreshDownloads() {
        loadNovelsWithDownloads()
    }

    /**
     * Mark a novel as "seen" so the update badge goes away.
     */
    fun clearUpdateBadge(novelId: String) {
        _novelsWithUpdates.value = _novelsWithUpdates.value - novelId
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences(
            com.abhinavxt.novelreader.worker.UpdateCheckerWorker.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val ids = prefs.getStringSet(
            com.abhinavxt.novelreader.worker.UpdateCheckerWorker.PREF_UPDATED_NOVEL_IDS,
            emptySet()
        )?.toMutableSet() ?: mutableSetOf()
        ids.remove(novelId)
        prefs.edit().putStringSet(
            com.abhinavxt.novelreader.worker.UpdateCheckerWorker.PREF_UPDATED_NOVEL_IDS, ids
        ).apply()
    }

    fun importEpub(uri: Uri) {
        if (epubImporter == null) return

        viewModelScope.launch {
            _importState.value = ImportState.Importing

            when (val result = epubImporter.importEpub(uri)) {
                is EpubImporter.ImportResult.Success -> {
                    _importState.value = ImportState.Success(
                        title = result.title,
                        chapterCount = result.chapterCount
                    )
                }
                is EpubImporter.ImportResult.Error -> {
                    _importState.value = ImportState.Error(result.message)
                }
            }
        }
    }

    fun clearImportState() {
        _importState.value = ImportState.Idle
    }

    fun removeFromLibrary(novelId: String) {
        viewModelScope.launch {
            if (novelId.startsWith("local_") && epubImporter != null) {
                epubImporter.deleteLocalNovel(novelId)
            }
            repository.removeFromLibrary(novelId)
            loadNovelsWithDownloads()
        }
    }

    companion object {
        fun provideFactory(repository: NovelRepository): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return LibraryViewModel(repository, null) as T
                }
            }
        }

        fun provideFactory(
            repository: NovelRepository,
            context: Context
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return LibraryViewModel(
                        repository = repository,
                        epubImporter = EpubImporter(context),
                        appContext = context
                    ) as T
                }
            }
        }
    }
}