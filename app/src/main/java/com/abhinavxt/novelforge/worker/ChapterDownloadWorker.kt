package com.abhinavxt.novelforge.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.abhinavxt.novelforge.MainActivity
import com.abhinavxt.novelforge.NovelReaderApplication
import com.abhinavxt.novelforge.data.DownloadQueue
import com.abhinavxt.novelforge.data.source.SourceManager
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Drains the download queue, oldest chapter first, outside any screen.
 *
 * Run as unique work, so there is only ever one; enqueuing more chapters while it
 * runs just adds rows it will pick up. State lives in the table, not here: if the
 * process dies the next run starts from whatever the rows say.
 */
class ChapterDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val manager = (context.applicationContext as NovelReaderApplication).downloadManager
    private val queue = manager.queue

    override suspend fun doWork(): Result {
        if (manager.isPaused) return Result.success()

        // Rows still DOWNLOADING belong to a run that never finished.
        queue.requeueInterrupted()

        var done = 0
        var failed = 0
        var foreground = false
        try {
            while (true) {
                val row = queue.next() ?: break
                val total = done + queue.pendingCount()
                val notification = progressNotification(row.novelTitle, done, total)
                if (!foreground) {
                    foreground = promote(notification)
                } else {
                    notify(PROGRESS_ID, notification)
                }

                when (val outcome = queue.attempt(row) { fetch(it) }) {
                    DownloadQueue.Outcome.Done -> {
                        done++
                        manager.noteCompleted(row.novelId)
                    }
                    DownloadQueue.Outcome.WillRetry -> delay(RETRY_DELAY_MS * (row.attempts + 1))
                    is DownloadQueue.Outcome.GaveUp -> {
                        failed++
                        Logger.d(TAG, "Gave up on ${row.chapterTitle}: ${outcome.error}")
                    }
                }

                // Be polite to the source.
                delay(POLITENESS_DELAY_MS)
            }
            if (failed > 0) notifyResult(done, failed)
            return Result.success()
        } catch (e: CancellationException) {
            // Paused, cancelled or the network went: the chapter in flight goes back in line.
            withContext(NonCancellable) { queue.requeueInterrupted() }
            throw e
        } finally {
            NotificationManagerCompat.from(applicationContext).cancel(PROGRESS_ID)
        }
    }

    private suspend fun fetch(row: com.abhinavxt.novelforge.data.database.DownloadQueueEntity): String? =
        SourceManager.getSourceFromNovelId(row.novelId)?.getChapterContent(row.chapterUrl)

    /** Makes the run a foreground service so the system doesn't stop it with the app closed. */
    private suspend fun promote(notification: android.app.Notification): Boolean = try {
        setForeground(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                ForegroundInfo(PROGRESS_ID, notification)
            }
        )
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The system can refuse a start from the background; the work still runs while the app lives.
        Logger.e(TAG, "Could not run in the foreground", e)
        false
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                applicationContext, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    private fun notify(id: Int, notification: android.app.Notification) {
        if (!canNotify()) return
        runCatching { NotificationManagerCompat.from(applicationContext).notify(id, notification) }
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            applicationContext, requestCode,
            Intent(applicationContext, DownloadActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun progressNotification(novelTitle: String, done: Int, total: Int): android.app.Notification {
        ensureChannel(applicationContext)
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading $novelTitle")
            .setContentText("$done of $total")
            .setProgress(total.coerceAtLeast(1), done, false)
            .setContentIntent(openAppIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Pause", actionIntent(DownloadActionReceiver.ACTION_PAUSE, 1))
            .addAction(0, "Cancel", actionIntent(DownloadActionReceiver.ACTION_CANCEL, 2))
            .build()
    }

    private fun notifyResult(done: Int, failed: Int) {
        ensureChannel(applicationContext)
        notify(
            RESULT_ID,
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Downloaded $done chapters")
                .setContentText("$failed failed")
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build()
        )
    }

    companion object {
        private const val TAG = "ChapterDownloadWorker"
        const val WORK_NAME = "chapter_downloads"
        private const val CHANNEL_ID = "chapter_downloads"
        private const val PROGRESS_ID = 7_100_000
        private const val RESULT_ID = 7_100_001
        private const val POLITENESS_DELAY_MS = 500L
        private const val RETRY_DELAY_MS = 2_000L

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Chapter downloads", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Progress of chapters downloading in the background" }
            )
        }
    }
}

/** Handles the Pause and Cancel buttons on the download notification. */
class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = (context.applicationContext as? NovelReaderApplication)?.downloadManager ?: return
        when (intent.action) {
            ACTION_PAUSE -> manager.pause()
            ACTION_CANCEL -> manager.cancelAll()
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.abhinavxt.novelforge.action.DOWNLOADS_PAUSE"
        const val ACTION_CANCEL = "com.abhinavxt.novelforge.action.DOWNLOADS_CANCEL"
    }
}
