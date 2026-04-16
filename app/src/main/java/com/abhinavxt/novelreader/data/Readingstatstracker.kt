package com.abhinavxt.novelreader.data

import com.abhinavxt.novelreader.data.database.ReadingStatDao
import com.abhinavxt.novelreader.data.database.ReadingStatEvent
import com.abhinavxt.novelreader.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.TimeZone

/**
 * Tracks reading activity and computes stats for the dashboard.
 *
 * Usage from ReaderViewModel:
 *   - Call [startSession] when a chapter is opened
 *   - Call [endSession] when the user leaves or finishes a chapter
 *
 * Thread-safe via [Mutex]. Calling [startSession] automatically ends
 * the previous session if one is active (prevents data loss on quick
 * chapter switches).
 */
class ReadingStatsTracker(private val dao: ReadingStatDao) {

    private data class Session(
        val novelId: String,
        val chapterId: String,
        val startMs: Long,
        val wordCount: Int
    )

    private val mutex = Mutex()
    @Volatile private var activeSession: Session? = null

    /**
     * Start tracking a reading session for a chapter.
     * Automatically ends any previous session first.
     */
    suspend fun startSession(novelId: String, chapterId: String, chapterContent: String) {
        mutex.withLock {
            // End previous session if active (#25 fix)
            activeSession?.let { prev ->
                recordSession(prev)
            }
            activeSession = Session(
                novelId = novelId,
                chapterId = chapterId,
                startMs = System.currentTimeMillis(),
                wordCount = chapterContent.split(Regex("\\s+")).count { it.isNotBlank() }
            )
        }
    }

    /**
     * End the current session and record a stat event.
     * Only records if the session lasted at least 10 seconds.
     */
    suspend fun endSession() {
        mutex.withLock {
            val session = activeSession ?: return@withLock
            recordSession(session)
            activeSession = null
        }
    }

    /**
     * Record a session to the database. Called inside mutex lock.
     */
    private suspend fun recordSession(session: Session) {
        val elapsed = System.currentTimeMillis() - session.startMs

        // Minimum 10 seconds to count as reading
        if (elapsed < 10_000) return

        withContext(Dispatchers.IO) {
            try {
                dao.insertEvent(
                    ReadingStatEvent(
                        novelId = session.novelId,
                        chapterId = session.chapterId,
                        wordsRead = session.wordCount,
                        readingTimeMs = elapsed
                    )
                )
                Logger.d("StatsTracker", "Recorded: ${session.wordCount}w, ${elapsed / 1000}s for ${session.chapterId}")
            } catch (e: Exception) {
                Logger.e("StatsTracker", "Failed to record stat", e)
            }
        }
    }

    // ── Dashboard queries ────────────────────────────────────────

    data class OverallStats(
        val totalWordsRead: Int,
        val totalReadingTimeMs: Long,
        val totalChapters: Int,
        val currentStreak: Int,      // consecutive days including today
        val longestStreak: Int
    )

    data class DayStats(
        val wordsRead: Int,
        val readingTimeMs: Long,
        val chaptersCompleted: Int
    )

    data class NovelStats(
        val novelId: String,
        val wordsRead: Int,
        val readingTimeMs: Long,
        val chaptersCompleted: Int
    )

    suspend fun getOverallStats(): OverallStats = withContext(Dispatchers.IO) {
        val totalWords = dao.getTotalWordsRead()
        val totalTime = dao.getTotalReadingTimeMs()
        val totalChapters = dao.getTotalChaptersCompleted()

        // Bucket raw timestamps into local-timezone days
        val rawTimestamps = dao.getAllCompletedTimestamps()
        val activeDaysDesc = rawTimestamps
            .map { floorToLocalMidnight(it) }
            .distinct()
            .sortedDescending()

        val (current, longest) = calculateStreaks(activeDaysDesc)

        OverallStats(
            totalWordsRead = totalWords,
            totalReadingTimeMs = totalTime,
            totalChapters = totalChapters,
            currentStreak = current,
            longestStreak = longest
        )
    }

    suspend fun getStatsForToday(): DayStats = withContext(Dispatchers.IO) {
        val (start, end) = todayRange()
        DayStats(
            wordsRead = dao.getWordsReadForDay(start, end),
            readingTimeMs = dao.getReadingTimeMsForDay(start, end),
            chaptersCompleted = dao.getChaptersCompletedForDay(start, end)
        )
    }

    suspend fun getStatsForNovel(novelId: String): NovelStats = withContext(Dispatchers.IO) {
        NovelStats(
            novelId = novelId,
            wordsRead = dao.getWordsReadForNovel(novelId),
            readingTimeMs = dao.getReadingTimeMsForNovel(novelId),
            chaptersCompleted = dao.getChaptersCompletedForNovel(novelId)
        )
    }

