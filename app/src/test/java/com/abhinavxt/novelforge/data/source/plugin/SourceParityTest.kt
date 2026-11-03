package com.abhinavxt.novelforge.data.source.plugin

import android.app.Application
import com.abhinavxt.novelforge.data.model.Novel
import com.abhinavxt.novelforge.data.model.NovelPreview
import com.abhinavxt.novelforge.data.source.BrowseSource
import com.abhinavxt.novelforge.data.source.Source
import com.abhinavxt.novelforge.data.source.SourceHttp
import com.abhinavxt.novelforge.data.source.SourceManager
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Live, opt-in comparison of a built-in Kotlin source with the bundled
 * plugin that replaces it. Both run against the real site through the
 * Source interface, exactly as the app calls them, and every difference in
 * ids, titles, chapter lists and chapter text is reported.
 *
 *     PARITY=royalroad ./gradlew :app:testDebugUnitTest --tests '*SourceParityTest*'
 *     PARITY=all       ...   (every bundled plugin whose Kotlin source still exists)
 *
 * Optional: PARITY_QUERY=<search term> (default: first word of the first popular title),
 * PARITY_NOVEL=<novel url> to compare one more novel besides the first two popular ones.
 *
 * The Kotlin side is looked up through SourceManager rather than by class,
 * so this keeps compiling after a converted source is deleted; it then
 * simply has nothing left to compare.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SourceParityTest {

    private val wanted = System.getenv("PARITY").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    @Test
    fun `plugins behave like the sources they replace`() = runBlocking {
        assumeTrue("set PARITY to run", wanted.isNotEmpty())
        val entries = BundledIndex.entries.filter { e ->
            "all" in wanted || e.id in wanted || e.sourceId in wanted
        }
        assumeTrue("no bundled plugin matches PARITY=$wanted", entries.isNotEmpty())

        val failures = mutableListOf<String>()
        for (e in entries) {
            val legacy = e.sourceId ?: continue
            val kotlin = SourceManager.getSource(legacy)
            if (kotlin == null || kotlin is JsPluginSource) {
                println("[$legacy] no Kotlin source left to compare against; skipped")
                continue
            }
            val plugin = JsPluginSource(
                InstalledPlugin(e.id, e.name, e.version, e.site, e.lang ?: "en", null, null, legacy),
                e.script,
                LiveHttp,
            )
            val diffs = Comparison(legacy).run(kotlin, plugin)
            if (diffs.isEmpty()) println("[$legacy] identical")
            else failures += diffs
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n", "parity differences:\n"))
    }

    private class Comparison(val tag: String) {
        val diffs = mutableListOf<String>()

        fun check(what: String, kotlin: Any?, plugin: Any?) {
            if (kotlin != plugin) diffs += "[$tag] $what\n    kotlin: ${show(kotlin)}\n    plugin: ${show(plugin)}"
        }

        private fun show(v: Any?): String = v.toString().replace("\n", "⏎").let {
            if (it.length > 300) it.take(300) + "… (${it.length} chars)" else it
        }

        suspend fun run(kotlin: Source, plugin: Source): List<String> {
            check("name", kotlin.name, plugin.name)
            check("baseUrl", kotlin.baseUrl, plugin.baseUrl)

            if (kotlin is BrowseSource) {
                check("canBrowse", kotlin.canBrowse, (plugin as BrowseSource).canBrowse)
                check("filters", kotlin.filters, plugin.filters)
            }

            val popular = kotlin.getPopular(1)
            println("[$tag] popular: ${popular.size} novels")
            if (popular.isEmpty()) diffs += "[$tag] Kotlin popular list is empty (site down or blocked?)"
            comparePreviews("popular(1)", popular, plugin.getPopular(1))
            comparePreviews("popular(2)", kotlin.getPopular(2), plugin.getPopular(2))

            val query = System.getenv("PARITY_QUERY")?.ifBlank { null }
                ?: popular.firstOrNull()?.title?.split(' ')?.firstOrNull { it.length > 3 }
                ?: "the"
            comparePreviews("search('$query')", kotlin.search(query), plugin.search(query))

            val extra = System.getenv("PARITY_NOVEL")?.ifBlank { null }
            for (url in popular.take(2).map { SourceManager.constructNovelUrl(it.id) } + listOfNotNull(extra)) {
                println("[$tag] details: $url")
                val k = kotlin.getNovelDetails(url)
                val p = plugin.getNovelDetails(url)
                compareNovel(url, k, p)
                val chapters = k?.chapters.orEmpty()
                for (ch in listOfNotNull(chapters.firstOrNull(), chapters.lastOrNull()).distinct()) {
                    check("content ${ch.url}", kotlin.getChapterContent(ch.url), plugin.getChapterContent(ch.url))
                }
            }
            return diffs
        }

        fun comparePreviews(what: String, k: List<NovelPreview>, p: List<NovelPreview>) {
            check("$what ids", k.map { it.id }, p.map { it.id })
            val byId = p.associateBy { it.id }
            for (kp in k) {
                val pp = byId[kp.id] ?: continue
                check("$what ${kp.id} title", kp.title, pp.title)
                check("$what ${kp.id} author", kp.author, pp.author)
                check("$what ${kp.id} cover", kp.coverUrl, pp.coverUrl)
                check("$what ${kp.id} description", kp.description, pp.description)
                check("$what ${kp.id} source", kp.source, pp.source)
            }
        }

        fun compareNovel(url: String, k: Novel?, p: Novel?) {
            if (k == null || p == null) {
                check("details $url present", k != null, p != null)
                return
            }
            check("details id", k.id, p.id)
            check("details title", k.title, p.title)
            check("details author", k.author, p.author)
            check("details cover", k.coverUrl, p.coverUrl)
            check("details description", k.description, p.description)
            check("details status", k.status, p.status)
            check("details source", k.source, p.source)
            check("chapter count", k.chapters.size, p.chapters.size)
            val firstDiff = k.chapters.indices.firstOrNull { it >= p.chapters.size || k.chapters[it] != p.chapters[it] }
            if (firstDiff != null) check("chapter #$firstDiff", k.chapters[firstDiff], p.chapters.getOrNull(firstDiff))
        }
    }

    /**
     * Plain OkHttp transport (no Cloudflare WebView under Robolectric). The
     * plugin's request follows the Kotlin source's immediately, which some
     * sites answer with 429; back off and retry so that isn't reported as a
     * difference.
     */
    private object LiveHttp : PluginHttp {
        override fun execute(request: PluginRequest): PluginResponse {
            var response = once(request)
            var wait = 3_000L
            while (response.status == 429 && wait <= 24_000L) {
                Thread.sleep(wait)
                wait *= 2
                response = once(request)
            }
            return response
        }

        private fun once(request: PluginRequest): PluginResponse {
            val builder = Request.Builder().url(request.url)
            request.headers.forEach { (k, v) -> builder.header(k, v) }
            if (request.headers.keys.none { it.equals("User-Agent", true) }) builder.header("User-Agent", DEFAULT_USER_AGENT)
            val body = if (request.method == "GET" || request.method == "HEAD") null else {
                val type = request.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.toMediaTypeOrNull()
                (request.body ?: "").toRequestBody(type)
            }
            builder.method(request.method, body)
            SourceHttp.client.newCall(builder.build()).execute().use { r ->
                if (!r.isSuccessful) println("[http] plugin got ${r.code} for ${request.method} ${request.url}")
                return PluginResponse(
                    r.code, r.request.url.toString(), r.body?.string() ?: "",
                    r.headers.names().associate { it.lowercase() to r.headers.values(it).joinToString(", ") },
                )
            }
        }
    }
}
