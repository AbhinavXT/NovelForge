package com.abhinavxt.novelreader.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.abhinavxt.novelreader.data.PronunciationManager
import com.abhinavxt.novelreader.data.database.PronunciationEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PronunciationViewModel(
    private val manager: PronunciationManager
) : ViewModel() {

    private val _entries = MutableStateFlow<List<PronunciationEntry>>(emptyList())
    val entries: StateFlow<List<PronunciationEntry>> = _entries.asStateFlow()

    init {
        viewModelScope.launch {
            manager.getAllEntries().collect { _entries.value = it }
        }
    }

    fun addEntry(word: String, replacement: String) {
        viewModelScope.launch { manager.addEntry(word, replacement) }
    }

    fun updateEntry(id: Long, word: String, replacement: String) {
        viewModelScope.launch { manager.updateEntry(id, word, replacement) }
    }

    fun deleteEntry(id: Long) {
        viewModelScope.launch { manager.deleteEntry(id) }
    }

    fun deleteAll() {
        viewModelScope.launch { manager.deleteAll() }
    }

    class Factory(private val manager: PronunciationManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PronunciationViewModel(manager) as T
        }
    }
}