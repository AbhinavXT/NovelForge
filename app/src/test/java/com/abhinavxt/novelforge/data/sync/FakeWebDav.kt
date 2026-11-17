package com.abhinavxt.novelforge.data.sync

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * In-memory WebDAV server as an OkHttp interceptor (no sockets). Keys of [files] and [collections]
 * are decoded paths from the host root, e.g. "dav/NovelForge/sync.json". [rootPath] is created as a
 * collection; point clients at [base].
 */
class FakeWebDav(val rootPath: String = "dav") {
    val files = LinkedHashMap<String, ByteArray>()
    val collections = LinkedHashSet<String>()
    val etags = HashMap<String, String>()
    val modified = HashMap<String, Long>()

    /** Server clock; also used for getlastmodified and the Date header. */
    var clock: () -> Long = System::currentTimeMillis
    var sendDateHeader = true
    var moveSupported = true
    var requireAuth = false
    val requestLog = ArrayList<String>()

    private var counter = 0
    val base: HttpUrl = "https://dav.example/$rootPath/".toHttpUrl()

    init {
        collections += rootPath
    }

    val interceptor = Interceptor { chain -> respond(chain.request()) }

    fun client(extra: List<Interceptor> = emptyList()): OkHttpClient =
        OkHttpClient.Builder().apply { extra.forEach { addInterceptor(it) } }.addInterceptor(interceptor).build()

    /** Convenience for tests: store a file under the root (creating parents) as another device would. */
    fun seed(path: String, bytes: ByteArray) {
        val full = "$rootPath/${path.trim('/')}"
        var parent = full.substringBeforeLast('/', "")
        while (parent.isNotEmpty()) {
            collections += parent
            parent = parent.substringBeforeLast('/', "")
        }
        write(full, bytes)
    }

    private fun write(key: String, bytes: ByteArray) {
        files[key] = bytes
        etags[key] = "\"e${++counter}\""
        modified[key] = clock()
    }

    private fun keyOf(url: HttpUrl) = url.pathSegments.filter { it.isNotEmpty() }.joinToString("/")
    private fun parent(key: String) = key.substringBeforeLast('/', "")

    private fun reply(req: Request, code: Int, body: String = "", vararg headers: Pair<String, String>): Response {
        val b = Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("fake")
            .body(body.toResponseBody("application/xml".toMediaType()))
        if (sendDateHeader) b.header("Date", http(clock()))
        headers.forEach { (k, v) -> b.header(k, v) }
        return b.build()
    }

    private fun http(ms: Long) =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC))

    private fun href(key: String, dir: Boolean): String {
        val b = "https://dav.example/".toHttpUrl().newBuilder()
        key.split('/').forEach { b.addPathSegment(it) }
        if (dir) b.addPathSegment("")
        return b.build().encodedPath
    }

    private fun respond(req: Request): Response {
        val key = keyOf(req.url)
        requestLog += "${req.method} $key"
        if (requireAuth && req.header("Authorization") == null) return reply(req, 401)
        return when (req.method) {
            "PROPFIND" -> propfind(req, key)
            "GET" -> {
                val data = files[key] ?: return reply(req, 404)
                val etag = etags[key]!!
                if (req.header("If-None-Match") == etag) {
                    reply(req, 304, "", "ETag" to etag)
                } else {
                    val b = Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .header("ETag", etag)
                        .body(data.toResponseBody("application/octet-stream".toMediaType()))
                    if (sendDateHeader) b.header("Date", http(clock()))
                    b.build()
                }
            }
            "PUT" -> {
                if (parent(key) !in collections) return reply(req, 409)
                if (key in collections) return reply(req, 405)
                val existed = key in files
                val buf = Buffer().also { req.body?.writeTo(it) }
                write(key, buf.readByteArray())
                reply(req, if (existed) 204 else 201)
            }
            "MKCOL" -> when {
                key in collections || key in files -> reply(req, 405)
                parent(key) !in collections -> reply(req, 409)
                else -> {
                    collections += key
                    reply(req, 201)
                }
            }
            "DELETE" -> {
                val hit = key in files || key in collections
                files.keys.removeAll { it == key || it.startsWith("$key/") }
                collections.removeAll { it == key || it.startsWith("$key/") }
                reply(req, if (hit) 204 else 404)
            }
            "MOVE" -> move(req, key)
            else -> reply(req, 405)
        }
    }

    private fun move(req: Request, key: String): Response {
        if (!moveSupported) return reply(req, 501)
        val dest = req.header("Destination")?.toHttpUrl()?.let { keyOf(it) } ?: return reply(req, 400)
        val data = files[key] ?: return reply(req, 404)
        if (parent(dest) !in collections) return reply(req, 409)
        val exists = dest in files
        if (exists && req.header("Overwrite") == "F") return reply(req, 412)
        files.remove(key)
        etags.remove(key)
        modified.remove(key)
        write(dest, data)
        return reply(req, if (exists) 204 else 201)
    }

    private fun propfind(req: Request, key: String): Response {
        val isDir = key in collections
        if (!isDir && key !in files) return reply(req, 404)
        val sb = StringBuilder("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">""")
        fun entry(k: String, dir: Boolean) {
            sb.append("<d:response><d:href>").append(href(k, dir)).append("</d:href><d:propstat><d:prop>")
            if (dir) {
                sb.append("<d:resourcetype><d:collection/></d:resourcetype>")
            } else {
                sb.append("<d:resourcetype/><d:getetag>").append(etags[k]).append("</d:getetag>")
                sb.append("<d:getlastmodified>").append(http(modified[k]!!)).append("</d:getlastmodified>")
                sb.append("<d:getcontentlength>").append(files[k]!!.size).append("</d:getcontentlength>")
            }
            sb.append("</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
        }
        entry(key, isDir)
        if (isDir && req.header("Depth") != "0") {
            collections.filter { it != key && parent(it) == key }.forEach { entry(it, true) }
            files.keys.filter { parent(it) == key }.forEach { entry(it, false) }
        }
        sb.append("</d:multistatus>")
        return reply(req, 207, sb.toString())
    }
}
