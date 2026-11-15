package com.abhinavxt.novelforge.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.abhinavxt.novelforge.data.database.AppDatabase
import com.abhinavxt.novelforge.data.database.ChapterDao
import com.abhinavxt.novelforge.data.database.ChapterEntity
import com.abhinavxt.novelforge.data.database.DownloadQueueDao
import com.abhinavxt.novelforge.data.database.DownloadQueueEntity
import com.abhinavxt.novelforge.data.database.DownloadQueueStatus
import com.abhinavxt.novelforge.data.model.Chapter
import com.abhinavxt.novelforge.worker.ChapterDownloadWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Download state for a single chapter
data class ChapterDownloadState(
    val chapterId: String,
    val status: DownloadStatus,
    val progress: Float = 0f
)

enum class DownloadStatus {
    NOT_DOWNLOADED,
    QUEUED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED
}

// Download state for a novel (bulk download)
data class NovelDownloadState(
    val novelId: String,
    val totalChapters: Int,
    val downloadedChapters: Int,
    val isDownloading: Boolean,
    val currentChapter: String? = null
)

// Download counts for the queue, as the downloads screen summarises them
data class DownloadSummary(
    val queued: Int,
    val downloading: Int,
    val failed: Int,
    val paused: Boolean
) {
    val pending: Int get() = queued + downloading
    val isEmpty: Boolean get() = pending + failed == 0
}

private fun String.toDownloadStatus(): DownloadStatus = when (this) {
    DownloadQueueStatus.DOWNLOADING -> DownloadStatus.DOWNLOADING
    DownloadQueueStatus.FAILED -> DownloadStatus.FAILED
    else -> DownloadStatus.QUEUED
}

/**
 * The queue's rules, apart from any scheduling, so they can be tested against a plain database.
 */
class DownloadQueue(
    private val queueDao: DownloadQueueDao,
    private val chapterDao: ChapterDao
) {
    sealed interface Outcome {
        data object Done : Outcome
        data object WillRetry : Outcome
        data class GaveUp(val error: String) : Outcome
    }

    /**
     * Adds the chapters that are neither downloaded nor already queued, in one batch
     * that keeps chapter order. Returns how many were added.
     */
    suspend fun enqueue(
        novelId: String,
        novelTitle: String,
        chapters: List<Chapter>,
        now: Long = System.currentTimeMillis()
    ): Int {
        val downloaded = chapterDao.getDownloadedChapterIds(novelId).toSet()
        val queued = queueDao.getQueuedIds().toSet()
        val fresh = chapters
            .filter { !it.isDownloaded && it.id !in downloaded && it.id !in queued }
            .distinctBy { it.id }
        if (fresh.isEmpty()) return 0

        // Saving the text is an UPDATE, so each chapter needs its row. Rows that exist are kept:
        // replacing one would throw away the text it already holds.
        chapterDao.insertChaptersIfAbsent(
            fresh.map { ChapterEntity(id = it.id, novelId = novelId, number = it.number, title = it.title, url = it.url) }
        )
        return queueDao.insertAll(
            fresh.map {
                DownloadQueueEntity(
                    chapterId = it.id,
                    novelId = novelId,
                    novelTitle = novelTitle,
                    chapterNumber = it.number,
                    chapterTitle = it.title,
                    chapterUrl = it.url,
                    addedAt = now
                )
            }
        ).count { it != -1L }
    }

    suspend fun next(): DownloadQueueEntity? = queueDao.nextQueued()

    suspend fun pendingCount(): Int = queueDao.countPending()

    suspend fun requeueInterrupted() = queueDao.requeueInterrupted()

    /** Fetches one row's chapter with [fetch], saves it and drops the row, or records the failure. */
    suspend fun attempt(row: DownloadQueueEntity, fetch: suspend (DownloadQueueEntity) -> String?): Outcome {
        queueDao.markDownloading(row.chapterId)
        val content = try {
            fetch(row)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return recordFailure(row, e.message ?: e.javaClass.simpleName)
        } ?: return recordFailure(row, "The source returned no text")
        chapterDao.markChapterDownloaded(row.chapterId, content, System.currentTimeMillis())
        queueDao.delete(row.chapterId)
        return Outcome.Done
    }

    /** A chapter gets [MAX_ATTEMPTS] tries; after that it stays FAILED until retried by hand. */
    suspend fun recordFailure(row: DownloadQueueEntity, message: String): Outcome {
        val attempts = row.attempts + 1
        val error = message.take(200)
        return if (attempts >= MAX_ATTEMPTS) {
            queueDao.markFailed(row.chapterId, attempts, error)
            Outcome.GaveUp(error)
        } else {
            queueDao.markRetry(row.chapterId, attempts, error)
            Outcome.WillRetry
        }
    }

    suspend fun retryFailed() = queueDao.retryFailed()
    suspend fun remove(chapterId: String) = queueDao.delete(chapterId)
    suspend fun removeNovel(novelId: String) = queueDao.deleteForNovel(novelId)
    suspend fun clear() = queueDao.deleteAll()

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}

