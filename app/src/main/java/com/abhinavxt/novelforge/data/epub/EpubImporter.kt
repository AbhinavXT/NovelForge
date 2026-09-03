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

        /**
         * The file's bytes already back a book in the library. Its own case
         * rather than an [Error] because nothing went wrong — a batch that
         * reported these as failures would tell the user their import broke
         * when it did exactly the right thing.
         *
         * [existingTitle] is the book already holding those bytes, which is
         * not necessarily what the new file is called.
         */
        data class Duplicate(val existingTitle: String) : ImportResult()
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
    /**
     * @param rememberSource when true, the novel records [uri] so a later
     *   library-folder scan recognises the file as already imported. Callers
     *   that import a one-off file the user picked by hand leave this off:
     *   remembering a URI from a transient picker grant buys nothing, since
     *   the same file picked twice does not produce the same URI.
     */
    suspend fun importDocument(
        uri: Uri,
        rememberSource: Boolean = false
    ): ImportResult = withContext(Dispatchers.IO) {
        val sourceUri = if (rememberSource) uri.toString() else null
        val format = DocumentFormat.detect(context, uri)

        // Hash only what we would actually import — hashing a PDF we are
        // about to reject reads the whole file to learn nothing.
        val contentHash = when (format) {
            DocumentFormat.EPUB,
            DocumentFormat.TEXT,
            DocumentFormat.MARKDOWN -> hashFile(uri)
            else -> null
        }

        // Checked BEFORE parsing, which makes duplicate detection a saving
        // rather than a cost: hashing is plain I/O, while the parse it
        // skips is zip plus XML.
        if (contentHash != null) {
            novelDao.findTitleByContentHash(contentHash)?.let { existing ->
                return@withContext ImportResult.Duplicate(existing)
            }
        }

        when (format) {
            DocumentFormat.EPUB -> importEpub(uri, sourceUri, contentHash)
            DocumentFormat.TEXT ->
                importTextLike(uri, markdown = false, sourceUri = sourceUri, contentHash = contentHash)
            DocumentFormat.MARKDOWN ->
                importTextLike(uri, markdown = true, sourceUri = sourceUri, contentHash = contentHash)
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

    /**
     * SHA-256 of the file's bytes, streamed in fixed-size blocks so that a
     * 200 MB text file costs 8 KB of memory rather than 200 MB.
     *
     * Returns null when the file cannot be read. A failed hash must never
     * block an import: the worst case of not having one is a duplicate the
     * user can delete, while the worst case of treating it as fatal is a
     * book that refuses to import for no reason the user can see.
     */
    private fun hashFile(uri: Uri): String? {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val stream = context.contentResolver.openInputStream(uri) ?: return null
            stream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Logger.e("EpubImporter", "Could not hash file for duplicate check", e)
            null
        }
    }

    private suspend fun importTextLike(
        uri: Uri,
        markdown: Boolean,
        sourceUri: String? = null,
        contentHash: String? = null
    ): ImportResult =
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
                persist(book, sourceUri, contentHash)
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

    suspend fun importEpub(
        uri: Uri,
        sourceUri: String? = null,
        contentHash: String? = null
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            // Step 1: Parse the EPUB
            val epub = parser.parse(uri)
                ?: return@withContext ImportResult.Error("Failed to parse EPUB file. The file may be corrupted or in an unsupported format.")

            if (epub.chapters.isEmpty()) {
                return@withContext ImportResult.Error("No readable chapters found in this EPUB.")
            }

            persist(epub, sourceUri, contentHash)

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
    private suspend fun persist(
        epub: EpubBook,
        sourceUri: String? = null,
        contentHash: String? = null
    ): ImportResult {
        // Second duplicate check, after parsing rather than before it.
        //
        // The hash check in importDocument only sees books imported since
        // contentHash existed. Everything already in the library at upgrade
        // time has a null hash and is invisible to it, and those files may
        // be long gone, so there is nothing to backfill from in advance.
        // Title and author are the only identity such a book still carries,
        // and they are only known once the file has been parsed.
        val title = epub.title.trim()
        val author = epub.author.trim()
        if (title.isNotEmpty()) {
            novelDao.findByTitleAndAuthor(title, author)?.let { existing ->
                // Adopt the file into the row that was already there. The
                // legacy gap then closes permanently for this book: the
                // next scan skips it on sourceUri and the next hand-picked
                // import skips it on the hash, neither of which has to
                // parse the file again.
                novelDao.linkImportedFile(existing.id, contentHash, sourceUri)
                return ImportResult.Duplicate(existing.title)
            }
        }

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
            lastUpdatedAt = System.currentTimeMillis(),
            sourceUri = sourceUri,
            contentHash = contentHash
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