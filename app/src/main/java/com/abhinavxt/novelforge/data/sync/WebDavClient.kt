package com.abhinavxt.novelforge.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** One child of a WebDAV collection. [etag] is kept exactly as the server sent it (quotes included). */
data class DavEntry(
    val name: String,
    val isCollection: Boolean,
    val etag: String?,
    val lastModified: Long?,
    val size: Long?,
)

sealed class DavGet {
    class Body(val bytes: ByteArray, val etag: String?) : DavGet()
    object NotModified : DavGet()

    /** The body is larger than the cap the caller gave; it was not read in full. */
    object TooLarge : DavGet()
    object Missing : DavGet()
}

/** A WebDAV server answered with an unexpected status. Network failures stay IOException. */
class WebDavException(val status: Int, message: String) : Exception(message)

/** The PROPFIND answer is never read past this size. */
const val MAX_LISTING_BYTES = 5L * 1024 * 1024

/**
 * Minimal WebDAV client. Authentication belongs to the [OkHttpClient] passed in. Paths are relative
 * to [base] and may contain any characters; they are encoded segment by segment.
 */
class WebDavClient(
    private val base: HttpUrl,
    private val client: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    var serverDateOffsetMs: Long? = null
        private set

    suspend fun list(path: String): List<DavEntry> = withContext(Dispatchers.IO) {
        val url = urlFor(path, trailingSlash = true)
        val req = Request.Builder().url(url)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML))
            .header("Depth", "1")
            .build()
        exec(req).use { resp ->
            if (resp.code == 404) return@use emptyList()
            ensure(resp, "PROPFIND")
            val body = resp.body
            // A hostile server could answer with an endless listing: read at most MAX_LISTING_BYTES.
            val text = if (body == null) "" else {
                if (body.contentLength() > MAX_LISTING_BYTES) throw WebDavException(413, "listing too large")
                val buf = Buffer()
                val src = body.source()
                while (buf.size <= MAX_LISTING_BYTES) if (src.read(buf, 8192) < 0) break
                if (buf.size > MAX_LISTING_BYTES) throw WebDavException(413, "listing too large")
                buf.readUtf8()
            }
            parseMultistatus(text, segments(url))
        }
    }

    /** [maxBytes] caps the body while it is read; a larger one gives [DavGet.TooLarge]. */
    suspend fun get(path: String, ifNoneMatch: String?, maxBytes: Long? = null): DavGet = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(urlFor(path)).get()
        if (ifNoneMatch != null) b.header("If-None-Match", ifNoneMatch)
        exec(b.build()).use { resp ->
            when {
                resp.code == 304 -> DavGet.NotModified
                resp.code == 404 -> DavGet.Missing
                else -> {
                    ensure(resp, "GET")
                    val body = resp.body
                    when {
                        body == null -> DavGet.Body(ByteArray(0), resp.header("ETag"))
                        maxBytes == null -> DavGet.Body(body.bytes(), resp.header("ETag"))
                        body.contentLength() > maxBytes -> DavGet.TooLarge
                        else -> {
                            // The header can lie or be absent: count while reading.
                            val buf = Buffer()
                            val src = body.source()
                            while (buf.size <= maxBytes) if (src.read(buf, 8192) < 0) break
                            if (buf.size > maxBytes) DavGet.TooLarge
                            else DavGet.Body(buf.readByteArray(), resp.header("ETag"))
                        }
                    }
                }
            }
        }
    }

    suspend fun put(path: String, bytes: ByteArray, type: String) = withContext(Dispatchers.IO) {
        val body: RequestBody = bytes.toRequestBody(type.toMediaTypeOrNull())
        exec(Request.Builder().url(urlFor(path)).put(body).build()).use { ensure(it, "PUT") }
    }

    suspend fun move(from: String, to: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(urlFor(from))
            .method("MOVE", null)
            .header("Destination", urlFor(to).toString())
            .header("Overwrite", "T")
            .build()
        exec(req).use { ensure(it, "MOVE") }
    }

    suspend fun mkcol(path: String) = withContext(Dispatchers.IO) {
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return@withContext
        val code = mkcolOnce(parts)
        if (code == 409) {
            // Parent missing: create each level from the top.
            for (i in 1..parts.size) {
                val c = mkcolOnce(parts.subList(0, i))
                if (c !in 200..299 && c != 405) throw WebDavException(c, "MKCOL failed: $c")
            }
        } else if (code !in 200..299 && code != 405) {
            throw WebDavException(code, "MKCOL failed: $code")
        }
    }

    suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        exec(Request.Builder().url(urlFor(path)).delete().build()).use { resp ->
            if (resp.code != 404) ensure(resp, "DELETE")
        }
    }

    private fun mkcolOnce(parts: List<String>): Int {
        val req = Request.Builder().url(urlFor(parts.joinToString("/"))).method("MKCOL", null).build()
        return exec(req).use { it.code }
    }

    private fun exec(req: Request): Response {
        val resp = client.newCall(req).execute()
        resp.headers.getDate("Date")?.let { serverDateOffsetMs = it.time - now() }
        return resp
    }

    private fun ensure(resp: Response, verb: String) {
        if (resp.code in 200..299) return
        throw WebDavException(resp.code, "$verb failed: ${resp.code} ${resp.message}")
    }

    private fun urlFor(path: String, trailingSlash: Boolean = false): HttpUrl {
        val b = base.newBuilder()
        // Drop a trailing empty segment so "base/" and "base" behave the same.
        val existing = base.pathSegments
        if (existing.isNotEmpty() && existing.last().isEmpty()) b.removePathSegment(existing.size - 1)
        for (s in path.split('/')) if (s.isNotEmpty()) b.addPathSegment(s)
        if (trailingSlash) b.addPathSegment("")
        return b.build()
    }

    private fun segments(url: HttpUrl) = url.pathSegments.filter { it.isNotEmpty() }

    private fun parseMultistatus(xml: String, selfSegments: List<String>): List<DavEntry> {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val out = ArrayList<DavEntry>()
        for (resp in doc.getAllElements()) {
            if (resp.local() != "response") continue
            val href = resp.firstChild("href")?.text()?.trim() ?: continue
            val segs = hrefSegments(href) ?: continue
            if (segs == selfSegments || segs.isEmpty()) continue
            // An href like %2F.. decodes to a name that would climb out of the folder: never trust it.
            val name = segs.last()
            if ('/' in name || '\\' in name || name == "." || name == "..") continue
            val props = resp.descendants("propstat")
                .filter { ps -> ps.firstChild("status")?.text()?.contains(" 200") != false }
                .mapNotNull { it.firstChild("prop") }
            fun prop(name: String) =
                props.firstNotNullOfOrNull { p -> p.firstChild(name)?.text()?.trim()?.ifEmpty { null } }
            val isCol = props.any { p -> p.firstChild("resourcetype")?.firstChild("collection") != null }
            out += DavEntry(
                name = name,
                isCollection = isCol,
                etag = prop("getetag"),
                lastModified = prop("getlastmodified")?.let { parseDate(it) },
                size = prop("getcontentlength")?.toLongOrNull(),
            )
        }
        return out
    }

    /** Decoded, non-empty path segments of an absolute URL, absolute path or relative href. */
    private fun hrefSegments(href: String): List<String>? {
        val url = if (href.startsWith("http://", true) || href.startsWith("https://", true)) {
            href.toHttpUrlOrNull()
        } else {
            ("http://localhost/" + href.trimStart('/')).toHttpUrlOrNull()
        } ?: return null
        return segments(url)
    }

    private fun parseDate(s: String): Long? = try {
        ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }

    private fun Element.local(): String = tagName().substringAfterLast(':').lowercase()
    private fun Element.firstChild(name: String): Element? = children().firstOrNull { it.local() == name }
    private fun Element.descendants(name: String): List<Element> =
        getAllElements().filter { it !== this && it.local() == name }

    private companion object {
        val XML = "application/xml; charset=utf-8".toMediaTypeOrNull()
        const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getetag/><d:getlastmodified/><d:getcontentlength/></d:prop></d:propfind>"""
    }
}
