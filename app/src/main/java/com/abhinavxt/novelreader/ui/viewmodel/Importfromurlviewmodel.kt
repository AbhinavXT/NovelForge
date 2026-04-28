package com.abhinavxt.novelreader.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.abhinavxt.novelreader.data.NovelRepository
import com.abhinavxt.novelreader.data.source.NovelUrlResolver
import com.abhinavxt.novelreader.data.source.SourceManager
import com.abhinavxt.novelreader.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State + flow for the "Import Novel from URL" screen.
 *
 * Lifecycle:
 *   start(url) → UiState.Resolving
 *               → UrlResolver runs (synchronous, cheap)
 *               → if matched: UiState.Fetching
 *                            → repository.fetchNovelDetails (network)
 *                            → UiState.Ready | UiState.Error
 *               → if not matched: UiState.Unsupported
 *
 * [confirmAdd] is the user pressing "Add to library" on the Ready screen.
 * It writes the novel to the DB. Navigation to the detail screen happens
 * in the composable, not here, so back-stack concerns stay in the UI.
 */
class ImportFromUrlViewModel(
    private val repository: NovelRepository
) : ViewModel() {

    sealed class UiState {
        data object Resolving : UiState()
        data class Fetching(val sourceName: String) : UiState()
        data class Unsupported(val url: String) : UiState()
        data class Error(val url: String, val message: String) : UiState()
        data class Ready(
            val novelId: String,
            val canonicalUrl: String,
            val sourceName: String,
            val title: String,
            val author: String,
            val description: String,
            val coverUrl: String,
            val chapterCount: Int,
            val alreadyInLibrary: Boolean
        ) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Resolving)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Remembered for retry(). Only the original URL is needed — we'll
    // re-run the whole pipeline.
    private var lastUrl: String = ""

    /**
     * Start the resolve → fetch pipeline. Idempotent; calling twice with
     * the same URL while already fetching is a no-op.
     */
    fun start(url: String) {
        if (url == lastUrl && _uiState.value !is UiState.Resolving) return
        lastUrl = url
        run(url)
    }

    fun retry() {
        if (lastUrl.isNotBlank()) run(lastUrl)
    }

    private fun run(url: String) {
        _uiState.value = UiState.Resolving

        viewModelScope.launch {
            // Step 1: resolve the URL. Synchronous string work.
            val resolved = NovelUrlResolver.resolve(url)
            if (resolved == null) {
                _uiState.value = UiState.Unsupported(url)
                return@launch
            }

            val source = SourceManager.getSource(resolved.sourceId)
            if (source == null) {
                // Resolver knew the source id but SourceManager doesn't
                // — only possible if a source is removed but the resolver
                // still has its pattern. Defensive branch.
                _uiState.value = UiState.Unsupported(url)
                return@launch
            }

            _uiState.value = UiState.Fetching(sourceName = source.name)

            // Step 2: hit the network for novel details.
            val novel = try {
                repository.fetchNovelDetails(resolved.novelId, resolved.canonicalUrl)
            } catch (e: Exception) {
                Logger.e("ImportFromUrl", "fetchNovelDetails failed", e)
                null
            }

            if (novel == null) {
                _uiState.value = UiState.Error(
                    url = url,
                    message = "We found the source (${source.name}) but couldn't " +
                            "load this novel. The link may be broken, or the site " +
                            "may be temporarily unavailable."
                )
                return@launch
            }

            // Step 3: check if already in library — changes the CTA label.
            val inLibrary = repository.isInLibrary(resolved.novelId)

            _uiState.value = UiState.Ready(
                novelId = resolved.novelId,
                canonicalUrl = resolved.canonicalUrl,
                sourceName = source.name,
                title = novel.title,
                author = novel.author,
                description = novel.description,
                coverUrl = novel.coverUrl ?: "",
                chapterCount = novel.chapters.size,
                alreadyInLibrary = inLibrary
            )
        }
    }

    /**
     * Write the currently-Ready novel to the library. Safe to call
     * even if already in library (upsert). Caller navigates to detail
     * after this returns.
     */
    fun confirmAdd() {
        val ready = _uiState.value as? UiState.Ready ?: return

        viewModelScope.launch {
            // Re-fetch to get the full Novel object with chapters —
            // we only stored the preview fields in UiState.Ready.
            // This is a cache hit in practice because repository/source
            // implementations tend to cache the last getNovelDetails call.
            val novel = try {
                repository.fetchNovelDetails(ready.novelId, ready.canonicalUrl)
            } catch (e: Exception) {
                Logger.e("ImportFromUrl", "confirmAdd refetch failed", e)
                null
            }

            if (novel != null) {
                repository.addToLibrary(novel)
            }
        }
    }

    class Factory(
        private val repository: NovelRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ImportFromUrlViewModel(repository) as T
        }
    }
}