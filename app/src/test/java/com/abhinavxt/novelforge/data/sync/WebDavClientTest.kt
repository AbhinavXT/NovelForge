package com.abhinavxt.novelforge.data.sync

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WebDavClientTest {
    private val dav = FakeWebDav()
    private val client = WebDavClient(dav.base, dav.client())

    private fun <T> run(block: suspend () -> T): T = runBlocking { block() }

    private fun fixed(xml: String) = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(207).message("Multi-Status")
            .body(xml.toResponseBody("application/xml".toMediaType())).build()
    }).build()

    @Test fun putGetRoundTripAndEtagChanges() = run {
        client.mkcol("NovelForge")
        client.put("NovelForge/a.txt", "one".toByteArray(), "text/plain")
        val first = client.get("NovelForge/a.txt", null) as DavGet.Body
        assertEquals("one", String(first.bytes))
        client.put("NovelForge/a.txt", "two".toByteArray(), "text/plain")
        val second = client.get("NovelForge/a.txt", null) as DavGet.Body
        assertNotEquals(first.etag, second.etag)
        assertEquals(DavGet.NotModified, client.get("NovelForge/a.txt", second.etag))
        assertTrue(client.get("NovelForge/a.txt", first.etag) is DavGet.Body)
    }

    @Test fun propfindAnswerLargerThan5MbIsRefused() {
        val huge = "<d:multistatus xmlns:d=\"DAV:\">" + " ".repeat((MAX_LISTING_BYTES + 1024).toInt()) + "</d:multistatus>"
        val c = WebDavClient("https://nas.local/dav/".toHttpUrl(), fixed(huge))
        try {
            run { c.list("NovelForge/devices") }
            fail("expected WebDavException")
        } catch (e: WebDavException) {
            assertEquals(413, e.status)
        }
    }

    @Test fun getMissingAndDelete() = run {
        assertEquals(DavGet.Missing, client.get("nope.txt", null))
        client.delete("nope.txt")
        client.put("x.txt", byteArrayOf(1), "application/octet-stream")
        client.delete("x.txt")
        assertEquals(DavGet.Missing, client.get("x.txt", null))
    }

    @Test fun listReturnsChildrenOnly() = run {
        client.mkcol("NovelForge/devices")
        client.put("NovelForge/devices/d1.json.gz", ByteArray(5), "application/gzip")
        client.put("NovelForge/sync.json", ByteArray(2), "application/json")
        val entries = client.list("NovelForge").sortedBy { it.name }
        assertEquals(listOf("devices", "sync.json"), entries.map { it.name })
        assertTrue(entries[0].isCollection)
        assertFalse(entries[1].isCollection)
        assertEquals(2L, entries[1].size)
        assertNotNull(entries[1].etag)
        assertNotNull(entries[1].lastModified)
        assertEquals(listOf("d1.json.gz"), client.list("NovelForge/devices/").map { it.name })
    }

    @Test fun listMissingFolderIsEmpty() = run { assertTrue(client.list("ghost").isEmpty()) }

    @Test fun mkcolExistingIsSuccessAndCreatesParents() = run {
        client.mkcol("a/b/c")
        assertTrue("dav/a/b/c" in dav.collections && "dav/a/b" in dav.collections)
        client.mkcol("a/b/c")
        client.mkcol("a")
    }

    @Test fun moveOverwrites() = run {
        client.put("t.tmp", "new".toByteArray(), "text/plain")
        client.put("f.json", "old".toByteArray(), "text/plain")
        client.move("t.tmp", "f.json")
        assertEquals("new", String((client.get("f.json", null) as DavGet.Body).bytes))
        assertEquals(DavGet.Missing, client.get("t.tmp", null))
    }

    @Test fun moveUnsupportedThrows501() = run {
        dav.moveSupported = false
        client.put("t.tmp", ByteArray(1), "text/plain")
        try {
            client.move("t.tmp", "f")
            fail()
        } catch (e: WebDavException) {
            assertEquals(501, e.status)
        }
    }

    @Test fun moveMissingSourceThrows404() = run {
        try {
            client.move("zzz", "f")
            fail()
        } catch (e: WebDavException) {
            assertEquals(404, e.status)
        }
    }

    @Test fun unauthorizedThrows401ForEveryVerb() = run {
        dav.requireAuth = true
        val ops: List<suspend () -> Any?> = listOf(
            { client.list("") }, { client.get("a", null) }, { client.put("a", ByteArray(1), "x/y") },
            { client.move("a", "b") }, { client.mkcol("a") }, { client.delete("a") },
        )
        for (op in ops) {
            try {
                op()
                fail()
            } catch (e: WebDavException) {
                assertEquals(401, e.status)
            }
        }
    }

    @Test fun authHeaderFromClientIsHonoured() = run {
        dav.requireAuth = true
        val auth = Interceptor { it.proceed(it.request().newBuilder().header("Authorization", "Basic x").build()) }
        val c = WebDavClient(dav.base, dav.client(listOf(auth)))
        c.put("ok.txt", ByteArray(1), "text/plain")
        assertTrue("dav/ok.txt" in dav.files)
    }

    @Test fun putIntoMissingParentThrows409() = run {
        try {
            client.put("no/such/f", ByteArray(1), "x/y")
            fail()
        } catch (e: WebDavException) {
            assertEquals(409, e.status)
            assertFalse(e.message!!.contains("example"))
        }
    }

    @Test fun pathEncoding() = run {
        val name = "My Book #1 é中.json"
        client.mkcol("Sub Dir")
        client.put("Sub Dir/$name", "x".toByteArray(), "text/plain")
        assertTrue("dav/Sub Dir/$name" in dav.files)
        assertEquals(listOf(name), client.list("Sub Dir").map { it.name })
        client.move("Sub Dir/$name", "Sub Dir/b#2 c")
        assertTrue("dav/Sub Dir/b#2 c" in dav.files)
    }

    @Test fun baseWithoutTrailingSlashWorks() = run {
        val c = WebDavClient("https://dav.example/dav".toHttpUrl(), dav.client())
        c.put("f.txt", ByteArray(1), "text/plain")
        assertTrue("dav/f.txt" in dav.files)
        assertEquals(listOf("f.txt"), c.list("").map { it.name })
    }

    @Test fun serverDateOffset() = run {
        val local = 1_000_000_000_000L
        val c = WebDavClient(dav.base, dav.client(), now = { local })
        assertNull(c.serverDateOffsetMs)
        dav.clock = { local + 90_000 }
        c.get("x", null)
        assertEquals(90_000L, c.serverDateOffsetMs)
        dav.clock = { local - 5_000 }
        c.get("x", null)
        assertEquals(-5_000L, c.serverDateOffsetMs)
        dav.sendDateHeader = false
        c.get("x", null)
        assertEquals(-5_000L, c.serverDateOffsetMs)
    }

    @Test fun serverDateOffsetNullWithoutHeader() = run {
        dav.sendDateHeader = false
        client.get("x", null)
        assertNull(client.serverDateOffsetMs)
    }

    private val nextcloud = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:oc="http://owncloud.org/ns">
 <d:response><d:href>/remote.php/dav/files/u/Books/</d:href><d:propstat><d:prop>
   <d:getlastmodified>Mon, 16 Nov 2026 10:00:00 GMT</d:getlastmodified><d:resourcetype><d:collection/></d:resourcetype>
   <d:getetag>&quot;aaa&quot;</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/files/u/Books/My%20Novel%20%C3%A9.epub</d:href><d:propstat><d:prop>
   <d:getlastmodified>Mon, 16 Nov 2026 10:05:00 GMT</d:getlastmodified><d:getcontentlength>1234</d:getcontentlength>
   <d:resourcetype/><d:getetag>&quot;bbb222&quot;</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
   <d:propstat><d:prop><d:foo/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/files/u/Books/Sub/</d:href><d:propstat><d:prop>
   <d:resourcetype><d:collection/></d:resourcetype><d:getetag>"ccc"</d:getetag></d:prop>
   <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