/**
 * The single entry point for downloading chapters. The work is a table of rows
 * drained by [ChapterDownloadWorker], so it outlives any screen and the process.
 */
class DownloadManager(
    private val context: Context,
    database: AppDatabase,
    private val scope: CoroutineScope
) {

    private val chapterDao = database.chapterDao()
    private val queueDao = database.downloadQueueDao()
    val queue = DownloadQueue(queueDao, chapterDao)

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _paused = MutableStateFlow(prefs.getBoolean(PREF_PAUSED, false))
    val paused: StateFlow<Boolean> = _paused.asStateFlow()
    val isPaused: Boolean get() = _paused.value

    private val _wifiOnly = MutableStateFlow(prefs.getBoolean(PREF_WIFI_ONLY, false))
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    /** Every queued row, oldest first. */
    val queueItems: Flow<List<DownloadQueueEntity>> = queueDao.observeAll()

    val summary: Flow<DownloadSummary> =
        combine(queueDao.observeCounts(), _paused) { c, paused ->
            DownloadSummary(c.queued, c.downloading, c.failed, paused)
        }

    // Chapters finished since each novel's batch began, so "3 of 20" survives rows being deleted
    private val completed = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Per-chapter state for chapters still in the queue; finished ones leave it. */
    val activeDownloads: StateFlow<Map<String, ChapterDownloadState>> = queueDao.observeAll()
        .map { rows ->
            rows.associate { row ->
                row.chapterId to ChapterDownloadState(row.chapterId, row.status.toDownloadStatus())
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val novelStates: StateFlow<Map<String, NovelDownloadState>> =
        combine(queueDao.observeAll(), completed, _paused) { rows, done, paused ->
            rows.filter { it.status != DownloadQueueStatus.FAILED }
                .groupBy { it.novelId }
                .mapValues { (novelId, pending) ->
                    val finished = done[novelId] ?: 0
                    NovelDownloadState(
                        novelId = novelId,
                        totalChapters = finished + pending.size,
                        downloadedChapters = finished,
                        isDownloading = !paused,
                        currentChapter = pending.firstOrNull { it.status == DownloadQueueStatus.DOWNLOADING }?.chapterTitle
                    )
                }
        }
            .onEach { states ->
                // A novel with nothing pending starts its next batch from zero.
                val stale = completed.value.keys.filter { it !in states }
                if (stale.isNotEmpty()) completed.value = completed.value - stale.toSet()
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** The novel first in line, or null when nothing is downloading. */
    val novelDownloadState: StateFlow<NovelDownloadState?> = novelStates
        .map { it.values.firstOrNull() }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Bulk progress for one novel, so its screen shows its own even while another novel downloads. */
    fun novelDownloadState(novelId: String): Flow<NovelDownloadState?> =
        novelStates.map { it[novelId] }.distinctUntilChanged()

    /**
     * Queues the chapters that aren't downloaded or queued yet and starts the worker.
     * Asking for a download is an explicit act, so it also lifts a pause.
     */
    suspend fun enqueue(novelId: String, novelTitle: String, chapters: List<Chapter>): Int {
        val (added, pending) = withContext(Dispatchers.IO) {
            queue.enqueue(novelId, novelTitle, chapters) to queue.pendingCount()
        }
        // Even when everything asked for was already queued, pressing Download should get a paused queue going.
        if (added > 0 || pending > 0) {
            setPaused(false)
            schedule(ExistingWorkPolicy.KEEP)
        }
        return added
    }

    fun pause() {
        setPaused(true)
        WorkManager.getInstance(context).cancelUniqueWork(ChapterDownloadWorker.WORK_NAME)
        // The chapter in flight is abandoned; show it as waiting rather than spinning.
        scope.launch(Dispatchers.IO) { queue.requeueInterrupted() }
    }

    fun resume() {
        setPaused(false)
        resumeIfPending()
    }

    fun cancelAll() {
        WorkManager.getInstance(context).cancelUniqueWork(ChapterDownloadWorker.WORK_NAME)
        scope.launch(Dispatchers.IO) { queue.clear() }
    }

    fun cancelNovel(novelId: String) {
        scope.launch(Dispatchers.IO) { queue.removeNovel(novelId) }
    }

    fun remove(chapterId: String) {
        scope.launch(Dispatchers.IO) { queue.remove(chapterId) }
    }

    fun retryFailed() {
        scope.launch(Dispatchers.IO) {
            queue.retryFailed()
            setPaused(false)
            schedule(ExistingWorkPolicy.KEEP)
        }
    }

    /** Called by the worker for each chapter it saves. */
    internal fun noteCompleted(novelId: String) {
        completed.value = completed.value + (novelId to ((completed.value[novelId] ?: 0) + 1))
    }

    fun setWifiOnly(enabled: Boolean) {
        if (_wifiOnly.value == enabled) return
        prefs.edit().putBoolean(PREF_WIFI_ONLY, enabled).apply()
        _wifiOnly.value = enabled
        // Work already waiting was built with the old constraint.
        scope.launch(Dispatchers.IO) {
            if (!isPaused && queue.pendingCount() > 0) schedule(ExistingWorkPolicy.REPLACE)
        }
    }

    /** Starts the worker if rows are waiting and the user hasn't paused; used at launch and on resume. */
    fun resumeIfPending() {
        scope.launch(Dispatchers.IO) {
            if (!isPaused && queue.pendingCount() > 0) schedule(ExistingWorkPolicy.KEEP)
        }
    }

    private fun setPaused(paused: Boolean) {
        prefs.edit().putBoolean(PREF_PAUSED, paused).apply()
        _paused.value = paused
    }

    private fun schedule(policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<ChapterDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (_wifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ChapterDownloadWorker.WORK_NAME, policy, request)
    }

    /** Ids of this novel's downloaded chapters, live, so ticks appear as the worker saves each one. */
    fun downloadedChapterIds(novelId: String): Flow<Set<String>> =
        chapterDao.observeDownloadedChapterIds(novelId).map { it.toSet() }

    // Get downloaded chapter content
    suspend fun getDownloadedContent(chapterId: String): String? {
        return withContext(Dispatchers.IO) {
            chapterDao.getChapterById(chapterId)?.content
        }
    }

    // Check if chapter is downloaded
    suspend fun isChapterDownloaded(chapterId: String): Boolean {
        return withContext(Dispatchers.IO) {
            chapterDao.getChapterById(chapterId)?.isDownloaded == true
        }
    }

    // Get download count for a novel
    fun getDownloadedChapterCount(novelId: String): Flow<Int> {
        return chapterDao.getDownloadedChapterCount(novelId)
    }

    // Delete a single chapter download
    suspend fun deleteChapterDownload(chapterId: String) {
        withContext(Dispatchers.IO) {
            chapterDao.deleteDownload(chapterId)
        }
    }

    // Delete all downloads for a novel
    suspend fun deleteAllDownloads(novelId: String) {
        withContext(Dispatchers.IO) {
            chapterDao.deleteAllDownloadsForNovel(novelId)
        }
    }

    companion object {
        const val PREFS_NAME = "chapter_downloads"
        const val PREF_PAUSED = "paused"
        const val PREF_WIFI_ONLY = "wifi_only"
    }
}
