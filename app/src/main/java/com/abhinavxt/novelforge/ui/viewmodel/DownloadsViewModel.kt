package com.abhinavxt.novelforge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.abhinavxt.novelforge.data.DownloadManager
import com.abhinavxt.novelforge.data.NovelRepository
import com.abhinavxt.novelforge.ui.screens.NovelDownloadInfo
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    init {
        loadDownloadedNovels()
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