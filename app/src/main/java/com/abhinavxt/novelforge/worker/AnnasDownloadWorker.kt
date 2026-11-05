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
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.abhinavxt.novelforge.MainActivity
import com.abhinavxt.novelforge.data.annas.AnnasArchiveClient
import com.abhinavxt.novelforge.data.annas.AnnasArchiveException
import com.abhinavxt.novelforge.data.annas.AnnasBook
import com.abhinavxt.novelforge.data.epub.EpubImporter
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException

/**
 * Downloads one Anna's Archive book and imports it, outside any screen.
 *
 * One unique work per MD5, so tapping the same book twice doesn't fetch it
 * twice, and leaving the screen doesn't stop the download. Progress goes to
 * both [setProgress] (for the search screen) and a notification; the
 * finished notification opens the book.
 */
class AnnasDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val md5 = inputData.getString(KEY_MD5).orEmpty()
    private val title = inputData.getString(KEY_TITLE).orEmpty().ifBlank { "Book" }
    private val notificationId = NOTIFICATION_BASE + md5.hashCode().and(0xFFFF)

    override suspend fun doWork(): Result {
        if (md5.length != 32) return Result.failure(failure("Invalid book."))
        val book = AnnasBook(md5 = md5, title = title, format = "epub")
        val dir = File(applicationContext.cacheDir, "annas").apply { mkdirs() }
        val file = File(dir, "$md5.epub")

        return try {
            notifyProgress(0, null)
            var lastUpdate = 0L
            AnnasArchiveClient(applicationContext).download(book, file) { bytes, total ->
                // Progress arrives every 64 KB; the UI and notification need far less.
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
                    notifyDone("Added to library", r.title, r.novelId)
                    Result.success(workDataOf(KEY_NOVEL_ID to r.novelId, KEY_RESULT_TITLE to r.title))
                }
                is EpubImporter.ImportResult.Duplicate -> {
                    notifyDone("Already in your library", r.existingTitle, null)
                    Result.success(workDataOf(KEY_DUPLICATE to true, KEY_RESULT_TITLE to r.existingTitle))
                }
                is EpubImporter.ImportResult.Error -> fail(r.message)
            }
        } catch (e: CancellationException) {
            cancelNotification()
            throw e
        } catch (e: AnnasArchiveException) {
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
        }
    }

    private fun fail(message: String): Result {
        notifyDone("Couldn't add \"$title\"", message, null)
        return Result.failure(failure(message))
    }

    private fun failure(message: String): Data = workDataOf(KEY_ERROR to message)

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
            .setContentTitle(title)
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

    private fun notifyDone(heading: String, text: String, novelId: String?) {
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
            .setSmallIcon(
                if (novelId != null || heading.startsWith("Already")) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error
            )
            .setContentTitle(heading)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(applicationContext).notify(notificationId, notification) }
    }

    private fun cancelNotification() {
        NotificationManagerCompat.from(applicationContext).cancel(notificationId)
    }

    companion object {
        private const val TAG = "AnnasDownloadWorker"
        private const val CHANNEL_ID = "annas_downloads"
        private const val NOTIFICATION_BASE = 7_000_000

        /** Every Anna's Archive download carries this tag. */
        const val TAG_DOWNLOAD = "annas_download"

        const val KEY_MD5 = "md5"
        const val KEY_TITLE = "title"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL = "total"
        const val KEY_NOVEL_ID = "novel_id"
        const val KEY_RESULT_TITLE = "result_title"
        const val KEY_DUPLICATE = "duplicate"
        const val KEY_ERROR = "error"

        fun workName(md5: String) = "annas_download_$md5"

        /**
         * Queues [book]. A download of the same book that's already queued
         * or running is kept; a finished or failed one is replaced, so this
         * is also how a failed download is retried.
         */
        fun enqueue(context: Context, book: AnnasBook) {
            val request = OneTimeWorkRequestBuilder<AnnasDownloadWorker>()
                .setInputData(workDataOf(KEY_MD5 to book.md5, KEY_TITLE to book.title))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .addTag(TAG_DOWNLOAD)
                .addTag("$TAG_DOWNLOAD:${book.md5}")
                .build()
            // KEEP only keeps work that hasn't finished: a queued or running
            // download stays, a failed one is replaced by this new attempt.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName(book.md5), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, md5: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(md5))
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Book downloads", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Books downloading from Anna's Archive" }
            )
        }
    }
}
