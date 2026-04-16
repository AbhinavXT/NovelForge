package com.abhinavxt.novelreader.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.abhinavxt.novelreader.data.NovelRepository
import com.abhinavxt.novelreader.data.ReadingStatsTracker
import com.abhinavxt.novelreader.data.model.Novel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class ContinueReadingData(
    val novel: Novel,
    val currentChapter: Int,
    val totalChapters: Int,
    val chapterId: String,
    val chapterUrl: String,
    val novelUrl: String = "",
    val chapterTitle: String = "",
    val lastReadAt: Long = 0L
)

/**
 * Reading activity data for the home screen streak card.
 */
data class ReadingActivityData(
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val todayMinutes: Int = 0,
    val todayWords: Int = 0,
    val todayChapters: Int = 0,
    val last7DaysActive: List<Boolean> = List(7) { false }  // index 0 = 6 days ago, index 6 = today
)

class HomeViewModel(
    private val repository: NovelRepository,
    private val statsTracker: ReadingStatsTracker? = null
) : ViewModel() {

    private val _libraryNovels = MutableStateFlow<List<Novel>>(emptyList())
    val libraryNovels: StateFlow<List<Novel>> = _libraryNovels.asStateFlow()

    private val _continueReadingList = MutableStateFlow<List<ContinueReadingData>>(emptyList())
    val continueReadingList: StateFlow<List<ContinueReadingData>> = _continueReadingList.asStateFlow()

    private val _continueReading = MutableStateFlow<ContinueReadingData?>(null)
    val continueReading: StateFlow<ContinueReadingData?> = _continueReading.asStateFlow()

    private val _totalChaptersRead = MutableStateFlow(0)
    val totalChaptersRead: StateFlow<Int> = _totalChaptersRead.asStateFlow()

    /** Reading activity data for the streak card */
    private val _readingActivity = MutableStateFlow(ReadingActivityData())
    val readingActivity: StateFlow<ReadingActivityData> = _readingActivity.asStateFlow()

    init {
        loadHomeData()
        loadReadingActivity()
    }

    private fun loadHomeData() {
        viewModelScope.launch {
            // Load library novels
            repository.getLibraryNovels().collect { novels ->
                _libraryNovels.value = novels
            }
        }

        viewModelScope.launch {
            // Load total chapters read
            _totalChaptersRead.value = repository.getTotalChaptersRead()
        }

        viewModelScope.launch {
            // Load all continue reading data
            loadAllContinueReading()
        }
    }

    private suspend fun loadAllContinueReading() {
        // Collect all reading progress (sorted by lastReadAt DESC from DAO)
        repository.getAllReadingProgress().collect { progressList ->
            val continueReadingDataList = mutableListOf<ContinueReadingData>()

            for (progress in progressList) {
                val novel = repository.getNovelById(progress.novelId) ?: continue

                // Get chapters from database to find the URL and title
                val chapters = repository.getChapters(progress.novelId).first()
                val currentChapter = chapters.find { it.id == progress.currentChapterId }

                if (currentChapter != null) {
                    continueReadingDataList.add(
                        ContinueReadingData(
                            novel = novel,
                            currentChapter = progress.currentChapterNumber,
                            totalChapters = chapters.size,
                            chapterId = currentChapter.id,
                            chapterUrl = currentChapter.url,
                            novelUrl = Companion.constructNovelUrl(progress.novelId),
                            chapterTitle = currentChapter.title,
                            lastReadAt = progress.lastReadAt
                        )
                    )
                }
            }

            // Sort by last read time (most recent first)
            val sortedList = continueReadingDataList.sortedByDescending { it.lastReadAt }
            _continueReadingList.value = sortedList

            // Set most recent for backward compatibility
            _continueReading.value = sortedList.firstOrNull()
        }
    }

    /**
     * Load streak, today's reading stats, and 7-day activity for the home card.
     */
    private fun loadReadingActivity() {
        val tracker = statsTracker ?: return
        viewModelScope.launch {
            try {
                val overall = tracker.getOverallStats()
                val today = tracker.getStatsForToday()
                val dailyWords = tracker.getDailyWordCounts(days = 7)

                _readingActivity.value = ReadingActivityData(
                    currentStreak = overall.currentStreak,
                    longestStreak = overall.longestStreak,
                    todayMinutes = (today.readingTimeMs / 60_000).toInt(),
                    todayWords = today.wordsRead,
                    todayChapters = today.chaptersCompleted,
                    // Each day: true if any words were read
                    last7DaysActive = dailyWords.map { it.second > 0 }
                )
            } catch (_: Exception) { }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _totalChaptersRead.value = repository.getTotalChaptersRead()
            loadAllContinueReading()
        }
        loadReadingActivity()
    }

    companion object {
        fun provideFactory(
            repository: NovelRepository,
            statsTracker: ReadingStatsTracker? = null
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HomeViewModel(repository, statsTracker) as T
                }
            }
        }

        private fun constructNovelUrl(novelId: String): String {
            return com.abhinavxt.novelreader.data.source.SourceManager.constructNovelUrl(novelId)
        }
    }
}