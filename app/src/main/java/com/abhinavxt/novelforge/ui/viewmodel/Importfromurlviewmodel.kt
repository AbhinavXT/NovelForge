package com.abhinavxt.novelforge.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.abhinavxt.novelforge.data.NovelRepository
import com.abhinavxt.novelforge.data.source.NovelUrlResolver
import com.abhinavxt.novelforge.data.source.SourceManager
import com.abhinavxt.novelforge.data.web.WebArticle
import com.abhinavxt.novelforge.data.web.WebPageImporter
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State + flow for the "Add from URL" screen.
 *
 * Two pipelines share one screen, chosen by whether the URL belongs to
 * a source we know:
 *
 *   MATCHED SOURCE
 *     start(url) -> Resolving -> Fetching -> Ready -> confirmAdd()
 *
 *   ANY OTHER PAGE  (the web-article path)
 *     start(url) -> Resolving -> ReadingPage -> ArticleReady
 *                -> saveArticle() -> Saving -> Saved
 *
 * The second pipeline is what makes "paste any webpage" work: when the
 * resolver has no match we no longer dead-end, we extract the readable
 * text and offer to save it as a one-chapter local book. From there the
 * existing EPUB export on the detail screen produces a real .epub.
 *
 * [confirmAdd] / [saveArticle] are the user pressing the CTA. Both write
 * to the DB; navigation happens in the composable so back-stack concerns
 * stay in the UI.
 */
class ImportFromUrlViewModel(
    private val repository: NovelRepository,
    private val webImporter: WebPageImporter
) : ViewModel() {

    sealed class UiState {
        data object Resolving : UiState()
        data class Fetching(val sourceName: String) : UiState()

        /** Web-article path: fetching + extracting an unrecognised page. */
        data class ReadingPage(val host: String) : UiState()

        /** Web-article path: extraction succeeded, awaiting confirmation. */
        data class ArticleReady(val article: WebArticle) : UiState()

        /** Web-article path: writing to the library. */
        data object Saving : UiState()

        /** Terminal success for the web-article path. */
        data class Saved(val novelId: String, val title: String) : UiState()

        data class Error(
            val url: String,
            val message: String,
            val retryable: Boolean = true
        ) : UiState()

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
     * Start the resolve -> fetch pipeline. Idempotent; calling twice with
     * the same URL while already working is a no-op.
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
            val source = resolved?.let { SourceManager.getSource(it.sourceId) }

            // No registered source owns this URL (or the resolver knows a
            // source id that's no longer registered) -> treat it as a plain
            // web page rather than giving up.
            if (resolved == null || source == null) {
                runWebArticle(url)
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
     * Web-article path. Kept separate from [run] so the matched-source
     * flow above reads exactly as it did before.
     */
    private suspend fun runWebArticle(url: String) {
        val normalized = NovelUrlResolver.normalize(url)
        if (normalized == null || !normalized.startsWith("http")) {
            _uiState.value = UiState.Error(
                url = url,
                message = "That doesn't look like a web link. Paste the full " +
                        "address of the page you want to save.",
                retryable = false
            )
            return
        }

        _uiState.value = UiState.ReadingPage(host = hostOf(normalized))

        when (val result = webImporter.load(normalized)) {
            is WebPageImporter.LoadResult.Success ->
                _uiState.value = UiState.ArticleReady(result.article)
            is WebPageImporter.LoadResult.Failure ->
                _uiState.value = UiState.Error(url = normalized, message = result.message)
        }
    }

    /**
     * Save the currently-previewed article to the library.
     * No-op unless we're in [UiState.ArticleReady], so a double-tap on the
     * CTA can't write the page twice.
     */
    fun saveArticle() {
        val ready = _uiState.value as? UiState.ArticleReady ?: return
        _uiState.value = UiState.Saving

        viewModelScope.launch {
            try {
                val novelId = webImporter.save(ready.article)
                _uiState.value = UiState.Saved(novelId = novelId, title = ready.article.title)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("ImportFromUrl", "saveArticle failed", e)
                _uiState.value = UiState.Error(
                    url = ready.article.url,
                    message = "Couldn't save this page to your library. " +
                            "Storage may be full."
                )
            }
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

    private fun hostOf(url: String): String = try {
        java.net.URI(url).host?.removePrefix("www.").orEmpty()
    } catch (e: Exception) {
        ""
    }

    class Factory(
        private val repository: NovelRepository,
        context: Context
    ) : ViewModelProvider.Factory {
        // applicationContext only — a ViewModel outlives the Activity.
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ImportFromUrlViewModel(
                repository = repository,
                webImporter = WebPageImporter(appContext)
            ) as T
        }
    }
}