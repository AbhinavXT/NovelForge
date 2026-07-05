package com.abhinavxt.novelreader.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.abhinavxt.novelreader.AppConfig
import com.abhinavxt.novelreader.NovelReaderApplication
import com.abhinavxt.novelreader.data.NovelRepository
import com.abhinavxt.novelreader.data.ReadingStatsTracker
import com.abhinavxt.novelreader.data.TTSManager
import com.abhinavxt.novelreader.data.database.BookmarkEntity
import com.abhinavxt.novelreader.data.model.Chapter
import com.abhinavxt.novelreader.data.model.Novel
import com.abhinavxt.novelreader.data.tts.AudioExporter
import com.abhinavxt.novelreader.data.tts.M4BAudiobookBuilder
import com.abhinavxt.novelreader.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface NovelDetailUiState {
    object Loading : NovelDetailUiState
    data class Success(
        val novel: Novel,
        val isInLibrary: Boolean,
        val isLocalNovel: Boolean = false
    ) : NovelDetailUiState
    data class Error(val message: String) : NovelDetailUiState
}

class NovelDetailViewModel(
    private val novelId: String,
    private val novelUrl: String,
    private val repository: NovelRepository,
    private val ttsManager: TTSManager,
    context: Context,
    private val statsTracker: ReadingStatsTracker? = null
) : ViewModel() {

    // Store application context for M4B builder access
    private val appContext = context.applicationContext

    private val _uiState = MutableStateFlow<NovelDetailUiState>(NovelDetailUiState.Loading)
    val uiState: StateFlow<NovelDetailUiState> = _uiState.asStateFlow()

    /**
     * Estimated total hours to finish the novel, based on user's WPM and
     * remaining unread chapters. Null = not yet calculated or not enough data.
     * Format: "~12 hrs" or "~45 min" for shorter novels.
     */
    private val _estimatedTimeToFinish = MutableStateFlow<String?>(null)
    val estimatedTimeToFinish: StateFlow<String?> = _estimatedTimeToFinish.asStateFlow()

    // Track chapters currently being downloaded
    private val _downloadingChapters = MutableStateFlow<Set<String>>(emptySet())
    val downloadingChapters: StateFlow<Set<String>> = _downloadingChapters.asStateFlow()

    // Check if this is a local novel (imported EPUB)
    private val isLocalNovel: Boolean = novelId.startsWith("local_")

    // ============ AUDIO EXPORT STATE ============

    val audioExporter: AudioExporter = AudioExporter(appContext, ttsManager)
    val exportState: StateFlow<AudioExporter.ExportState> = audioExporter.exportState
    val exportingChapters: StateFlow<Set<String>> = audioExporter.exportingChapters

    // Chapters that already have exported audio files
    private val _audioExportedChapters = MutableStateFlow<Set<String>>(emptySet())
    val audioExportedChapters: StateFlow<Set<String>> = _audioExportedChapters.asStateFlow()

    // ============ BOOKMARK STATE ============

    // All bookmarks for this novel — observed as a Flow so the UI auto-updates
    private val _bookmarks = MutableStateFlow<List<BookmarkEntity>>(emptyList())
    val bookmarks: StateFlow<List<BookmarkEntity>> = _bookmarks.asStateFlow()

    // Bookmark count for the tab badge
    private val _bookmarkCount = MutableStateFlow(0)
    val bookmarkCount: StateFlow<Int> = _bookmarkCount.asStateFlow()

    // ============ HIGHLIGHT STATE ============

    // All highlights for this novel — for an "All Highlights" tab/screen
    private val _highlights = MutableStateFlow<List<com.abhinavxt.novelreader.data.database.HighlightEntity>>(emptyList())
    val highlights: StateFlow<List<com.abhinavxt.novelreader.data.database.HighlightEntity>> = _highlights.asStateFlow()

    // Highlight count for the tab badge
    private val _highlightCount = MutableStateFlow(0)
    val highlightCount: StateFlow<Int> = _highlightCount.asStateFlow()

    // ============ READING PROGRESS ============

    // Current chapter the user is reading (for auto-scroll)
    private val _currentReadingChapterId = MutableStateFlow<String?>(null)
    val currentReadingChapterId: StateFlow<String?> = _currentReadingChapterId.asStateFlow()

    // ============ UPDATE CHANGELOG ============

    /**
     * Number of new chapters since the user last viewed the detail screen.
     * Driven by the delta between NovelEntity.totalChapters and
     * NovelEntity.previousTotalChapters. The UpdateCheckerWorker bumps
     * totalChapters when it finds new chapters; previousTotalChapters
     * stays at the old value until markUpdateSeen() is called.
     */
    private val _newChapterCount = MutableStateFlow(0)
    val newChapterCount: StateFlow<Int> = _newChapterCount.asStateFlow()

    init {
        loadNovel()
        loadBookmarks()
        loadHighlights()
        loadReadingProgress()
        loadNewChapterCount()
        observeVoiceChanges()
    }

    private fun loadNovel() {
        viewModelScope.launch {
            _uiState.value = NovelDetailUiState.Loading

            try {
                if (isLocalNovel) {
                    loadLocalNovel()
                } else {
                    loadOnlineNovel()
                }
            } catch (e: Exception) {
                _uiState.value = NovelDetailUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private suspend fun loadLocalNovel() {
        val novel = repository.getNovelById(novelId)

        if (novel != null) {
            val chapters = repository.getChapters(novelId).first()
            val chaptersWithStatus = chapters.map { chapter ->
                chapter.copy(isDownloaded = true)
            }

            val novelWithChapters = novel.copy(chapters = chaptersWithStatus)

            _uiState.value = NovelDetailUiState.Success(
                novel = novelWithChapters,
                isInLibrary = true,
                isLocalNovel = true
            )
            calculateEstimatedTime(novelWithChapters)
            refreshAudioExportStatus()
        } else {
            _uiState.value = NovelDetailUiState.Error("Local novel not found")
        }
    }

    private suspend fun loadOnlineNovel() {
        val isInLibrary = repository.isInLibrary(novelId)
        val novel = repository.fetchNovelDetails(novelId, novelUrl)

        if (novel != null) {
            // One query for all downloaded ids instead of one query per
            // chapter — the old loop was N single-row DB hits for an
            // N-chapter novel (2,000+ for long webnovels).
            val downloadedIds = repository.getDownloadedChapterIds(novelId)
            val chaptersWithStatus = novel.chapters.map { chapter ->
                chapter.copy(isDownloaded = chapter.id in downloadedIds)
            }

            val novelWithDownloadStatus = novel.copy(chapters = chaptersWithStatus)

            _uiState.value = NovelDetailUiState.Success(
                novel = novelWithDownloadStatus,
                isInLibrary = isInLibrary,
                isLocalNovel = false
            )
            calculateEstimatedTime(novelWithDownloadStatus)
            refreshAudioExportStatus()
        } else {
            _uiState.value = NovelDetailUiState.Error("Failed to load novel")
        }
    }

    /**
     * Download a single chapter
     */
    fun downloadChapter(chapter: Chapter) {
        if (!AppConfig.ONLINE_SOURCES_ENABLED) return
        val currentState = _uiState.value
        if (currentState !is NovelDetailUiState.Success) return
        if (currentState.isLocalNovel) return
        if (chapter.isDownloaded) return

        viewModelScope.launch {
            // Add to downloading set
            _downloadingChapters.value = _downloadingChapters.value + chapter.id

            try {
                val success = repository.downloadChapter(
                    novelId = novelId,
                    chapterId = chapter.id,
                    chapterUrl = chapter.url
                )

                if (success) {
                    // Update the chapter's download status in UI state
                    updateChapterDownloadStatus(chapter.id, true)
                }
            } finally {
                // Remove from downloading set
                _downloadingChapters.value = _downloadingChapters.value - chapter.id
            }
        }
    }

    /**
     * Update a chapter's download status in the UI state
     */
    private fun updateChapterDownloadStatus(chapterId: String, isDownloaded: Boolean) {
        val currentState = _uiState.value
        if (currentState !is NovelDetailUiState.Success) return

        val updatedChapters = currentState.novel.chapters.map { chapter ->
            if (chapter.id == chapterId) {
                chapter.copy(isDownloaded = isDownloaded)
            } else {
                chapter
            }
        }

        _uiState.value = currentState.copy(
            novel = currentState.novel.copy(chapters = updatedChapters)
        )
    }

    /**
     * Refresh download status for all chapters
     */
    fun refreshDownloadStatus() {
        viewModelScope.launch {
            val currentState = _uiState.value
            if (currentState !is NovelDetailUiState.Success) return@launch
            if (currentState.isLocalNovel) return@launch

            // Same one-query pattern as loadOnlineNovel — avoids N
            // single-row lookups on every refresh.
            val downloadedIds = repository.getDownloadedChapterIds(novelId)
            val updatedChapters = currentState.novel.chapters.map { chapter ->
                chapter.copy(isDownloaded = chapter.id in downloadedIds)
            }

            _uiState.value = currentState.copy(
                novel = currentState.novel.copy(chapters = updatedChapters)
            )
        }
    }

    fun toggleLibrary() {
        val currentState = _uiState.value
        if (currentState !is NovelDetailUiState.Success) return
        if (currentState.isLocalNovel) return

        viewModelScope.launch {
            if (currentState.isInLibrary) {
                repository.removeFromLibrary(novelId)
            } else {
                repository.addToLibrary(currentState.novel)
            }

            _uiState.value = currentState.copy(
                isInLibrary = !currentState.isInLibrary
            )
        }
    }

    fun getFirstChapter(): Chapter? {
        val currentState = _uiState.value
        if (currentState is NovelDetailUiState.Success) {
            return currentState.novel.chapters.firstOrNull()
        }
        return null
    }

    // ============ READING PROGRESS ============

    /**
     * Load reading progress so the chapter list can auto-scroll to where the user left off.
     */
    private fun loadReadingProgress() {
        viewModelScope.launch {
            try {
                val progress = repository.getReadingProgress(novelId)
                if (progress != null) {
                    _currentReadingChapterId.value = progress.currentChapterId
                    Logger.d("NovelDetailVM", "Reading progress: chapter=${progress.currentChapterId}")
                }
            } catch (e: Exception) {
                Logger.w("NovelDetailVM", "Failed to load reading progress: ${e.message}")
            }
        }
    }

    // ============ UPDATE CHANGELOG METHODS ============

    /**
     * Check how many new chapters have appeared since the user last
     * viewed this novel's detail screen. Uses the delta between
     * totalChapters and previousTotalChapters in NovelEntity.
     */
    private fun loadNewChapterCount() {
        viewModelScope.launch {
            try {
                _newChapterCount.value = repository.getNewChapterCount(novelId)
            } catch (e: Exception) {
                Logger.w("NovelDetailVM", "Failed to load new chapter count: ${e.message}")
            }
        }
    }

    /**
     * Mark the novel's update as "seen" — resets the badge by setting
     * previousTotalChapters = totalChapters in the database.
     * Called automatically after the user has viewed the detail screen
     * for a few seconds, or manually when they tap the badge.
     */
    fun markUpdateSeen() {
        viewModelScope.launch {
            try {
                repository.markUpdateSeen(novelId)
                _newChapterCount.value = 0
            } catch (e: Exception) {
                Logger.w("NovelDetailVM", "Failed to mark update seen: ${e.message}")
            }
        }
    }

    // ============ BOOKMARK METHODS ============

    /**
     * Observe bookmarks for this novel as a Flow.
     * This means the bookmark tab updates automatically when bookmarks
     * are added or removed (even from the reader screen).
     */
    private fun loadBookmarks() {
        viewModelScope.launch {
            repository.getBookmarksForNovel(novelId).collect { bookmarkList ->
                _bookmarks.value = bookmarkList
                _bookmarkCount.value = bookmarkList.size
            }
        }
    }

    /**
     * Re-check export status whenever the TTS voice changes,
     * since exports are voice-specific.
     * drop(1) skips the initial emission — loadNovel() already handles the first check.
     */
    private fun observeVoiceChanges() {
        viewModelScope.launch {
            ttsManager.currentVoice
                .drop(1)  // skip initial value; loadNovel handles it
                .collect {
                    refreshAudioExportStatus()
                }
        }
    }

    /**
     * Observe highlights for this novel as a Flow.
     * Powers the "All Highlights" screen accessible from the detail screen.
     */
    private fun loadHighlights() {
        viewModelScope.launch {
            repository.getHighlightsForNovel(novelId).collect { highlightList ->
                _highlights.value = highlightList
                _highlightCount.value = highlightList.size
            }
        }
    }

    /**
     * Delete a single highlight. The Flow collection in loadHighlights()
     * will automatically update the UI.
     */
    fun deleteHighlight(highlightId: Long) {
        viewModelScope.launch {
            try {
                repository.deleteHighlight(highlightId)
                Logger.d("Highlight $highlightId deleted")
            } catch (e: Exception) {
                Logger.e("Failed to delete highlight", e)
            }
        }
    }

    /**
     * Update the note/annotation on a highlight.
     */
    fun updateHighlightNote(highlightId: Long, note: String?) {
        viewModelScope.launch {
            try {
                repository.updateHighlightNote(highlightId, note)
            } catch (e: Exception) {
                Logger.e("Failed to update highlight note", e)
            }
        }
    }

    /**
     * Delete a single bookmark. The Flow collection in loadBookmarks()
     * will automatically update the UI.
     */
    fun deleteBookmark(bookmarkId: Long) {
        viewModelScope.launch {
            try {
                repository.deleteBookmark(bookmarkId)
                Logger.d("Bookmark $bookmarkId deleted")
            } catch (e: Exception) {
                Logger.e("Failed to delete bookmark", e)
            }
        }
    }

    /**
     * Update the note on a bookmark.
     */
    fun updateBookmarkNote(bookmarkId: Long, note: String?) {
        viewModelScope.launch {
            try {
                repository.updateBookmarkNote(bookmarkId, note)
                Logger.d("Bookmark $bookmarkId note updated")
            } catch (e: Exception) {
                Logger.e("Failed to update bookmark note", e)
            }
        }
    }

    // ============ AUDIO EXPORT METHODS ============

    /**
     * Refresh which chapters have exported audio files on disk.
     */
    fun refreshAudioExportStatus() {
        val currentState = _uiState.value
        if (currentState !is NovelDetailUiState.Success) return

        viewModelScope.launch {
            val chapters = currentState.novel.chapters.map { it.id to it.title }
            val voiceName = ttsManager.currentVoice.value?.displayName

            // File I/O — run off the main thread
            val exported = withContext(Dispatchers.IO) {
                audioExporter.getExportedChapterIds(
                    novelTitle = currentState.novel.title,
                    chapters = chapters,
                    voiceName = voiceName
                )
            }
            _audioExportedChapters.value = exported
            Logger.d("NovelDetailVM", "Audio exported (voice=$voiceName): ${exported.size}/${chapters.size} chapters")
        }
    }

    /**
     * Export a chapter as a WAV audio file using the current TTS voice.
     * Fetches chapter content, then delegates to AudioExporter's own scope
     * so the export survives navigating away from this screen.
     */
    fun exportChapterAudio(chapter: Chapter) {
        val currentState = _uiState.value
        if (currentState !is NovelDetailUiState.Success) return

        viewModelScope.launch {
            try {
                val content = repository.getChapterContent(novelId, chapter.id, chapter.url)
                if (content.isNullOrBlank()) {
                    Logger.e("NovelDetailVM", "No content for chapter: ${chapter.title}")
                    return@launch
                }

                // Launch in AudioExporter's own scope — continues even if ViewModel is cleared
                audioExporter.launchExport(
                    chapterId = chapter.id,
                    novelTitle = currentState.novel.title,
                    chapterTitle = chapter.title,
                    voiceName = ttsManager.currentVoice.value?.displayName ?: "default",
                    text = content,
                    speakerId = if (ttsManager.sherpaEngine.isReady)
                        ttsManager.sherpaEngine.getCurrentSpeakerIdValue() else 0,
                    speed = 1.0f,
                    onComplete = { success ->
                        if (success) refreshAudioExportStatus()
                    }
                )
            } catch (e: Exception) {
                Logger.e("NovelDetailVM", "Audio export failed", e)
            }
        }
    }

    /**
     * Export ALL chapters as audio sequentially. Skips already-exported chapters.
     * Runs in AudioExporter's own scope so it survives screen navigation.
     */
    fun exportAllChaptersAudio() {
        val currentState = _uiState.value
        if (currentState !is NovelDetailUiState.Success) return

        viewModelScope.launch {
            val novel = currentState.novel
            val exported = _audioExportedChapters.value
            val voiceName = ttsManager.currentVoice.value?.displayName ?: "default"
            val speakerId = if (ttsManager.sherpaEngine.isReady)
                ttsManager.sherpaEngine.getCurrentSpeakerIdValue() else 0

            // Pre-fetch content for all unexported chapters
            val chaptersToExport = mutableListOf<AudioExporter.ExportChapterInfo>()
            for (chapter in novel.chapters) {
                if (chapter.id in exported) continue
                try {
                    val content = repository.getChapterContent(novelId, chapter.id, chapter.url)
                    if (!content.isNullOrBlank()) {
                        chaptersToExport.add(
                            AudioExporter.ExportChapterInfo(
                                chapterId = chapter.id,
                                chapterTitle = chapter.title,
                                content = content
                            )
                        )
                    }
                } catch (e: Exception) {
                    Logger.w("NovelDetailVM", "Failed to fetch content for ${chapter.title}")
                }
            }

            // Launch in AudioExporter's own scope
            audioExporter.launchExportAll(
                chapters = chaptersToExport,
                novelTitle = novel.title,
                voiceName = voiceName,
                speakerId = speakerId,
                alreadyExported = exported,
                onEachComplete = { chapterId ->
                    _audioExportedChapters.value = _audioExportedChapters.value + chapterId
                },
                onAllDone = {
                    refreshAudioExportStatus()
                }
            )
        }
    }

    /**
     * Export a range of chapters (by chapter number) as audio.
     * Works like exportAllChaptersAudio but only for chapters in the range.
     */
    fun exportChapterRangeAudio(fromChapter: Int, toChapter: Int) {
        val currentState = _uiState.value
        if (currentState !is NovelDetailUiState.Success) return

        viewModelScope.launch {
            val novel = currentState.novel
            val exported = _audioExportedChapters.value
            val voiceName = ttsManager.currentVoice.value?.displayName ?: "default"
            val speakerId = if (ttsManager.sherpaEngine.isReady)
                ttsManager.sherpaEngine.getCurrentSpeakerIdValue() else 0

            val chaptersToExport = mutableListOf<AudioExporter.ExportChapterInfo>()
            for (chapter in novel.chapters) {
                if (chapter.number !in fromChapter..toChapter) continue
                if (chapter.id in exported) continue
                try {
                    val content = repository.getChapterContent(novelId, chapter.id, chapter.url)
                    if (!content.isNullOrBlank()) {
                        chaptersToExport.add(
                            AudioExporter.ExportChapterInfo(
                                chapterId = chapter.id,
                                chapterTitle = chapter.title,
                                content = content
                            )
                        )
                    }
                } catch (e: Exception) {
                    Logger.w("NovelDetailVM", "Failed to fetch content for ${chapter.title}")
                }
            }

            audioExporter.launchExportAll(
                chapters = chaptersToExport,
                novelTitle = novel.title,
                voiceName = voiceName,
                speakerId = speakerId,
                alreadyExported = exported,
                onEachComplete = { chapterId ->
                    _audioExportedChapters.value = _audioExportedChapters.value + chapterId
                },
                onAllDone = {
                    refreshAudioExportStatus()
                }
            )
        }
    }

    /**
     * Cancel the current audio export.
     */
    fun cancelAudioExport() {
        audioExporter.cancel()
    }

    // ── Reading time estimate ───────────────────────────────────

    /**
     * Calculate estimated time to finish the novel based on:
     *  - User's WPM from ReadingStatsTracker (defaults to 250 if no data)
     *  - Current reading progress (which chapter they're on)
     *  - Average web novel chapter length (~2200 words)
     *
     * Result is formatted as "~45 min" or "~12 hrs".
     */
    private fun calculateEstimatedTime(novel: Novel) {
        viewModelScope.launch {
            try {
                val wpm = statsTracker?.getUserWPM() ?: ReadingStatsTracker.DEFAULT_WPM
                val progress = repository.getReadingProgress(novelId)
                val currentChapter = progress?.currentChapterNumber ?: 0
                val remainingChapters = (novel.chapters.size - currentChapter).coerceAtLeast(0)

                if (remainingChapters == 0) {
                    _estimatedTimeToFinish.value = null
                    return@launch
                }

                // Average web novel chapter ≈ 2200 words (Royal Road/Wuxia average)
                val avgWordsPerChapter = 2200
                val estimatedWords = remainingChapters * avgWordsPerChapter
                val estimatedMinutes = (estimatedWords.toDouble() / wpm).toInt()

                _estimatedTimeToFinish.value = when {
                    estimatedMinutes < 1 -> null
                    estimatedMinutes < 60 -> "~${estimatedMinutes} min"
                    else -> {
                        val hours = estimatedMinutes / 60
                        val mins = estimatedMinutes % 60
                        if (mins > 15) "~${hours}.${(mins * 10 / 60)} hrs"
                        else "~$hours hrs"
                    }
                }
            } catch (e: Exception) {
                Logger.e("NovelDetailVM", "Failed to estimate reading time", e)
                _estimatedTimeToFinish.value = null
            }
        }
    }

    // ── M4B Audiobook generation ───────────────────────────────

    /** Observe M4B build progress from the UI */
    val m4bBuildState: StateFlow<M4BAudiobookBuilder.BuildState>
        get() = (appContext as NovelReaderApplication).m4bBuilder.state

    /**
     * Generate a chaptered M4B audiobook from exported WAV files.
     * Requires all chapters to be exported as WAV first.
     */
    fun generateM4BAudiobook() {
        val currentState = _uiState.value
        if (currentState !is NovelDetailUiState.Success) return

        val voiceName = ttsManager.currentVoice.value?.displayName
        (appContext as NovelReaderApplication).m4bBuilder.launch(
            novelTitle = currentState.novel.title,
            voiceFilter = voiceName
        )
    }

    fun cancelM4BBuild() {
        (appContext as NovelReaderApplication).m4bBuilder.cancel()
    }

    fun resetM4BState() {
        (appContext as NovelReaderApplication).m4bBuilder.reset()
    }

    companion object {
        fun provideFactory(
            novelId: String,
            novelUrl: String,
            repository: NovelRepository,
            ttsManager: TTSManager,
            context: Context,
            statsTracker: ReadingStatsTracker? = null
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return NovelDetailViewModel(novelId, novelUrl, repository, ttsManager, context, statsTracker) as T
                }
            }
        }
    }
}