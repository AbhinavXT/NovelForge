package com.abhinavxt.novelforge.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.abhinavxt.novelforge.data.UserFacingException
import com.abhinavxt.novelforge.data.opds.OpdsAcquisition
import com.abhinavxt.novelforge.data.opds.OpdsCatalog
import com.abhinavxt.novelforge.data.opds.OpdsCatalogStore
import com.abhinavxt.novelforge.data.opds.OpdsClient
import com.abhinavxt.novelforge.data.opds.OpdsEntry
import com.abhinavxt.novelforge.data.opds.OpdsFeed
import com.abhinavxt.novelforge.util.Logger
import com.abhinavxt.novelforge.worker.BookDownloadState
import com.abhinavxt.novelforge.worker.BookDownloadWorker
import com.abhinavxt.novelforge.worker.OpdsDownloadWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Browse OPDS catalogs and add their books to the library.
 *
 * Browsing is a stack of feed pages inside one screen: a catalog's root,
 * then whatever section or search result was opened from it. Back pops
 * the stack; an empty stack is the catalog list. Downloads run in
 * [OpdsDownloadWorker], keyed by file address.
 */
class OpdsViewModel(
    private val appContext: Context,
    private val store: OpdsCatalogStore,
    private val client: OpdsClient
) : ViewModel() {

    data class Page(
        val catalog: OpdsCatalog,
        val url: String,
        val title: String,
        val entries: List<OpdsEntry> = emptyList(),
        val nextUrl: String? = null,
        val loading: Boolean = true,
        val loadingMore: Boolean = false,
        val error: String? = null,
        /** What was searched, when this page is search results. */
        val query: String? = null
    )

    val catalogs: StateFlow<List<OpdsCatalog>> = store.catalogs

    private val _stack = MutableStateFlow<List<Page>>(emptyList())
    val stack: StateFlow<List<Page>> = _stack.asStateFlow()

    /** Catalog id → its root feed, which says how to search it. */
    private val roots = mutableMapOf<String, OpdsFeed>()

    private val _searchable = MutableStateFlow<Set<String>>(emptySet())
    /** Ids of catalogs whose root feed offers search. */
    val searchable: StateFlow<Set<String>> = _searchable.asStateFlow()

    /** File-address key → download state. */
    val downloads: StateFlow<Map<String, BookDownloadState>> =
        WorkManager.getInstance(appContext)
            .getWorkInfosByTagFlow(OpdsDownloadWorker.TAG_DOWNLOAD)
            .map { BookDownloadWorker.statesByKey(it, OpdsDownloadWorker.TAG_DOWNLOAD) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private var loadJob: Job? = null

    // ── Browsing ────────────────────────────────────────────────────────

    fun openCatalog(catalog: OpdsCatalog) {
        push(Page(catalog = catalog, url = catalog.url, title = catalog.name))
    }

    fun openEntry(entry: OpdsEntry) {
        val top = _stack.value.lastOrNull() ?: return
        val url = entry.navigationUrl ?: return
        push(Page(catalog = top.catalog, url = url, title = entry.title))
    }

    fun search(query: String) {
        val top = _stack.value.lastOrNull() ?: return
        val q = query.trim()
        if (q.isEmpty()) return
        val root = roots[top.catalog.id] ?: return
        // A new search replaces search results rather than stacking on them.
        val base = _stack.value.dropLastWhile { it.query != null }
        val page = Page(catalog = top.catalog, url = "", title = "“$q”", query = q)
        _stack.value = base + page
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            load(page) { client.search(top.catalog, root, q) }
        }
    }

    /** @return false when there was nothing to go back to (leave the screen). */
    fun back(): Boolean {
        if (_stack.value.isEmpty()) return false
        loadJob?.cancel()
        _stack.value = _stack.value.dropLast(1)
        return true
    }

    fun retry() {
        val top = _stack.value.lastOrNull() ?: return
        if (top.query != null) {
            _stack.value = _stack.value.dropLast(1)
            search(top.query)
        } else {
            _stack.value = _stack.value.dropLast(1)
            push(top.copy(entries = emptyList(), nextUrl = null, error = null, loading = true))
        }
    }

    fun loadMore() {
        val top = _stack.value.lastOrNull() ?: return
        val next = top.nextUrl ?: return
        if (top.loading || top.loadingMore) return
        replaceTop(top) { it.copy(loadingMore = true) }
        loadJob = viewModelScope.launch {
            try {
                val feed = client.feed(top.catalog, next)
                replaceTop(top) { p ->
                    val known = p.entries.mapTo(HashSet()) { it.id to it.title }
                    p.copy(
                        entries = p.entries + feed.entries.filter { (it.id to it.title) !in known },
                        nextUrl = feed.nextUrl?.takeIf { it != next },
                        loadingMore = false
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Next page failed", e)
                replaceTop(top) { it.copy(loadingMore = false, nextUrl = null, error = messageFor(e)) }
            }
        }
    }

    private fun push(page: Page) {
        _stack.value = _stack.value + page
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            load(page) { client.feed(page.catalog, page.url) }
        }
    }

    private suspend fun load(page: Page, fetch: suspend () -> OpdsFeed) {
        try {
            val feed = fetch()
            if (page.url == page.catalog.url && page.query == null) {
                roots[page.catalog.id] = feed
                if (client.canSearch(feed)) _searchable.value = _searchable.value + page.catalog.id
            }
            replaceTop(page) {
                it.copy(
                    entries = feed.entries,
                    nextUrl = feed.nextUrl,
                    loading = false,
                    // The catalog's own name reads better than its feed title at the root.
                    title = if (page.query == null && page.url != page.catalog.url) feed.title.ifBlank { it.title } else it.title
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Feed failed: ${page.url}", e)
            replaceTop(page) { it.copy(loading = false, error = messageFor(e)) }
        }
    }

    /** Updates [page] if it's still on top; a page the user has left is dropped. */
    private fun replaceTop(page: Page, update: (Page) -> Page) {
        val stack = _stack.value
        val top = stack.lastOrNull() ?: return
        if (top.catalog.id != page.catalog.id || top.url != page.url || top.query != page.query) return
        _stack.value = stack.dropLast(1) + update(top)
    }

    // ── Downloads ───────────────────────────────────────────────────────

    fun download(entry: OpdsEntry, acquisition: OpdsAcquisition) {
        val catalog = _stack.value.lastOrNull()?.catalog ?: return
        OpdsDownloadWorker.enqueue(appContext, catalog.id, entry.title, acquisition)
    }

    fun cancelDownload(acquisition: OpdsAcquisition) = OpdsDownloadWorker.cancel(appContext, acquisition.url)

    /** The state of whichever of [entry]'s files has a download, if any. */
    fun downloadOf(entry: OpdsEntry, downloads: Map<String, BookDownloadState>): Pair<OpdsAcquisition, BookDownloadState>? =
        entry.acquisitions.firstNotNullOfOrNull { a ->
            downloads[OpdsDownloadWorker.keyFor(a.url)]?.let { a to it }
        }

    // ── Catalogs ────────────────────────────────────────────────────────

    /** @return an error to show, or null when saved. */
    fun saveCatalog(id: String?, name: String, url: String, username: String, password: String): String? {
        val normalized = OpdsCatalogStore.normalizeUrl(url) ?: return "That isn't a web address."
        if (normalized.startsWith("http://")) {
            return "NovelForge only connects to catalogs over https. Use your server's https address."
        }
        val catalog = OpdsCatalog(
            id = id ?: UUID.randomUUID().toString(),
            name = name.trim().ifBlank { normalized.substringAfter("://").substringBefore('/') },
            url = normalized,
            username = username.trim(),
            password = password
        )
        store.save(catalog)
        roots.remove(catalog.id)
        _searchable.value = _searchable.value - catalog.id
        return null
    }

    fun deleteCatalog(id: String) {
        store.delete(id)
        roots.remove(id)
    }

    private fun messageFor(e: Exception): String = when (e) {
        is UserFacingException -> e.message ?: "Something went wrong."
        is java.io.IOException -> "Couldn't reach the catalog. Check your connection and try again."
        else -> "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            OpdsViewModel(appContext, OpdsCatalogStore(appContext), OpdsClient()) as T
    }

    private companion object {
        const val TAG = "OpdsViewModel"
    }
}
