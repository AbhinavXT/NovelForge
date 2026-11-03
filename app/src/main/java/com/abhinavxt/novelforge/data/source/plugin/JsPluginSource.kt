package com.abhinavxt.novelforge.data.source.plugin

// Source plugins — the Source adapter.
//
// Wraps one installed JS plugin and exposes it through NovelForge's Source
// interface, so search, library, downloads, the reader and the update checker
// treat it like any built-in source.
//
// IDs follow the same reversible scheme as NfSourceAdapter:
//     sourceId  = "js-<sanitised plugin id>"   (never contains "_")
//     novelId   = "<sourceId>_<base64url(novelUrl)>"
//     chapterId = "<novelId>_c<sha1(chapterUrl)[0..9]>"
//
// Bundled plugins that replaced a built-in source register under that
// source's id instead (InstalledPlugin.legacySourceId). If the old source
// wrote ids in a different format ("rr_12345"), the plugin returns them as
// `id` on novels, list items and chapters; they are only accepted when they
// start with that source's prefix, so a plugin can never hand out ids that
// route to another source. Without an `id`, the default scheme applies.
//
// The script is compiled lazily on first use (or by warmUp() in the
// background), so registering every installed plugin at app start costs one
// small JSON read, not N script evaluations.

import android.util.Base64
import com.abhinavxt.novelforge.data.model.Chapter
import com.abhinavxt.novelforge.data.model.Novel
import com.abhinavxt.novelforge.data.model.NovelPreview
import com.abhinavxt.novelforge.data.source.BrowseFilters
import com.abhinavxt.novelforge.data.source.BrowseSource
import com.abhinavxt.novelforge.data.source.FilterOption
import com.abhinavxt.novelforge.data.source.nf.stripHtml
import com.abhinavxt.novelforge.util.Logger
import com.abhinavxt.novelforge.util.ReaderText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.URI
import java.security.MessageDigest

