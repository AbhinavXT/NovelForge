package com.abhinavxt.novelforge.data.source.plugin

// Source plugins — the Source adapter.
//
// Wraps one installed JS plugin and exposes it through NovelForge's Source
// interface, so search, library, downloads, the reader and the update checker
// treat it like any built-in source.
//
// IDs follow the same reversible scheme as NfSourceAdapter:
//     sourceId = "js-<sanitised plugin id>"   (never contains "_")
//     novelId  = "<sourceId>_<base64url(novelUrl)>"
//     chapterId = "<novelId>_c<sha1(chapterUrl)[0..9]>"
//
// The script is compiled lazily on first use, so registering every installed
// plugin at app start costs one small JSON read, not N script evaluations.

import android.util.Base64
import com.abhinavxt.novelforge.data.model.Chapter
import com.abhinavxt.novelforge.data.model.Novel
import com.abhinavxt.novelforge.data.model.NovelPreview
import com.abhinavxt.novelforge.data.source.Source
import com.abhinavxt.novelforge.util.Logger
import com.abhinavxt.novelforge.util.ReaderText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.net.URI
import java.security.MessageDigest

class JsPluginSource(
    val plugin: InstalledPlugin,
    private val scriptFile: File,
    private val http: PluginHttp,
) : Source {

    override val id: String = plugin.sourceId
    override val name: String =
        if (plugin.lang.equals("en", ignoreCase = true) || plugin.lang.isBlank()) plugin.name
        else "${plugin.name} (${plugin.lang})"
    override val baseUrl: String = plugin.site

    @Volatile private var loaded: JsPlugin? = null

    private fun runtime(): JsPlugin = loaded ?: synchronized(this) {
        loaded ?: JsPlugin.load(
            script = scriptFile.readText(),
            fileName = scriptFile.name,
            http = http,
            log = { Logger.d(TAG, "[${plugin.id}] $it") },
        ).also { loaded = it }
    }

    /** Runs a plugin function off the main thread; cancellation interrupts the script. */
    private suspend fun call(function: String, vararg args: Any?): Any? =
        runInterruptible(Dispatchers.IO) { runtime().call(function, *args) }

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

    /** Plugins may return absolute URLs (`url`) or site-relative paths (`path`, LNReader style). */
    private fun resolve(urlOrPath: String?): String? {
        val raw = urlOrPath?.trim()?.ifEmpty { null } ?: return null
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        return runCatching { URI(plugin.site.trimEnd('/') + "/").resolve(raw.removePrefix("/")).toString() }
            .getOrNull()
    }

    private fun Map<*, *>.str(key: String): String? =
        (this[key] as? String)?.trim()?.ifEmpty { null }

    private fun Map<*, *>.link(): String? = resolve(str("url") ?: str("path"))

    private fun toPreviews(result: Any?): List<NovelPreview> =
        (result as? List<*>).orEmpty()
            .filterIsInstance<Map<*, *>>()
            .mapNotNull { item ->
                val url = item.link() ?: return@mapNotNull null
                NovelPreview(
                    id = novelIdFromUrl(url),
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

    override suspend fun search(query: String): List<NovelPreview> =
        guarded("search", emptyList()) { toPreviews(call("searchNovels", query, 1)) }

    override suspend fun getPopular(page: Int): List<NovelPreview> =
        guarded("popularNovels(p$page)", emptyList()) { toPreviews(call("popularNovels", page)) }

    override suspend fun getNovelDetails(novelUrl: String): Novel? = guarded("parseNovel", null) {
        val result = call("parseNovel", novelUrl) as? Map<*, *> ?: return@guarded null
        // Derive the id from the URL the app asked for, so it round-trips
        // exactly with what search()/getPopular() produced.
        val novelId = novelIdFromUrl(novelUrl)
        val chapters = (result["chapters"] as? List<*>).orEmpty()
            .filterIsInstance<Map<*, *>>()
            .mapNotNull { ch -> ch.link()?.let { url -> url to ch.str("name") } }
            .distinctBy { it.first }
            .mapIndexed { index, (url, title) ->
                Chapter(
                    id = chapterIdFor(novelId, url),
                    number = index + 1,
                    title = title ?: "Chapter ${index + 1}",
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
        runInterruptible(Dispatchers.Default) { ReaderText.fromHtml(html) }.ifBlank { null }
    }

    private companion object {
        const val TAG = "JsPlugin"
    }
}
