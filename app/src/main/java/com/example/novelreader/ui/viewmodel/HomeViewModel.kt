package com.example.novelreader.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.novelreader.data.NovelRepository
import com.example.novelreader.data.model.Novel
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

class HomeViewModel(
    private val repository: NovelRepository
) : ViewModel() {

    private val _libraryNovels = MutableStateFlow<List<Novel>>(emptyList())
    val libraryNovels: StateFlow<List<Novel>> = _libraryNovels.asStateFlow()

    // Changed: Now a list of all novels with reading progress
    private val _continueReadingList = MutableStateFlow<List<ContinueReadingData>>(emptyList())
    val continueReadingList: StateFlow<List<ContinueReadingData>> = _continueReadingList.asStateFlow()

    // Keep single continueReading for backward compatibility (most recent)
    private val _continueReading = MutableStateFlow<ContinueReadingData?>(null)
    val continueReading: StateFlow<ContinueReadingData?> = _continueReading.asStateFlow()

    private val _totalChaptersRead = MutableStateFlow(0)
    val totalChaptersRead: StateFlow<Int> = _totalChaptersRead.asStateFlow()

    init {
        loadHomeData()
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

    fun refresh() {
        viewModelScope.launch {
            _totalChaptersRead.value = repository.getTotalChaptersRead()
            loadAllContinueReading()
        }
    }

    companion object {
        fun provideFactory(repository: NovelRepository): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HomeViewModel(repository) as T
                }
            }
        }

        private fun constructNovelUrl(novelId: String): String {
            return when {
                novelId.startsWith("rr_") -> {
                    "https://www.royalroad.com/fiction/${novelId.removePrefix("rr_")}"
                }
                novelId.startsWith("rnf_") -> {
                    "https://readnovelfull.com/${novelId.removePrefix("rnf_")}.html"
                }
                novelId.startsWith("local_") -> {
                    // Local novels don't need a URL, use placeholder
                    "local://$novelId"
                }
                else -> ""
            }
        }
    }
}