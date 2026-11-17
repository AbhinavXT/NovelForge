package com.abhinavxt.novelforge.data

import android.content.Context
import android.net.Uri
import com.abhinavxt.novelforge.data.epub.BookFileAccess
import com.abhinavxt.novelforge.data.epub.EpubImporter
import com.abhinavxt.novelforge.data.epub.LibraryFolder

/**
 * The one way a book leaves the library, shared by the Library screen and by sync.
 *
 * Steps, in order: remember a folder-scanned file as unwanted (the novel row IS the record that the
 * scan saw the file, so without this the next scan imports it again); delete an imported book's
 * cover; delete the rows (novel, chapters, progress, bookmarks, highlights, category links,
 * updates, translation data); clear imported-book formatting and tell the widget; release the picker
 * grant of a hand-picked file nobody else uses. The user's original file is never touched. [context]
 * is null in tests and context-free callers: the file steps are skipped.
 *
 * [remove] does all of it. Sync calls [removeRows] inside its database transaction and runs the
 * returned [Effects] only after that transaction committed, so a rollback leaves the files, the
 * ignore list and the grants alone. The effects are ordered so that, if the process dies between the
 * commit and the effects, the worst case is a leftover cover or formatting file, a grant that is not
 * released, or a missing ignore record (a folder scan may import the file again); never a book that
 * is in the database but unreadable.
 */
class LibraryRemover(
    private val repository: NovelRepository,
    private val context: Context? = null,
    private val deleteLocalFiles: (suspend (novelId: String) -> Unit)? =
        context?.let { c -> { id -> EpubImporter(c).deleteLocalNovel(id) } },
) {
    /** Test seams for the two file steps; the defaults do the real thing. */
    internal var ignoreFolderUri: (uri: String) -> Unit =
        { uri -> context?.let { LibraryFolder.ignoreUri(it, uri) } }
    internal var releaseFileAccess: (uri: String) -> Unit =
        { uri -> context?.let { BookFileAccess.release(it, Uri.parse(uri)) } }

    /** The non-database part of one removal. */
    inner class Effects internal constructor(
        private val novelId: String,
        private val ignore: String?,
        private val release: String?,
    ) {
        private var ignored = false

        /** Writes the folder-scan ignore record (idempotent). */
        fun ignoreNow() {
            if (ignored) return
            ignored = true
            ignore?.let(ignoreFolderUri)
        }

        /** What the old `remove` did before it deleted the rows. */
        suspend fun beforeRows() {
            ignoreNow()
            if (novelId.startsWith("local_")) deleteLocalFiles?.invoke(novelId)
        }

        /** What is left after the commit when [ignoreNow] already ran before the rows were deleted. */
        suspend fun afterCommit() {
            // Each step on its own: a failing one (a locked file) must not skip the rest.
            try { ignoreNow() } catch (_: Exception) {}
            try {
                if (novelId.startsWith("local_")) deleteLocalFiles?.invoke(novelId)
            } catch (_: Exception) {}
            afterRows()
        }

        /** What the old `remove` did after it deleted the rows. */
        suspend fun afterRows() {
            try {
                repository.cleanupAfterRemoval(novelId)
            } finally {
                // A failing cleanup must not leave the file grant behind.
                release?.let { uri ->
                    // Another book may hold the same file (a re-import linked as a duplicate); keep its grant.
                    if (!repository.isSourceUriInUse(uri)) releaseFileAccess(uri)
                }
            }
        }

        /** Everything, once the rows are gone and committed. */
        suspend fun run() {
            beforeRows()
            afterRows()
        }
    }

    private suspend fun plan(novelId: String): Effects {
        var ignore: String? = null
        var release: String? = null
        if (context != null) {
            repository.getSourceUri(novelId)?.let { uri ->
                // A hand-picked file's grant is released once the row is gone.
                if (LibraryFolder.isInFolder(uri)) ignore = uri else release = uri
            }
        }
        return Effects(novelId, ignore, release)
    }

    suspend fun remove(novelId: String) {
        val fx = plan(novelId)
        fx.beforeRows()
        repository.removeRowsFromLibrary(novelId)
        fx.afterRows()
    }

    /**
     * Deletes the rows only (safe inside a transaction) and returns the work to run after it commits.
     * The folder-scan ignore record is written here, before the rows go: if the transaction commits and
     * the process dies or is cancelled before the effects run, the scan still does not re-import the file.
     * If the transaction rolls back, the record is a harmless entry for a book that is still in the
     * library: the scan already skips files of books it knows, and the entry only matters once that
     * book is removed (which writes the same entry again).
     */
    suspend fun removeRows(novelId: String): Effects {
        val fx = plan(novelId)
        fx.ignoreNow()
        repository.removeRowsFromLibrary(novelId)
        return fx
    }
}
