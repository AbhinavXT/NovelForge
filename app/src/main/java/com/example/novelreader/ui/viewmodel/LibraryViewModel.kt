package com.example.novelreader.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.novelreader.data.NovelRepository
import com.example.novelreader.data.epub.EpubImporter
import com.example.novelreader.data.model.Novel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Import state for UI feedback
 */
sealed interface ImportState {
    object Idle : ImportState
    object Importing : ImportState
    data class Success(val title: String, val chapterCount: Int) : ImportState
    data class Error(val message: String) : ImportState
}

class LibraryViewModel(
    private val repository: NovelRepository,
    private val epubImporter: EpubImporter? = null
) : ViewModel() {

    private val _libraryNovels = MutableStateFlow<List<Novel>>(emptyList())
    val libraryNovels: StateFlow<List<Novel>> = _libraryNovels.asStateFlow()

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    init {
        loadLibrary()
    }

    private fun loadLibrary() {
        viewModelScope.launch {
            repository.getLibraryNovels().collect { novels ->
                _libraryNovels.value = novels
            }
        }
    }

    /**
     * Import an EPUB file
     */
    fun importEpub(uri: Uri) {
        if (epubImporter == null) return

        viewModelScope.launch {
            _importState.value = ImportState.Importing

            when (val result = epubImporter.importEpub(uri)) {
                is EpubImporter.ImportResult.Success -> {
                    _importState.value = ImportState.Success(
                        title = result.title,
                        chapterCount = result.chapterCount
                    )
                }
                is EpubImporter.ImportResult.Error -> {
                    _importState.value = ImportState.Error(result.message)
                }
            }
        }
    }

    /**
     * Clear import state (after showing success/error message)
     */
    fun clearImportState() {
        _importState.value = ImportState.Idle
    }

    /**
     * Remove novel from library
     */
    fun removeFromLibrary(novelId: String) {
        viewModelScope.launch {
            // If it's a local novel, also delete associated files
            if (novelId.startsWith("local_") && epubImporter != null) {
                epubImporter.deleteLocalNovel(novelId)
            }
            repository.removeFromLibrary(novelId)
        }
    }

    companion object {
        // Original factory without context (for backward compatibility)
        fun provideFactory(repository: NovelRepository): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return LibraryViewModel(repository, null) as T
                }
            }
        }

        // New factory with context for EPUB import
        fun provideFactory(
            repository: NovelRepository,
            context: Context
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return LibraryViewModel(
                        repository = repository,
                        epubImporter = EpubImporter(context)
                    ) as T
                }
            }
        }
    }
}