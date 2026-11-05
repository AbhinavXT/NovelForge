package com.abhinavxt.novelforge.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.abhinavxt.novelforge.MainActivity
import com.abhinavxt.novelforge.data.UserFacingException
import com.abhinavxt.novelforge.data.epub.EpubImporter
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException

/** Where one background book download has got to, as a screen shows it. */
sealed interface BookDownloadState {
    data object Queued : BookDownloadState
    data class Running(val bytes: Long, val total: Long?) : BookDownloadState
    data class Imported(val novelId: String, val title: String) : BookDownloadState
    data class AlreadyInLibrary(val title: String) : BookDownloadState
    data class Failed(val message: String) : BookDownloadState
}

/**
 * Downloads one book file and imports it, outside any screen.
 *
 * Subclasses only say how to fetch the file ([fetch]); this class owns the
 * rest, the same for every source: progress to [setProgress] and a
 * notification, the import, a finished notification that opens the book,
 * and output data that [statesByKey] turns back into [BookDownloadState]s.
 *
 * Each download is tagged `<tag prefix>:<key>`, where the key identifies
 * the book within its source (an MD5, a hash of a URL).
 */
abstract class BookDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    /** Shown in notifications before the import knows the real title. */
    protected val displayTitle: String = inputData.getString(KEY_TITLE).orEmpty().ifBlank { "Book" }

    /** Identifies the book within its source; also names the temporary file. */
    protected abstract val bookKey: String

    /** Downloads the book's EPUB into [dest]. Throw [UserFacingException] for a message worth showing. */
    protected abstract suspend fun fetch(dest: File, onProgress: (Long, Long?) -> Unit)

    private val notificationId: Int
        get() = NOTIFICATION_BASE + bookKey.hashCode().and(0xFFFF)

    final override suspend fun doWork(): Result {
        val dir = File(applicationContext.cacheDir, "book_downloads").apply { mkdirs() }
        val file = File(dir, "${bookKey.filter { it.isLetterOrDigit() }.take(64)}.epub")

        return try {
            notifyProgress(0, null)
            var lastUpdate = 0L
            fetch(file) { bytes, total ->
                // Progress arrives every few KB; the UI and notification need far less.
                val now = System.currentTimeMillis()
                if (now - lastUpdate >= 500 || bytes == total) {
                    lastUpdate = now
                    setProgressAsync(workDataOf(KEY_BYTES to bytes, KEY_TOTAL to (total ?: -1L)))
                    notifyProgress(bytes, total)
                }
            }

            notifyProgress(-1, null)
            when (val r = EpubImporter(applicationContext).importDocument(Uri.fromFile(file))) {
                is EpubImporter.ImportResult.Success -> {
                    notifyDone("Added to library", r.title, r.novelId, ok = true)
                    Result.success(workDataOf(KEY_NOVEL_ID to r.novelId, KEY_RESULT_TITLE to r.title))
                }
                is EpubImporter.ImportResult.Duplicate -> {
                    notifyDone("Already in your library", r.existingTitle, null, ok = true)
                    Result.success(workDataOf(KEY_DUPLICATE to true, KEY_RESULT_TITLE to r.existingTitle))
                }
                is EpubImporter.ImportResult.Error -> fail(r.message)
            }
        } catch (e: CancellationException) {
            NotificationManagerCompat.from(applicationContext).cancel(notificationId)
            throw e
        } catch (e: UserFacingException) {
            fail(e.message ?: "Download failed.")
        } catch (e: IOException) {
            Logger.e(TAG, "Download failed", e)
            fail("Network error. Check your connection and try again.")
        } catch (e: Exception) {
            Logger.e(TAG, "Download failed", e)
            fail("Something went wrong: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            // The importer copies the text into the database; the file is spent.
            file.delete()
            File(file.parentFile, file.name + ".part").delete()
        }
    }

    private fun fail(message: String): Result {
        notifyDone("Couldn't add \"$displayTitle\"", message, null, ok = false)
        return Result.failure(workDataOf(KEY_ERROR to message))
    }

    // ── Notifications ───────────────────────────────────────────────────

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    applicationContext, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED

    /** @param bytes -1 while importing. */
    private fun notifyProgress(bytes: Long, total: Long?) {
        if (!canNotify()) return
        ensureChannel(applicationContext)
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(displayTitle)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
        when {
            bytes < 0 -> builder.setContentText("Adding to library…").setProgress(0, 0, true)
            total != null && total > 0 -> builder
                .setContentText("Downloading")
                .setProgress(100, ((bytes * 100) / total).toInt().coerceIn(0, 100), false)
            else -> builder.setContentText("Downloading").setProgress(0, 0, true)
        }
        runCatching { NotificationManagerCompat.from(applicationContext).notify(notificationId, builder.build()) }
    }

    private fun notifyDone(heading: String, text: String, novelId: String?, ok: Boolean) {
        if (!canNotify()) return
        ensureChannel(applicationContext)
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (novelId != null) putExtra(UpdateCheckerWorker.EXTRA_NAVIGATE_NOVEL, novelId)
        }
        val pending = PendingIntent.getActivity(
            applicationContext, notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(heading)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(applicationContext).notify(notificationId, notification) }
    }

    companion object {
        private const val TAG = "BookDownloadWorker"
        private const val CHANNEL_ID = "annas_downloads" // kept from 2.1.2 so existing settings carry over
        private const val NOTIFICATION_BASE = 7_000_000

        const val KEY_TITLE = "title"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL = "total"
        const val KEY_NOVEL_ID = "novel_id"
        const val KEY_RESULT_TITLE = "result_title"
        const val KEY_DUPLICATE = "duplicate"
        const val KEY_ERROR = "error"

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Book downloads", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Books downloading into your library" }
            )
        }

        /**
         * One state per book key, from the downloads tagged `<tagPrefix>:<key>`.
         * An unfinished attempt outranks finished ones, so a retry in progress
         * hides the failure before it; a cancelled download leaves no state.
         */
        fun statesByKey(infos: List<WorkInfo>, tagPrefix: String): Map<String, BookDownloadState> {
            val result = LinkedHashMap<String, BookDownloadState>()
            for (info in infos) {
                val key = info.tags.firstNotNullOfOrNull { tag ->
                    tag.removePrefix("$tagPrefix:").takeIf { it != tag }
                } ?: continue
                val state = when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> BookDownloadState.Queued
                    WorkInfo.State.RUNNING -> {
                        val total = info.progress.getLong(KEY_TOTAL, -1L)
                        BookDownloadState.Running(
                            bytes = info.progress.getLong(KEY_BYTES, 0L),
                            total = total.takeIf { it > 0 }
                        )
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val out: Data = info.outputData
                        val title = out.getString(KEY_RESULT_TITLE).orEmpty()
                        val novelId = out.getString(KEY_NOVEL_ID)
                        if (novelId != null) BookDownloadState.Imported(novelId, title)
                        else BookDownloadState.AlreadyInLibrary(title)
                    }
                    WorkInfo.State.FAILED -> BookDownloadState.Failed(
                        info.outputData.getString(KEY_ERROR) ?: "Download failed."
                    )
                    WorkInfo.State.CANCELLED -> null
                }
                val existing = result[key]
                if (existing is BookDownloadState.Queued || existing is BookDownloadState.Running) continue
                if (state == null) result.remove(key) else result[key] = state
            }
            return result
        }
    }
}