</d:multistatus>"""

    private val apache = """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:" xmlns:ns0="DAV:">
<D:response xmlns:lp1="DAV:" xmlns:lp2="http://apache.org/dav/props/">
<D:href>https://nas.local/dav/Books/</D:href>
<D:propstat><D:prop><lp1:resourcetype><D:collection/></lp1:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
<D:response xmlns:lp1="DAV:" xmlns:lp2="http://apache.org/dav/props/">
<D:href>https://nas.local/dav/Books/a%23b.json</D:href>
<D:propstat><D:prop><lp1:resourcetype/><lp1:getcontentlength>42</lp1:getcontentlength>
<lp1:getlastmodified>Tue, 17 Nov 2026 08:30:15 GMT</lp1:getlastmodified><lp1:getetag>"2a-5f"</lp1:getetag>
</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
</D:multistatus>"""

    @Test fun parsesNextcloudSample() = run {
        val c = WebDavClient("https://cloud.example/remote.php/dav/files/u/".toHttpUrl(), fixed(nextcloud))
        val e = c.list("Books")
        assertEquals(listOf("My Novel é.epub", "Sub"), e.map { it.name })
        assertFalse(e[0].isCollection)
        assertEquals("\"bbb222\"", e[0].etag)
        assertEquals(1234L, e[0].size)
        assertEquals(java.time.Instant.parse("2026-11-16T10:05:00Z").toEpochMilli(), e[0].lastModified)
        assertTrue(e[1].isCollection)
        assertEquals("\"ccc\"", e[1].etag)
        assertNull(e[1].lastModified)
    }

    @Test fun parsesApacheSample() = run {
        val c = WebDavClient("https://nas.local/dav/".toHttpUrl(), fixed(apache))
        val e = c.list("Books")
        assertEquals(1, e.size)
        assertEquals("a#b.json", e[0].name)
        assertFalse(e[0].isCollection)
        assertEquals("\"2a-5f\"", e[0].etag)
        assertEquals(42L, e[0].size)
        assertEquals(java.time.Instant.parse("2026-11-17T08:30:15Z").toEpochMilli(), e[0].lastModified)
    }

    @Test fun parsesDefaultNamespace() = run {
        val xml = """<multistatus xmlns="DAV:"><response><href>/dav/F/</href><propstat><prop><resourcetype><collection/></resourcetype></prop><status>HTTP/1.1 200 OK</status></propstat></response>
