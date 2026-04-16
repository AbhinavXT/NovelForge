package com.abhinavxt.novelreader.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.abhinavxt.novelreader.data.DictionaryRepository
import com.abhinavxt.novelreader.data.DictionaryState
import com.abhinavxt.novelreader.data.NovelRepository
import com.abhinavxt.novelreader.data.ChapterPrefetcher
import com.abhinavxt.novelreader.data.ReadingStatsTracker
import com.abhinavxt.novelreader.data.ThemePreferences
import com.abhinavxt.novelreader.data.database.HighlightEntity
import com.abhinavxt.novelreader.data.model.Chapter
import com.abhinavxt.novelreader.data.model.ReaderSettings
import com.abhinavxt.novelreader.data.model.ReaderTheme
import com.abhinavxt.novelreader.data.model.ReaderFont
import com.abhinavxt.novelreader.data.model.ReadingMode
import com.abhinavxt.novelreader.data.model.PageTransition
import com.abhinavxt.novelreader.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
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
    private val repository: NovelRepository,
    private val themePreferences: ThemePreferences? = null,
    private val statsTracker: ReadingStatsTracker? = null,
    private val prefetcher: ChapterPrefetcher? = null
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

    // ============ BOOKMARK STATE ============

    private val _isInLibrary = MutableStateFlow(false)
    val isInLibrary: StateFlow<Boolean> = _isInLibrary.asStateFlow()

    // ============ READING TIME ESTIMATE ============

    private val _estimatedMinutesLeft = MutableStateFlow<Int?>(null)
    val estimatedMinutesLeft: StateFlow<Int?> = _estimatedMinutesLeft.asStateFlow()

    private val _userWPM = MutableStateFlow<Int?>(null)
    val userWPM: StateFlow<Int?> = _userWPM.asStateFlow()

    private val _bookmarkSavedEvent = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val bookmarkSavedEvent = _bookmarkSavedEvent.receiveAsFlow()

    // ============ DICTIONARY STATE ============

    private val _dictionaryState = MutableStateFlow<DictionaryState>(DictionaryState.Idle)
    val dictionaryState: StateFlow<DictionaryState> = _dictionaryState.asStateFlow()

    // ============ HIGHLIGHT STATE ============

    /**
     * Highlights for the current chapter — observed by the reader to render
     * colored overlays on paragraph text. Updated via Room Flow whenever
     * the user adds/removes/edits a highlight.
     */
    private val _chapterHighlights = MutableStateFlow<List<HighlightEntity>>(emptyList())
    val chapterHighlights: StateFlow<List<HighlightEntity>> = _chapterHighlights.asStateFlow()

    // Feedback signal: emits once when a highlight is saved
    private val _highlightSavedEvent = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val highlightSavedEvent = _highlightSavedEvent.receiveAsFlow()

    fun lookupWord(word: String) {
        val cleanWord = word.trim()
        if (cleanWord.isBlank()) return

        _dictionaryState.value = DictionaryState.Loading

        val languageCode = themePreferences?.dictionaryLanguage?.value?.code ?: "en"

        viewModelScope.launch {
            val result = DictionaryRepository.lookup(cleanWord, languageCode)
            _dictionaryState.value = if (result != null) {
                DictionaryState.Success(result)
            } else {
                DictionaryState.NotFound(cleanWord)
            }
        }
    }

    fun dismissDictionary() {
        _dictionaryState.value = DictionaryState.Idle
    }

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

                _isInLibrary.value = repository.isInLibrary(novelId)

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

    private fun splitIntoParagraphs(content: String): List<String> {
        return content
            .split("\n\n", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private var autoRetryEnabled = false
    private val maxAutoRetries = 3
    private var autoRetryCount = 0

    private fun loadChapter() {
        viewModelScope.launch {
            _uiState.value = ReaderUiState.Loading

            try {
                val cachedContent = prefetcher?.getIfCached(currentChapterId)

                val content = cachedContent ?: repository.getChapterContent(
                    novelId = novelId,
                    chapterId = currentChapterId,
                    chapterUrl = currentChapterUrl
                )
                val settings = repository.getReaderSettings()

                if (content != null) {
                    retryCount = 0
                    autoRetryCount = 0

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

                    val paragraphs = splitIntoParagraphs(content)

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

                    statsTracker?.startSession(novelId, currentChapterId, content)

                    viewModelScope.launch {
                        val wordCount = content.split(Regex("\\s+")).count { it.isNotBlank() }
                        val estimatedMins = statsTracker?.estimateMinutes(wordCount)
                        _estimatedMinutesLeft.value = estimatedMins
                        _userWPM.value = statsTracker?.getUserWPM()
                    }

                    saveProgress(currentIndex + 1, savedParagraphIndex)

                    shouldRestorePosition = false
                    autoRetryEnabled = false

                    // Load highlights for this chapter via Room Flow
                    loadHighlightsForChapter(currentChapterId)

                    if (chapterList.isNotEmpty() && currentIndex >= 0) {
                        prefetcher?.prefetchAround(
                            novelId = novelId,
                            currentIndex = currentIndex,
                            chapters = chapterList.map {
                                ChapterPrefetcher.ChapterRef(id = it.id, url = it.url)
                            }
                        )
                    }
                } else {
                    handleLoadError("Failed to load chapter content. The source may be temporarily unavailable.")
                }
            } catch (e: java.net.SocketTimeoutException) {
                handleLoadError("Connection timed out. The source is responding slowly.")
            } catch (e: java.net.UnknownHostException) {
                handleLoadError("No internet connection. Please check your network.")
            } catch (e: java.io.IOException) {
                handleLoadError("Network error: ${e.message ?: "Connection failed"}")
            } catch (e: Exception) {
                e.printStackTrace()
                handleLoadError("Error: ${e.message ?: "Something went wrong"}")
            }
        }
    }

    /**
     * Observe highlights for the given chapter from Room.
     * Collects into _chapterHighlights so the UI can render overlays.
     */
    private fun loadHighlightsForChapter(chapterId: String) {
        viewModelScope.launch {
            repository.getHighlightsForChapter(chapterId).collect { highlights ->
                _chapterHighlights.value = highlights
            }
        }
    }

    private fun handleLoadError(message: String) {
        if (autoRetryEnabled && autoRetryCount < maxAutoRetries) {
            autoRetryCount++
            loadChapter()
        } else {
            autoRetryEnabled = false
            autoRetryCount = 0
            _uiState.value = ReaderUiState.Error("$message Tap to retry.")
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
            } catch (e: Exception) { /* Ignore */ }
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
                viewModelScope.launch { statsTracker?.endSession() }
                shouldRestorePosition = false
                autoRetryEnabled = false
                autoRetryCount = 0
                currentChapterId = prev.id
                currentChapterUrl = prev.url
                loadChapter()
            }
        }
    }

    fun goToNextChapter() {
        goToNextChapter(withAutoRetry = false)
    }

    fun goToNextChapterWithRetry() {
        goToNextChapter(withAutoRetry = true)
    }

    private fun goToNextChapter(withAutoRetry: Boolean) {
        val currentState = _uiState.value
        if (currentState is ReaderUiState.Success) {
            currentState.chapter.nextChapter?.let { next ->
                viewModelScope.launch { statsTracker?.endSession() }
                shouldRestorePosition = false
                autoRetryEnabled = withAutoRetry
                autoRetryCount = 0
                currentChapterId = next.id
                currentChapterUrl = next.url
                loadChapter()
            }
        }
    }

    fun goToChapter(chapterId: String, chapterUrl: String) {
        shouldRestorePosition = false
        autoRetryEnabled = false
        autoRetryCount = 0
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
        if (currentState is ReaderUiState.Success && currentState.settings.fontSize < 32) {
            updateSettings(currentState.settings.copy(fontSize = currentState.settings.fontSize + 1))
        }
    }

    fun decreaseFontSize() {
        val currentState = _uiState.value
        if (currentState is ReaderUiState.Success && currentState.settings.fontSize > 12) {
            updateSettings(currentState.settings.copy(fontSize = currentState.settings.fontSize - 1))
        }
    }

    fun cycleTheme() {
        val currentState = _uiState.value
        if (currentState is ReaderUiState.Success) {
            val nextTheme = when (currentState.settings.theme) {
                ReaderTheme.PAPER -> ReaderTheme.SEPIA
                ReaderTheme.SEPIA -> ReaderTheme.SOLARIZED_LIGHT
                ReaderTheme.SOLARIZED_LIGHT -> ReaderTheme.DARK
                ReaderTheme.DARK -> ReaderTheme.AMOLED
                ReaderTheme.AMOLED -> ReaderTheme.NORD
                ReaderTheme.NORD -> ReaderTheme.DRACULA
                ReaderTheme.DRACULA -> ReaderTheme.GRUVBOX
                ReaderTheme.GRUVBOX -> ReaderTheme.CATPPUCCIN
                ReaderTheme.CATPPUCCIN -> ReaderTheme.NAVY
                ReaderTheme.NAVY -> ReaderTheme.GREY
                ReaderTheme.GREY -> ReaderTheme.PAPER
            }
            updateSettings(currentState.settings.copy(theme = nextTheme))
        }
    }

    fun cycleFont() {
        val currentState = _uiState.value
        if (currentState is ReaderUiState.Success) {
            val nextFont = when (currentState.settings.font) {
                ReaderFont.LITERATA -> ReaderFont.LORA
                ReaderFont.LORA -> ReaderFont.MERRIWEATHER
                ReaderFont.MERRIWEATHER -> ReaderFont.CRIMSON_TEXT
                ReaderFont.CRIMSON_TEXT -> ReaderFont.SOURCE_SANS
                ReaderFont.SOURCE_SANS -> ReaderFont.NOTO_SANS
                ReaderFont.NOTO_SANS -> ReaderFont.OPEN_DYSLEXIC
                ReaderFont.OPEN_DYSLEXIC -> ReaderFont.JETBRAINS_MONO
                ReaderFont.JETBRAINS_MONO -> ReaderFont.LITERATA
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

    // ============ BOOKMARK METHODS ============

    fun addBookmarkAtParagraph(paragraphIndex: Int, paragraphText: String) {
        val currentState = _uiState.value
        if (currentState !is ReaderUiState.Success) return

        val chapter = currentState.chapter

        viewModelScope.launch {
            try {
                val snippet = if (paragraphText.length > 150) {
                    paragraphText.take(150).trim() + "…"
                } else {
                    paragraphText.trim()
                }

                repository.addBookmark(
                    novelId = novelId,
                    chapterId = currentChapterId,
                    chapterUrl = currentChapterUrl,
                    chapterNumber = chapter.chapterNumber,
                    chapterTitle = chapter.chapterTitle,
                    paragraphIndex = paragraphIndex,
                    textSnippet = snippet
                )

                Logger.d("Bookmark added at Ch.${chapter.chapterNumber}, paragraph $paragraphIndex")
                _bookmarkSavedEvent.trySend(Unit)
            } catch (e: Exception) {
                Logger.e("Failed to add bookmark", e)
            }
        }
    }

    // ============ HIGHLIGHT METHODS ============

    /**
     * Add a highlight from a text selection in the reader.
     *
     * The highlight is stored with paragraph index and character offsets
     * so we can render the overlay accurately. The selected text is also
     * stored for display in the "All Highlights" list even if the source
     * chapter content changes later.
     */
    fun addHighlight(
        paragraphIndex: Int,
        startOffset: Int,
        endOffset: Int,
        selectedText: String,
        color: String = "YELLOW"
    ) {
        val currentState = _uiState.value
        if (currentState !is ReaderUiState.Success) return

        val chapter = currentState.chapter

        viewModelScope.launch {
            try {
                repository.addHighlight(
                    novelId = novelId,
                    chapterId = currentChapterId,
                    chapterNumber = chapter.chapterNumber,
                    chapterTitle = chapter.chapterTitle,
                    paragraphIndex = paragraphIndex,
                    startOffset = startOffset,
                    endOffset = endOffset,
                    selectedText = selectedText,
                    color = color
                )
                Logger.d("Highlight added: Ch.${chapter.chapterNumber}, para $paragraphIndex [$startOffset:$endOffset]")
                _highlightSavedEvent.trySend(Unit)
            } catch (e: Exception) {
                Logger.e("Failed to add highlight", e)
            }
        }
    }

    fun updateHighlightNote(highlightId: Long, note: String?) {
        viewModelScope.launch {
            try {
                repository.updateHighlightNote(highlightId, note)
            } catch (e: Exception) {
                Logger.e("Failed to update highlight note", e)
            }
        }
    }

    fun updateHighlightColor(highlightId: Long, color: String) {
        viewModelScope.launch {
            try {
                repository.updateHighlightColor(highlightId, color)
            } catch (e: Exception) {
                Logger.e("Failed to update highlight color", e)
            }
        }
    }

    fun deleteHighlight(highlightId: Long) {
        viewModelScope.launch {
            try {
                repository.deleteHighlight(highlightId)
            } catch (e: Exception) {
                Logger.e("Failed to delete highlight", e)
            }
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

    // ============ LIFECYCLE ============

    override fun onCleared() {
        super.onCleared()
        prefetcher?.clear()
        viewModelScope.launch {
            statsTracker?.endSession()
        }
    }

    companion object {
        fun provideFactory(
            novelId: String,
            chapterId: String,
            chapterUrl: String,
            novelUrl: String,
            repository: NovelRepository,
            themePreferences: ThemePreferences? = null,
            statsTracker: ReadingStatsTracker? = null,
            prefetcher: ChapterPrefetcher? = null
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ReaderViewModel(novelId, chapterId, chapterUrl, novelUrl, repository, themePreferences, statsTracker, prefetcher) as T
                }
            }
        }
    }
}