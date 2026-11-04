package com.abhinavxt.novelforge.data.epub

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.abhinavxt.novelforge.data.database.AppDatabase
import com.abhinavxt.novelforge.data.database.ChapterEntity
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Drops an imported book's chapter text from the database and brings it back
 * from the book's file on demand.
 *
 * An imported book costs about 1.5× its file size once its text and search
 * index are stored. For someone who already keeps hundreds of books on the
 * device that is a second copy of their library, so an archived book keeps
 * only its rows — progress, bookmarks, highlights, categories, codex — and
 * re-reads the text from the file when it is wanted again.
 *
 * The whole design rests on one promise: a restored book has exactly the
 * chapters it had before. Bookmarks, highlights and progress point at chapter
 * numbers and paragraph indexes, so a restore that shifted a chapter would
 * quietly move every one of them. Hence:
 *
 * - **Archiving is refused** unless re-parsing the file reproduces the stored
 *   text exactly. The check happens while the text still exists to compare
 *   against; afterwards there is nothing left to check with.
 * - **Restoring is refused** when the file now produces different text, unless
 *   the caller says to accept it (the user chose to, knowing positions may
 *   shift). The comparison is against [fingerprint], saved at archive time.
 */
class LocalBookArchive(
    context: Context,
    private val database: AppDatabase = AppDatabase.getDatabase(context),
    private val importer: EpubImporter = EpubImporter(context.applicationContext, database)
) {

    private val appContext = context.applicationContext

    private val novelDao = database.novelDao()
    private val chapterDao = database.chapterDao()

    sealed interface ArchiveResult {
        /** [freedBytes] approximates the stored text, UTF-16, before SQLite overhead. */
        data class Archived(val freedBytes: Long) : ArchiveResult
        data object AlreadyArchived : ArchiveResult

        /** Online books re-download instead; there is no file to come back from. */
        data object NotLocal : ArchiveResult

        /** The book never recorded where its file is, so archiving it would lose it. */
        data object NoFile : ArchiveResult

        /** The file could not be opened or parsed right now. */
        data class FileUnavailable(val message: String) : ArchiveResult

        /**
         * The file parses, but not into the text the library holds — it was
         * edited, replaced, or the parser has changed since import. Archiving
         * would make the next restore disagree with the user's bookmarks.
         */
        data object FileChanged : ArchiveResult
        data object NotFound : ArchiveResult
    }

    sealed interface RestoreResult {
        data class Restored(val chapterCount: Int) : RestoreResult

        /** Every chapter already has its text. */
        data object NothingToRestore : RestoreResult
        data object NotLocal : RestoreResult

        /** No file is recorded for this book; ask the user to locate it. */
        data object NeedsFile : RestoreResult

        /** The recorded (or given) file could not be opened or parsed. */
        data class FileUnavailable(val message: String) : RestoreResult

        /**
         * The file reads, but its chapters are not the ones archived. Call
         * again with `acceptChanges = true` to restore anyway.
         */
        data class DifferentText(val fileChapters: Int, val libraryChapters: Int) : RestoreResult
        data object NotFound : RestoreResult
    }

    suspend fun archive(novelId: String): ArchiveResult = withContext(Dispatchers.IO) {
        lock.withLock {
            val novel = novelDao.getNovelById(novelId) ?: return@withLock ArchiveResult.NotFound
            if (novel.source != LOCAL_SOURCE) return@withLock ArchiveResult.NotLocal
            if (novel.archivedAt != null) return@withLock ArchiveResult.AlreadyArchived
            val uri = novel.sourceUri?.let(Uri::parse) ?: return@withLock ArchiveResult.NoFile
            unreadableReason(uri)?.let { return@withLock ArchiveResult.FileUnavailable(it) }

            val book = when (val parsed = importer.parseDocument(uri)) {
                is EpubImporter.ParseResult.Failed ->
                    return@withLock ArchiveResult.FileUnavailable(parsed.message)
                is EpubImporter.ParseResult.Parsed -> parsed.book
            }
            val fileTexts = book.chapters.sortedBy { it.order }.map { it.content }

            // One chapter at a time: a single query over a whole book's text
            // can overflow SQLite's 2 MB cursor window.
            val rows = chapterDao.getChaptersForNovelOnce(novelId)
            if (rows.size != fileTexts.size) return@withLock ArchiveResult.FileChanged
            var freedChars = 0L
            rows.forEachIndexed { index, row ->
                // Chapters without text are compared by count alone: a book
                // restored from a backup arrives with none, and that is
                // exactly the book this feature should be able to take in.
                val stored = chapterDao.getChapterById(row.id)?.content ?: return@forEachIndexed
                if (row.number != index + 1 || stored != fileTexts[index]) {
                    return@withLock ArchiveResult.FileChanged
                }
                freedChars += stored.length
            }

            database.withTransaction {
                chapterDao.deleteAllDownloadsForNovel(novelId)
                novelDao.markArchived(novelId, System.currentTimeMillis(), fingerprint(fileTexts))
            }
            ArchiveResult.Archived(freedBytes = freedChars * 2)
        }
    }

    /**
     * Bring back the text of an archived book — or of any imported book that
     * is missing it, which is how books arrive from a backup.
     *
     * @param fileUri a file to read instead of the recorded one.
     * @param acceptChanges restore even if the file no longer matches.
     * @param rememberFile store [fileUri] as the book's file from now on. Only
     *   when the app holds a lasting grant on it; see [restoreFromPickedFile].
     */
    suspend fun restore(
        novelId: String,
        fileUri: Uri? = null,
        acceptChanges: Boolean = false,
        rememberFile: Boolean = fileUri != null
    ): RestoreResult = withContext(Dispatchers.IO) {
        lock.withLock {
            val novel = novelDao.getNovelById(novelId) ?: return@withLock RestoreResult.NotFound
            if (novel.source != LOCAL_SOURCE) return@withLock RestoreResult.NotLocal

            val rows = chapterDao.getChaptersForNovelOnce(novelId)
            if (novel.archivedAt == null && rows.isNotEmpty() && rows.all { it.isDownloaded }) {
                return@withLock RestoreResult.NothingToRestore
            }

            val uri = fileUri ?: novel.sourceUri?.let(Uri::parse)
                ?: return@withLock RestoreResult.NeedsFile
            unreadableReason(uri)?.let { return@withLock RestoreResult.FileUnavailable(it) }

            val book = when (val parsed = importer.parseDocument(uri)) {
                is EpubImporter.ParseResult.Failed ->
                    return@withLock RestoreResult.FileUnavailable(parsed.message)
                is EpubImporter.ParseResult.Parsed -> parsed.book
            }
            val chapters = book.chapters.sortedBy { it.order }
            val texts = chapters.map { it.content }
            val newFingerprint = fingerprint(texts)

            if (!acceptChanges) {
                val same = novel.textFingerprint?.let { it == newFingerprint }
                    // Books from before v20 have no fingerprint. Chapter
                    // count and titles are the strongest evidence left.
                    ?: (rows.size == chapters.size &&
                            rows.zip(chapters).all { (row, ch) -> row.title == ch.title })
                if (!same) {
                    return@withLock RestoreResult.DifferentText(chapters.size, rows.size)
                }
            }

            val existing = rows.associateBy { it.number }
            val now = System.currentTimeMillis()
            database.withTransaction {
                chapters.forEachIndexed { index, chapter ->
                    val number = index + 1
                    val row = existing[number]
                    if (row != null) {
                        chapterDao.markChapterDownloaded(row.id, chapter.content, now)
                    } else {
                        chapterDao.insertChapter(
                            ChapterEntity(
                                id = "${novelId}_ch$number",
                                novelId = novelId,
                                number = number,
                                title = chapter.title,
                                url = "local://$novelId/$number",
                                isDownloaded = true,
                                content = chapter.content,
                                downloadedAt = now
                            )
                        )
                    }
                }
                // Only reachable with acceptChanges: the file got shorter.
                chapterDao.deleteChaptersAfter(novelId, chapters.size)
                novelDao.markRestored(
                    novelId = novelId,
                    fingerprint = newFingerprint,
                    totalChapters = chapters.size,
                    uri = if (rememberFile) fileUri?.toString() else null
                )
            }
            Logger.d(TAG, "Restored ${chapters.size} chapters for $novelId")
            RestoreResult.Restored(chapters.size)
        }
    }

    /**
     * Restore from a file the user just picked because the recorded one is
     * gone or never existed.
     *
     * Takes a lasting grant on [uri] so the book stays archivable, and on
     * success hands back the grant on the file it replaces — unless that file
     * came through the library folder, or another book still points at it.
     * If Android will not keep the grant, the book is still restored, just
     * without remembering the file.
     */
    suspend fun restoreFromPickedFile(
        novelId: String,
        uri: Uri,
        acceptChanges: Boolean = false
    ): RestoreResult {
        val previous = novelDao.getSourceUri(novelId)
        val held = BookFileAccess.hold(appContext, uri)
        val result = restore(novelId, uri, acceptChanges, rememberFile = held)

        if (held) {
            if (result is RestoreResult.Restored) {
                // The picked file replaced the old one.
                if (previous != null && previous != uri.toString()) releaseIfUnused(previous)
            } else {
                // The picked file was not used after all.
                releaseIfUnused(uri.toString())
            }
        }
        return result
    }

    /**
     * Why [uri] cannot be opened, in words for the user, or null if it can.
     *
     * Checked before parsing because a parse failure surfaces as an exception
     * message — a provider's internal path and a stack-trace phrase — which
     * is the wrong thing to show someone whose only question is where their
     * book went. Whatever is wrong *inside* a readable file still comes from
     * the parser, which already words its errors for users.
     */
    private fun unreadableReason(uri: Uri): String? = try {
        val stream = appContext.contentResolver.openInputStream(uri)
        stream?.close()
        if (stream == null) "the file couldn't be opened" else null
    } catch (e: java.io.FileNotFoundException) {
        "it may have been moved, renamed or deleted"
    } catch (e: SecurityException) {
        "the app no longer has permission to read it"
    } catch (e: Exception) {
        "the file couldn't be opened"
    }

    private suspend fun releaseIfUnused(uri: String) {
        if (LibraryFolder.isInFolder(uri) || novelDao.isSourceUriInUse(uri)) return
        BookFileAccess.release(appContext, Uri.parse(uri))
    }

    /** Tally of [archiveAll], by what happened to each book. */
    data class BulkArchiveSummary(
        val archived: Int = 0,
        val freedBytes: Long = 0,
        /** No file recorded — imported by hand before files were remembered. */
        val noFile: Int = 0,
        /** The file could not be opened or parsed. */
        val unavailable: Int = 0,
        /** The file no longer parses into the stored text. */
        val changed: Int = 0,
        /** Stopped early by the caller; the rest were not looked at. */
        val stopped: Boolean = false
    ) {
        val skipped: Int get() = noFile + unavailable + changed
    }

    /**
     * Archive every imported book that still holds its text.
     *
     * One book at a time through [archive], so every book gets the same
     * check a single archive does and a book that fails it is skipped, not
     * the whole run. Each book is its own transaction: stopping halfway —
     * by [shouldStop], or the process dying — leaves every book either fully
     * archived or untouched, and running again picks up the rest.
     *
     * @param onProgress called before each book with (done, total).
     * @param shouldStop checked between books.
     */
    suspend fun archiveAll(
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
        shouldStop: () -> Boolean = { false }
    ): BulkArchiveSummary {
        val ids = novelDao.getUnarchivedLocalNovelIds()
        var summary = BulkArchiveSummary()
        for ((index, id) in ids.withIndex()) {
            if (shouldStop()) return summary.copy(stopped = true)
            onProgress(index, ids.size)
            val result = try {
                archive(id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // One unreadable book must not end a run over thousands.
                Logger.e(TAG, "Archiving $id failed", e)
                ArchiveResult.FileUnavailable(e.message ?: "unknown error")
            } catch (e: OutOfMemoryError) {
                ArchiveResult.FileUnavailable("the file is too large to check")
            }
            summary = when (result) {
                is ArchiveResult.Archived -> summary.copy(
                    archived = summary.archived + 1,
                    freedBytes = summary.freedBytes + result.freedBytes
                )
                ArchiveResult.NoFile -> summary.copy(noFile = summary.noFile + 1)
                is ArchiveResult.FileUnavailable ->
                    summary.copy(unavailable = summary.unavailable + 1)
                ArchiveResult.FileChanged -> summary.copy(changed = summary.changed + 1)
                // Archived, removed or changed source since the list was read.
                ArchiveResult.AlreadyArchived,
                ArchiveResult.NotFound,
                ArchiveResult.NotLocal -> summary
            }
        }
        onProgress(ids.size, ids.size)
        return summary
    }

    suspend fun isArchived(novelId: String): Boolean =
        novelDao.getNovelById(novelId)?.archivedAt != null

    companion object {
        private const val TAG = "LocalBookArchive"
        const val LOCAL_SOURCE = "local"

        /**
         * One lock for every instance: an archive and a restore of the same
         * book racing each other could leave it marked archived with its text
         * present, or restored with none.
         */
        private val lock = Mutex()

        /**
         * SHA-256 over chapter texts in order. Each text is length-prefixed so
         * that moving a paragraph across a chapter boundary — same characters,
         * different chapters — still changes the result.
         */
        fun fingerprint(chapterTexts: List<String>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(chapterTexts.size.toString().toByteArray())
            for (text in chapterTexts) {
                val bytes = text.toByteArray(Charsets.UTF_8)
                digest.update("\u0000${bytes.size}\u0000".toByteArray())
                digest.update(bytes)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
