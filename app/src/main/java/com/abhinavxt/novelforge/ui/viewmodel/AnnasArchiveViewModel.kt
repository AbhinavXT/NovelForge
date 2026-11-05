package com.abhinavxt.novelforge.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.abhinavxt.novelforge.data.annas.AnnasArchiveClient
import com.abhinavxt.novelforge.data.annas.AnnasArchiveException
import com.abhinavxt.novelforge.data.annas.AnnasArchivePrefs
import com.abhinavxt.novelforge.data.annas.AnnasBook
import com.abhinavxt.novelforge.data.annas.AnnasFilters
import com.abhinavxt.novelforge.data.annas.AnnasVerificationRequired
import com.abhinavxt.novelforge.util.Logger
import com.abhinavxt.novelforge.worker.AnnasDownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Search Anna's Archive, and queue downloads that import in the background.
 *
 * Downloads run in [AnnasDownloadWorker], so they carry on when the user
 * leaves the screen; this ViewModel only reads their state back from
 * WorkManager, keyed by MD5, to badge the matching results.
 */
class AnnasArchiveViewModel(
    private val appContext: Context,
    private val prefs: AnnasArchivePrefs,
    private val client: AnnasArchiveClient
) : ViewModel() {

    data class SearchState(
        val query: String = "",
        val filters: AnnasFilters = AnnasFilters(),
        val books: List<AnnasBook> = emptyList(),
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        val error: String? = null,
        val page: Int = 0,
        val endReached: Boolean = false,
        /** True once any search has run, to tell "no results" from "not yet". */
        val searched: Boolean = false,
        /** The site wants the person to pass a check on this page first. */
        val verification: Verification? = null
    )

    data class Verification(val url: String, val page: Int)

    sealed interface DownloadState {
        data object Queued : DownloadState
        data class Running(val bytes: Long, val total: Long?) : DownloadState
        data class Imported(val novelId: String, val title: String) : DownloadState
        data class AlreadyInLibrary(val title: String) : DownloadState
        data class Failed(val message: String) : DownloadState
    }

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    /** MD5 → the latest download of that book. Books never downloaded are absent. */
    val downloads: StateFlow<Map<String, DownloadState>> =
        WorkManager.getInstance(appContext)
            .getWorkInfosByTagFlow(AnnasDownloadWorker.TAG_DOWNLOAD)
            .map(::toDownloadStates)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private var searchJob: Job? = null

    val mirror: String get() = prefs.baseUrl
    val secretKey: String get() = prefs.secretKey

    fun onQueryChange(query: String) = _search.update { it.copy(query = query) }

    /** Changing a filter re-runs the current search, if there is one. */
    fun onFiltersChange(filters: AnnasFilters) {
        _search.update { it.copy(filters = filters) }
        if (_search.value.searched) submitSearch()
    }

    fun submitSearch() {
        val query = _search.value.query.trim()
        if (query.isEmpty()) return
        searchJob?.cancel()
        _search.update {
            SearchState(query = it.query, filters = it.filters, loading = true, searched = true)
        }
        searchJob = viewModelScope.launch { loadPage(query, 1) }
    }

    fun loadMore() {
        val s = _search.value
        if (s.loading || s.loadingMore || s.endReached || s.page == 0 || s.verification != null) return
        _search.update { it.copy(loadingMore = true) }
        searchJob = viewModelScope.launch { loadPage(s.query.trim(), s.page + 1) }
    }

    private suspend fun loadPage(query: String, page: Int) {
        try {
            applyPage(client.search(query, page, _search.value.filters), page)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: AnnasVerificationRequired) {
            _search.update { it.copy(verification = Verification(e.url, page)) }
        } catch (e: Exception) {
            Logger.e(TAG, "Search failed", e)
            _search.update {
                it.copy(loading = false, loadingMore = false, error = messageFor(e))
            }
        }
    }

    /** The person cleared the check; [html] is the page it let them through to. */
    fun onVerified(html: String) {
        val v = _search.value.verification ?: return
        _search.update { it.copy(verification = null) }
        searchJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                applyPage(client.parseResults(html, v.url), v.page)
            } catch (e: Exception) {
                Logger.e(TAG, "Verified page unreadable", e)
                _search.update {
                    it.copy(loading = false, loadingMore = false, error = messageFor(e))
                }
            }
        }
    }

    fun onVerificationCancelled() {
        _search.update {
            it.copy(
                verification = null,
                loading = false,
                loadingMore = false,
                error = "Search needs Anna's Archive's check to be completed. " +
                        "Members can add their secret key in settings to skip it."
            )
        }
    }

    private fun applyPage(found: List<AnnasBook>, page: Int) {
        _search.update { s ->
            val known = s.books.mapTo(HashSet()) { it.md5 }
            val fresh = found.filter { it.md5 !in known }
            s.copy(
                books = if (page == 1) found else s.books + fresh,
                loading = false,
                loadingMore = false,
                error = null,
                page = page,
                // Pages overlap at the edges; nothing new means the end.
                endReached = found.isEmpty() || (page > 1 && fresh.isEmpty())
            )
        }
    }

    /** Other editions of [book] among the loaded results. */
    fun editionsOf(book: AnnasBook): List<AnnasBook> = editionsOf(book, _search.value.books)

    fun download(book: AnnasBook) = AnnasDownloadWorker.enqueue(appContext, book)

    fun cancelDownload(md5: String) = AnnasDownloadWorker.cancel(appContext, md5)

    /** @return an error to show, or null when saved. */
    fun saveSettings(mirror: String, key: String): String? {
        val normalized = AnnasArchivePrefs.normalizeBaseUrl(mirror)
            ?: return "That isn't a web address. Example: annas-archive.gl"
        prefs.baseUrl = normalized
        prefs.secretKey = key
        return null
    }

    private fun messageFor(e: Exception): String = when (e) {
        is AnnasArchiveException -> e.message ?: "Something went wrong."
        is java.io.IOException -> "Network error. Check your connection and try again."
        else -> "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val prefs = AnnasArchivePrefs(appContext)
            return AnnasArchiveViewModel(
                appContext = appContext,
                prefs = prefs,
                client = AnnasArchiveClient(appContext, prefs)
            ) as T
        }
    }

    companion object {
        private const val TAG = "AnnasArchiveVM"

        /**
         * One state per book. An unfinished attempt outranks finished ones,
         * so a retry in progress hides the failure before it.
         */
        internal fun toDownloadStates(infos: List<WorkInfo>): Map<String, DownloadState> {
            val result = LinkedHashMap<String, DownloadState>()
            for (info in infos) {
                val md5 = info.tags.firstNotNullOfOrNull { tag ->
                    tag.removePrefix("${AnnasDownloadWorker.TAG_DOWNLOAD}:").takeIf { it != tag }
                } ?: continue
                val state = when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadState.Queued
                    WorkInfo.State.RUNNING -> {
                        val total = info.progress.getLong(AnnasDownloadWorker.KEY_TOTAL, -1L)
                        DownloadState.Running(
                            bytes = info.progress.getLong(AnnasDownloadWorker.KEY_BYTES, 0L),
                            total = total.takeIf { it > 0 }
                        )
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val out = info.outputData
                        val title = out.getString(AnnasDownloadWorker.KEY_RESULT_TITLE).orEmpty()
                        val novelId = out.getString(AnnasDownloadWorker.KEY_NOVEL_ID)
                        if (novelId != null) DownloadState.Imported(novelId, title)
                        else DownloadState.AlreadyInLibrary(title)
                    }
                    WorkInfo.State.FAILED -> DownloadState.Failed(
                        info.outputData.getString(AnnasDownloadWorker.KEY_ERROR) ?: "Download failed."
                    )
                    WorkInfo.State.CANCELLED -> null
                }
                val existing = result[md5]
                if (existing is DownloadState.Queued || existing is DownloadState.Running) continue
                if (state == null) result.remove(md5) else result[md5] = state
            }
            return result
        }

        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

        /**
         * Same title (ignoring case, punctuation and any subtitle) and, when
         * both list one, an author in common.
         */
        internal fun editionsOf(book: AnnasBook, all: List<AnnasBook>): List<AnnasBook> {
            val key = titleKey(book.title)
            if (key.isEmpty()) return emptyList()
            return all.filter {
                it.md5 != book.md5 && titleKey(it.title) == key && sameAuthor(it.author, book.author)
            }
        }

        /** "Dune: Deluxe Edition (Ace)" → "dune"; "&" counts as "and". */
        internal fun titleKey(title: String): String =
            title.substringBefore(':').substringBefore(" - ").substringBefore('(')
                .lowercase().replace("&", " and ").replace(NON_WORD, " ").trim()

        internal fun sameAuthor(a: String, b: String): Boolean {
            if (a.isBlank() || b.isBlank()) return true
            fun words(s: String) = s.lowercase().split(NON_WORD).filter { it.length > 2 }.toSet()
            return words(a).intersect(words(b)).isNotEmpty()
        }
    }
}
