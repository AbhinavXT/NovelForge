package com.example.novelreader.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.novelreader.data.NovelRepository
import com.example.novelreader.data.model.Chapter
import com.example.novelreader.data.model.ReaderSettings
import com.example.novelreader.data.model.ReaderTheme
import com.example.novelreader.data.model.ReaderFont
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Navigation info for a chapter
data class ChapterNav(
    val id: String,
    val title: String,
    val url: String,
    val number: Int
)

// Complete chapter data for the reader
data class ReaderChapterData(
    val novelId: String,
    val novelTitle: String,
    val chapterId: String,
    val chapterTitle: String,
    val chapterNumber: Int,
    val content: String,
    val paragraphs: List<String>,  // Content split into paragraphs
    val totalChapters: Int,
    val prevChapter: ChapterNav?,
    val nextChapter: ChapterNav?,
    val isFirstChapter: Boolean,
    val isLastChapter: Boolean,
    val savedParagraphIndex: Int = 0  // Which paragraph user was reading
)

sealed interface ReaderUiState {
    object Loading : ReaderUiState
    data class Success(
        val chapter: ReaderChapterData,
        val settings: ReaderSettings
    ) : ReaderUiState
    data class Error(val message: String) : ReaderUiState
}

class ReaderViewModel(
    private val novelId: String,
    initialChapterId: String,
    initialChapterUrl: String,
    private val novelUrl: String,
    private val repository: NovelRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    // Track current chapter info
    private var currentChapterId: String = initialChapterId
    private var currentChapterUrl: String = initialChapterUrl

    // Cache the chapter list for navigation
    private var chapterList: List<Chapter> = emptyList()
    private var novelTitle: String = ""

    // Track retry count
    private var retryCount = 0
    private val maxRetries = 3

    // Track if we should restore paragraph position
    private var shouldRestorePosition = true

    init {
        loadChapterList()
    }

    private fun loadChapterList() {
        viewModelScope.launch {
            try {
                val dbChapters = repository.getChapters(novelId).first()

                if (dbChapters.isNotEmpty()) {
                    chapterList = dbChapters
                    val novel = repository.getNovelById(novelId)
                    novelTitle = novel?.title ?: ""
                } else {
                    fetchChapterListFromNetwork()
                }

                loadChapter()
            } catch (e: Exception) {
                e.printStackTrace()
                loadChapter()
            }
        }
    }

    private suspend fun fetchChapterListFromNetwork() {
        try {
            val novel = repository.fetchNovelDetails(novelId, novelUrl)

            if (novel != null) {
                chapterList = novel.chapters
                novelTitle = novel.title
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Split content into paragraphs for tracking
    private fun splitIntoParagraphs(content: String): List<String> {
        return content
            .split("\n\n", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun loadChapter() {
        viewModelScope.launch {
            _uiState.value = ReaderUiState.Loading

            try {
                val content = repository.getChapterContent(
                    novelId = novelId,
                    chapterId = currentChapterId,
                    chapterUrl = currentChapterUrl
                )
                val settings = repository.getReaderSettings()

                if (content != null) {
                    retryCount = 0

                    val currentIndex = chapterList.indexOfFirst { it.id == currentChapterId }
                    val currentChapter = chapterList.getOrNull(currentIndex)

                    val prevChapter = if (currentIndex > 0) {
                        chapterList.getOrNull(currentIndex - 1)?.toChapterNav()
                    } else null

                    val nextChapter = if (currentIndex >= 0 && currentIndex < chapterList.size - 1) {
                        chapterList.getOrNull(currentIndex + 1)?.toChapterNav()
                    } else null

                    val hasChapterList = chapterList.isNotEmpty()
                    val isFirst = hasChapterList && currentIndex == 0
                    val isLast = hasChapterList && currentIndex == chapterList.size - 1 && currentIndex >= 0

                    // Split content into paragraphs
                    val paragraphs = splitIntoParagraphs(content)

                    // Get saved paragraph index for this chapter
                    val savedParagraphIndex = if (shouldRestorePosition) {
                        val progress = repository.getReadingProgress(novelId)
                        if (progress?.currentChapterId == currentChapterId) {
                            progress.paragraphIndex.coerceIn(0, paragraphs.size - 1)
                        } else {
                            0
                        }
                    } else {
                        0
                    }

                    val chapterData = ReaderChapterData(
                        novelId = novelId,
                        novelTitle = novelTitle,
                        chapterId = currentChapterId,
                        chapterTitle = currentChapter?.title ?: "Chapter",
                        chapterNumber = currentChapter?.number ?: (currentIndex + 1).coerceAtLeast(1),
                        content = content,
                        paragraphs = paragraphs,
                        totalChapters = chapterList.size,
                        prevChapter = prevChapter,
                        nextChapter = nextChapter,
                        isFirstChapter = isFirst,
                        isLastChapter = isLast,
                        savedParagraphIndex = savedParagraphIndex
                    )

                    _uiState.value = ReaderUiState.Success(
                        chapter = chapterData,
                        settings = settings
                    )

                    // Save reading progress
                    saveProgress(currentIndex + 1, savedParagraphIndex)

                    shouldRestorePosition = false
                } else {
                    _uiState.value = ReaderUiState.Error(
                        "Failed to load chapter content. The source may be temporarily unavailable. Tap to retry."
                    )
                }
            } catch (e: java.net.SocketTimeoutException) {
                _uiState.value = ReaderUiState.Error(
                    "Connection timed out. The source is responding slowly. Tap to retry."
                )
            } catch (e: java.net.UnknownHostException) {
                _uiState.value = ReaderUiState.Error(
                    "No internet connection. Please check your network and tap to retry."
                )
            } catch (e: java.io.IOException) {
                _uiState.value = ReaderUiState.Error(
                    "Network error: ${e.message ?: "Connection failed"}. Tap to retry."
                )
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = ReaderUiState.Error(
                    "Error: ${e.message ?: "Something went wrong"}. Tap to retry."
                )
            }
        }
    }

    fun reloadChapter() {
        retryCount++
        loadChapter()
    }

    fun forceReloadChapter() {
        retryCount = 0
        viewModelScope.launch {
            _uiState.value = ReaderUiState.Loading

            try {
                val dbChapters = repository.getChapters(novelId).first()
                if (dbChapters.isNotEmpty()) {
                    chapterList = dbChapters
                }
            } catch (e: Exception) {
                // Ignore
            }

            loadChapter()
        }
    }

    private fun saveProgress(chapterNumber: Int, paragraphIndex: Int = 0) {
        viewModelScope.launch {
            try {
                repository.saveReadingProgress(
                    novelId = novelId,
                    chapterId = currentChapterId,
                    chapterNumber = chapterNumber,
                    paragraphIndex = paragraphIndex
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Save paragraph index - called from UI when scrolling
    fun saveParagraphIndex(paragraphIndex: Int) {
        val currentState = _uiState.value
        if (currentState is ReaderUiState.Success) {
            viewModelScope.launch {
                try {
                    repository.saveReadingProgress(
                        novelId = novelId,
                        chapterId = currentChapterId,
                        chapterNumber = currentState.chapter.chapterNumber,
                        paragraphIndex = paragraphIndex
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun goToPreviousChapter() {
        val currentState = _uiState.value
        if (currentState is ReaderUiState.Success) {
            currentState.chapter.prevChapter?.let { prev ->
                shouldRestorePosition = false
                currentChapterId = prev.id
                currentChapterUrl = prev.url
                loadChapter()
            }
        }
    }

    fun goToNextChapter() {
        val currentState = _uiState.value
        if (currentState is ReaderUiState.Success) {
            currentState.chapter.nextChapter?.let { next ->
                shouldRestorePosition = false
                currentChapterId = next.id
                currentChapterUrl = next.url
                loadChapter()
            }
        }
    }

    fun goToChapter(chapterId: String, chapterUrl: String) {
        shouldRestorePosition = false
        currentChapterId = chapterId
        currentChapterUrl = chapterUrl
        loadChapter()
    }

    fun canGoPrevious(): Boolean {
        val currentState = _uiState.value
        return currentState is ReaderUiState.Success && currentState.chapter.prevChapter != null
    }

    fun canGoNext(): Boolean {
        val currentState = _uiState.value
        return currentState is ReaderUiState.Success && currentState.chapter.nextChapter != null
    }

    // Settings methods
    fun updateSettings(newSettings: ReaderSettings) {
        val currentState = _uiState.value
        if (currentState is ReaderUiState.Success) {
            _uiState.value = currentState.copy(settings = newSettings)

            viewModelScope.launch {
                repository.saveReaderSettings(newSettings)
            }
        }
    }

    fun increaseFontSize() {
        val currentState = _uiState.value
        if (currentState is ReaderUiState.Success && currentState.settings.fontSize < 28) {
            updateSettings(currentState.settings.copy(fontSize = currentState.settings.fontSize + 2))
        }
    }

    fun decreaseFontSize() {
        val currentState = _uiState.value
        if (currentState is ReaderUiState.Success && currentState.settings.fontSize > 12) {
            updateSettings(currentState.settings.copy(fontSize = currentState.settings.fontSize - 2))
        }
    }

    fun cycleTheme() {
        val currentState = _uiState.value
        if (currentState is ReaderUiState.Success) {
            val nextTheme = when (currentState.settings.theme) {
                ReaderTheme.LIGHT -> ReaderTheme.DARK
                ReaderTheme.DARK -> ReaderTheme.SEPIA
                ReaderTheme.SEPIA -> ReaderTheme.GREY
                ReaderTheme.GREY -> ReaderTheme.PAPER
                ReaderTheme.PAPER -> ReaderTheme.NAVY
                ReaderTheme.NAVY -> ReaderTheme.SOLARIZED_LIGHT
                ReaderTheme.SOLARIZED_LIGHT -> ReaderTheme.SOLARIZED_DARK
                ReaderTheme.SOLARIZED_DARK -> ReaderTheme.LIGHT
            }
            updateSettings(currentState.settings.copy(theme = nextTheme))
        }
    }

    fun cycleFont() {
        val currentState = _uiState.value
        if (currentState is ReaderUiState.Success) {
            val nextFont = when (currentState.settings.font) {
                ReaderFont.SANS_SERIF -> ReaderFont.SERIF
                ReaderFont.SERIF -> ReaderFont.MONOSPACE
                ReaderFont.MONOSPACE -> ReaderFont.CURSIVE
                ReaderFont.CURSIVE -> ReaderFont.SANS_SERIF
            }
            updateSettings(currentState.settings.copy(font = nextFont))
        }
    }

    fun setFont(font: ReaderFont) {
        val currentState = _uiState.value
        if (currentState is ReaderUiState.Success) {
            updateSettings(currentState.settings.copy(font = font))
        }
    }

    private fun Chapter.toChapterNav(): ChapterNav {
        return ChapterNav(
            id = this.id,
            title = this.title,
            url = this.url,
            number = this.number
        )
    }

    companion object {
        fun provideFactory(
            novelId: String,
            chapterId: String,
            chapterUrl: String,
            novelUrl: String,
            repository: NovelRepository
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ReaderViewModel(novelId, chapterId, chapterUrl, novelUrl, repository) as T
                }
            }
        }
    }
}