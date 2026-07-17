package com.abhinavxt.novelforge.data.annas

import android.content.Context
import com.abhinavxt.novelforge.data.source.nf.NfHttp
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Anna's Archive integration.
 *
 * Deliberately NOT a [com.abhinavxt.novelforge.data.source.Source]: AA serves
 * whole EPUB files via mirror links, not chapters. (QuickNovel routes it
 * through a separate EpubResponse/extractor path for the same reason — see
 * the exclusion note in NfSources.kt.) NovelForge's flow instead is:
 *   search -> detail (mirror links) -> resolveMirror -> downloadEpub
 *   -> EpubImporter.importEpub() -> library entry as a local_ novel.
 *
 * Selectors ported from QuickNovel's providers/AnnasArchive.kt (HTML path;
 * the /db/md5 JSON endpoint is Cloudflare-gated upstream, so QN abandoned it).
 */
object AnnasArchiveApi {

    private const val TAG = "AnnasArchive"
    const val MAIN_URL = "https://annas-archive.gl"

    data class Book(
        val title: String,
        /** Absolute URL of the /md5/... detail page. */
        val url: String,
        val coverUrl: String?,
    )

    data class Mirror(
        val label: String,
        val url: String,
        /** True when the URL itself points at an .epub file. */
        val isDirect: Boolean,
    )

    data class BookDetail(
        val title: String,
        val url: String,
        val author: String?,
        val coverUrl: String?,
        val synopsis: String?,
        val mirrors: List<Mirror>,
    )

    /** Dedicated download client: no retry interceptor, generous read timeout
     *  for large files on slow mirrors. */
    private val downloadClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> MAIN_URL + url
            else -> "$MAIN_URL/$url"
        }
    }

    /**
     * Search AA, restricted to EPUBs (?ext=epub). AA hides result markup in
     * HTML comments for non-JS clients, so comments are unwrapped before
     * parsing — same trick QuickNovel uses.
     */
    suspend fun search(query: String): List<Book> = withContext(Dispatchers.IO) {
        val url = "$MAIN_URL/search?index=&page=1&sort=&ext=epub&display=&q=" +
                query.trim().replace(" ", "+")
        val raw = NfHttp.app.get(url).text
        val unwrapped = raw.replace(Regex("<!--([\\W\\w]*?)-->")) { it.groupValues[1] }
        val document = Jsoup.parse(unwrapped, MAIN_URL)

        document.select("div.js-aarecord-list-outer div.flex.pt-3.pb-3.border-b")
            .mapNotNull { element ->
                val link = element.selectFirst("a")?.attr("href") ?: ""
                if (!link.startsWith("/md5/")) return@mapNotNull null
                val title = element
                    .selectFirst("div.max-w-full.overflow-hidden.flex.flex-col.justify-around a")
                    ?.text()
                    ?: return@mapNotNull null
                Book(
                    title = title,
                    url = fixUrl(link) ?: return@mapNotNull null,
                    coverUrl = fixUrl(element.selectFirst("div img")?.attr("src")),
                )
            }
    }

    /**
     * Load a book's detail page and collect usable mirror links.
     * Skips members-only (fast_download), Cloudflare-gated (slow_download)
     * and the stray /datasets anchor — same filtering as QuickNovel.
     */
    suspend fun getDetails(bookUrl: String): BookDetail = withContext(Dispatchers.IO) {
        val document = NfHttp.app.get(bookUrl).document

        val mirrors = document.select("ul.mb-4 > li > a.js-download-link")
            .mapNotNull { element ->
                val link = fixUrl(element.attr("href")) ?: return@mapNotNull null
                if (link.contains("fast_download")) return@mapNotNull null
                if (link.contains("slow_download")) return@mapNotNull null
                if (link.endsWith("/datasets")) return@mapNotNull null
                Mirror(
                    label = element.text().ifBlank { "Mirror" },
                    url = link,
                    isDirect = link.contains(".epub"),
                )
            }
            // Direct links first — no extraction step, highest success rate.
            .sortedByDescending { it.isDirect }

        BookDetail(
            title = document.selectFirst("div.text-2xl")?.ownText()
                ?: throw IllegalStateException("Could not parse book title — page layout may have changed"),
            url = bookUrl,
            author = document.selectFirst("main > div > div > a")?.ownText(),
            coverUrl = fixUrl(
                document.selectFirst("main > div > div > div > div > div > img")?.attr("src")
            ),
            synopsis = document.selectFirst("main > div > div > div > div.mb-1")?.text(),
            mirrors = mirrors,
        )
    }

    /**
     * Resolve a mirror to a directly-downloadable file URL, or null.
     * Direct links pass through; libgen.li pages get the QuickNovel LibgenLi
     * treatment (tbody>tr>td>a = the GET link); anything else falls back to
     * scanning the page for an .epub anchor.
     */
    suspend fun resolveMirror(mirror: Mirror): String? = withContext(Dispatchers.IO) {
        if (mirror.isDirect) return@withContext mirror.url
        try {
            val host = mirror.url
                .removePrefix("https://").removePrefix("http://")
            val document = NfHttp.app.get(mirror.url).document
            val resolved: String? = when {
                host.startsWith("libgen.li") ->
                    document.selectFirst("tbody>tr>td>a")?.attr("abs:href")
                else ->
                    document.selectFirst("a[href*=.epub]")?.attr("abs:href")
                        ?: document.selectFirst("h2 > a:contains(GET)")?.attr("abs:href")
            }
            resolved?.takeIf { it.isNotBlank() }?.replace("http://", "https://")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Mirror resolve failed (${mirror.label}): ${e.message}")
            null
        }
    }

    /**
     * Stream [fileUrl] into cacheDir and validate it's actually a zip/EPUB
     * ("PK" magic) — mirrors love returning HTML error pages with 200s, and
     * without this check those would reach EpubParser as corrupt files.
     *
     * @param onProgress 0f..1f, or null progress when length is unknown.
     * @return the downloaded file, or null on any failure (file cleaned up).
     */
    suspend fun downloadEpub(
        context: Context,
        fileUrl: String,
        onProgress: (Float?) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        val outFile = File(
            context.cacheDir,
            "annas_${System.currentTimeMillis()}.epub"
        )
        try {
            val request = Request.Builder()
                .url(fileUrl)
                .header("User-Agent", com.abhinavxt.novelforge.data.source.nf.USER_AGENT)
                .get()
                .build()

            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Logger.e(TAG, "Download HTTP ${response.code} from $fileUrl")
                    return@withContext null
                }
                val body = response.body ?: return@withContext null
                val total = body.contentLength() // -1 when unknown

                body.byteStream().use { input ->
                    outFile.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            copied += read
                            onProgress(
                                if (total > 0) (copied.toFloat() / total).coerceIn(0f, 1f)
                                else null
                            )
                        }
                    }
                }
            }

            // Zip magic check: EPUBs are zips and must start with "PK".
            val magic = ByteArray(2)
            outFile.inputStream().use { it.read(magic) }
            if (magic[0] != 'P'.code.toByte() || magic[1] != 'K'.code.toByte()) {
                Logger.e(TAG, "Downloaded file is not a zip (mirror returned an error page?)")
                outFile.delete()
                return@withContext null
            }

            outFile
        } catch (e: kotlinx.coroutines.CancellationException) {
            outFile.delete()
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Download failed: ${e.message}")
            outFile.delete()
            null
        }
    }
}