package com.abhinavxt.novelreader.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.abhinavxt.novelreader.data.NovelRepository
import com.abhinavxt.novelreader.data.database.UpdateEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Updates feed (Phase 6): chronological list of new-chapter events
 * across the library, written by UpdateCheckerWorker.
 */
class UpdatesViewModel(
    private val repository: NovelRepository
) : ViewModel() {

    private val _updates = MutableStateFlow<List<UpdateEntity>>(emptyList())
    val updates: StateFlow<List<UpdateEntity>> = _updates.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getRecentUpdates().collect { list ->
                _updates.value = list
            }
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            repository.clearUpdatesFeed()
        }
    }

    companion object {
        fun provideFactory(repository: NovelRepository): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return UpdatesViewModel(repository) as T
                }
            }
        }
    }
}
