package com.abhinavxt.novelforge.data.epub

import android.content.Context
import android.net.Uri
import com.abhinavxt.novelforge.data.database.AppDatabase
import com.abhinavxt.novelforge.data.database.ChapterEntity
import com.abhinavxt.novelforge.data.database.NovelEntity
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Handles importing EPUB files into the app's database
 */
class EpubImporter(private val context: Context) {

    private val database = AppDatabase.getDatabase(context)
    private val novelDao = database.novelDao()
    private val chapterDao = database.chapterDao()
    private val parser = EpubParser(context)

    /**
     * Import result sealed class
     */
    sealed class ImportResult {
        data class Success(val novelId: String, val title: String, val chapterCount: Int) : ImportResult()
        data class Error(val message: String) : ImportResult()
    }

    /**
     * Import an EPUB file from URI
     * @param uri URI from file picker
     * @return ImportResult indicating success or failure
     */
    /**
     * Import any supported document. Dispatches on file type, then runs the
     * SAME pipeline for all of them.
     *
     * Note there is no "convert to EPUB first" step: EpubBook is already the
     * shape every parser targets, so writing a ZIP only to immediately re-parse
     * it would buy nothing but I/O and a temp file.
     */
    suspend fun importDocument(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        when (DocumentFormat.detect(context, uri)) {
            DocumentFormat.EPUB -> importEpub(uri)
            DocumentFormat.TEXT -> importTextLike(uri, markdown = false)
            DocumentFormat.MARKDOWN -> importTextLike(uri, markdown = true)
            DocumentFormat.PDF -> ImportResult.Error(
                "PDF isn't supported. PDFs store page layout rather than text, " +
                        "so the result reads poorly. Converting to EPUB first " +
                        "(Calibre does this well) gives a much better import."
            )
            DocumentFormat.UNSUPPORTED -> ImportResult.Error(
                "Unsupported file type. Supported: .epub, .txt, .md"
            )
        }
    }

    private suspend fun importTextLike(uri: Uri, markdown: Boolean): ImportResult =
        withContext(Dispatchers.IO) {
            try {
                val fileName = DocumentFormat.displayName(context, uri)
                val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
                    // Text files have no declared encoding. UTF-8 covers the
                    // overwhelming majority; malformed bytes become U+FFFD
                    // rather than throwing, so a mis-encoded file still imports
                    // with a few bad glyphs instead of failing outright.
                    stream.readBytes().toString(Charsets.UTF_8)
                } ?: return@withContext ImportResult.Error("Could not open file.")

                if (raw.isBlank()) return@withContext ImportResult.Error("File is empty.")

                val book = TextDocumentParser.parse(raw, fileName, markdown)
                if (book.chapters.isEmpty()) {
                    return@withContext ImportResult.Error("No readable text found in this file.")
                }
                persist(book)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                // A multi-hundred-MB .txt is the realistic way to hit this.
                ImportResult.Error("File is too large to import.")
            } catch (e: Exception) {
                Logger.e("EpubImporter", "Text import failed", e)
                ImportResult.Error("Import failed: ${e.message ?: "Unknown error"}")
            }
        }

    suspend fun importEpub(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            // Step 1: Parse the EPUB
            val epub = parser.parse(uri)
                ?: return@withContext ImportResult.Error("Failed to parse EPUB file. The file may be corrupted or in an unsupported format.")

            if (epub.chapters.isEmpty()) {
                return@withContext ImportResult.Error("No readable chapters found in this EPUB.")
            }

            persist(epub)

        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("EpubImporter", "Import failed", e)
            ImportResult.Error("Import failed: ${e.message ?: "Unknown error"}")
        }
    }

    /**
     * Shared tail of every import path: assign an id, save the cover, write the
     * novel and its chapters. Identical for EPUB, TXT and Markdown, so it lives
     * in one place rather than being duplicated per format.
     */
    private suspend fun persist(epub: EpubBook): ImportResult {
        // Step 2: Generate unique ID for this novel
        val novelId = "local_${UUID.randomUUID().toString().take(8)}"

        // Step 3: Save cover image if available
        val coverPath = epub.coverImage?.let { coverBytes ->
            saveCoverImage(novelId, coverBytes)
        }

        // Step 4: Create novel entity
        val novelEntity = NovelEntity(
            id = novelId,
            title = epub.title,
            author = epub.author,
            coverUrl = coverPath, // Local file path or null
            description = epub.description,
            source = "local", // Mark as local source
            status = "Completed", // Imported documents are complete by definition
            totalChapters = epub.chapters.size,
            addedToLibraryAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis()
        )

        // Step 5: Create chapter entities (with content already stored)
        val chapterEntities = epub.chapters.map { chapter ->
            ChapterEntity(
                id = "${novelId}_ch${chapter.order}",
                novelId = novelId,
                number = chapter.order,
                title = chapter.title,
                url = "local://$novelId/${chapter.order}", // Fake URL for local content
                isDownloaded = true, // Content is already available
                content = chapter.content, // Store content directly
                downloadedAt = System.currentTimeMillis()
            )
        }

        // Step 6: Save to database
        novelDao.insertNovel(novelEntity)
        chapterDao.insertChapters(chapterEntities)

        return ImportResult.Success(
            novelId = novelId,
            title = epub.title,
            chapterCount = epub.chapters.size
        )
    }

    /**
     * Save cover image to internal storage
     * @return File path to saved image, or null if failed
     */
    private fun saveCoverImage(novelId: String, imageBytes: ByteArray): String? {
        return try {
            val coversDir = File(context.filesDir, "covers")
            if (!coversDir.exists()) {
                coversDir.mkdirs()
            }

            val coverFile = File(coversDir, "$novelId.jpg")
            FileOutputStream(coverFile).use { fos ->
                fos.write(imageBytes)
            }

            coverFile.absolutePath
        } catch (e: Exception) {
            Logger.e("Error", e)
            null
        }
    }

    /**
     * Delete a local novel and its associated files
     */
    suspend fun deleteLocalNovel(novelId: String) = withContext(Dispatchers.IO) {
        // Delete cover image
        val coverFile = File(context.filesDir, "covers/$novelId.jpg")
        if (coverFile.exists()) {
            coverFile.delete()
        }

        // Database cleanup is handled by NovelRepository.removeFromLibrary()
    }
}