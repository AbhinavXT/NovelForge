package com.abhinavxt.novelreader.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.abhinavxt.novelreader.data.NovelRepository
import com.abhinavxt.novelreader.data.ReadingStatsTracker
import com.abhinavxt.novelreader.data.database.ReadingStatEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ── Activity feed item ──────────────────────────────────────────
data class ReadingSession(
    val novelId: String,
    val novelTitle: String,
    val chapterId: String,
    val wordsRead: Int,
    val readingTimeMs: Long,
    val completedAt: Long,
    val wpm: Int                    // Session-specific WPM
)

// ── Achievement badge ───────────────────────────────────────────
data class Achievement(
    val id: String,
    val title: String,
    val description: String,
    val icon: String,               // FontAwesome icon name hint
    val earned: Boolean,
    val progress: Float             // 0..1 progress toward earning
)

// ── Full UI state ───────────────────────────────────────────────
data class StatsUiState(
    val isLoading: Boolean = true,

    // Overall
    val totalWordsRead: Int = 0,
    val totalReadingTimeMs: Long = 0,
    val totalChapters: Int = 0,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val userWPM: Int = 250,
    val booksEquivalent: Float = 0f,

    // Today
    val todayWords: Int = 0,
    val todayTimeMs: Long = 0,
    val todayChapters: Int = 0,

    // Charts
    val dailyWordCounts: List<Pair<String, Int>> = emptyList(),
    val dailyReadingTime: List<Pair<String, Int>> = emptyList(),

    // Activity feed (recent sessions)
    val recentSessions: List<ReadingSession> = emptyList(),

    // Personal records
    val longestSessionMs: Long = 0,
    val mostWordsInSession: Int = 0,
    val bestDayWords: Int = 0,
    val bestDayDate: String = "",
    val distinctNovelsRead: Int = 0,

    // Weekly comparison (this week vs last)
    val thisWeekWords: Int = 0,
    val lastWeekWords: Int = 0,
    val thisWeekTimeMs: Long = 0,
    val lastWeekTimeMs: Long = 0,
    val thisWeekChapters: Int = 0,
    val lastWeekChapters: Int = 0,
    val thisWeekDaysActive: Int = 0,

    // Heatmap — daily reading minutes, last 12 weeks (84 entries)
    val heatmapData: List<Int> = emptyList(),

    // Achievements
    val achievements: List<Achievement> = emptyList(),

    // Avg per chapter
    val avgChapterTimeMs: Long = 0
)

