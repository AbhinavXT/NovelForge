package com.example.novelreader.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.novelreader.data.BackupInfo
import com.example.novelreader.data.BackupManager
import com.example.novelreader.data.BackupResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class BackupUiState {
    object Idle : BackupUiState()
    object Loading : BackupUiState()
    data class BackupSuccess(val message: String) : BackupUiState()
    data class RestoreSuccess(val message: String) : BackupUiState()
    data class Error(val message: String) : BackupUiState()
    data class ConfirmRestore(val info: BackupInfo, val uri: Uri) : BackupUiState()
}

class SettingsViewModel(
    private val backupManager: BackupManager
) : ViewModel() {

    private val _backupState = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val backupState: StateFlow<BackupUiState> = _backupState.asStateFlow()

    fun generateBackupFilename(): String {
        return backupManager.generateBackupFilename()
    }

    fun createBackup(uri: Uri) {
        viewModelScope.launch {
            _backupState.value = BackupUiState.Loading

            when (val result = backupManager.createBackup(uri)) {
                is BackupResult.Success -> {
                    _backupState.value = BackupUiState.BackupSuccess(result.message)
                }
                is BackupResult.Error -> {
                    _backupState.value = BackupUiState.Error(result.message)
                }
            }
        }
    }

    fun prepareRestore(uri: Uri) {
        viewModelScope.launch {
            _backupState.value = BackupUiState.Loading

            val info = backupManager.getBackupInfo(uri)
            if (info != null) {
                _backupState.value = BackupUiState.ConfirmRestore(info, uri)
            } else {
                _backupState.value = BackupUiState.Error(
                    "Could not read backup file. Please select a valid Novel Reader backup."
                )
            }
        }
    }

    fun confirmRestore(uri: Uri, includeDownloads: Boolean) {
        viewModelScope.launch {
            _backupState.value = BackupUiState.Loading

            when (val result = backupManager.restoreBackup(uri, includeDownloads)) {
                is BackupResult.Success -> {
                    _backupState.value = BackupUiState.RestoreSuccess(result.message)
                }
                is BackupResult.Error -> {
                    _backupState.value = BackupUiState.Error(result.message)
                }
            }
        }
    }

    fun cancelRestore() {
        _backupState.value = BackupUiState.Idle
    }

    fun dismissMessage() {
        _backupState.value = BackupUiState.Idle
    }

    class Factory(
        private val backupManager: BackupManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(backupManager) as T
        }
    }
}