<response><href>/dav/F/x.json</href><propstat><prop><getetag>"q"</getetag></prop><status>HTTP/1.1 200 OK</status></propstat></response></multistatus>"""
        val e = WebDavClient("https://h/dav/".toHttpUrl(), fixed(xml)).list("F")
        assertEquals(listOf("x.json"), e.map { it.name })
        assertEquals("\"q\"", e[0].etag)
    }

    @Test fun getWithCap_stopsAtTheLimit_andAllowsASmallBody() = run {
        client.put("big.bin", ByteArray(5_000), "application/octet-stream")
        assertEquals(DavGet.TooLarge, client.get("big.bin", null, maxBytes = 4_000))
        val ok = client.get("big.bin", null, maxBytes = 5_000) as DavGet.Body
        assertEquals(5_000, ok.bytes.size)
    }

    @Test fun getWithCap_countsWhileReading_whenTheLengthIsUnknown() = run {
        val chunked = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val body = object : okhttp3.ResponseBody() {
                override fun contentType() = null
                override fun contentLength() = -1L
                override fun source() = okio.Buffer().write(ByteArray(10_000))
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        }).build()
        assertEquals(DavGet.TooLarge, WebDavClient("https://h/dav/".toHttpUrl(), chunked).get("x", null, maxBytes = 4_000))
    }

    @Test fun hrefThatDecodesToParentTraversal_isIgnored() = run {
        val xml = """<multistatus xmlns="DAV:"><response><href>/dav/F/</href><propstat><prop><resourcetype><collection/></resourcetype></prop><status>HTTP/1.1 200 OK</status></propstat></response>
<response><href>/dav/F/%2F..</href><propstat><prop><getetag>"a"</getetag></prop><status>HTTP/1.1 200 OK</status></propstat></response>
<response><href>/dav/F/a%5Cb</href><propstat><prop><getetag>"b"</getetag></prop><status>HTTP/1.1 200 OK</status></propstat></response>
<response><href>/dav/F/ok.json</href><propstat><prop><getetag>"d"</getetag></prop><status>HTTP/1.1 200 OK</status></propstat></response></multistatus>"""
        val e = WebDavClient("https://h/dav/".toHttpUrl(), fixed(xml)).list("F")
        assertEquals(listOf("ok.json"), e.map { it.name })
    }
}
