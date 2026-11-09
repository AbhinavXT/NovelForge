package com.abhinavxt.novelforge.data.annas

import android.content.Context
import android.webkit.CookieManager
import com.abhinavxt.novelforge.data.UserFacingException
import com.abhinavxt.novelforge.data.source.SourceHttp
import com.abhinavxt.novelforge.data.source.net.USER_AGENT
import com.abhinavxt.novelforge.data.source.net.WebViewSupport
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Search filters, as the site's own search form names them. An empty value
 * means "any". Values are the site's: `lang=en`, `content=book_fiction`,
 * `sort=newest`.
 */
data class AnnasFilters(
    val language: String = "",
    val content: String = "",
    val sort: String = ""
) {
    companion object {
        val LANGUAGES = listOf(
            "" to "Any language", "en" to "English", "es" to "Spanish", "fr" to "French",
            "de" to "German", "it" to "Italian", "pt" to "Portuguese", "ru" to "Russian",
            "zh" to "Chinese", "ja" to "Japanese",
        )
        val CONTENTS = listOf(
            "" to "Any type", "book_fiction" to "Fiction", "book_nonfiction" to "Non-fiction",
            "book_comic" to "Comics",
        )
        val SORTS = listOf(
            "" to "Most relevant", "newest" to "Newest", "oldest" to "Oldest",
            "largest" to "Largest", "smallest" to "Smallest",
        )
    }
}

/** A failure worth showing the user as-is. */
open class AnnasArchiveException(message: String, cause: Throwable? = null) :
    UserFacingException(message, cause)

/**
 * The site wants a person to complete a check at [url]. Show it in a
 * visible WebView, then hand the cleared page to [AnnasArchiveClient.parseResults].
 */
class AnnasVerificationRequired(val url: String) : AnnasArchiveException(
    "Anna's Archive wants to check that you're a person."
)

/**
 * Searches Anna's Archive and downloads files from it.
 *
 * Searching: `/search` sits behind DDoS-Guard for signed-out visitors. With a
 * member's secret key we sign in (POST /account/, which isn't checked) and
 * the session cookie skips the check. Without one, the page is rendered in
 * [ChallengeWebView], which passes it; the cookies it earns are reused for
 * plain requests until they expire.
 *
 * Downloading: members get a direct link from the fast-download API. Anyone
 * else gets the file from LibGen, which indexes the same files under the
 * same MD5. Either way the bytes are checked against that MD5, so a mirror
 * serving the wrong file (or an HTML error page) never reaches the library.
 */
