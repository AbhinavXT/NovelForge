package com.abhinavxt.novelforge.ui.viewmodel

import androidx.lifecycle.ViewModel
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.abhinavxt.novelforge.data.NovelRepository
import com.abhinavxt.novelforge.data.ReadingStatsTracker
import com.abhinavxt.novelforge.data.model.Novel
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
    private val statsTracker: ReadingStatsTracker? = null,
    private val appContext: Context? = null
) : ViewModel() {

    private val _libraryNovels = MutableStateFlow<List<Novel>>(emptyList())
    val libraryNovels: StateFlow<List<Novel>> = _libraryNovels.asStateFlow()

    // ── Recently updated: library novels with unseen new chapters ──
    // Reuses the exact badge mechanism the Library tab already has —
    // UpdateCheckerWorker writes updated novel ids to SharedPreferences;
    // we intersect that set with the library list. No new infrastructure.
    private val _recentlyUpdated = MutableStateFlow<List<Novel>>(emptyList())
    val recentlyUpdated: StateFlow<List<Novel>> = _recentlyUpdated.asStateFlow()

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
            // Load library novels; recompute the recently-updated
            // intersection on every library change
            repository.getLibraryNovels().collect { novels ->
                _libraryNovels.value = novels
                _recentlyUpdated.value = novels.filter { it.id in loadUpdatedIds() }
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
            _recentlyUpdated.value = _libraryNovels.value.filter { it.id in loadUpdatedIds() }
            loadAllContinueReading()
        }
        loadReadingActivity()
    }

    /** Ids of novels the UpdateCheckerWorker flagged as having new chapters. */
    private fun loadUpdatedIds(): Set<String> {
        val ctx = appContext ?: return emptySet()
        val prefs = ctx.getSharedPreferences(
            com.abhinavxt.novelforge.worker.UpdateCheckerWorker.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        return prefs.getStringSet(
            com.abhinavxt.novelforge.worker.UpdateCheckerWorker.PREF_UPDATED_NOVEL_IDS,
            emptySet()
        ) ?: emptySet()
    }

    companion object {
        fun provideFactory(
            repository: NovelRepository,
            statsTracker: ReadingStatsTracker? = null,
            appContext: Context? = null
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HomeViewModel(repository, statsTracker, appContext) as T
                }
            }
        }

        private fun constructNovelUrl(novelId: String): String {
            return com.abhinavxt.novelforge.data.source.SourceManager.constructNovelUrl(novelId)
        }
    }
}