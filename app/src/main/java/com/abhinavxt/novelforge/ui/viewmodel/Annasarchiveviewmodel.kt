package com.abhinavxt.novelforge.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.abhinavxt.novelforge.data.annas.AnnasArchiveApi
import com.abhinavxt.novelforge.data.epub.EpubImporter
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the Anna's Archive screen: search, book detail, and the
 * mirror-resolve -> download -> EpubImporter pipeline. The end product is a
 * normal local_ novel — no Source/SourceManager involvement.
 */
class AnnasArchiveViewModel(
    appContext: Context
) : ViewModel() {

    private val context = appContext.applicationContext
    private val importer = EpubImporter(context)

    sealed interface SearchState {
        object Idle : SearchState
        object Loading : SearchState
        data class Results(val books: List<AnnasArchiveApi.Book>) : SearchState
        data class Error(val message: String) : SearchState
    }

    sealed interface BookState {
        object Hidden : BookState
        data class LoadingDetail(val book: AnnasArchiveApi.Book) : BookState
        data class Detail(val detail: AnnasArchiveApi.BookDetail) : BookState

        /** progress is null while length is unknown (indeterminate bar). */
        data class Downloading(
            val detail: AnnasArchiveApi.BookDetail,
            val mirrorLabel: String,
            val progress: Float?,
        ) : BookState

        data class Importing(val detail: AnnasArchiveApi.BookDetail) : BookState
        data class Done(
            val detail: AnnasArchiveApi.BookDetail,
            val novelId: String,
            val chapterCount: Int,
        ) : BookState

        data class Error(
            val detail: AnnasArchiveApi.BookDetail?,
            val book: AnnasArchiveApi.Book,
            val message: String,
        ) : BookState
    }

    private val _searchState = MutableStateFlow<SearchState>(SearchState.Idle)
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _bookState = MutableStateFlow<BookState>(BookState.Hidden)
    val bookState: StateFlow<BookState> = _bookState.asStateFlow()

    private var searchJob: Job? = null
    private var importJob: Job? = null

    fun onQueryChange(query: String) {
        _query.value = query
    }

    fun search() {
        val q = _query.value.trim()
        if (q.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _searchState.value = SearchState.Loading
            try {
                val books = AnnasArchiveApi.search(q)
                _searchState.value = SearchState.Results(books)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("AnnasArchiveVM", "Search failed: ${e.message}")
                _searchState.value = SearchState.Error(
                    e.message ?: "Search failed. Check your connection."
                )
            }
        }
    }

    fun openBook(book: AnnasArchiveApi.Book) {
        importJob?.cancel()
        importJob = viewModelScope.launch {
            _bookState.value = BookState.LoadingDetail(book)
            try {
                val detail = AnnasArchiveApi.getDetails(book.url)
                _bookState.value = BookState.Detail(detail)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("AnnasArchiveVM", "Detail load failed: ${e.message}")
                _bookState.value = BookState.Error(
                    detail = null,
                    book = book,
                    message = e.message ?: "Couldn't load book details."
                )
            }
        }
    }

    /** Retry hook for the error state — reloads detail or restarts import. */
    fun retry(state: BookState.Error) {
        if (state.detail != null) importBook(state.detail) else openBook(state.book)
    }

    fun closeBook() {
        importJob?.cancel()
        _bookState.value = BookState.Hidden
    }

    /**
     * Try mirrors in order (direct .epub links first — see getDetails sort):
     * resolve -> download -> zip-validate. First success gets imported;
     * a failed mirror just falls through to the next one.
     */
    fun importBook(detail: AnnasArchiveApi.BookDetail) {
        importJob?.cancel()
        importJob = viewModelScope.launch {
            if (detail.mirrors.isEmpty()) {
                _bookState.value = BookState.Error(
                    detail, bookFrom(detail), "No usable download mirrors for this book."
                )
                return@launch
            }

            var imported = false
            for (mirror in detail.mirrors) {
                _bookState.value = BookState.Downloading(detail, mirror.label, null)

                val fileUrl = AnnasArchiveApi.resolveMirror(mirror) ?: continue
                val file = AnnasArchiveApi.downloadEpub(context, fileUrl) { progress ->
                    _bookState.value = BookState.Downloading(detail, mirror.label, progress)
                } ?: continue

                _bookState.value = BookState.Importing(detail)
                try {
                    when (val result = importer.importEpub(android.net.Uri.fromFile(file))) {
                        is EpubImporter.ImportResult.Success -> {
                            _bookState.value = BookState.Done(
                                detail = detail,
                                novelId = result.novelId,
                                chapterCount = result.chapterCount,
                            )
                            imported = true
                        }
                        is EpubImporter.ImportResult.Error -> {
                            // A parsed-but-broken epub from this mirror; a
                            // different mirror may carry a healthy file.
                            Logger.e("AnnasArchiveVM", "Import failed: ${result.message}")
                        }
                    }
                } finally {
                    file.delete() // imported content lives in Room; temp is done
                }
                if (imported) break
            }

            if (!imported) {
                _bookState.value = BookState.Error(
                    detail, bookFrom(detail),
                    "All mirrors failed. The book may be unavailable right now — try again later."
                )
            }
        }
    }

    private fun bookFrom(detail: AnnasArchiveApi.BookDetail) = AnnasArchiveApi.Book(
        title = detail.title,
        url = detail.url,
        coverUrl = detail.coverUrl,
    )

    companion object {
        fun provideFactory(appContext: Context): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AnnasArchiveViewModel(appContext.applicationContext) as T
                }
            }
        }
    }
}