    /**
     * Calculate the user's average reading speed in words-per-minute.
     *
     * Uses all recorded reading sessions. Returns 250 WPM (average adult
     * reading speed) if not enough data has been collected yet (< 1 minute
     * of tracked reading).
     *
     * Clamped to 50–1500 WPM to filter out outliers (e.g., user left the
     * screen open without reading, or skipped through very quickly).
     */
    suspend fun getUserWPM(): Int = withContext(Dispatchers.IO) {
        val totalWords = dao.getTotalWordsRead()
        val totalTimeMs = dao.getTotalReadingTimeMs()
        val totalMinutes = totalTimeMs / 60_000.0

        if (totalMinutes < 1.0) {
            DEFAULT_WPM // Not enough data yet
        } else {
            (totalWords / totalMinutes).toInt().coerceIn(50, 1500)
        }
    }

    /**
     * Estimate how many minutes it would take this user to read [wordCount] words.
     * Returns null if wordCount is 0.
     */
    suspend fun estimateMinutes(wordCount: Int): Int? {
        if (wordCount <= 0) return null
        val wpm = getUserWPM()
        return ((wordCount.toDouble() / wpm) + 0.5).toInt().coerceAtLeast(1)
    }

    companion object {
        /** Default reading speed before we have enough data. Average adult = ~250 WPM. */
        const val DEFAULT_WPM = 250
    }

    /**
     * Get daily word counts for the last [days] days (for a bar chart).
     * Returns a list of pairs: (dayLabel, wordsRead).
     */
    suspend fun getDailyWordCounts(days: Int = 14): List<Pair<String, Int>> = withContext(Dispatchers.IO) {
        val cal = Calendar.getInstance()
        val result = mutableListOf<Pair<String, Int>>()

        for (i in (days - 1) downTo 0) {
            cal.timeInMillis = System.currentTimeMillis()
            cal.add(Calendar.DAY_OF_YEAR, -i)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis

            cal.add(Calendar.DAY_OF_YEAR, 1)
            val end = cal.timeInMillis

            val label = run {
                cal.timeInMillis = start
                val month = cal.get(Calendar.MONTH) + 1
                val day = cal.get(Calendar.DAY_OF_MONTH)
                "$month/$day"
            }

            val words = dao.getWordsReadForDay(start, end)
            result.add(label to words)
        }

        result
    }

    // ── Streak calculation ───────────────────────────────────────

    /**
     * Calculate current and longest streaks from a sorted-descending list of active day timestamps.
     */
    private fun calculateStreaks(activeDaysDesc: List<Long>): Pair<Int, Int> {
        if (activeDaysDesc.isEmpty()) return 0 to 0

        val todayMs = todayMidnightMs()
        val yesterdayMs = todayMs - 86_400_000L
        val oneDayMs = 86_400_000L

        // Current streak: must include today or yesterday
        var currentStreak = 0
        val firstActive = activeDaysDesc.firstOrNull() ?: return 0 to 0
        if (firstActive != todayMs && firstActive != yesterdayMs) {
            // No recent activity, current streak is 0
            currentStreak = 0
        } else {
            currentStreak = 1
            for (i in 1 until activeDaysDesc.size) {
                val diff = activeDaysDesc[i - 1] - activeDaysDesc[i]
                if (diff == oneDayMs) {
                    currentStreak++
                } else {
                    break
                }
            }
        }

        // Longest streak
        var longestStreak = 1
        var runLength = 1
        for (i in 1 until activeDaysDesc.size) {
            val diff = activeDaysDesc[i - 1] - activeDaysDesc[i]
            if (diff == oneDayMs) {
                runLength++
                if (runLength > longestStreak) longestStreak = runLength
            } else {
                runLength = 1
            }
        }

        return currentStreak to longestStreak
    }

    private fun floorToLocalMidnight(timestampMs: Long): Long {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.timeInMillis = timestampMs
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun todayMidnightMs(): Long {
        return floorToLocalMidnight(System.currentTimeMillis())
    }

    private fun todayRange(): Pair<Long, Long> {
        val start = todayMidnightMs()
        return start to (start + 86_400_000L)
    }

    // Backup/restore
    suspend fun getAllForBackup(): List<ReadingStatEvent> {
        return withContext(Dispatchers.IO) { dao.getAllEventsOnce() }
    }

    suspend fun insertForRestore(event: ReadingStatEvent) {
        withContext(Dispatchers.IO) { dao.insertEventReplace(event) }
    }
}