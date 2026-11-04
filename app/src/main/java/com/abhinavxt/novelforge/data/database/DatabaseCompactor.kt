package com.abhinavxt.novelforge.data.database

import android.content.Context
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Gives the space freed by archiving and deleting back to the device.
 *
 * Deleting rows does not shrink an SQLite file. Freed pages go on a free
 * list for reuse, and the search index is worse: FTS4 records a deletion as
 * a marker and keeps the old entries until its segments are merged. So
 * archiving a 2 GB library frees nothing the user can see in their storage
 * settings until the file is rewritten — which is what this does:
 *
 * 1. **Merge the search index** (`optimize`), which drops the entries of
 *    every deleted or archived chapter. Without this step the rest has
 *    little to give back, since the index is about a third of the text.
 * 2. **VACUUM**, which rewrites the file with only live pages. Skipped when
 *    [vacuumBlocker] says it can't run safely; step 1 and 3 still help.
 * 3. **Checkpoint and truncate the WAL**, which otherwise keeps the size of
 *    everything step 1 and 2 just wrote.
 */
class DatabaseCompactor(
    private val context: Context,
    private val database: AppDatabase
) {

    /**
     * @param fileBytes what the database takes on disk, its WAL included.
     * @param reclaimableBytes free pages a VACUUM would drop. A lower bound:
     *   stale search-index entries only become free pages once merged.
     */
    data class Usage(val fileBytes: Long, val reclaimableBytes: Long)

    sealed interface Result {
        /**
         * @param vacuumSkipped why step 2 did not run, in words for the user,
         *   or null if it did.
         */
        data class Compacted(
            val beforeBytes: Long,
            val afterBytes: Long,
            val vacuumSkipped: String? = null
        ) : Result {
            val freedBytes: Long get() = (beforeBytes - afterBytes).coerceAtLeast(0)
        }

        data class Failed(val message: String) : Result
    }

    private val sqlite get() = database.openHelper.writableDatabase

    suspend fun usage(): Usage = withContext(Dispatchers.IO) {
        val pages = pragma("page_count")
        val free = pragma("freelist_count")
        val pageSize = pragma("page_size")
        Usage(
            fileBytes = fileBytes() ?: (pages * pageSize),
            reclaimableBytes = free * pageSize
        )
    }

    suspend fun compact(): Result = withContext(Dispatchers.IO) {
        val before = usage().fileBytes
        try {
            // Room creates the index as `chapters_fts` (see MIGRATION_13_14).
            sqlite.execSQL("INSERT INTO chapters_fts(chapters_fts) VALUES('optimize')")

            val blocker = vacuumBlocker()
            if (blocker == null) {
                sqlite.execSQL("VACUUM")
            } else {
                Logger.d(TAG, "Skipping VACUUM: $blocker")
                // A database created with incremental auto-vacuum can still
                // drop its free pages without a full rewrite.
                if (pragma("auto_vacuum") == AUTO_VACUUM_INCREMENTAL) {
                    query("PRAGMA incremental_vacuum")
                }
            }

            // Busy readers can stop a TRUNCATE checkpoint from finishing.
            // That is not a failure: the WAL is reset on the next one.
            query("PRAGMA wal_checkpoint(TRUNCATE)")

            Result.Compacted(before, usage().fileBytes, vacuumSkipped = blocker)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // SQLITE_FULL mid-VACUUM is the realistic case. SQLite rolls the
            // rewrite back, so the database is as it was, only not smaller.
            Logger.e(TAG, "Compaction failed", e)
            Result.Failed(e.message ?: "unknown error")
        }
    }

    /**
     * Why VACUUM should not run now, or null if it can.
     *
     * VACUUM copies every live page into a temporary database and then back
     * into the main file through the WAL. Android builds SQLite to keep
     * temporary databases in memory, so the copy costs native memory the
     * size of the live data, and the write-back needs that much disk again.
     * After a bulk archive the live data is small — metadata, progress,
     * online downloads — and both are cheap; with gigabytes of text still
     * stored, they are not, and a VACUUM that dies of either has spent
     * minutes for nothing.
     */
    private fun vacuumBlocker(): String? {
        val live = (pragma("page_count") - pragma("freelist_count")) * pragma("page_size")
        if (live > MAX_VACUUM_LIVE_BYTES) {
            return "the library still stores too much text to rewrite in one go — " +
                    "archive more books or delete downloads first"
        }
        val dir = databaseFile()?.parentFile ?: context.filesDir
        if (dir.usableSpace < live * 2) {
            return "not enough free storage to rewrite the database"
        }
        return null
    }

    private fun databaseFile(): File? =
        database.openHelper.databaseName?.let { context.getDatabasePath(it) }

    /** Main file plus WAL, or null for an in-memory database. */
    private fun fileBytes(): Long? {
        val main = databaseFile()?.takeIf { it.exists() } ?: return null
        val wal = File(main.path + "-wal")
        return main.length() + (if (wal.exists()) wal.length() else 0L)
    }

    private fun pragma(name: String): Long =
        sqlite.query("PRAGMA $name").use { if (it.moveToFirst()) it.getLong(0) else 0L }

    /** Run a statement that returns rows, which `execSQL` refuses on Android. */
    private fun query(sql: String) {
        sqlite.query(sql).use { it.moveToFirst() }
    }

    companion object {
        private const val TAG = "DatabaseCompactor"
        private const val AUTO_VACUUM_INCREMENTAL = 2L

        /**
         * Above this much live data, VACUUM's in-memory copy risks being
         * killed on a low-end phone. Far above what an archived library
         * needs: ten thousand archived books keep a few tens of MB.
         */
        private const val MAX_VACUUM_LIVE_BYTES = 256L * 1024 * 1024
    }
}
