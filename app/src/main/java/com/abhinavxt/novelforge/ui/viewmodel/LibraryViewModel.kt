package com.abhinavxt.novelforge.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.abhinavxt.novelforge.data.NovelRepository
import com.abhinavxt.novelforge.data.database.CategoryEntity
import com.abhinavxt.novelforge.data.database.ReadingProgressEntity
import com.abhinavxt.novelforge.data.epub.BookFileAccess
import com.abhinavxt.novelforge.data.epub.EpubImporter
import com.abhinavxt.novelforge.data.epub.LocalBookArchive
import com.abhinavxt.novelforge.NovelReaderApplication
import com.abhinavxt.novelforge.ui.components.ArchiveProblem
import com.abhinavxt.novelforge.ui.components.describe
import com.abhinavxt.novelforge.ui.components.toProblem
import com.abhinavxt.novelforge.data.epub.LibraryFolder
import com.abhinavxt.novelforge.data.model.Novel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ImportState {
    object Idle : ImportState
    object Importing : ImportState
    data class Success(val title: String, val chapterCount: Int) : ImportState
    data class Error(val message: String) : ImportState

    /**
     * Walking the library folder, before the file count is known. Distinct
     * from [ImportingBatch] because enumeration has no denominator to show
     * progress against, and on a deep folder it is the slow part.
     */
    object Scanning : ImportState

    /** Progress while a multi-file selection is being imported one at a time. */
    data class ImportingBatch(val completed: Int, val total: Int) : ImportState

    /**
     * A single file was skipped because the library already holds a book
     * with the same bytes. Its own state rather than an [Error] so the UI
     * can say what happened plainly instead of dressing a correct outcome
     * up as a failure.
     */
    data class AlreadyInLibrary(val existingTitle: String) : ImportState

    /**
     * Summary of a multi-file import. [firstError] carries the message from
     * the first failure so a batch that goes wrong for one reason still
     * explains itself, rather than reporting a bare count.
     *
     * [duplicates] is counted apart from [failed] because a skipped
     * duplicate is the import working, not the import breaking.
     */
    data class BatchDone(
        val imported: Int,
        val failed: Int,
        val firstError: String?,
        val duplicates: Int = 0
    ) : ImportState
}

/** Something an archive action needs to tell the user, shown once. */
sealed interface ArchiveEvent {
    data class Message(val text: String) : ArchiveEvent

    /** A restore needs the user to locate the file or accept a changed one. */
    data class NeedsFile(val novel: Novel, val problem: ArchiveProblem) : ArchiveEvent
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

    // ── Archive ──────────────────────────────────────────────────
    private val bookArchive: LocalBookArchive? =
        (appContext as? NovelReaderApplication)?.bookArchive

    private val _archivedIds = MutableStateFlow<Set<String>>(emptySet())
    val archivedIds: StateFlow<Set<String>> = _archivedIds.asStateFlow()

    // A channel, not state: each event is shown exactly once, and a message
    // arriving while another is on screen waits its turn instead of
    // replacing it.
    private val archiveEvents = Channel<ArchiveEvent>(Channel.BUFFERED)
    val archiveEvent: Flow<ArchiveEvent> = archiveEvents.receiveAsFlow()

    /** Ids of books with an archive or restore running, to stop double taps. */
    private val _archiveBusy = MutableStateFlow<Set<String>>(emptySet())
    val archiveBusy: StateFlow<Set<String>> = _archiveBusy.asStateFlow()

