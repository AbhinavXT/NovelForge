package com.example.novelreader.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.novelreader.data.NovelRepository
import com.example.novelreader.data.database.BookmarkEntity
import com.example.novelreader.data.model.Chapter
import com.example.novelreader.data.model.Novel
import com.example.novelreader.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    private val repository: NovelRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<NovelDetailUiState>(NovelDetailUiState.Loading)
    val uiState: StateFlow<NovelDetailUiState> = _uiState.asStateFlow()

    // Track chapters currently being downloaded
    private val _downloadingChapters = MutableStateFlow<Set<String>>(emptySet())
    val downloadingChapters: StateFlow<Set<String>> = _downloadingChapters.asStateFlow()

    // Check if this is a local novel (imported EPUB)
    private val isLocalNovel: Boolean = novelId.startsWith("local_")

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
        } else {
            _uiState.value = NovelDetailUiState.Error("Failed to load novel")
        }
    }

    /**
     * Download a single chapter
     */
    fun downloadChapter(chapter: Chapter) {
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

    companion object {
        fun provideFactory(
            novelId: String,
            novelUrl: String,
            repository: NovelRepository
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return NovelDetailViewModel(novelId, novelUrl, repository) as T
                }
            }
        }
    }
}