class JsPluginSource(
    val plugin: InstalledPlugin,
    private val scriptFile: File,
    private val http: PluginHttp,
) : BrowseSource {

    override val id: String = plugin.sourceId
    override val name: String =
        if (plugin.lang.equals("en", ignoreCase = true) || plugin.lang.isBlank()) plugin.name
        else "${plugin.name} (${plugin.lang})"
    override val baseUrl: String = plugin.site

    private val legacyPrefix: String? = plugin.legacyIdPrefix?.let { "${it}_" }

    @Volatile private var loaded: JsPlugin? = null

    private fun runtime(): JsPlugin = loaded ?: synchronized(this) {
        loaded ?: JsPlugin.load(
            script = scriptFile.readText(),
            fileName = scriptFile.name,
            http = http,
            log = { Logger.d(TAG, "[${plugin.id}] $it") },
        ).also { loaded = it }
    }

    /** Compiles the script now. Failures are left for the first real call to report. */
    fun warmUp() {
        runCatching { runtime() }
    }

    /** The runtime if it compiles; null (logged) otherwise. Used by the synchronous properties. */
    private fun runtimeOrNull(): JsPlugin? = try {
        runtime()
    } catch (e: Exception) {
        Logger.e(TAG, "[${plugin.id}] failed to load: ${e.message}")
        null
    }

    // ── Optional exports ────────────────────────────────────────────────

    override val canBrowse: Boolean by lazy { runtimeOrNull()?.hasFunction("browseNovels") == true }

    override val filters: BrowseFilters by lazy {
        val raw = runCatching { runtimeOrNull()?.export("filters") as? Map<*, *> }.getOrNull()
            ?: return@lazy BrowseFilters()
        fun options(key: String) = (raw[key] as? List<*>).orEmpty()
            .filterIsInstance<Map<*, *>>()
            .mapNotNull { o ->
                val value = o["value"]?.toString() ?: return@mapNotNull null
                FilterOption(label = o["label"]?.toString() ?: value, value = value)
            }
        BrowseFilters(categories = options("categories"), orderBys = options("orderBys"), tags = options("tags"))
    }

    /** `rateLimitMs`: wait this long before each call, one call at a time (QuickNovel's rateLimitTime). */
    private val rateLimitMs: Long by lazy {
        ((runCatching { runtimeOrNull()?.export("rateLimitMs") }.getOrNull() as? Double) ?: 0.0)
            .toLong().coerceIn(0L, 10_000L)
    }
    private val rateLimitMutex = Mutex()

    /** `stripCredits: false` keeps "Translator: … Editor: …" lines and author-note containers. */
    private val stripCredits: Boolean by lazy {
        runCatching { runtimeOrNull()?.export("stripCredits") }.getOrNull() != false
    }

    /** Runs a plugin function off the main thread; cancellation interrupts the script. */
    private suspend fun call(function: String, vararg args: Any?): Any? {
        val run: suspend () -> Any? = { runInterruptible(Dispatchers.IO) { runtime().call(function, *args) } }
        if (rateLimitMs == 0L) return run()
        return rateLimitMutex.withLock {
            delay(rateLimitMs)
            run()
        }
    }

    // ── Ids ─────────────────────────────────────────────────────────────

    private val b64Flags = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    fun novelIdFromUrl(novelUrl: String): String =
        id + "_" + Base64.encodeToString(novelUrl.toByteArray(Charsets.UTF_8), b64Flags)

    fun novelUrlFromId(novelId: String): String = runCatching {
        String(Base64.decode(novelId.removePrefix("${id}_"), b64Flags), Charsets.UTF_8)
    }.getOrDefault("")

    private fun chapterIdFor(novelId: String, chapterUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(chapterUrl.toByteArray(Charsets.UTF_8))
        return "${novelId}_c" + digest.joinToString("") { "%02x".format(it) }.take(10)
    }

    /** A plugin-supplied id, if this plugin may supply ids and it has the right prefix. */
    private fun explicitId(item: Map<*, *>): String? {
        val prefix = legacyPrefix ?: return null
        val given = item.str("id") ?: return null
        if (given.startsWith(prefix) && given.length > prefix.length) return given
        Logger.e(TAG, "[${plugin.id}] ignoring id '$given' (must start with '$prefix')")
        return null
    }

    // ── Conversion ──────────────────────────────────────────────────────

    /** Plugins may return absolute URLs (`url`) or site-relative paths (`path`, LNReader style). */
    private fun resolve(urlOrPath: String?): String? {
        val raw = urlOrPath?.trim()?.ifEmpty { null } ?: return null
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        if (raw.startsWith("//")) return "https:$raw"
        return runCatching { URI(plugin.site.trimEnd('/') + "/").resolve(raw.removePrefix("/")).toString() }
            .getOrNull()
    }

    /** A string field; blank counts as missing. Not trimmed: values reach the app as the plugin wrote them. */
    private fun Map<*, *>.str(key: String): String? =
        (this[key] as? String)?.takeIf { it.isNotBlank() }

    private fun Map<*, *>.link(): String? = resolve(str("url") ?: str("path"))

    private fun toPreviews(result: Any?): List<NovelPreview> =
        (result as? List<*>).orEmpty()
            .filterIsInstance<Map<*, *>>()
            .mapNotNull { item ->
                val url = item.link() ?: return@mapNotNull null
                NovelPreview(
                    id = explicitId(item) ?: novelIdFromUrl(url),
                    title = item.str("name") ?: item.str("title") ?: return@mapNotNull null,
                    author = item.str("author") ?: "",
                    coverUrl = resolve(item.str("cover")),
                    description = item.str("summary") ?: "",
                    source = name,
                )
            }
            .distinctBy { it.id }

    private inline fun <T> guarded(what: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.e(TAG, "[${plugin.id}] $what failed: ${e.message}")
        fallback
    }

    // ── Source ──────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<NovelPreview> =
        guarded("search", emptyList()) { toPreviews(call("searchNovels", query, 1)) }

    override suspend fun getPopular(page: Int): List<NovelPreview> =
        guarded("popularNovels(p$page)", emptyList()) { toPreviews(call("popularNovels", page)) }

    override suspend fun browse(
        page: Int,
        category: String?,
        orderBy: String?,
        tag: String?,
    ): List<NovelPreview> {
        if (!canBrowse) return emptyList()
        return guarded("browseNovels(p$page)", emptyList()) {
            val options = mapOf("category" to category, "orderBy" to orderBy, "tag" to tag)
            toPreviews(call("browseNovels", page, options))
        }
    }

    override suspend fun getNovelDetails(novelUrl: String): Novel? = guarded("parseNovel", null) {
        val result = call("parseNovel", novelUrl) as? Map<*, *> ?: return@guarded null
        // Derive the id from the URL the app asked for, so it round-trips
        // exactly with what search()/getPopular() produced.
        val novelId = explicitId(result) ?: novelIdFromUrl(novelUrl)
        val chapters = (result["chapters"] as? List<*>).orEmpty()
            .filterIsInstance<Map<*, *>>()
            .mapNotNull { ch -> ch.link()?.let { url -> url to ch } }
            .distinctBy { it.first }
            .mapIndexed { index, (url, ch) ->
                Chapter(
                    id = explicitId(ch) ?: chapterIdFor(novelId, url),
                    // Optional: a source's own numbering, when it isn't just the list position.
                    number = (ch["number"] as? Double)?.toInt() ?: (index + 1),
                    title = ch.str("name") ?: "Chapter ${index + 1}",
                    url = url,
                )
            }
        Novel(
            id = novelId,
            title = result.str("name") ?: result.str("title") ?: "Untitled",
            author = result.str("author") ?: "",
            coverUrl = resolve(result.str("cover")),
            description = result.str("summary") ?: "",
            source = name,
            status = result.str("status") ?: "Unknown",
            chapters = chapters,
        )
    }

    override suspend fun getChapterContent(chapterUrl: String): String? = guarded("parseChapter", null) {
        val html = call("parseChapter", chapterUrl) as? String ?: return@guarded null
        runInterruptible(Dispatchers.Default) {
            // Same clean-up the QuickNovel adapter applied: author-note
            // containers and "Translator: … Editor: …" credit lines.
            ReaderText.fromHtml(if (stripCredits) stripHtml(html, stripAuthorNotes = true) else html)
        }.ifBlank { null }
    }

    private companion object {
        const val TAG = "JsPlugin"
    }
}
