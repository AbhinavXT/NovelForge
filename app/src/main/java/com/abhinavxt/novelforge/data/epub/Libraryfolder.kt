package com.abhinavxt.novelforge.data.epub

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * A folder of book files the user already keeps on their device, which
 * NovelForge can enumerate and import from.
 *
 * The folder is a reading source, not a storage location: importing still
 * parses each file into the database, and the app never writes into the
 * folder or depends on it afterwards. Pointing NovelForge at the same
 * directory another reader app uses is therefore safe — nothing is moved,
 * renamed, or modified.
 *
 * Enumeration uses raw [DocumentsContract] rather than androidx.documentfile,
 * matching AutoBackupWorker, so no new dependency is needed.
 */
object LibraryFolder {

    private const val TAG = "LibraryFolder"

    const val PREFS_NAME = "library_folder_prefs"
    private const val PREF_TREE_URI = "tree_uri"
    private const val PREF_LAST_SCAN = "last_scan_time"
    private const val PREF_IGNORED_URIS = "ignored_uris"
    private const val PREF_DUPLICATE_URIS = "duplicate_uris"

    /**
     * Subdirectories are followed, because organising books into
     * author or series folders is the norm. The depth cap stops a
     * scan pointed at the storage root from walking the whole device,
     * and the file cap bounds memory for the returned list.
     */
    private const val MAX_DEPTH = 6
    private const val MAX_FILES = 5000

    /** One importable file found in the folder tree. */
    data class ScannedFile(
        val uri: Uri,
        val name: String,
        val format: DocumentFormat
    )

    /** Outcome of enumerating the folder, before any importing happens. */
    sealed interface ScanListing {
        data class Found(
            val newFiles: List<ScannedFile>,
            val alreadyImported: Int,
            val ignored: Int
        ) : ScanListing

        data class Error(val message: String) : ScanListing
    }

    // ── Folder selection ────────────────────────────────────────

    fun getFolderUri(context: Context): String? =
        prefs(context).getString(PREF_TREE_URI, null)

    fun setFolderUri(context: Context, uri: String) {
        prefs(context).edit().putString(PREF_TREE_URI, uri).apply()
    }

    fun clearFolder(context: Context) {
        // Both skip lists hold URIs built from the old tree grant. They are
        // meaningless against a different folder, so dropping them here
        // stops a stale entry from silently suppressing a real file later.
        prefs(context).edit()
            .remove(PREF_TREE_URI)
            .remove(PREF_LAST_SCAN)
            .remove(PREF_IGNORED_URIS)
            .remove(PREF_DUPLICATE_URIS)
            .apply()
    }

    fun getLastScanTime(context: Context): Long =
        prefs(context).getLong(PREF_LAST_SCAN, 0L)

    fun setLastScanTime(context: Context, time: Long) {
        prefs(context).edit().putLong(PREF_LAST_SCAN, time).apply()
    }

