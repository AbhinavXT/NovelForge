package com.abhinavxt.novelforge.data.opds

import com.abhinavxt.novelforge.data.UserFacingException
import com.abhinavxt.novelforge.data.source.SourceHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.UnknownServiceException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A catalog problem worth showing as-is. */
class OpdsException(message: String, cause: Throwable? = null) : UserFacingException(message, cause)

/**
 * Fetches OPDS feeds and downloads books from them.
 *
 * Credentials go only to the catalog's own host. A feed can link anywhere
 * (covers on a CDN, downloads on a mirror), and a password sent along to
 * those would be a password handed to strangers; OkHttp also drops the
 * header on a redirect to another host.
 */
class OpdsClient {

    private val feedClient: OkHttpClient = SourceHttp.client
    private val fileClient: OkHttpClient = SourceHttp.client.newBuilder()
        .readTimeout(2, TimeUnit.MINUTES)
        .build()

    /** Catalog id → its search template, once found. */
    private val templates = ConcurrentHashMap<String, String>()

    suspend fun feed(catalog: OpdsCatalog, url: String = catalog.url): OpdsFeed = withContext(Dispatchers.IO) {
        val body = get(catalog, url)
        if (!OpdsParser.looksLikeFeed(body)) {
            throw OpdsException(
                "That address isn't an OPDS catalog. Servers usually publish it at a path " +
                        "like /opds; check your server's settings for the exact address."
            )
        }
        OpdsParser.parseFeed(body, url)
    }

    /** True when [feed] (a catalog's root) says how to search it. */
    fun canSearch(feed: OpdsFeed): Boolean = feed.searchTemplate != null || feed.openSearchUrl != null

    suspend fun search(catalog: OpdsCatalog, root: OpdsFeed, query: String): OpdsFeed {
        val template = templates[catalog.id] ?: resolveTemplate(catalog, root).also { templates[catalog.id] = it }
        return feed(catalog, OpdsParser.fillTemplate(template, query))
    }

    private suspend fun resolveTemplate(catalog: OpdsCatalog, root: OpdsFeed): String = withContext(Dispatchers.IO) {
        val template = root.searchTemplate ?: root.openSearchUrl?.let { osd ->
            OpdsParser.parseOpenSearch(get(catalog, osd), osd)
        } ?: throw OpdsException("This catalog doesn't offer search. Browse its sections instead.")
        upgradeToHttps(template, catalog.url)
    }

    /**
     * A catalog served over https sometimes advertises an http search
     * template (Project Gutenberg does). Android blocks plain http here, so
     * ask for the same address over https, which such hosts serve too.
     */
    private fun upgradeToHttps(url: String, catalogUrl: String): String =
        if (url.startsWith("http://") && catalogUrl.startsWith("https://")) "https://" + url.removePrefix("http://")
        else url

    /** Downloads the EPUB at [acquisition] into [dest]. */
    suspend fun download(
        catalog: OpdsCatalog,
        acquisition: OpdsAcquisition,
        dest: File,
        onProgress: (Long, Long?) -> Unit
    ) = withContext(Dispatchers.IO) {
        val part = File(dest.parentFile, dest.name + ".part")
        try {
            execute(fileClient, request(catalog, upgradeToHttps(acquisition.url, catalog.url))).use { response ->
                checkStatus(response.code, catalog)
                val body = response.body ?: throw OpdsException("The catalog sent an empty file.")
                val total = body.contentLength().takeIf { it > 0 } ?: acquisition.length
                if (total != null && total > MAX_FILE_BYTES) throw tooLarge()

                var read = 0L
                var head = ByteArray(0)
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (head.size < 4) head += buffer.copyOf(minOf(n, 4 - head.size))
                            output.write(buffer, 0, n)
                            read += n
                            if (read > MAX_FILE_BYTES) throw tooLarge()
                            onProgress(read, total)
                        }
                    }
                }
                // An EPUB is a ZIP. A login page or error page served with a 200 isn't.
                if (!(head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte())) {
                    throw OpdsException("The catalog sent a web page instead of the book. It may need you to sign in.")
                }
            }
            if (!part.renameTo(dest)) throw IOException("couldn't save the file")
        } finally {
            part.delete()
        }
    }

    private fun tooLarge() = OpdsException("This file is too large to import (over 200 MB).")

    private fun get(catalog: OpdsCatalog, url: String): String =
        execute(feedClient, request(catalog, url, "application/atom+xml, application/xml;q=0.9, */*;q=0.5"))
            .use { response ->
                checkStatus(response.code, catalog)
                response.body?.string().orEmpty()
            }

    private fun request(catalog: OpdsCatalog, url: String, accept: String? = null): Request {
        val httpUrl = url.toHttpUrlOrNull() ?: throw OpdsException("That isn't a web address: $url")
        return Request.Builder().url(httpUrl)
            .header("User-Agent", USER_AGENT)
            .apply {
                if (accept != null) header("Accept", accept)
                val catalogHost = catalog.url.toHttpUrlOrNull()?.host
                if (catalog.needsLogin && httpUrl.host == catalogHost) {
                    // Blank passwords are real: Standard Ebooks signs patrons in by email alone.
                    header("Authorization", Credentials.basic(catalog.username, catalog.password, Charsets.UTF_8))
                }
            }
            .build()
    }

    private fun execute(client: OkHttpClient, request: Request) = try {
        client.newCall(request).execute()
    } catch (e: UnknownServiceException) {
        // Android refuses plain http unless an app opts in; NovelForge doesn't.
        throw OpdsException(
            "NovelForge only connects to catalogs over https. Use your server's https address.", e
        )
    }

    private fun checkStatus(code: Int, catalog: OpdsCatalog) {
        when {
            code in 200..299 -> Unit
            code == 401 || code == 403 -> throw OpdsException(
                if (catalog.needsLogin) "The catalog didn't accept your sign-in. Check the username and password."
                else "This catalog needs you to sign in. Edit it to add your username and password."
            )
            code == 404 -> throw OpdsException("The catalog doesn't have that page (HTTP 404).")
            else -> throw OpdsException("The catalog answered HTTP $code. Try again later.")
        }
    }

    companion object {
        private const val MAX_FILE_BYTES = 200L * 1024 * 1024
        private const val USER_AGENT = "NovelForge (Android; OPDS)"
    }
}
