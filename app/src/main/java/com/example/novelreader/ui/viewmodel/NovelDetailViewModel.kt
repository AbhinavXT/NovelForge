package com.example.novelreader.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.novelreader.data.NovelRepository
import com.example.novelreader.data.model.Chapter
import com.example.novelreader.data.model.Novel
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
        val isLocalNovel: Boolean = false  // Added to track local novels
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

    // Check if this is a local novel (imported EPUB)
    private val isLocalNovel: Boolean = novelId.startsWith("local_")

    init {
        loadNovel()
    }

    private fun loadNovel() {
        viewModelScope.launch {
            _uiState.value = NovelDetailUiState.Loading

            try {
                if (isLocalNovel) {
                    // Local novel - load from database
                    loadLocalNovel()
                } else {
                    // Online novel - fetch from network
                    loadOnlineNovel()
                }
            } catch (e: Exception) {
                _uiState.value = NovelDetailUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private suspend fun loadLocalNovel() {
        // Get novel from database
        val novel = repository.getNovelById(novelId)

        if (novel != null) {
            // Get chapters from database
            val chapters = repository.getChapters(novelId).first()

            // Mark all chapters as downloaded (they're stored locally)
            val chaptersWithStatus = chapters.map { chapter ->
                chapter.copy(isDownloaded = true)
            }

            val novelWithChapters = novel.copy(chapters = chaptersWithStatus)

            _uiState.value = NovelDetailUiState.Success(
                novel = novelWithChapters,
                isInLibrary = true,  // Local novels are always in library
                isLocalNovel = true
            )
        } else {
            _uiState.value = NovelDetailUiState.Error("Local novel not found")
        }
    }

    private suspend fun loadOnlineNovel() {
        // Check library first
        val isInLibrary = repository.isInLibrary(novelId)

        // Fetch novel details from network
        val novel = repository.fetchNovelDetails(novelId, novelUrl)

        if (novel != null) {
            // Update chapters with download status
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

    fun toggleLibrary() {
        val currentState = _uiState.value
        if (currentState !is NovelDetailUiState.Success) return

        // Don't allow removing local novels from library here
        // They should be removed via LibraryScreen
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