package com.abhinavxt.novelforge.worker

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.abhinavxt.novelforge.NovelReaderApplication
import com.abhinavxt.novelforge.data.BackupResult
import com.abhinavxt.novelforge.util.Logger
import java.util.concurrent.TimeUnit

/**
 * Automatic daily backup (Phase 7).
 *
 * Writes a timestamped backup file (BackupManager's existing JSON
 * format) into a user-chosen SAF folder, then prunes to the newest
 * [KEEP_COUNT]. Point the folder at a synced directory (Syncthing,
 * Drive, etc.) and you get free multi-device backup.
 *
 * Uses raw DocumentsContract instead of the androidx.documentfile
 * library so no new dependency is needed.
 */
class AutoBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!isEnabled(context)) return Result.success()
        val folderUriString = getFolderUri(context) ?: return Result.success()

        return try {
            val treeUri = Uri.parse(folderUriString)

            // The user can revoke folder access from system settings at
            // any time. Succeed quietly rather than retry-spinning; the
            // Settings screen shows the folder as needing re-selection.
            val stillGranted = context.contentResolver.persistedUriPermissions.any {
                it.uri == treeUri && it.isWritePermission
            }
            if (!stillGranted) {
                Logger.w(TAG, "Backup folder permission revoked — skipping")
                return Result.success()
            }

            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)

            val app = context as NovelReaderApplication
            val fileName = app.backupManager.generateBackupFilename()

            val fileUri = DocumentsContract.createDocument(
                context.contentResolver,
                parentDocUri,
                "application/json",
                fileName
            ) ?: run {
                Logger.e(TAG, "Could not create backup file in folder")
                return Result.retry()
            }

            when (val result = app.backupManager.createBackup(fileUri)) {
                is BackupResult.Success -> {
                    pruneOldBackups(context, treeUri)
                    setLastBackupTime(context, System.currentTimeMillis())
                    Logger.d(TAG, "Auto-backup written: $fileName")
                    Result.success()
                }
                is BackupResult.Error -> {
                    Logger.e(TAG, "Auto-backup failed: ${result.message}")
                    Result.retry()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Auto-backup crashed", e)
            Result.retry()
        }
    }

    /**
     * Keep only the newest [KEEP_COUNT] auto-backup files. Timestamped
     * filenames (yyyy-MM-dd_HHmmss) sort chronologically as strings, so
     * name-descending order == newest first.
     */
    private fun pruneOldBackups(context: Context, treeUri: Uri) {
        try {
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)

            val backups = mutableListOf<Pair<String, String>>()  // (docId, name)
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0)
                    val name = cursor.getString(1) ?: continue
                    if (name.startsWith("novel_forge_backup_") && name.endsWith(".json")) {
                        backups.add(docId to name)
                    }
                }
            }

            backups
                .sortedByDescending { it.second }
                .drop(KEEP_COUNT)
                .forEach { (docId, name) ->
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    try {
                        DocumentsContract.deleteDocument(context.contentResolver, docUri)
                        Logger.d(TAG, "Pruned old backup: $name")
                    } catch (e: Exception) {
                        Logger.e(TAG, "Could not prune $name", e)
                    }
                }
        } catch (e: Exception) {
            // Pruning failure must never fail the backup itself
            Logger.e(TAG, "Prune pass failed", e)
        }
    }

    companion object {
        private const val TAG = "AutoBackupWorker"
        private const val WORK_NAME = "auto_backup_work"
        private const val KEEP_COUNT = 5

        const val PREFS_NAME = "auto_backup_prefs"
        private const val PREF_ENABLED = "enabled"
        private const val PREF_FOLDER_URI = "folder_uri"
        private const val PREF_LAST_BACKUP = "last_backup_time"

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_ENABLED, false)

        /** Persists the flag AND (re)schedules/cancels the work. */
        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_ENABLED, enabled).apply()
            schedule(context, enabled)
        }

        fun getFolderUri(context: Context): String? =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_FOLDER_URI, null)

        fun setFolderUri(context: Context, uri: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(PREF_FOLDER_URI, uri).apply()
        }

        fun getLastBackupTime(context: Context): Long =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(PREF_LAST_BACKUP, 0L)

        private fun setLastBackupTime(context: Context, time: Long) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(PREF_LAST_BACKUP, time).apply()
        }

        fun schedule(context: Context, enabled: Boolean) {
            val workManager = WorkManager.getInstance(context)
            if (!enabled) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .build()
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
