package com.abhinavxt.novelforge.data.source.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

class JsPluginTest {

    private val pages = mapOf(
        "https://novels.example.com/popular?page=1" to """
            <div class="novel-item"><a href="/novel/one"><img src="/c/1.jpg"></a><h3>One</h3></div>
            <div class="novel-item"><a href="/novel/two"><img src="/c/2.jpg"></a><h3>Two</h3></div>
        """,
        "https://novels.example.com/search?q=dragon%20king&page=1" to """
            <div class="novel-item"><a href="/novel/dk"></a><h3>Dragon King</h3></div>
        """,
        "https://novels.example.com/novel/one" to """
            <h1 class="title">One</h1><div class="author">A. Writer</div>
            <div class="summary">A story.</div><div class="status">Ongoing</div>
            <ul class="chapters"><li><a href="/novel/one/1">Ch 1</a></li><li><a href="/novel/one/2">Ch 2</a></li></ul>
        """,
        "https://novels.example.com/novel/one/1" to """
            <div id="chapter-content"><p>First.</p><div class="ads">BUY</div><p>Second.</p></div>
        """,
    )

    private val requests = mutableListOf<PluginRequest>()

    private val http = PluginHttp { req ->
        requests += req
        val body = pages[req.url] ?: return@PluginHttp PluginResponse(404, req.url, "nope", emptyMap())
        PluginResponse(200, req.url, body, mapOf("content-type" to "text/html"))
    }

    private fun exampleScript(): String {
        // Unit tests run with the module directory as the working directory.
        val file = listOf(File("../docs/plugins/example-plugin.js"), File("docs/plugins/example-plugin.js"))
            .first { it.exists() }
        return file.readText()
    }

    @Test
    fun `example plugin loads and reports its metadata`() {
        val plugin = JsPlugin.load(exampleScript(), "example.js", http)
        assertEquals("example-novels", plugin.info.id)
        assertEquals("1.0.0", plugin.info.version)
        assertEquals("https://novels.example.com", plugin.info.site)
    }

    @Test
    fun `example plugin lists, searches, parses novels and chapters`() {
        val plugin = JsPlugin.load(exampleScript(), "example.js", http)

        @Suppress("UNCHECKED_CAST")
        val popular = plugin.call("popularNovels", 1) as List<Map<String, Any?>>
        assertEquals(listOf("One", "Two"), popular.map { it["name"] })
        assertEquals("/novel/one", popular[0]["path"])
        assertEquals("https://novels.example.com/c/1.jpg", popular[0]["cover"])

        @Suppress("UNCHECKED_CAST")
        val found = plugin.call("searchNovels", "dragon king", 1) as List<Map<String, Any?>>
        assertEquals("Dragon King", found.single()["name"])

        @Suppress("UNCHECKED_CAST")
        val novel = plugin.call("parseNovel", "https://novels.example.com/novel/one") as Map<String, Any?>
        assertEquals("A. Writer", novel["author"])
        assertEquals("Ongoing", novel["status"])
        @Suppress("UNCHECKED_CAST")
        val chapters = novel["chapters"] as List<Map<String, Any?>>
        assertEquals("https://novels.example.com/novel/one/2", chapters[1]["url"])

        val html = plugin.call("parseChapter", "https://novels.example.com/novel/one/1") as String
        assertTrue(html.contains("First.") && html.contains("Second."))
        assertTrue("ads should be removed", !html.contains("BUY"))
    }

    @Test
    fun `errors thrown by the plugin surface as PluginException`() {
        val plugin = JsPlugin.load(exampleScript(), "example.js", http)
        try {
            plugin.call("parseChapter", "https://novels.example.com/missing")
            fail("expected PluginException")
        } catch (e: PluginException) {
            assertTrue(e.message!!, e.message!!.contains("HTTP 404"))
        }
    }

    @Test
    fun `missing exports are rejected at load time`() {
        val script = """module.exports = { id: "x", name: "X", version: "1", site: "https://x.test",
            popularNovels: function () { return []; } };"""
        try {
            JsPlugin.load(script, "bad.js", http)
            fail("expected PluginException")
        } catch (e: PluginException) {
            assertTrue(e.message!!, e.message!!.contains("searchNovels"))
        }
    }

