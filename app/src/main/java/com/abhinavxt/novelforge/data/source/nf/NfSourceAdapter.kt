package com.abhinavxt.novelforge.data.source.nf

// QuickNovel compatibility layer (package: data.source.nf) — Source adapter.
//
// Wraps a QuickNovel MainAPI provider and exposes it through NovelForge's Source
// interface, so the rest of the app (search, library, downloads, reader, update
// checker) needs zero knowledge of where the source implementation came from.
//
// Novel ID scheme
// ---------------
// Native NovelForge sources encode a site-specific slug into the novel ID
// ("rr_12345", "rnf_some-novel") and SourceManager.constructNovelUrl() rebuilds
// the URL with hardcoded per-source logic. That does not scale to ~40 sources,
// so adapter-backed sources use a universal, reversible scheme instead:
//
//     novelId = "<sourceId>_<base64url(novelUrl)>"
//
// The source id doubles as the prefix (it contains no underscore), which keeps
// SourceManager.getSourceFromNovelId()'s substringBefore("_") parsing working.
// Chapter IDs are "<novelId>_c<sha1(chapterUrl)[0..9]>" — stable across refreshes
// so bookmarks, highlights and reading progress keyed on chapter id survive
// chapter-list updates.

import android.util.Base64
import com.abhinavxt.novelforge.data.model.Chapter
import com.abhinavxt.novelforge.data.model.Novel
import com.abhinavxt.novelforge.data.model.NovelPreview
import com.abhinavxt.novelforge.data.source.BrowseFilters
import com.abhinavxt.novelforge.data.source.BrowseSource
import com.abhinavxt.novelforge.data.source.FilterOption
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.security.MessageDigest

