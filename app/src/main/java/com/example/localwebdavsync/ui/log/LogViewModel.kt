package com.example.localwebdavsync.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localwebdavsync.data.entity.LogRecord
import com.example.localwebdavsync.repository.DeveloperLogRepository
import com.example.localwebdavsync.repository.SettingsRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LogViewModel(
    private val developerLogRepository: DeveloperLogRepository,
    settingsRepository: SettingsRepository
) : ViewModel() {
    val logs: StateFlow<List<LogRecord>> = developerLogRepository.observeRecentLogs().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )
    val detailedDebugLogsEnabled: StateFlow<Boolean> = settingsRepository.settings
        .map { it.detailedDebugLogsEnabled }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = settingsRepository.readSettings().detailedDebugLogsEnabled
        )

    fun clearLogs() {
        viewModelScope.launch {
            developerLogRepository.clear()
        }
    }

    class Factory(
        private val developerLogRepository: DeveloperLogRepository,
        private val settingsRepository: SettingsRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(LogViewModel::class.java)) {
                return LogViewModel(developerLogRepository, settingsRepository) as T
            }
            throw IllegalArgumentException("未知 ViewModel 类型：${modelClass.name}")
        }
    }
}
