package com.example.localwebdavsync.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localwebdavsync.repository.SettingsRepository
import com.example.localwebdavsync.repository.WebDavSettings
import com.example.localwebdavsync.webdav.WebDavClient
import com.example.localwebdavsync.webdav.WebDavConnectionTestResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val baseUrl: String = SettingsRepository.DEFAULT_JIANGUOYUN_URL,
    val username: String = "",
    val appPassword: String = "",
    val autoScanAndSyncOnAppStart: Boolean = false,
    val detailedDebugLogsEnabled: Boolean = false,
    val testing: Boolean = false,
    val testMessage: SettingsMessage? = null,
    val saveMessage: SettingsMessage? = null
)

data class SettingsMessage(
    val type: SettingsMessageType,
    val httpStatusCode: Int? = null
)

enum class SettingsMessageType {
    SETTINGS_SAVED,
    TEST_SUCCESS,
    ADDRESS_ERROR,
    AUTH_ERROR,
    NETWORK_ERROR,
    SERVER_ERROR
}

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val webDavClient: WebDavClient
) : ViewModel() {
    private val _uiState = MutableStateFlow(settingsRepository.readSettings().toUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun updateBaseUrl(value: String) {
        _uiState.value = _uiState.value.copy(baseUrl = value, testMessage = null, saveMessage = null)
    }

    fun updateUsername(value: String) {
        _uiState.value = _uiState.value.copy(username = value, testMessage = null, saveMessage = null)
    }

    fun updateAppPassword(value: String) {
        _uiState.value = _uiState.value.copy(appPassword = value, testMessage = null, saveMessage = null)
    }

    fun updateDetailedDebugLogsEnabled(value: Boolean) {
        settingsRepository.setDetailedDebugLogEnabled(value)
        _uiState.value = _uiState.value.copy(detailedDebugLogsEnabled = value, saveMessage = null)
    }

    fun updateAutoScanAndSyncOnAppStart(value: Boolean) {
        settingsRepository.setAutoScanAndSyncOnAppStart(value)
        _uiState.value = _uiState.value.copy(autoScanAndSyncOnAppStart = value, saveMessage = null)
    }

    fun save() {
        val state = _uiState.value
        settingsRepository.saveWebDavSettings(state.toWebDavSettings())
        _uiState.value = settingsRepository.readSettings()
            .toUiState(saveMessage = SettingsMessage(SettingsMessageType.SETTINGS_SAVED))
    }

    fun testConnection() {
        val state = _uiState.value
        _uiState.value = state.copy(testing = true, testMessage = null, saveMessage = null)
        viewModelScope.launch {
            val result = webDavClient.testConnection(
                baseUrl = state.baseUrl,
                username = state.username,
                appPassword = state.appPassword
            )
            if (result == WebDavConnectionTestResult.Success) {
                settingsRepository.saveWebDavSettings(state.toWebDavSettings())
                _uiState.value = settingsRepository.readSettings().toUiState(
                    testMessage = SettingsMessage(SettingsMessageType.TEST_SUCCESS)
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    testing = false,
                    testMessage = result.toMessage()
                )
            }
        }
    }

    private fun SettingsUiState.toWebDavSettings(): WebDavSettings {
        return WebDavSettings(
            baseUrl = baseUrl,
            username = username,
            appPassword = appPassword,
            autoScanAndSyncOnAppStart = autoScanAndSyncOnAppStart,
            detailedDebugLogsEnabled = detailedDebugLogsEnabled
        )
    }

    private fun WebDavSettings.toUiState(
        testMessage: SettingsMessage? = null,
        saveMessage: SettingsMessage? = null
    ): SettingsUiState {
        return SettingsUiState(
            baseUrl = baseUrl,
            username = username,
            appPassword = appPassword,
            autoScanAndSyncOnAppStart = autoScanAndSyncOnAppStart,
            detailedDebugLogsEnabled = detailedDebugLogsEnabled,
            testMessage = testMessage,
            saveMessage = saveMessage
        )
    }

    private fun WebDavConnectionTestResult.toMessage(): SettingsMessage {
        return when (this) {
            WebDavConnectionTestResult.Success -> SettingsMessage(SettingsMessageType.TEST_SUCCESS)
            WebDavConnectionTestResult.AddressError -> SettingsMessage(SettingsMessageType.ADDRESS_ERROR)
            WebDavConnectionTestResult.AuthError -> SettingsMessage(SettingsMessageType.AUTH_ERROR)
            WebDavConnectionTestResult.NetworkError -> SettingsMessage(SettingsMessageType.NETWORK_ERROR)
            is WebDavConnectionTestResult.ServerError -> SettingsMessage(
                type = SettingsMessageType.SERVER_ERROR,
                httpStatusCode = httpStatusCode
            )
        }
    }

    class Factory(
        private val settingsRepository: SettingsRepository,
        private val webDavClient: WebDavClient
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                return SettingsViewModel(settingsRepository, webDavClient) as T
            }
            throw IllegalArgumentException("未知 ViewModel 类型：${modelClass.name}")
        }
    }
}
