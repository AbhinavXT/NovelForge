package com.abhinavxt.novelforge.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.abhinavxt.novelforge.data.database.AppDatabase
import com.abhinavxt.novelforge.data.database.ChapterEntity
import com.abhinavxt.novelforge.data.source.SourceManager
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager worker that:
 * 1. Checks all library novels for new chapters
 * 2. Sends a grouped notification if any are found
 * 3. Optionally auto-downloads new chapters (if enabled in prefs)
 *
 * Schedule via [UpdateCheckerWorker.schedule].
 */
class UpdateCheckerWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Starting update check...")

            // #21: Access DB through Application singleton instead of creating new instance
            val app = context.applicationContext as com.abhinavxt.novelforge.NovelReaderApplication
            val db = AppDatabase.getDatabase(context)
            val novelDao = db.novelDao()
            val chapterDao = db.chapterDao()

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val autoDownload = prefs.getBoolean(PREF_AUTO_DOWNLOAD, false)

            val novels = novelDao.getAllNovelsOnce()
                .filter { !it.id.startsWith("local_") }
            val updatedNovels = mutableListOf<Triple<String, String, Int>>()

            // #23: Group by source to throttle per-host
            val novelsBySource = novels.groupBy { novel ->
                novel.id.substringBefore("_")
            }

            for ((sourcePrefix, sourceNovels) in novelsBySource) {
                var requestCount = 0

                for (novel in sourceNovels) {
                    val source = SourceManager.getSourceFromNovelId(novel.id) ?: continue

                    try {
                        // Per-source throttle: 2 seconds between requests to same host
                        if (requestCount > 0) {
                            kotlinx.coroutines.delay(2000)
                        }
                        requestCount++

                        val novelUrl = SourceManager.constructNovelUrl(novel.id)
                        if (novelUrl.isBlank() || novelUrl.startsWith("local://")) continue

                        val freshNovel = source.getNovelDetails(novelUrl) ?: continue
                        val existingMax = chapterDao.getMaxChapterNumber(novel.id) ?: 0

                        val newChapters = freshNovel.chapters.filter { it.number > existingMax }
                        if (newChapters.isEmpty()) continue

                        val newEntities = newChapters.map { ch ->
                            ChapterEntity(
                                id = ch.id,
                                novelId = novel.id,
                                number = ch.number,
                                title = ch.title,
                                url = ch.url,
                                isDownloaded = false
                            )
                        }
                        chapterDao.insertChapters(newEntities)

                        novelDao.updateNovel(
                            novel.copy(
                                totalChapters = freshNovel.chapters.size,
                                lastUpdatedAt = System.currentTimeMillis()
                            )
                        )

                        updatedNovels.add(Triple(novel.id, novel.title, newChapters.size))
                        Logger.d(TAG, "${novel.title}: ${newChapters.size} new chapters")

                        // Auto-download if enabled
                        if (autoDownload) {
                            for (ch in newChapters) {
                                try {
                                    kotlinx.coroutines.delay(2000) // Throttle downloads too
                                    val content = source.getChapterContent(ch.url)
                                    if (content != null) {
                                        chapterDao.markChapterDownloaded(
                                            chapterId = ch.id,
                                            content = content,
                                            downloadedAt = System.currentTimeMillis()
                                        )
                                    }
                                } catch (e: Exception) {
                                    Logger.e(TAG, "Auto-download failed for ${ch.title}", e)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, "Failed checking ${novel.title}", e)
                    }
                }
            }

            // Send notification if there are updates
            if (updatedNovels.isNotEmpty()) {
                sendUpdateNotification(
                    updates = updatedNovels.map { it.second to it.third },
                    novelIds = updatedNovels.map { it.first },
                    autoDownloaded = autoDownload
                )

                // Store updated novel IDs for badge display in library
                val existingIds = prefs.getStringSet(PREF_UPDATED_NOVEL_IDS, emptySet())
                    ?.toMutableSet() ?: mutableSetOf()
                existingIds.addAll(updatedNovels.map { it.first })
                prefs.edit().putStringSet(PREF_UPDATED_NOVEL_IDS, existingIds).apply()
            }

            Logger.d(TAG, "Update check complete. ${updatedNovels.size} novels updated.")
            Result.success()
        } catch (e: Exception) {
            Logger.e(TAG, "Update check failed", e)
            Result.retry()
        }
    }

    private fun sendUpdateNotification(
        updates: List<Pair<String, Int>>,
        novelIds: List<String>,
        autoDownloaded: Boolean
    ) {
        createNotificationChannel()

        // Check notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) return
        }

        val totalNew = updates.sumOf { it.second }
        val title = if (updates.size == 1) {
            "${updates[0].first}: ${updates[0].second} new chapter${if (updates[0].second > 1) "s" else ""}"
        } else {
            "$totalNew new chapters across ${updates.size} novels"
        }

        val body = if (autoDownloaded) {
            updates.joinToString("\n") { "${it.first} — ${it.second} downloaded" }
        } else {
            updates.joinToString("\n") { "${it.first} — ${it.second} new" }
        }

        // Deep-link: tapping the notification opens the novel detail screen
        // Single novel → opens that novel. Multiple → opens library.
        val intent = Intent(context, com.abhinavxt.novelforge.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (novelIds.size == 1) {
                putExtra(EXTRA_NAVIGATE_NOVEL, novelIds[0])
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Chapter Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications when new chapters are available"
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "UpdateChecker"
        private const val CHANNEL_ID = "chapter_updates"
        private const val NOTIFICATION_ID = 9001
        private const val WORK_NAME = "novel_update_checker"

        const val PREFS_NAME = "update_checker_prefs"
        const val PREF_ENABLED = "update_checker_enabled"
        const val PREF_INTERVAL_HOURS = "update_checker_interval"
        const val PREF_AUTO_DOWNLOAD = "update_checker_auto_download"
        const val PREF_UPDATED_NOVEL_IDS = "updated_novel_ids"
        const val EXTRA_NAVIGATE_NOVEL = "navigate_to_novel"

        /**
         * Schedule or cancel the periodic update checker.
         *
         * @param intervalHours How often to check (6, 12, 24). Minimum is 6 per WorkManager.
         */
        fun schedule(context: Context, enabled: Boolean, intervalHours: Long = 12) {
            val workManager = WorkManager.getInstance(context)

            if (!enabled) {
                workManager.cancelUniqueWork(WORK_NAME)
                Logger.d(TAG, "Update checker disabled")
                return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            // Clamp to minimum 6 hours (WorkManager PeriodicWork minimum is 15 min,
            // but checking more often than every 6 hours is wasteful)
            val interval = intervalHours.coerceAtLeast(6)

            val request = PeriodicWorkRequestBuilder<UpdateCheckerWorker>(
                interval, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )

            Logger.d(TAG, "Update checker scheduled every ${interval}h")
        }

        /**
         * Run a one-time check immediately.
         */
        fun runNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = androidx.work.OneTimeWorkRequestBuilder<UpdateCheckerWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(request)
            Logger.d(TAG, "One-time update check enqueued")
        }
    }
}