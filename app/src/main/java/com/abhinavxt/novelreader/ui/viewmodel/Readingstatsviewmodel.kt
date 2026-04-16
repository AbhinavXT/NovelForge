package com.abhinavxt.novelreader.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.abhinavxt.novelreader.data.NovelRepository
import com.abhinavxt.novelreader.data.ReadingStatsTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StatsUiState(
    val isLoading: Boolean = true,
    val totalWordsRead: Int = 0,
    val totalReadingTimeMs: Long = 0,
    val totalChapters: Int = 0,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val todayWords: Int = 0,
    val todayTimeMs: Long = 0,
    val todayChapters: Int = 0,
    val dailyWordCounts: List<Pair<String, Int>> = emptyList(),

    // ── New fields ──────────────────────────────────────────────
    val userWPM: Int = 250,             // User's calculated words-per-minute
    val avgChapterTimeMs: Long = 0,     // Average time spent per chapter
    val booksEquivalent: Float = 0f     // Total words ÷ 80,000 (avg novel length)
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
            val daily = tracker.getDailyWordCounts(14)
            val wpm = tracker.getUserWPM()

            // Average time per chapter — avoid divide by zero
            val avgChapterMs = if (overall.totalChapters > 0) {
                overall.totalReadingTimeMs / overall.totalChapters
            } else 0L

            // Books equivalent: average novel is ~80,000 words
            val booksEq = overall.totalWordsRead / 80_000f

            _uiState.value = StatsUiState(
                isLoading = false,
                totalWordsRead = overall.totalWordsRead,
                totalReadingTimeMs = overall.totalReadingTimeMs,
                totalChapters = overall.totalChapters,
                currentStreak = overall.currentStreak,
                longestStreak = overall.longestStreak,
                todayWords = today.wordsRead,
                todayTimeMs = today.readingTimeMs,
                todayChapters = today.chaptersCompleted,
                dailyWordCounts = daily,
                userWPM = wpm,
                avgChapterTimeMs = avgChapterMs,
                booksEquivalent = booksEq
            )
        }
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