class NfSourceAdapter(
    private val api: MainAPI,
    /** Short, stable, underscore-free prefix. Becomes part of DB keys — never change it. */
    override val id: String,
) : BrowseSource {

    // ── Threading contract ──────────────────────────────────────────────
    // Every suspend override below wraps its body in withContext(IO).
    //
    // This is the ONLY dispatch boundary for all 18 adapter-backed sources.
    // NovelRepository's network functions are plain `suspend` with no
    // withContext, and none of the ported MainAPI providers dispatch either,
    // so without this the whole chain inherits the caller's dispatcher --
    // which for viewModelScope.launch { } is Dispatchers.Main.immediate.
    //
    // Two things then run on the main thread:
    //   1. NfResponse.text -> okhttpResponse.body.string(). Call.await() uses
    //      enqueue(), so OkHttp resumes us as soon as HEADERS arrive; the body
    //      is still unread. .string() therefore blocks on a socket read, which
    //      trips BlockGuard -> NetworkOnMainThreadException. That lands in the
    //      broad `catch (e: Exception)` below and is logged as a generic
    //      "failed", so it looks like a dead source rather than a threading bug.
    //   2. Jsoup.parse() -- once in the provider (.document) and twice more
    //      here (stripHtml + htmlToPlainText). Pure CPU on a full HTML page.
    //
    // IO rather than Default because the block is network-dominated; the Jsoup
    // parses ride along rather than paying a second dispatch hop.
    // ────────────────────────────────────────────────────────────────────

    override val canBrowse: Boolean = api.hasMainPage

    override val filters: BrowseFilters by lazy {
        BrowseFilters(
            categories = api.mainCategories.map { FilterOption(it.first, it.second) },
            orderBys = api.orderBys.map { FilterOption(it.first, it.second) },
            tags = api.tags.map { FilterOption(it.first, it.second) },
        )
    }

    override val name: String =
        if (api.lang == "en") api.name else "${api.name} (${api.lang.substringBefore("/").trim()})"

    override val baseUrl: String = api.mainUrl

    private val b64Flags = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    fun novelIdFromUrl(novelUrl: String): String =
        id + "_" + Base64.encodeToString(novelUrl.toByteArray(Charsets.UTF_8), b64Flags)

    fun novelUrlFromId(novelId: String): String = runCatching {
        String(Base64.decode(novelId.removePrefix("${id}_"), b64Flags), Charsets.UTF_8)
    }.getOrDefault("")

    private fun chapterIdFor(novelId: String, chapterUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(chapterUrl.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "${novelId}_c${hex.take(10)}"
    }

    /** QuickNovel's APIRepository serialises calls per-API when a rate limit is set. */
    private suspend fun <T> rateLimited(block: suspend () -> T): T {
        if (!api.hasRateLimit) return block()
        api.rateLimitMutex.lock()
        return try {
            kotlinx.coroutines.delay(api.rateLimitTime)
            block()
        } finally {
            api.rateLimitMutex.unlock()
        }
    }

    private fun SearchResponse.toPreview() = NovelPreview(
        id = novelIdFromUrl(url),
        title = name,
        author = "",
        coverUrl = posterUrl,
        description = latestChapter?.let { "Latest: $it" } ?: "",
        source = this@NfSourceAdapter.name,
    )

    override suspend fun search(query: String): List<NovelPreview> =
        withContext(Dispatchers.IO) {
            try {
                rateLimited { api.search(query) }
                    .orEmpty()
                    .distinctBy { it.url }
                    .map { it.toPreview() }
            } catch (e: Exception) {
                Logger.e("NfSource", "[$id] search failed: ${e.message}")
                emptyList()
            }
        }

    override suspend fun browse(
        page: Int,
        category: String?,
        orderBy: String?,
        tag: String?,
    ): List<NovelPreview> = withContext(Dispatchers.IO) {
        if (!api.hasMainPage) return@withContext emptyList()
        try {
            rateLimited {
                api.loadMainPage(
                    page = page,
                    mainCategory = category,
                    orderBy = orderBy,
                    tag = tag,
                )
            }.list
                .distinctBy { it.url }
                .map { it.toPreview() }
        } catch (e: Exception) {
            Logger.e("NfSource", "[$id] browse failed (p$page): ${e.message}")
            emptyList()
        }
    }

    override suspend fun getPopular(page: Int): List<NovelPreview> =
        // Old behaviour preserved: first option of each filter list.
        browse(
            page = page,
            category = api.mainCategories.firstOrNull()?.second,
            orderBy = api.orderBys.firstOrNull()?.second,
            tag = api.tags.firstOrNull()?.second,
        )

    override suspend fun getNovelDetails(novelUrl: String): Novel? =
        withContext(Dispatchers.IO) {
            try {
                val response = rateLimited { api.load(novelUrl) } as? StreamResponse
                    ?: return@withContext null
                // Derive the id from the URL the app asked for, so it round-trips
                // exactly with what search()/getPopular() produced.
                val novelId = novelIdFromUrl(novelUrl)

                val chapters = response.data
                    .distinctBy { it.url }
                    .mapIndexed { index, chapterData ->
                        Chapter(
                            id = chapterIdFor(novelId, chapterData.url),
                            number = index + 1,
                            title = chapterData.name.ifBlank { "Chapter ${index + 1}" },
                            url = chapterData.url,
                        )
                    }

                Novel(
                    id = novelId,
                    title = response.name,
                    author = response.author ?: "",
                    coverUrl = response.posterUrl,
                    description = response.synopsis ?: "",
                    source = name,
                    status = response.status?.displayName ?: "Unknown",
                    chapters = chapters,
                )
            } catch (e: Exception) {
                Logger.e("NfSource", "[$id] getNovelDetails failed: ${e.message}")
                null
            }
        }

    override suspend fun getChapterContent(chapterUrl: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val html = rateLimited { api.loadHtml(chapterUrl) }
                    ?: return@withContext null
                // Both stripHtml() and htmlToPlainText() run a full Jsoup.parse.
                // Kept inside the IO block deliberately -- they are the two most
                // expensive CPU operations in a chapter load.
                val cleaned = stripHtml(
                    html,
                    chapterName = null,
                    chapterIndex = null,
                    stripAuthorNotes = true
                )
                htmlToPlainText(cleaned).ifBlank { null }
            } catch (e: Exception) {
                Logger.e("NfSource", "[$id] getChapterContent failed: ${e.message}")
                null
            }
        }

    /**
     * NovelForge's reader consumes plain text with paragraphs separated by blank
     * lines (see the native sources), while QuickNovel providers return HTML.
     */
    private fun htmlToPlainText(html: String): String {
        val document = Jsoup.parse(html)
        document.select("script, style, noscript, iframe, ins, .ads, .advertisement").remove()

        val paragraphs = document.select("p")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
        if (paragraphs.isNotEmpty()) {
            return paragraphs.joinToString("\n\n")
        }

        // Fallback for providers that emit bare text/<br> separated content.
        document.select("br").append("\\n")
        val text = document.body()?.wholeText() ?: document.wholeText()
        return text
            .replace("\\n", "\n")
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }
}