    // The file the user last picked for a book, so "Restore anyway" applies
    // to it rather than to the recorded file that went missing.
    private var pickedRestoreUri: Uri? = null

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        loadLibrary()
        loadNovelsWithDownloads()
        loadNovelsBeingRead()
        loadUpdateBadges()
        loadCategories()
        viewModelScope.launch {
            repository.getArchivedNovelIds().collect { _archivedIds.value = it }
        }
    }

    // ── Archive actions ──────────────────────────────────────────

    fun archive(novel: Novel) {
        val archive = bookArchive ?: return
        runBusy(novel.id) {
            val result = archive.archive(novel.id)
            archiveEvents.send(ArchiveEvent.Message(result.describe(novel.title)))
            loadNovelsWithDownloads()
        }
    }

    /**
     * Restore an archived book. With [fileUri] the user has just pointed at
     * its file; with [acceptChanges] they agreed to a file that differs.
     */
    fun restore(novel: Novel, fileUri: Uri? = null, acceptChanges: Boolean = false) {
        runBusy(novel.id) { restoreNow(novel, fileUri, acceptChanges, announce = true) }
    }

    fun restoreAnyway(novel: Novel) = restore(novel, pickedRestoreUri, acceptChanges = true)

    /**
     * Restore if archived, for actions that need the text — exporting, for
     * one. Returns true when the text is there. On failure the problem is
     * raised as an event, so the user can fix it and try again.
     */
    suspend fun ensureRestored(novel: Novel): Boolean {
        if (novel.id !in _archivedIds.value) return true
        return restoreNow(novel, null, false, announce = false)
    }

    private suspend fun restoreNow(
        novel: Novel,
        fileUri: Uri?,
        acceptChanges: Boolean,
        announce: Boolean
    ): Boolean {
        val archive = bookArchive ?: return false
        val result = if (fileUri != null) {
            archive.restoreFromPickedFile(novel.id, fileUri, acceptChanges)
        } else {
            archive.restore(novel.id, acceptChanges = acceptChanges)
        }
        return when (result) {
            is LocalBookArchive.RestoreResult.Restored -> {
                pickedRestoreUri = null
                if (announce) {
                    archiveEvents.send(ArchiveEvent.Message("Restored \"${novel.title}\""))
                }
                loadNovelsWithDownloads()
                true
            }
            LocalBookArchive.RestoreResult.NothingToRestore -> true
            else -> {
                pickedRestoreUri = fileUri
                archiveEvents.send(
                    result.toProblem()
                        ?.let { ArchiveEvent.NeedsFile(novel, it) }
                        ?: ArchiveEvent.Message("Couldn't restore \"${novel.title}\"")
                )
                false
            }
        }
    }

    private fun runBusy(novelId: String, block: suspend () -> Unit) {
        if (novelId in _archiveBusy.value) return
        _archiveBusy.value = _archiveBusy.value + novelId
        viewModelScope.launch {
            try {
                block()
            } finally {
                _archiveBusy.value = _archiveBusy.value - novelId
            }
        }
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
            com.abhinavxt.novelforge.worker.UpdateCheckerWorker.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val ids = prefs.getStringSet(
            com.abhinavxt.novelforge.worker.UpdateCheckerWorker.PREF_UPDATED_NOVEL_IDS,
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
            com.abhinavxt.novelforge.worker.UpdateCheckerWorker.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val ids = prefs.getStringSet(
            com.abhinavxt.novelforge.worker.UpdateCheckerWorker.PREF_UPDATED_NOVEL_IDS,
            emptySet()
        )?.toMutableSet() ?: mutableSetOf()
        ids.remove(novelId)
        prefs.edit().putStringSet(
            com.abhinavxt.novelforge.worker.UpdateCheckerWorker.PREF_UPDATED_NOVEL_IDS, ids
        ).apply()
    }

    /**
     * Import any supported document (.epub, .txt, .md). The importer picks the
     * parser; all formats converge on the same persistence path.
     */
    fun importDocument(uri: Uri) {
        if (epubImporter == null) return

        viewModelScope.launch {
            _importState.value = ImportState.Importing

            when (val result = importPicked(epubImporter, uri)) {
                is EpubImporter.ImportResult.Success -> {
                    _importState.value = ImportState.Success(
                        title = result.title,
                        chapterCount = result.chapterCount
                    )
                }
                is EpubImporter.ImportResult.Error -> {
                    _importState.value = ImportState.Error(result.message)
                }
                is EpubImporter.ImportResult.Duplicate -> {
                    _importState.value =
                        ImportState.AlreadyInLibrary(result.existingTitle)
                }
            }
        }
    }

    /**
     * Import one hand-picked file, keeping lasting access to it when Android
     * allows so the book can later be archived and re-read from the file.
     *
     * The grant is taken before importing because the import records the URI
     * and a recorded URI without a grant is worse than none. It is handed back
     * afterwards if no book ended up using it — a failed import, or a
     * duplicate of a book that already has its own file.
     */
    private suspend fun importPicked(
        importer: EpubImporter,
        uri: Uri
    ): EpubImporter.ImportResult {
        val context = appContext
        val held = context != null && BookFileAccess.hold(context, uri)
        val result = importer.importDocument(uri, rememberSource = held)
        if (held && !repository.isSourceUriInUse(uri.toString())) {
            BookFileAccess.release(context!!, uri)
        }
        return result
    }

    /**
     * Import a whole selection at once. Files are handled sequentially rather
     * than concurrently: each import parses an entire book into memory and
     * writes its chapters, so running several at once trades a modest speed
     * gain for a real risk of OutOfMemoryError on a large selection.
     *
     * One bad file never aborts the run — failures are counted and reported
     * at the end, so importing 200 books does not stop on book 3.
     */
    fun importDocuments(uris: List<Uri>) {
        if (epubImporter == null || uris.isEmpty()) return
        if (uris.size == 1) {
            importDocument(uris.first())
            return
        }

        viewModelScope.launch {
            var imported = 0
            var failed = 0
            var duplicates = 0
            var firstError: String? = null

            uris.forEachIndexed { index, uri ->
                _importState.value = ImportState.ImportingBatch(index, uris.size)
                when (val result = importPicked(epubImporter, uri)) {
                    is EpubImporter.ImportResult.Success -> imported++
                    is EpubImporter.ImportResult.Duplicate -> duplicates++
                    is EpubImporter.ImportResult.Error -> {
                        failed++
                        if (firstError == null) firstError = result.message
                    }
                }
            }

            loadNovelsWithDownloads()
            _importState.value =
                ImportState.BatchDone(imported, failed, firstError, duplicates)
        }
    }

    /**
     * Import everything new in the user's library folder.
     *
     * The scan is resumable rather than durable: it runs in [viewModelScope],
     * so leaving the Library screen mid-run stops it. That is acceptable
     * precisely because each imported book records its own file URI, so
     * starting the scan again picks up exactly where it stopped instead of
     * redoing the work. A WorkManager job would survive navigation, but it
     * would also need progress plumbing and a foreground notification to
     * earn its keep — worth revisiting if scans routinely run long.
     */
    fun scanLibraryFolder() {
        val importer = epubImporter ?: return
        val context = appContext ?: return

        viewModelScope.launch {
            _importState.value = ImportState.Scanning

            val listing = LibraryFolder.listNewBooks(
                context = context,
                alreadyImported = repository.getImportedSourceUris()
            )

            when (listing) {
                is LibraryFolder.ScanListing.Error -> {
                    _importState.value = ImportState.Error(listing.message)
                    return@launch
                }

                is LibraryFolder.ScanListing.Found -> {
                    if (listing.newFiles.isEmpty()) {
                        LibraryFolder.setLastScanTime(context, System.currentTimeMillis())
                        _importState.value = ImportState.BatchDone(
                            imported = 0,
                            failed = 0,
                            firstError = null
                        )
                        return@launch
                    }

                    var imported = 0
                    var failed = 0
                    var duplicates = 0
                    var firstError: String? = null
                    // Read once per scan, so toggling it mid-run does not
                    // leave a library half one way and half the other.
                    val archived = LibraryFolder.importsArchived(context)

                    listing.newFiles.forEachIndexed { index, file ->
                        _importState.value =
                            ImportState.ImportingBatch(index, listing.newFiles.size)

                        // rememberSource so the next scan skips this file.
                        val result = importer.importDocument(
                            uri = file.uri,
                            rememberSource = true,
                            archived = archived
                        )
                        when (result) {
                            is EpubImporter.ImportResult.Success -> imported++
                            // The URI was new but the bytes were not — the
                            // same book sitting in two folders, or one the
                            // user picked by hand before the folder existed.
                            //
                            // Recorded here because a rejected file has
                            // nowhere else to leave a trace: the novel row
                            // it collided with is already spoken for. Without
                            // this the scan would re-open and re-report the
                            // same file on every run.
                            is EpubImporter.ImportResult.Duplicate -> {
                                duplicates++
                                LibraryFolder.markDuplicate(
                                    context, file.uri.toString()
                                )
                            }
                            is EpubImporter.ImportResult.Error -> {
                                failed++
                                if (firstError == null) {
                                    firstError = "${file.name}: ${result.message}"
                                }
                            }
                        }
                    }

                    LibraryFolder.setLastScanTime(context, System.currentTimeMillis())
                    loadNovelsWithDownloads()
                    _importState.value =
                        ImportState.BatchDone(imported, failed, firstError, duplicates)
                }
            }
        }
    }

    /**
     * Rescan, deliberately including books the user removed earlier.
     *
     * Removing a scanned book records its file as unwanted, which is right
     * by default — otherwise the next scan drags it straight back. But that
     * left the only way back through the Settings screen, which is a long
     * walk for a change of mind about a book you are looking at.
     */
    fun rescanIncludingRemoved() {
        val context = appContext ?: return
        LibraryFolder.clearIgnoredUris(context)
        scanLibraryFolder()
    }

    fun clearImportState() {
        _importState.value = ImportState.Idle
    }

    fun removeFromLibrary(novelId: String) {
        viewModelScope.launch {
            // A book that came from the library folder has to be remembered
            // as unwanted before its row disappears, because the row IS the
            // record that the file was seen. Without this the next scan
            // would cheerfully import it again.
            val context = appContext
            var releaseAfterRemoval: String? = null
            if (context != null) {
                repository.getSourceUri(novelId)?.let { uri ->
                    if (LibraryFolder.isInFolder(uri)) {
                        LibraryFolder.ignoreUri(context, uri)
                    } else {
                        // A hand-picked file: its grant is released below,
                        // once the row is gone, to free a slot in the budget.
                        releaseAfterRemoval = uri
                    }
                }
            }

            if (novelId.startsWith("local_") && epubImporter != null) {
                epubImporter.deleteLocalNovel(novelId)
            }
            repository.removeFromLibrary(novelId)
            releaseAfterRemoval?.let { uri ->
                // Another book may hold the same file (a re-import that was
                // linked as a duplicate); its grant must survive.
                if (!repository.isSourceUriInUse(uri)) {
                    BookFileAccess.release(context!!, Uri.parse(uri))
                }
            }
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
            // Unwrap to applicationContext HERE rather than trusting the call
            // site. LibraryScreen passes LocalContext.current, which is the
            // Activity -- and this ViewModel survives configuration changes,
            // so holding it would leak a destroyed Activity and its whole
            // view tree on every rotation.
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return LibraryViewModel(
                        repository = repository,
                        epubImporter = EpubImporter(appContext),
                        appContext = appContext
                    ) as T
                }
            }
        }
    }
}