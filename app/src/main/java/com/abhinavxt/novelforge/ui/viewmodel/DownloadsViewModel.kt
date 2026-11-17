package com.abhinavxt.novelforge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.abhinavxt.novelforge.data.DownloadManager
import com.abhinavxt.novelforge.data.DownloadSummary
import com.abhinavxt.novelforge.data.NovelRepository
import com.abhinavxt.novelforge.data.database.DownloadQueueEntity
import com.abhinavxt.novelforge.ui.screens.NovelDownloadInfo
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadsViewModel(
    private val repository: NovelRepository,
    private val downloadManager: DownloadManager
) : ViewModel() {

    private val _downloadedNovels = MutableStateFlow<List<NovelDownloadInfo>>(emptyList())
    val downloadedNovels: StateFlow<List<NovelDownloadInfo>> = _downloadedNovels.asStateFlow()

    private val _totalStorageUsed = MutableStateFlow(0L)
    val totalStorageUsed: StateFlow<Long> = _totalStorageUsed.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // The background queue; lives in the manager, this only exposes it to the screen
    val queueItems: StateFlow<List<DownloadQueueEntity>> = downloadManager.queueItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val queueSummary: StateFlow<DownloadSummary> = downloadManager.summary
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadSummary(0, 0, 0, false))

    fun pauseQueue() = downloadManager.pause()
    fun resumeQueue() = downloadManager.resume()
    fun cancelQueue() = downloadManager.cancelAll()
    fun removeFromQueue(chapterId: String) = downloadManager.remove(chapterId)
    fun retryFailed() = downloadManager.retryFailed()

    init {
        loadDownloadedNovels()
        // Chapters finishing in the background change the sizes and counts shown below.
        viewModelScope.launch {
            downloadManager.queueItems
                .map { it.size }
                .distinctUntilChanged()
                .drop(1)
                .collect { loadDownloadedNovels() }
        }
    }

    private fun loadDownloadedNovels() {
        viewModelScope.launch {
            _isLoading.value = true

            try {
                val novels = repository.getNovelsWithDownloads()
                _downloadedNovels.value = novels

                // Calculate total storage
                _totalStorageUsed.value = novels.sumOf { it.sizeBytes }
            } catch (e: Exception) {
                Logger.e("Error", e)
            }

            _isLoading.value = false
        }
    }

    fun deleteDownloadsForNovel(novelId: String) {
        viewModelScope.launch {
            downloadManager.deleteAllDownloads(novelId)
            loadDownloadedNovels() // Refresh the list
        }
    }

    fun refresh() {
        loadDownloadedNovels()
    }

    companion object {
        fun provideFactory(
            repository: NovelRepository,
            downloadManager: DownloadManager
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return DownloadsViewModel(repository, downloadManager) as T
                }
            }
        }
    }
}