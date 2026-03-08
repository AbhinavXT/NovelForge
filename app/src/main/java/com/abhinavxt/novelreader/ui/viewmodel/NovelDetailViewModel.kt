package com.abhinavxt.novelreader.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.abhinavxt.novelreader.AppConfig
import com.abhinavxt.novelreader.data.NovelRepository
import com.abhinavxt.novelreader.data.TTSManager
import com.abhinavxt.novelreader.data.database.BookmarkEntity
import com.abhinavxt.novelreader.data.model.Chapter
import com.abhinavxt.novelreader.data.model.Novel
import com.abhinavxt.novelreader.data.tts.AudioExporter
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
    context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow<NovelDetailUiState>(NovelDetailUiState.Loading)
    val uiState: StateFlow<NovelDetailUiState> = _uiState.asStateFlow()

    // Track chapters currently being downloaded
    private val _downloadingChapters = MutableStateFlow<Set<String>>(emptySet())
    val downloadingChapters: StateFlow<Set<String>> = _downloadingChapters.asStateFlow()

    // Check if this is a local novel (imported EPUB)
    private val isLocalNovel: Boolean = novelId.startsWith("local_")

    // ============ AUDIO EXPORT STATE ============

    val audioExporter: AudioExporter = AudioExporter(context, ttsManager)
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

    init {
        loadNovel()
        loadBookmarks()  // Start observing bookmarks immediately
        observeVoiceChanges()  // Refresh export status when voice changes
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
            refreshAudioExportStatus()
        } else {
            _uiState.value = NovelDetailUiState.Error("Local novel not found")
        }
    }

    private suspend fun loadOnlineNovel() {
        val isInLibrary = repository.isInLibrary(novelId)
        val novel = repository.fetchNovelDetails(novelId, novelUrl)

        if (novel != null) {
            val chaptersWithStatus = novel.chapters.map { chapter ->
                val isDownloaded = repository.isChapterDownloaded(chapter.id)
                chapter.copy(isDownloaded = isDownloaded)
            }

            val novelWithDownloadStatus = novel.copy(chapters = chaptersWithStatus)

            _uiState.value = NovelDetailUiState.Success(
                novel = novelWithDownloadStatus,
                isInLibrary = isInLibrary,
                isLocalNovel = false
            )
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

            val updatedChapters = currentState.novel.chapters.map { chapter ->
                val isDownloaded = repository.isChapterDownloaded(chapter.id)
                chapter.copy(isDownloaded = isDownloaded)
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
     * Fetches chapter content if needed, then runs the export.
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

                val success = audioExporter.exportChapter(
                    chapterId = chapter.id,
                    novelTitle = currentState.novel.title,
                    chapterTitle = chapter.title,
                    voiceName = ttsManager.currentVoice.value?.displayName ?: "default",
                    text = content,
                    speakerId = if (ttsManager.sherpaEngine.isReady)
                        ttsManager.sherpaEngine.getCurrentSpeakerIdValue() else 0,
                    speed = 1.0f
                )
                if (success) refreshAudioExportStatus()
            } catch (e: Exception) {
                Logger.e("NovelDetailVM", "Audio export failed", e)
            }
        }
    }

    /**
     * Export ALL chapters as audio sequentially. Skips already-exported chapters.
     */
    fun exportAllChaptersAudio() {
        val currentState = _uiState.value
        if (currentState !is NovelDetailUiState.Success) return

        viewModelScope.launch {
            val novel = currentState.novel
            val exported = _audioExportedChapters.value

            for (chapter in novel.chapters) {
                if (chapter.id in exported) {
                    Logger.d("NovelDetailVM", "Skipping already exported: ${chapter.title}")
                    continue
                }

                try {
                    val content = repository.getChapterContent(novelId, chapter.id, chapter.url)
                    if (content.isNullOrBlank()) {
                        Logger.w("NovelDetailVM", "No content for: ${chapter.title}, skipping")
                        continue
                    }

                    val success = audioExporter.exportChapter(
                        chapterId = chapter.id,
                        novelTitle = novel.title,
                        chapterTitle = chapter.title,
                        voiceName = ttsManager.currentVoice.value?.displayName ?: "default",
                        text = content,
                        speakerId = if (ttsManager.sherpaEngine.isReady)
                            ttsManager.sherpaEngine.getCurrentSpeakerIdValue() else 0,
                        speed = 1.0f
                    )
                    if (success) {
                        _audioExportedChapters.value = _audioExportedChapters.value + chapter.id
                    }
                } catch (e: Exception) {
                    Logger.e("NovelDetailVM", "Export failed for ${chapter.title}", e)
                }
            }

            refreshAudioExportStatus()
        }
    }

    /**
     * Cancel the current audio export.
     */
    fun cancelAudioExport() {
        audioExporter.cancel()
    }

    companion object {
        fun provideFactory(
            novelId: String,
            novelUrl: String,
            repository: NovelRepository,
            ttsManager: TTSManager,
            context: Context
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return NovelDetailViewModel(novelId, novelUrl, repository, ttsManager, context) as T
                }
            }
        }
    }
}