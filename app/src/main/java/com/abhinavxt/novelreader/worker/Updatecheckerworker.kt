package com.abhinavxt.novelreader.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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
import com.abhinavxt.novelreader.data.database.AppDatabase
import com.abhinavxt.novelreader.data.database.ChapterEntity
import com.abhinavxt.novelreader.data.source.SourceManager
import com.abhinavxt.novelreader.util.Logger
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

            val db = AppDatabase.getDatabase(context)
            val novelDao = db.novelDao()
            val chapterDao = db.chapterDao()

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val autoDownload = prefs.getBoolean(PREF_AUTO_DOWNLOAD, false)

            val novels = novelDao.getAllNovelsOnce()
            val updatedNovels = mutableListOf<Triple<String, String, Int>>() // id, title, new chapter count

            for (novel in novels) {
                // Skip local/EPUB novels
                if (novel.id.startsWith("local_")) continue

                val source = SourceManager.getSourceFromNovelId(novel.id) ?: continue

                try {
                    // Build the novel URL from the ID
                    val novelUrl = buildNovelUrl(novel.id) ?: continue

                    val freshNovel = source.getNovelDetails(novelUrl) ?: continue
                    val existingMax = chapterDao.getMaxChapterNumber(novel.id) ?: 0

                    val newChapters = freshNovel.chapters.filter { it.number > existingMax }
                    if (newChapters.isEmpty()) continue

                    // Insert new chapter entries into DB
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

                    // Update novel's totalChapters and lastUpdatedAt
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
                                val content = source.getChapterContent(ch.url)
                                if (content != null) {
                                    chapterDao.markChapterDownloaded(
                                        chapterId = ch.id,
                                        content = content,
                                        downloadedAt = System.currentTimeMillis()
                                    )
                                }
                                // Small delay to be polite to servers
                                kotlinx.coroutines.delay(1000)
                            } catch (e: Exception) {
                                Logger.e(TAG, "Auto-download failed for ${ch.title}", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed checking ${novel.title}", e)
                }
            }

            // Send notification if there are updates
            if (updatedNovels.isNotEmpty()) {
                sendUpdateNotification(
                    updatedNovels.map { it.second to it.third },
                    autoDownload
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

    private fun buildNovelUrl(novelId: String): String? {
        return when {
            novelId.startsWith("rr_") ->
                "https://www.royalroad.com/fiction/${novelId.removePrefix("rr_")}"
            novelId.startsWith("rnf_") ->
                "https://readnovelfull.com/${novelId.removePrefix("rnf_")}.html"
            novelId.startsWith("fwn_") ->
                "https://freewebnovel.com/novel/${novelId.removePrefix("fwn_")}"
            novelId.startsWith("lr_") ->
                "https://libread.com/libread/${novelId.removePrefix("lr_")}"
            novelId.startsWith("nfn_") ->
                "https://novelfull.net/${novelId.removePrefix("nfn_")}.html"
            novelId.startsWith("pr_") -> {
                val slug = novelId.removePrefix("pr_").replace("~", "/")
                "https://pawread.com/$slug"
            }
            novelId.startsWith("pt_") ->
                "https://primodialtranslation.com/series/${novelId.removePrefix("pt_")}/"
            else -> null
        }
    }

    private fun sendUpdateNotification(
        updates: List<Pair<String, Int>>,
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

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
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