class ReadingStatsViewModel(
    private val tracker: ReadingStatsTracker,
    private val repository: NovelRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    init {
        loadStats()
    }

    fun loadStats() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            val overall = tracker.getOverallStats()
            val today = tracker.getStatsForToday()
            val dailyWords = tracker.getDailyWordCounts(14)
            val dailyTime = tracker.getDailyReadingTime(14)
            val wpm = tracker.getUserWPM()
            val recent = tracker.getRecentSessions(15)
            val records = tracker.getPersonalRecords()
            val weekly = tracker.getWeeklyComparison()
            val heatmap = tracker.getHeatmapData(12)

            val booksEq = overall.totalWordsRead / 80_000f
            val avgChapter = if (overall.totalChapters > 0) {
                overall.totalReadingTimeMs / overall.totalChapters
            } else 0L

            // Resolve real novel titles from the DB. The old approach
            // parsed the novelId string, which only looked right for
            // online sources whose ids embed URL slugs — local EPUB ids
            // are "local_<uuid8>" and rendered as gibberish like
            // "F22fecf1". Removed novels fall back to the slug heuristic.
            val titleById = recent
                .map { it.novelId }
                .distinct()
                .mapNotNull { id ->
                    repository.getNovelById(id)?.let { novel -> id to novel.title }
                }
                .toMap()

            // Build sessions with per-session WPM
            val sessions = recent.map { event ->
                val minutes = event.readingTimeMs / 60_000.0
                val sessionWpm = if (minutes > 0.1) {
                    (event.wordsRead / minutes).toInt().coerceIn(50, 1500)
                } else wpm
                ReadingSession(
                    novelId = event.novelId,
                    novelTitle = titleById[event.novelId]
                        ?: fallbackNameFromId(event.novelId),
                    chapterId = event.chapterId,
                    wordsRead = event.wordsRead,
                    readingTimeMs = event.readingTimeMs,
                    completedAt = event.completedAt,
                    wpm = sessionWpm
                )
            }

            // Compute achievements
            val achievements = computeAchievements(
                totalWords = overall.totalWordsRead,
                totalChapters = overall.totalChapters,
                currentStreak = overall.currentStreak,
                longestStreak = overall.longestStreak,
                totalTimeMs = overall.totalReadingTimeMs,
                distinctNovels = records.distinctNovelsRead,
                wpm = wpm
            )

            _uiState.value = StatsUiState(
                isLoading = false,
                totalWordsRead = overall.totalWordsRead,
                totalReadingTimeMs = overall.totalReadingTimeMs,
                totalChapters = overall.totalChapters,
                currentStreak = overall.currentStreak,
                longestStreak = overall.longestStreak,
                userWPM = wpm,
                booksEquivalent = booksEq,
                todayWords = today.wordsRead,
                todayTimeMs = today.readingTimeMs,
                todayChapters = today.chaptersCompleted,
                dailyWordCounts = dailyWords,
                dailyReadingTime = dailyTime,
                recentSessions = sessions,
                longestSessionMs = records.longestSessionMs,
                mostWordsInSession = records.mostWordsInSession,
                bestDayWords = records.bestDayWords,
                bestDayDate = records.bestDayDate,
                distinctNovelsRead = records.distinctNovelsRead,
                thisWeekWords = weekly.thisWeekWords,
                lastWeekWords = weekly.lastWeekWords,
                thisWeekTimeMs = weekly.thisWeekTimeMs,
                lastWeekTimeMs = weekly.lastWeekTimeMs,
                thisWeekChapters = weekly.thisWeekChapters,
                lastWeekChapters = weekly.lastWeekChapters,
                thisWeekDaysActive = weekly.thisWeekDaysActive,
                heatmapData = heatmap,
                achievements = achievements,
                avgChapterTimeMs = avgChapter
            )
        }
    }

    /**
     * Last-resort display name for sessions whose novel is no longer in
     * the library: strip the source prefix and de-slugify. For local
     * ids ("local_<uuid8>") there is nothing meaningful to recover, so
     * label them as an imported book instead of showing hex noise.
     */
    private fun fallbackNameFromId(novelId: String): String {
        if (novelId.startsWith("local_")) return "Imported book"
        return novelId
            .substringAfter("_")
            .replace("-", " ")
            .replace("~", "/")
            .replaceFirstChar { it.uppercase() }
    }

    /**
     * Compute achievement badges based on current stats.
     * Each has a progress bar (0..1) and earned boolean.
     */
    private fun computeAchievements(
        totalWords: Int,
        totalChapters: Int,
        currentStreak: Int,
        longestStreak: Int,
        totalTimeMs: Long,
        distinctNovels: Int,
        wpm: Int
    ): List<Achievement> {
        val totalHours = totalTimeMs / 3_600_000.0

        return listOf(
            // Word count milestones
            Achievement("words_10k", "Bookworm", "Read 10,000 words", "book",
                totalWords >= 10_000, (totalWords / 10_000f).coerceAtMost(1f)),
            Achievement("words_100k", "Scholar", "Read 100,000 words", "graduation-cap",
                totalWords >= 100_000, (totalWords / 100_000f).coerceAtMost(1f)),
            Achievement("words_500k", "Savant", "Read 500,000 words", "brain",
                totalWords >= 500_000, (totalWords / 500_000f).coerceAtMost(1f)),
            Achievement("words_1m", "Legendary Reader", "Read 1,000,000 words", "crown",
                totalWords >= 1_000_000, (totalWords / 1_000_000f).coerceAtMost(1f)),

            // Streak milestones
            Achievement("streak_7", "Week Warrior", "7-day reading streak", "fire",
                longestStreak >= 7, (longestStreak / 7f).coerceAtMost(1f)),
            Achievement("streak_30", "Monthly Master", "30-day reading streak", "fire",
                longestStreak >= 30, (longestStreak / 30f).coerceAtMost(1f)),
            Achievement("streak_100", "Century Club", "100-day reading streak", "fire",
                longestStreak >= 100, (longestStreak / 100f).coerceAtMost(1f)),

            // Chapter milestones
            Achievement("chapters_50", "Page Turner", "Complete 50 chapters", "bookmark",
                totalChapters >= 50, (totalChapters / 50f).coerceAtMost(1f)),
            Achievement("chapters_500", "Chapter Crusher", "Complete 500 chapters", "bookmark",
                totalChapters >= 500, (totalChapters / 500f).coerceAtMost(1f)),

            // Time milestones
            Achievement("hours_10", "Dedicated", "10 hours of reading", "clock",
                totalHours >= 10, (totalHours / 10).toFloat().coerceAtMost(1f)),
            Achievement("hours_100", "Marathon Reader", "100 hours of reading", "clock",
                totalHours >= 100, (totalHours / 100).toFloat().coerceAtMost(1f)),

            // Novel diversity
            Achievement("novels_5", "Explorer", "Read 5 different novels", "compass",
                distinctNovels >= 5, (distinctNovels / 5f).coerceAtMost(1f)),
            Achievement("novels_20", "Bibliophile", "Read 20 different novels", "compass",
                distinctNovels >= 20, (distinctNovels / 20f).coerceAtMost(1f)),

            // Speed
            Achievement("speed_300", "Speed Reader", "Average 300+ WPM", "bolt",
                wpm >= 300, (wpm / 300f).coerceAtMost(1f)),
            Achievement("speed_500", "Lightning Eyes", "Average 500+ WPM", "bolt",
                wpm >= 500, (wpm / 500f).coerceAtMost(1f))
        )
    }

    class Factory(
        private val tracker: ReadingStatsTracker,
        private val repository: NovelRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ReadingStatsViewModel(tracker, repository) as T
        }
    }
}