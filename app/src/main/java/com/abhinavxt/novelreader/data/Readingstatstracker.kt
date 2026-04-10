package com.abhinavxt.novelreader.data

import com.abhinavxt.novelreader.data.database.ReadingStatDao
import com.abhinavxt.novelreader.data.database.ReadingStatEvent
import com.abhinavxt.novelreader.util.Logger
import kotlinx.coroutines.Dispatchers
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
 * The tracker records one [ReadingStatEvent] per completed session
 * with word count and time spent.
 */
class ReadingStatsTracker(private val dao: ReadingStatDao) {

    // Active session tracking
    private var sessionNovelId: String? = null
    private var sessionChapterId: String? = null
    private var sessionStartMs: Long = 0L
    private var sessionWordCount: Int = 0

    /**
     * Start tracking a reading session for a chapter.
     */
    fun startSession(novelId: String, chapterId: String, chapterContent: String) {
        sessionNovelId = novelId
        sessionChapterId = chapterId
        sessionStartMs = System.currentTimeMillis()
        sessionWordCount = chapterContent.split(Regex("\\s+")).count { it.isNotBlank() }
    }

    /**
     * End the current session and record a stat event.
     * Only records if the session lasted at least 10 seconds
     * (avoids logging accidental taps).
     */
    suspend fun endSession() {
        val novelId = sessionNovelId ?: return
        val chapterId = sessionChapterId ?: return
        val elapsed = System.currentTimeMillis() - sessionStartMs

        // Minimum 10 seconds to count as reading
        if (elapsed < 10_000) {
            clearSession()
            return
        }

        withContext(Dispatchers.IO) {
            try {
                dao.insertEvent(
                    ReadingStatEvent(
                        novelId = novelId,
                        chapterId = chapterId,
                        wordsRead = sessionWordCount,
                        readingTimeMs = elapsed
                    )
                )
                Logger.d("StatsTracker", "Recorded: ${sessionWordCount}w, ${elapsed / 1000}s for $chapterId")
            } catch (e: Exception) {
                Logger.e("StatsTracker", "Failed to record stat", e)
            }
        }

        clearSession()
    }

    private fun clearSession() {
        sessionNovelId = null
        sessionChapterId = null
        sessionStartMs = 0L
        sessionWordCount = 0
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
        val activeDays = dao.getActiveDaysMs()

        val (current, longest) = calculateStreaks(activeDays)

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

            val label = if (i == 0) "Today" else if (i == 1) "Yday" else {
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

    private fun todayMidnightMs(): Long {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
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