    @Test
    fun `java is unreachable from plugins`() {
        val plugin = JsPlugin.load(
            minimal("""
                return [typeof Packages, typeof java, typeof JavaImporter, typeof getClass];
            """),
            "probe.js", http,
        )
        assertEquals(listOf("undefined", "undefined", "undefined", "undefined"), plugin.call("popularNovels", 1))
    }

    @Test
    fun `infinite loops time out and cannot be caught by the plugin`() {
        val plugin = JsPlugin.load(
            minimal("try { while (true) {} } catch (e) { return 'swallowed'; }"),
            "loop.js", http, callTimeoutMs = 300,
        )
        val start = System.currentTimeMillis()
        try {
            plugin.call("popularNovels", 1)
            fail("expected timeout")
        } catch (e: PluginException) {
            assertTrue(e.message!!, e.message!!.contains("timed out"))
        }
        assertTrue(System.currentTimeMillis() - start < 5_000)
    }

    @Test
    fun `interrupting the thread cancels a running script`() {
        val plugin = JsPlugin.load(minimal("while (true) {}"), "spin.js", http)
        var thrown: Throwable? = null
        val t = Thread {
            try {
                plugin.call("popularNovels", 1)
            } catch (e: Throwable) {
                thrown = e
            }
        }
        t.start()
        Thread.sleep(200)
        t.interrupt()
        t.join(5_000)
        assertTrue("script kept running after interrupt", !t.isAlive)
        assertTrue("got $thrown", thrown is InterruptedException)
    }

    @Test
    fun `fetch sends method, headers and form bodies and rejects non-http urls`() {
        val plugin = JsPlugin.load(
            minimal("""
                var r = fetch("https://novels.example.com/novel/one",
                    { method: "POST", headers: { "X-Test": "1" }, body: { q: "a b", n: 2 } });
                var blocked;
                try { fetch("file:///etc/passwd"); blocked = "no"; } catch (e) { blocked = "yes"; }
                return [r.status, r.ok, r.headers.get("Content-Type"), blocked];
            """),
            "fetch.js", http,
        )
        assertEquals(listOf(200.0, true, "text/html", "yes"), plugin.call("popularNovels", 1))
        val req = requests.single()
        assertEquals("POST", req.method)
        assertEquals("1", req.headers["X-Test"])
        assertEquals("application/x-www-form-urlencoded", req.headers["Content-Type"])
        assertEquals("q=a%20b&n=2", req.body)
    }

    @Test
    fun `network errors are catchable js errors`() {
        val failing = PluginHttp { throw IOException("boom") }
        val plugin = JsPlugin.load(
            minimal("try { fetch('https://x.test'); return 'no'; } catch (e) { return e.name + ': ' + e.message; }"),
            "net.js", failing,
        )
        assertEquals("NetworkError: fetch https://x.test: boom", plugin.call("popularNovels", 1))
    }

    @Test
    fun `missing html elements come back as null`() {
        val plugin = JsPlugin.load(
            minimal("return parseHtml('<p>x</p>').selectFirst('.nope');"),
            "null.js", http,
        )
        assertNull(plugin.call("popularNovels", 1))
    }

    @Test
    fun `es6 syntax promised in PLUGINS md works`() {
        val plugin = JsPlugin.load(
            minimal("""
                const xs = [1, 2, 3];
                let doubled = xs.map(x => x * 2);
                return `${'$'}{doubled.join(",")}|${'$'}{parseHtml("<a href='/p'>l</a>", "https://s.test/").selectFirst("a").absUrl("href")}`;
            """),
            "es6.js", http,
        )
        assertEquals("2,4,6|https://s.test/p", plugin.call("popularNovels", 1))
    }

    private fun minimal(popularBody: String) = """
        module.exports = {
          id: "t", name: "T", version: "1", site: "https://x.test",
          popularNovels: function (page) { $popularBody },
          searchNovels: function () { return []; },
          parseNovel: function () { return null; },
          parseChapter: function () { return ""; }
        };
    """.trimIndent()
}
