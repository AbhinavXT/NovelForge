package com.abhinavxt.novelforge.data.web

import android.content.Context
import com.abhinavxt.novelforge.data.database.AppDatabase
import com.abhinavxt.novelforge.data.database.ChapterEntity
import com.abhinavxt.novelforge.data.database.NovelEntity
import com.abhinavxt.novelforge.data.source.SourceManager
import com.abhinavxt.novelforge.data.source.nf.USER_AGENT
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Saves an arbitrary web page into the library as a one-chapter local
 * novel — the same shape EpubImporter produces, so the reader, TTS,
 * full-text search, annotations and EPUB *export* all work on it with
 * zero changes elsewhere.
 *
 * Split into [load] and [save] deliberately: the UI previews what was
 * extracted before anything touches the database, and confirming does
 * not re-fetch the page.
 */
class WebPageImporter(private val context: Context) {

    private val database = AppDatabase.getDatabase(context)
    private val novelDao = database.novelDao()
    private val chapterDao = database.chapterDao()

    sealed class LoadResult {
        data class Success(val article: WebArticle) : LoadResult()
        /** [message] is user-facing. */
        data class Failure(val message: String) : LoadResult()
    }

    /**
     * Fetch + extract. No database writes, no file writes.
     * Safe to abandon — the caller can navigate away mid-flight.
     */
    suspend fun load(url: String): LoadResult = withContext(Dispatchers.IO) {
        try {
            val doc = WebPageFetcher.fetch(url)
            // Extraction is pure CPU over a possibly large DOM — it stays on
            // Dispatchers.IO with the fetch rather than hopping back to the
            // caller's dispatcher, which for a ViewModel would be Main.
            val article = ArticleExtractor.extract(doc)
            if (article == null) {
                LoadResult.Failure(
                    "Couldn't find readable text on this page. Pages that are " +
                            "mostly video, images or an app shell don't convert well."
                )
            } else {
                LoadResult.Success(article)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: WebPageFetcher.UnreadablePageException) {
            LoadResult.Failure(e.message ?: "Couldn't load that page.")
        } catch (e: Exception) {
            Logger.e(TAG, "load failed for $url", e)
            LoadResult.Failure(
                "Couldn't reach that page. Check the link and your connection."
            )
        }
    }

    /**
     * Write the article to the library. Returns the new novel id.
     *
     * Cover download failures are non-fatal — a book without a cover is
     * still a book, and og:image often points at a CDN that 403s.
     */
    suspend fun save(article: WebArticle): String = withContext(Dispatchers.IO) {
        val novelId = "local_${UUID.randomUUID().toString().take(8)}"

        val coverPath = article.coverImageUrl?.let { downloadCover(novelId, it) }

        val novel = NovelEntity(
            id = novelId,
            title = article.title,
            // Byline when the page had one; otherwise the site is the most
            // useful thing to show in the library grid.
            author = article.author.ifBlank { article.siteName },
            coverUrl = coverPath,
            description = buildDescription(article),
            // "local" is what marks a novel as having no remote source —
            // it keeps this page out of update checks and chapter downloads.
            source = "local",
            status = "Completed",
            totalChapters = 1,
            addedToLibraryAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis()
        )

        val chapter = ChapterEntity(
            id = "${novelId}_ch1",
            novelId = novelId,
            number = 1,
            title = article.title,
            // Same fake-URL convention as EpubImporter so nothing downstream
            // ever tries to hit the network for this chapter's content.
            url = "local://$novelId/1",
            isDownloaded = true,
            content = article.toChapterContent(),
            downloadedAt = System.currentTimeMillis()
        )

        novelDao.insertNovel(novel)
        chapterDao.insertChapters(listOf(chapter))

        novelId
    }

    /**
     * Description doubles as provenance — six months later the user
     * should be able to see where a saved page came from.
     */
    private fun buildDescription(article: WebArticle): String = buildString {
        if (article.excerpt.isNotBlank()) {
            append(article.excerpt.trim())
            append("\n\n")
        }
        append("Saved from ")
        append(article.siteName.ifBlank { "the web" })
        append("\n")
        append(article.url)
    }

    private fun downloadCover(novelId: String, imageUrl: String): String? {
        return try {
            val request = Request.Builder()
                .url(imageUrl)
                .header("User-Agent", USER_AGENT)
                .build()

            SourceManager.sharedClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                if (body.contentType()?.type != "image") return null
                if (body.contentLength() > MAX_COVER_BYTES) return null

                val bytes = body.bytes()
                if (bytes.isEmpty() || bytes.size > MAX_COVER_BYTES) return null

                // Same directory and naming as EpubImporter, so the existing
                // local-novel cleanup path deletes this file too.
                val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
                val coverFile = File(coversDir, "$novelId.jpg")
                FileOutputStream(coverFile).use { it.write(bytes) }
                coverFile.absolutePath
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Cover download failed: ${e.message}")
            null
        }
    }

    private companion object {
        const val TAG = "WebPageImporter"
        const val MAX_COVER_BYTES = 5L * 1024 * 1024
    }
}