    /**
     * True while the persisted grant is still held. The user can revoke
     * folder access from system settings at any time, and the tree URI
     * outlives the permission, so the stored value alone proves nothing.
     */
    fun hasAccess(context: Context): Boolean {
        val stored = getFolderUri(context) ?: return false
        val treeUri = runCatching { Uri.parse(stored) }.getOrNull() ?: return false
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission
        }
    }

    // ── Removed books ───────────────────────────────────────────

    /**
     * Files the user has deleted from their library after a scan brought
     * them in. Without this, the next scan would import them again: the
     * dedupe key lives on the novel row, so deleting the novel also
     * deletes the record that it was ever seen.
     */
    fun getIgnoredUris(context: Context): Set<String> =
        prefs(context).getStringSet(PREF_IGNORED_URIS, emptySet()) ?: emptySet()

    fun ignoreUri(context: Context, uri: String) {
        val updated = getIgnoredUris(context).toMutableSet().apply { add(uri) }
        prefs(context).edit().putStringSet(PREF_IGNORED_URIS, updated).apply()
    }

    /** Lets a deliberate rescan pick up books the user removed earlier. */
    fun clearIgnoredUris(context: Context) {
        prefs(context).edit().remove(PREF_IGNORED_URIS).apply()
    }

    // ── Resolved duplicates ─────────────────────────────────────

    /**
     * Files a previous scan already judged to be duplicates of something in
     * the library.
     *
     * These need their own record because a rejected file has nowhere else
     * to leave one. The dedupe key lives on the novel row, and that row is
     * already spoken for — either by a different file's URI, or by a hash
     * matched before the file was even parsed. Without this list the scan
     * re-opens the same rejected files on every run and reports them again
     * each time, which is noise rather than information: the user was told
     * once, and nothing has changed since.
     */
    fun getDuplicateUris(context: Context): Set<String> =
        prefs(context).getStringSet(PREF_DUPLICATE_URIS, emptySet()) ?: emptySet()

    fun markDuplicate(context: Context, uri: String) {
        val updated = getDuplicateUris(context).toMutableSet().apply { add(uri) }
        prefs(context).edit().putStringSet(PREF_DUPLICATE_URIS, updated).apply()
    }

    // ── Enumeration ─────────────────────────────────────────────

    /**
     * Walk the chosen folder and return the importable files that are not
     * already in the library.
     *
     * [alreadyImported] is the set of `novels.sourceUri` values, fetched
     * once by the caller — a membership test per file rather than a query
     * per file, which is the difference between a scan of a 300-book
     * folder taking one query or three hundred.
     */
    suspend fun listNewBooks(
        context: Context,
        alreadyImported: Set<String>
    ): ScanListing = withContext(Dispatchers.IO) {
        val stored = getFolderUri(context)
            ?: return@withContext ScanListing.Error("No library folder chosen yet.")

        if (!hasAccess(context)) {
            return@withContext ScanListing.Error(
                "Access to the library folder was revoked. Choose it again."
            )
        }

        val treeUri = runCatching { Uri.parse(stored) }.getOrNull()
            ?: return@withContext ScanListing.Error("The saved library folder is unreadable.")

        try {
            val ignored = getIgnoredUris(context)
            val knownDuplicates = getDuplicateUris(context)
            val found = mutableListOf<ScannedFile>()
            var skippedImported = 0
            var skippedIgnored = 0

            // Breadth-first over subdirectories, tracking depth so a scan
            // pointed at the storage root cannot walk forever.
            val queue = ArrayDeque<Pair<String, Int>>()
            queue.add(DocumentsContract.getTreeDocumentId(treeUri) to 0)
            val visited = mutableSetOf<String>()

            while (queue.isNotEmpty() && found.size < MAX_FILES) {
                coroutineContext.ensureActive()
                val (docId, depth) = queue.removeFirst()
                if (!visited.add(docId)) continue

                val childrenUri =
                    DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)

                context.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ),
                    null, null, null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        if (found.size >= MAX_FILES) break
                        val childId = cursor.getString(0) ?: continue
                        val name = cursor.getString(1) ?: continue
                        val mime = cursor.getString(2)

                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (depth < MAX_DEPTH) queue.add(childId to depth + 1)
                            continue
                        }

                        // Extension only: the cursor already told us the
                        // name, and asking the provider for a MIME type per
                        // file would undo the point of a single query.
                        val format = DocumentFormat.fromFileName(name)
                        if (format != DocumentFormat.EPUB &&
                            format != DocumentFormat.TEXT &&
                            format != DocumentFormat.MARKDOWN
                        ) continue

                        val fileUri =
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                                .toString()

                        when {
                            fileUri in alreadyImported -> skippedImported++
                            fileUri in ignored -> skippedIgnored++
                            // Counted with the already-imported files, not
                            // reported separately: the user was told about
                            // this one on the scan that found it.
                            fileUri in knownDuplicates -> skippedImported++
                            else -> found.add(
                                ScannedFile(Uri.parse(fileUri), name, format)
                            )
                        }
                    }
                }
            }

            // Alphabetical, so a long import reads as orderly progress
            // rather than arriving in whatever order the provider chose.
            ScanListing.Found(
                newFiles = found.sortedBy { it.name.lowercase() },
                alreadyImported = skippedImported,
                ignored = skippedIgnored
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Folder scan failed", e)
            ScanListing.Error("Could not read the library folder: ${e.message ?: "unknown error"}")
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
