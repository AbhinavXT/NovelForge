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
class EpubImporter(
    private val context: Context,
    private val database: AppDatabase = AppDatabase.getDatabase(context)
) {

    private val novelDao = database.novelDao()
    private val chapterDao = database.chapterDao()
    private val parser = EpubParser(context)
    private val oldParser = EpubParser(context, blockquoteBreaks = false)
    private val formatting = BookFormattingStore(context, database)

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
     * @param rememberSource when true, the novel records [uri] as its file:
     *   a later library-folder scan recognises it as already imported, and
     *   the book can be archived and re-read from it. Only pass true when the
     *   app holds lasting access to [uri] — the folder's grant, or one taken
     *   with [BookFileAccess.hold] — since a URI from a transient picker
     *   grant stops opening once the process dies.
     * @param archived store the book already archived: its chapters, titles
     *   and fingerprint, but not their text, which is read from the file when
     *   the book is opened. For library-folder scans of libraries large
     *   enough that a second copy of every book is the problem. Ignored
     *   without [rememberSource], since an archived book with no file could
     *   never come back.
     */
    suspend fun importDocument(
        uri: Uri,
        rememberSource: Boolean = false,
        archived: Boolean = false
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
            novelDao.findByContentHash(contentHash)?.let { existing ->
                // Same bytes, so this file can stand in for the book's own.
                // A book imported by hand before files were remembered gains
                // one here, which is what makes it archivable; one that
                // already has a file keeps it (COALESCE).
                if (sourceUri != null) {
                    novelDao.linkImportedFile(existing.id, contentHash, sourceUri)
                }
                return@withContext ImportResult.Duplicate(existing.title)
            }
        }

        when (format) {
            DocumentFormat.PDF -> return@withContext ImportResult.Error(
                "PDF isn't supported. PDFs store page layout rather than text, " +
                        "so the result reads poorly. Converting to EPUB first " +
                        "(Calibre does this well) gives a much better import."
            )
            DocumentFormat.UNSUPPORTED -> return@withContext ImportResult.Error(
                "Unsupported file type. Supported: .epub, .txt, .md"
            )
            else -> Unit
        }

        when (val parsed = parseDocument(uri, format)) {
            is ParseResult.Failed -> ImportResult.Error(parsed.message)
            is ParseResult.Parsed -> try {
                persist(parsed.book, sourceUri, contentHash, archived = archived && sourceUri != null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("EpubImporter", "Import failed", e)
                ImportResult.Error("Import failed: ${e.message ?: "Unknown error"}")
            }
        }
    }

    /** Outcome of turning a file into an [EpubBook] without saving anything. */
    sealed interface ParseResult {
        data class Parsed(val book: EpubBook) : ParseResult
        data class Failed(val message: String) : ParseResult
    }

    /**
     * Parse a supported document into an [EpubBook], touching nothing in the
     * database.
     *
     * Split out of the import so the archive can re-read a book's file and
     * get exactly what the import stored. That only holds while both go
     * through this one function: a second parsing path, however similar,
     * could drift and make a restored book disagree with its own bookmarks.
     */
    suspend fun parseDocument(
        uri: Uri,
        format: DocumentFormat = DocumentFormat.detect(context, uri),
        /** Read the text as versions before 2.2.0 did; see [EpubParser]. */
        oldText: Boolean = false
    ): ParseResult = withContext(Dispatchers.IO) {
        when (format) {
            DocumentFormat.EPUB -> parseEpub(uri, if (oldText) oldParser else parser)
            DocumentFormat.TEXT -> parseTextLike(uri, markdown = false)
            DocumentFormat.MARKDOWN -> parseTextLike(uri, markdown = true)
            DocumentFormat.PDF,
            DocumentFormat.UNSUPPORTED ->
                ParseResult.Failed("Unsupported file type. Supported: .epub, .txt, .md")
        }
    }

    private fun parseEpub(uri: Uri, parser: EpubParser): ParseResult = try {
        val epub = parser.parse(uri)
        when {
            epub == null -> ParseResult.Failed(
                "Failed to parse EPUB file. The file may be corrupted or in an unsupported format."
            )
            epub.chapters.isEmpty() ->
                ParseResult.Failed("No readable chapters found in this EPUB.")
            else -> ParseResult.Parsed(epub)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.e("EpubImporter", "EPUB parse failed", e)
        ParseResult.Failed("Import failed: ${e.message ?: "Unknown error"}")
    }

    private fun parseTextLike(uri: Uri, markdown: Boolean): ParseResult = try {
        val fileName = DocumentFormat.displayName(context, uri)
        // Text files have no declared encoding. UTF-8 covers the
        // overwhelming majority; malformed bytes become U+FFFD rather
        // than throwing, so a mis-encoded file still imports with a few
        // bad glyphs instead of failing outright.
        val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        }
        when {
            raw == null -> ParseResult.Failed("Could not open file.")
            raw.isBlank() -> ParseResult.Failed("File is empty.")
            else -> {
                val book = TextDocumentParser.parse(raw, fileName, markdown)
                if (book.chapters.isEmpty()) {
                    ParseResult.Failed("No readable text found in this file.")
                } else {
                    ParseResult.Parsed(book)
                }
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: OutOfMemoryError) {
        // A multi-hundred-MB .txt is the realistic way to hit this.
        ParseResult.Failed("File is too large to import.")
    } catch (e: Exception) {
        Logger.e("EpubImporter", "Text parse failed", e)
        ParseResult.Failed("Import failed: ${e.message ?: "Unknown error"}")
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

    /**
     * Shared tail of every import path: assign an id, save the cover, write the
     * novel and its chapters. Identical for EPUB, TXT and Markdown, so it lives
     * in one place rather than being duplicated per format.
     */
    private suspend fun persist(
        epub: EpubBook,
        sourceUri: String? = null,
        contentHash: String? = null,
        archived: Boolean = false
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
        val now = System.currentTimeMillis()
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
            contentHash = contentHash,
            // Computed from the text either way. For an archived import it
            // is the only trace of that text the database keeps, and what
            // the first restore checks the file against.
            textFingerprint = LocalBookArchive.fingerprint(epub.chapters.map { it.content }),
            archivedAt = if (archived) now else null
        )

        // Step 5: Create chapter entities (with content already stored)
        val chapterEntities = epub.chapters.map { chapter ->
            ChapterEntity(
                id = "${novelId}_ch${chapter.order}",
                novelId = novelId,
                number = chapter.order,
                title = chapter.displayTitle,
                url = "local://$novelId/${chapter.order}", // Fake URL for local content
                // An archived import keeps the row — numbering, title, and
                // a place for bookmarks to point — but not the text.
                isDownloaded = !archived,
                content = if (archived) null else chapter.content,
                downloadedAt = if (archived) null else now
            )
        }

        // Step 6: Save to database
        novelDao.insertNovel(novelEntity)
        chapterDao.insertChapters(chapterEntities)
        // Formatting and pictures go with the text; the contents list goes
        // with the chapter list, which an archived import keeps too.
        if (!archived) formatting.saveFormatting(novelId, epub) { number -> "${novelId}_ch$number" }
        formatting.saveToc(novelId, epub.toc)

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