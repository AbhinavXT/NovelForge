package com.abhinavxt.novelforge.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.abhinavxt.novelforge.data.annas.AnnasArchiveClient
import com.abhinavxt.novelforge.data.annas.AnnasArchiveException
import com.abhinavxt.novelforge.data.annas.AnnasArchivePrefs
import com.abhinavxt.novelforge.data.annas.AnnasBook
import com.abhinavxt.novelforge.data.annas.AnnasVerificationRequired
import com.abhinavxt.novelforge.data.epub.EpubImporter
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Search Anna's Archive, then download a result and import it like any
 * EPUB the user picked by hand.
 *
 * One download at a time: the importer and the mirrors both prefer it, and
 * a second tap while one runs is far more likely a mis-tap than a queue.
 */
class AnnasArchiveViewModel(
    private val appContext: Context,
    private val prefs: AnnasArchivePrefs,
    private val client: AnnasArchiveClient,
    private val importer: EpubImporter
) : ViewModel() {

    data class SearchState(
        val query: String = "",
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
        data object Idle : DownloadState
        data class Downloading(val book: AnnasBook, val bytes: Long, val total: Long?) : DownloadState
        data class Importing(val book: AnnasBook) : DownloadState
        data class Imported(val novelId: String, val title: String) : DownloadState
        data class AlreadyInLibrary(val title: String) : DownloadState
        data class Failed(val book: AnnasBook, val message: String) : DownloadState
    }

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download.asStateFlow()

    private var searchJob: Job? = null
    private var downloadJob: Job? = null

    val mirror: String get() = prefs.baseUrl
    val secretKey: String get() = prefs.secretKey

    fun onQueryChange(query: String) = _search.update { it.copy(query = query) }

    fun submitSearch() {
        val query = _search.value.query.trim()
        if (query.isEmpty()) return
        searchJob?.cancel()
        _search.update {
            SearchState(query = it.query, loading = true, searched = true)
        }
        searchJob = viewModelScope.launch { loadPage(query, 1) }
    }

    fun loadMore() {
        val s = _search.value
        if (s.loading || s.loadingMore || s.endReached || s.page == 0) return
        _search.update { it.copy(loadingMore = true) }
        searchJob = viewModelScope.launch { loadPage(s.query.trim(), s.page + 1) }
    }

    private suspend fun loadPage(query: String, page: Int) {
        try {
            applyPage(client.search(query, page), page)
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

    fun download(book: AnnasBook) {
        if (downloadJob?.isActive == true) return
        _download.value = DownloadState.Downloading(book, 0, null)

        downloadJob = viewModelScope.launch {
            val dir = File(appContext.cacheDir, "annas").apply { mkdirs() }
            val file = File(dir, "${book.md5}.epub")
            try {
                client.download(book, file) { bytes, total ->
                    _download.value = DownloadState.Downloading(book, bytes, total)
                }
                _download.value = DownloadState.Importing(book)
                _download.value = when (val r = importer.importDocument(Uri.fromFile(file))) {
                    is EpubImporter.ImportResult.Success -> DownloadState.Imported(r.novelId, r.title)
                    is EpubImporter.ImportResult.Duplicate -> DownloadState.AlreadyInLibrary(r.existingTitle)
                    is EpubImporter.ImportResult.Error -> DownloadState.Failed(book, r.message)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _download.value = DownloadState.Idle
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Download failed", e)
                _download.value = DownloadState.Failed(book, messageFor(e))
            } finally {
                // The importer copies the text into the database; the file is spent.
                file.delete()
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _download.value = DownloadState.Idle
    }

    fun dismissDownload() {
        if (downloadJob?.isActive != true) _download.value = DownloadState.Idle
    }

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
                client = AnnasArchiveClient(appContext, prefs),
                importer = EpubImporter(appContext)
            ) as T
        }
    }

    private companion object {
        const val TAG = "AnnasArchiveVM"
    }
}
