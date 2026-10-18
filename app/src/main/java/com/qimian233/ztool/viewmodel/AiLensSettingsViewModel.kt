package com.qimian233.ztool.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qimian233.ztool.data.ailens.AiLensRestartResult
import com.qimian233.ztool.data.ailens.AiLensSettingsRepository
import com.qimian233.ztool.data.ailens.AiLensSettingsUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiLensSettingsViewModel(
    private val repository: AiLensSettingsRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(AiLensSettingsUiState())
    val uiState: StateFlow<AiLensSettingsUiState> = _uiState.asStateFlow()

    fun loadSettings() {
        _uiState.value = repository.loadState()
    }

    fun setShowCopyButton(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(showCopyButton = enabled)
        repository.saveShowCopyButton(enabled)
    }

    fun setCloseCardAfterCopy(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(closeCardAfterCopy = enabled)
        repository.saveCloseCardAfterCopy(enabled)
    }

    fun setExitLensAfterCopy(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(exitLensAfterCopy = enabled)
        repository.saveExitLensAfterCopy(enabled)
    }

    fun showRestartDialog() {
        _uiState.value = _uiState.value.copy(showRestartDialog = true)
    }

    fun dismissRestartDialog() {
        _uiState.value = _uiState.value.copy(showRestartDialog = false)
    }

    fun restartScope(onFailure: () -> Unit) {
        _uiState.value = _uiState.value.copy(showRestartDialog = false)
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.restartScope()
            if (result is AiLensRestartResult.Failure) {
                withContext(Dispatchers.Main) {
                    onFailure()
                }
            }
        }
    }
}
