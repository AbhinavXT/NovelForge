package com.abhinavxt.novelforge.data.source.plugin

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Opt-in check of the bundled plugins against the live sites: for each one,
 * popular page 1 -> details of the first novel -> its first chapter.
 *
 *     LIVE_PLUGINS=all ./gradlew :app:testDebugUnitTest --tests '*LivePluginCheck*'
 *     LIVE_PLUGINS=royalroad,wtrlab ...
 *
 * Sites change and go down, so this is a maintenance tool, not part of the
 * normal test run (Gradle excludes it unless LIVE_PLUGINS is set). It calls
 * the scripts directly, without the Cloudflare solver, so sites that
 * challenge this network show up as failures here but may work on a phone.
 */
class LivePluginCheck {

    private val wanted = System.getenv("LIVE_PLUGINS").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val http = PluginHttp { request ->
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (k, v) -> builder.header(k, v) }
        if (request.headers.keys.none { it.equals("User-Agent", true) }) builder.header("User-Agent", DEFAULT_USER_AGENT)
        val body = if (request.method == "GET" || request.method == "HEAD") null else {
            val type = request.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.toMediaTypeOrNull()
            (request.body ?: "").toRequestBody(type)
        }
        builder.method(request.method, body)
        client.newCall(builder.build()).execute().use { r ->
            PluginResponse(
                r.code, r.request.url.toString(), r.body?.string() ?: "",
                r.headers.names().associate { it.lowercase() to r.headers.values(it).joinToString(", ") },
            )
        }
    }

    @Test
    fun `bundled plugins work against the live sites`() {
        assumeTrue("set LIVE_PLUGINS to run", wanted.isNotEmpty())
        val entries = BundledIndex.entries.filter { "all" in wanted || it.id in wanted || it.sourceId in wanted }
        assumeTrue("no bundled plugin matches LIVE_PLUGINS=$wanted", entries.isNotEmpty())

        val failures = mutableListOf<String>()
        for (e in entries) {
            val line = StringBuilder("[${e.id}] ")
            try {
                val plugin = JsPlugin.load(e.script.readText(), e.script.name, http)
                @Suppress("UNCHECKED_CAST")
                val popular = plugin.call("popularNovels", 1) as? List<Map<String, Any?>> ?: emptyList()
                line.append("popular ${popular.size}")
                val first = popular.firstOrNull() ?: throw AssertionError("popular list is empty")
                val novelUrl = absolute(e.site, (first["url"] ?: first["path"]) as String)

                @Suppress("UNCHECKED_CAST")
                val novel = plugin.call("parseNovel", novelUrl) as? Map<String, Any?>
                    ?: throw AssertionError("no details for $novelUrl")
                @Suppress("UNCHECKED_CAST")
                val chapters = novel["chapters"] as? List<Map<String, Any?>> ?: emptyList()
                line.append(", '${novel["name"]}' ${chapters.size} chapters")
                val ch = chapters.firstOrNull() ?: throw AssertionError("no chapters for $novelUrl")
                val chapterUrl = absolute(e.site, (ch["url"] ?: ch["path"]) as String)

                val html = plugin.call("parseChapter", chapterUrl) as? String
                val text = html?.replace(Regex("<[^>]+>"), "")?.trim().orEmpty()
                line.append(", chapter ${text.length} chars")
                if (text.length < 200) throw AssertionError("chapter text too short at $chapterUrl")
                println("$line  OK")
            } catch (t: Throwable) {
                println("$line  FAILED: ${t.message}")
                failures += "${e.id}: ${t.message}"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n", "live check failures:\n"))
    }

    private fun absolute(site: String, urlOrPath: String): String =
        if (urlOrPath.startsWith("http")) urlOrPath
        else java.net.URI(site.trimEnd('/') + "/").resolve(urlOrPath.removePrefix("/")).toString()
}
