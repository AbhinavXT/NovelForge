package com.example.novelreader.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.novelreader.data.NovelRepository
import com.example.novelreader.data.model.NovelPreview
import com.example.novelreader.data.source.Source
import com.example.novelreader.data.source.SourceManager
import com.example.novelreader.util.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SearchUiState {
    object Initial : SearchUiState
    object Loading : SearchUiState
    data class Success(val novels: List<NovelPreview>) : SearchUiState
    data class Error(val message: String) : SearchUiState
}

class SearchViewModel(
    private val repository: NovelRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Initial)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private var searchJob: Job? = null
    private var popularJob: Job? = null  // Track popular novels loading job

    private val _popularNovels = MutableStateFlow<List<NovelPreview>>(emptyList())
    val popularNovels: StateFlow<List<NovelPreview>> = _popularNovels.asStateFlow()

    // Source selection
    private val _selectedSource = MutableStateFlow(SourceManager.getDefaultSource())
    val selectedSource: StateFlow<Source> = _selectedSource.asStateFlow()

    val availableSources: List<Source> = SourceManager.getAllSources()

    init {
        loadPopularNovels()
    }

    fun selectSource(source: Source) {
        if (_selectedSource.value.id == source.id) return

        // Cancel any ongoing requests
        searchJob?.cancel()
        popularJob?.cancel()

        _selectedSource.value = source
        _searchQuery.value = ""
        _uiState.value = SearchUiState.Initial
        _popularNovels.value = emptyList()
        loadPopularNovels()
    }

    private fun loadPopularNovels() {
        // Cancel previous popular novels request
        popularJob?.cancel()

        popularJob = viewModelScope.launch {
            // Capture the source at the start of the request
            val requestSource = _selectedSource.value

            try {
                val novels = repository.getPopularNovels(source = requestSource)

                // Only update if source hasn't changed during the request
                if (_selectedSource.value.id == requestSource.id) {
                    _popularNovels.value = novels
                }
            } catch (e: Exception) {
                Logger.e("Error", e)
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query

        searchJob?.cancel()

        if (query.isBlank()) {
            _uiState.value = SearchUiState.Initial
            return
        }

        searchJob = viewModelScope.launch {
            delay(500)
            performSearch(query)
        }
    }

    fun searchNow() {
        val query = _searchQuery.value
        if (query.isBlank()) return

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            performSearch(query)
        }
    }

    private suspend fun performSearch(query: String) {
        _uiState.value = SearchUiState.Loading

        // Capture the source at the start of the request
        val requestSource = _selectedSource.value

        try {
            val results = repository.searchNovels(query, source = requestSource)

            // Only update if source hasn't changed during the request
            if (_selectedSource.value.id == requestSource.id) {
                _uiState.value = if (results.isEmpty()) {
                    SearchUiState.Success(emptyList())
                } else {
                    SearchUiState.Success(results)
                }
            }
        } catch (e: Exception) {
            // Only update if source hasn't changed
            if (_selectedSource.value.id == requestSource.id) {
                _uiState.value = SearchUiState.Error(
                    e.message ?: "Search failed. Please check your internet connection."
                )
            }
        }
    }

    companion object {
        fun provideFactory(repository: NovelRepository): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SearchViewModel(repository) as T
                }
            }
        }
    }
}