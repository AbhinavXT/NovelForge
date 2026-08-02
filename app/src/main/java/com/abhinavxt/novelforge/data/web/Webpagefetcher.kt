package com.abhinavxt.novelforge.data.web

import com.abhinavxt.novelforge.data.source.SourceManager
import com.abhinavxt.novelforge.data.source.nf.USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/**
 * Fetches an arbitrary web page and hands back a parsed [Document].
 *
 * Runs on the shared OkHttpClient so it inherits the same timeouts and
 * retry/backoff behaviour as every source in the app.
 *
 * Three guards, all of which exist because this accepts *any* URL the
 * user pastes rather than a known-good source:
 *  1. Content-Type must look like markup. A shared PDF or image URL
 *     fails fast with a readable message instead of Jsoup returning an
 *     empty document and the user seeing "no readable text".
 *  2. Body is capped at [MAX_BYTES]. contentLength() is a hint that is
 *     absent on chunked responses, so the cap is also enforced while
 *     reading — an endless stream can't OOM the app.
 *  3. Charset comes from the Content-Type header when present; when it
 *     isn't, we pass null and let Jsoup sniff `<meta charset>` itself,
 *     which is the common case on older sites.
 */
object WebPageFetcher {

    /** 6 MB of HTML is already an extreme outlier; real articles are <1 MB. */
    private const val MAX_BYTES = 6 * 1024 * 1024

    /** Thrown for responses we can't turn into an article. Message is user-facing. */
    class UnreadablePageException(message: String) : IOException(message)

    suspend fun fetch(url: String): Document = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        SourceManager.sharedClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw UnreadablePageException(
                    when (response.code) {
                        401, 403 -> "This page is behind a login or blocks automated access."
                        404, 410 -> "That page doesn't exist any more."
                        429 -> "The site is rate-limiting us. Try again in a minute."
                        else -> "The site returned an error (HTTP ${response.code})."
                    }
                )
            }

            val body = response.body ?: throw UnreadablePageException("The site sent an empty response.")
            val contentType = body.contentType()
            val mime = contentType?.let { "${it.type}/${it.subtype}" }?.lowercase()

            if (mime != null && !isMarkup(mime)) {
                throw UnreadablePageException(
                    "That link points to a $mime file, not a web page."
                )
            }

            val declaredLength = body.contentLength()
            if (declaredLength > MAX_BYTES) {
                throw UnreadablePageException("That page is too large to convert.")
            }

            val bytes = body.byteStream().readCapped(MAX_BYTES)
            if (bytes.isEmpty()) {
                throw UnreadablePageException("The site sent an empty page.")
            }

            // response.request.url is the URL *after* redirects, so relative
            // links (and therefore og:image resolution) use the right base.
            Jsoup.parse(
                ByteArrayInputStream(bytes),
                contentType?.charset()?.name(),
                response.request.url.toString()
            )
        }
    }

    private fun isMarkup(mime: String): Boolean =
        mime == "text/html" ||
                mime == "text/plain" ||
                mime == "application/xhtml+xml" ||
                mime == "application/xml" ||
                mime == "text/xml"

    /**
     * Read at most [limit] bytes. Stops early rather than buffering an
     * unbounded response — the truncated HTML still parses, and a page
     * whose article body doesn't start in the first 6 MB isn't one we
     * were going to extract cleanly anyway.
     */
    private suspend fun InputStream.readCapped(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        use { stream ->
            while (total < limit) {
                // Cooperative cancellation: the user can leave the screen
                // mid-download and we stop reading instead of finishing.
                coroutineContext.ensureActive()
                val read = stream.read(buffer, 0, minOf(buffer.size, limit - total))
                if (read == -1) break
                out.write(buffer, 0, read)
                total += read
            }
        }
        return out.toByteArray()
    }
}