class AnnasArchiveClient(
    context: Context,
    private val prefs: AnnasArchivePrefs = AnnasArchivePrefs(context)
) {
    private val appContext = context.applicationContext

    /** Redirects are followed by hand so a `?check=1` hop is seen, not looped on. */
    private val pageClient: OkHttpClient = SourceHttp.client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val fileClient: OkHttpClient = SourceHttp.client.newBuilder()
        .readTimeout(2, TimeUnit.MINUTES)
        .build()

    /** (key + mirror) → session cookie, for this process only. */
    @Volatile
    private var session: Pair<String, String>? = null

    val hasSecretKey: Boolean get() = prefs.secretKey.isNotBlank()

    // ── Search ──────────────────────────────────────────────────────────

    /** @param page 1-based. EPUB only, since that's what the importer reads. */
    suspend fun search(
        query: String,
        page: Int,
        filters: AnnasFilters = AnnasFilters()
    ): List<AnnasBook> = withContext(Dispatchers.IO) {
        val preferred = prefs.baseUrl
        val mirrors = listOf(preferred) + AnnasArchivePrefs.KNOWN_MIRRORS.filter { it != preferred }
        var lastError: AnnasArchiveException? = null

        for (base in mirrors) {
            try {
                val books = searchOn(base, query, page, filters)
                if (base != preferred) prefs.baseUrl = base
                return@withContext books
            } catch (e: MirrorDown) {
                Logger.w(TAG, "Mirror $base unusable: ${e.message}")
                lastError = AnnasArchiveException(
                    "Couldn't reach Anna's Archive. Its domains change often: set a " +
                            "working mirror in Anna's Archive settings.", e
                )
            }
        }
        throw lastError ?: AnnasArchiveException("Couldn't reach Anna's Archive.")
    }

    /** The mirror itself is unusable (offline, parked), so the next one may do. */
    private class MirrorDown(message: String, cause: Throwable? = null) : Exception(message, cause)

    private suspend fun searchOn(
        base: String,
        query: String,
        page: Int,
        filters: AnnasFilters
    ): List<AnnasBook> {
        val url = base.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("q", query)
            .addQueryParameter("ext", "epub")
            .apply {
                if (filters.language.isNotEmpty()) addQueryParameter("lang", filters.language)
                if (filters.content.isNotEmpty()) addQueryParameter("content", filters.content)
                if (filters.sort.isNotEmpty()) addQueryParameter("sort", filters.sort)
            }
            .apply { if (page > 1) addQueryParameter("page", page.toString()) }
            .build()
            .toString()

        val html = fetchPassingCheck(base, url)
        return parse(html, url)
    }

    /** Results from a page the person got past the check in a visible WebView. */
    fun parseResults(html: String, url: String): List<AnnasBook> =
        try {
            parse(html, url)
        } catch (e: MirrorDown) {
            throw AnnasArchiveException("That page isn't Anna's Archive any more.", e)
        }

    private fun parse(html: String, url: String): List<AnnasBook> {
        return when (val parsed = AnnasArchiveParser.parseSearch(html, url)) {
            // ext=epub is asked for, but the importer reads nothing else, so
            // a mirror that ignores the filter mustn't offer what can't import.
            is AnnasArchiveParser.Page.Results ->
                parsed.books.filter { it.format.isEmpty() || it.format == "epub" }
            AnnasArchiveParser.Page.Parked -> throw MirrorDown("parked domain")
            AnnasArchiveParser.Page.Challenge -> throw AnnasArchiveException(CHECK_FAILED)
            AnnasArchiveParser.Page.Unrecognized -> throw AnnasArchiveException(
                "Anna's Archive returned a page NovelForge doesn't recognise. " +
                        "The site may have changed."
            )
        }
    }

    private suspend fun fetchPassingCheck(base: String, url: String): String {
        val key = prefs.secretKey
        for (attempt in 0..1) {
            val cookie = buildCookie(base, url, key, relogin = attempt == 1)
            when (val r = get(url, cookie)) {
                is Fetched.Html -> if (!AnnasArchiveParser.isChallenge(r.body)) return r.body
                Fetched.Challenge -> Unit
            }
            // A stale session looks just like being signed out: sign in once more.
            if (key.isBlank()) break
        }
        Logger.d(TAG, "Browser check on $url, trying a WebView")
        return when (val r = ChallengeWebView.render(appContext, url)) {
            is ChallengeWebView.Result.Passed -> r.html
            // The person has to answer this one; the screen shows it to them.
            ChallengeWebView.Result.NeedsPerson,
            ChallengeWebView.Result.TimedOut -> throw AnnasVerificationRequired(url)
        }
    }

    private sealed interface Fetched {
        data class Html(val body: String) : Fetched
        data object Challenge : Fetched
    }

    private fun get(startUrl: String, cookie: String): Fetched {
        var url = startUrl
        repeat(MAX_REDIRECTS + 1) {
            val request = Request.Builder().url(url)
                .header("User-Agent", userAgent())
                .header("Accept-Language", "en-US,en;q=0.9")
                .apply { if (cookie.isNotEmpty()) header("Cookie", cookie) }
                .build()
            val response = try {
                pageClient.newCall(request).execute()
            } catch (e: IOException) {
                throw MirrorDown(e.message ?: "network error", e)
            }
            response.use {
                if (isDdosGuardBlock(it)) return Fetched.Challenge
                if (it.isRedirect) {
                    val location = it.header("Location") ?: return Fetched.Html(it.body?.string().orEmpty())
                    val next = it.request.url.resolve(location) ?: throw MirrorDown("bad redirect")
                    if (next.queryParameter("check") == "1") return Fetched.Challenge
                    url = next.toString()
                    return@repeat
                }
                if (!it.isSuccessful) throw MirrorDown("HTTP ${it.code}")
                return Fetched.Html(it.body?.string().orEmpty())
            }
        }
        throw MirrorDown("too many redirects")
    }

    private fun isDdosGuardBlock(response: Response): Boolean =
        response.code == 403 &&
                response.header("Server")?.contains("ddos-guard", ignoreCase = true) == true

    /** WebView cookies (a solved check) plus the signed-in session, if any. */
    private fun buildCookie(base: String, url: String, key: String, relogin: Boolean): String {
        val parts = mutableListOf<String>()
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.let(parts::add)
        if (key.isNotBlank()) signIn(base, key, relogin)?.let(parts::add)
        return parts.joinToString("; ")
    }

    /** @return the `aa_…` session cookie, or null if sign-in was blocked. */
    private fun signIn(base: String, key: String, force: Boolean): String? {
        val id = "$key\n$base"
        session?.let { (cachedId, cookie) -> if (!force && cachedId == id) return cookie }

        val request = Request.Builder().url("$base/account/")
            .header("User-Agent", userAgent())
            .post(FormBody.Builder().add("key", key).build())
            .build()
        val response = try {
            pageClient.newCall(request).execute()
        } catch (e: IOException) {
            throw MirrorDown(e.message ?: "network error", e)
        }
        response.use {
            if (isDdosGuardBlock(it)) return null
            val cookie = it.headers("Set-Cookie")
                .map { header -> header.substringBefore(';').trim() }
                .filter { c -> c.startsWith("aa_") && '=' in c }
                .joinToString("; ")
            if (cookie.isEmpty()) {
                throw AnnasArchiveException(
                    "Anna's Archive didn't accept your secret key. Check it in " +
                            "Anna's Archive settings, or clear it to search signed out."
                )
            }
            session = id to cookie
            return cookie
        }
    }

    /**
     * The WebView's own user agent, since DDoS-Guard ties the cookies it hands
     * out to the browser that earned them.
     */
    private fun userAgent(): String =
        WebViewSupport.userAgent ?: USER_AGENT

    // ── Download ────────────────────────────────────────────────────────

    /**
     * Downloads [book] into [dest], verifying it against the book's MD5.
     * @param onProgress bytes so far, and the total when the server says.
     */
    suspend fun download(
        book: AnnasBook,
        dest: File,
        onProgress: (Long, Long?) -> Unit
    ) = withContext(Dispatchers.IO) {
        val failures = mutableListOf<String>()

        if (prefs.secretKey.isNotBlank()) {
            try {
                val url = fastDownloadUrl(book.md5, prefs.secretKey)
                fetchFile(url, book.md5, dest, onProgress)
                return@withContext
            } catch (e: AnnasArchiveException) {
                // A member out of fast downloads for today can still use LibGen.
                Logger.w(TAG, "Fast download failed, trying LibGen: ${e.message}")
                failures += e.message.orEmpty()
            } catch (e: IOException) {
                Logger.w(TAG, "Fast download failed, trying LibGen: ${e.message}")
            }
        }

        for (mirror in LIBGEN_MIRRORS) {
            currentCoroutineContext().ensureActive()
            try {
                val url = libgenFileUrl(mirror, book.md5) ?: continue
                fetchFile(url, book.md5, dest, onProgress)
                return@withContext
            } catch (e: IOException) {
                Logger.w(TAG, "LibGen mirror $mirror failed: ${e.message}")
            } catch (e: AnnasArchiveException) {
                Logger.w(TAG, "LibGen mirror $mirror failed: ${e.message}")
                failures += e.message.orEmpty()
            }
        }

        throw AnnasArchiveException(
            failures.firstOrNull { it.isNotBlank() }
                ?: if (prefs.secretKey.isBlank()) {
                    "No free mirror has this file right now. Members can add their " +
                            "secret key in Anna's Archive settings for fast downloads."
                } else {
                    "Couldn't download this file. Try another edition."
                }
        )
    }

    private fun fastDownloadUrl(md5: String, key: String): String {
        val url = prefs.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("dyn/api/fast_download.json")
            .addQueryParameter("md5", md5)
            .addQueryParameter("key", key)
            .build()
        val request = Request.Builder().url(url).header("User-Agent", userAgent()).build()
        val (code, body) = try {
            fileClient.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        } catch (e: IOException) {
            throw AnnasArchiveException("Couldn't reach Anna's Archive for the download.", e)
        }
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw AnnasArchiveException("Anna's Archive's download API answered HTTP $code.")
        val link = json.optString("download_url").takeIf { it.startsWith("http") }
        if (link != null) return link
        val error = json.optString("error").ifBlank { "HTTP $code" }
        throw AnnasArchiveException("Anna's Archive: $error")
    }

    /** LibGen's landing page for an MD5 carries a one-time `get.php` link. */
    private fun libgenFileUrl(mirror: String, md5: String): String? {
        val request = Request.Builder().url("$mirror/ads.php?md5=$md5")
            .header("User-Agent", USER_AGENT)
            .build()
        fileClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Logger.w(TAG, "LibGen $mirror: HTTP ${response.code}")
                return null
            }
            val body = response.body?.string().orEmpty()
            val doc = Jsoup.parse(body, response.request.url.toString())
            val link = doc.selectFirst("a[href*=get.php]")?.absUrl("href")?.takeIf { it.isNotBlank() }
            // LibGen answers 200 with a short error page when its database is down.
            if (link == null) Logger.w(TAG, "LibGen $mirror: no file link (${doc.title().ifBlank { body.take(120) }})")
            return link
        }
    }

    private suspend fun fetchFile(
        url: String,
        md5: String,
        dest: File,
        onProgress: (Long, Long?) -> Unit
    ) {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val part = File(dest.parentFile, dest.name + ".part")
        try {
            fileClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("empty response")
                if (body.contentType()?.subtype?.contains("html") == true) {
                    throw IOException("got a web page instead of the file")
                }
                val total = body.contentLength().takeIf { it > 0 }
                if (total != null && total > MAX_FILE_BYTES) {
                    throw AnnasArchiveException("This file is too large to import (over 200 MB).")
                }

                val digest = MessageDigest.getInstance("MD5")
                var read = 0L
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            read += n
                            if (read > MAX_FILE_BYTES) {
                                throw AnnasArchiveException("This file is too large to import (over 200 MB).")
                            }
                            onProgress(read, total)
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (actual != md5) {
                    throw IOException("checksum mismatch (got $actual)")
                }
            }
            if (!part.renameTo(dest)) throw IOException("couldn't save the file")
        } finally {
            part.delete()
        }
    }

    companion object {
        private const val TAG = "AnnasArchive"
        private const val MAX_REDIRECTS = 5
        private const val MAX_FILE_BYTES = 200L * 1024 * 1024

        private const val CHECK_FAILED =
            "Anna's Archive's browser check didn't clear. Try again, or add a " +
                    "member's secret key in Anna's Archive settings: signed-in " +
                    "searches skip the check."

        /** LibGen mirrors with the `ads.php` → `get.php` flow, tried in order. */
        val LIBGEN_MIRRORS = listOf(
            "https://libgen.li",
            "https://libgen.gl",
            "https://libgen.vg",
            "https://libgen.la",
            "https://libgen.bz",
        )
    }
}
