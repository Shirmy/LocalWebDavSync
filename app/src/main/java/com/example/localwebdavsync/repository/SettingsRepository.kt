package com.example.localwebdavsync.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class WebDavSettings(
    val baseUrl: String = SettingsRepository.DEFAULT_JIANGUOYUN_URL,
    val username: String = "",
    val appPassword: String = "",
    val autoScanAndSyncOnAppStart: Boolean = false,
    val detailedDebugLogsEnabled: Boolean = false
)

class SettingsRepository(context: Context) {
    private val preferences = createEncryptedPreferences(context.applicationContext)

    private val settingsState = MutableStateFlow(readSettings())
    val settings: StateFlow<WebDavSettings> = settingsState

    fun saveWebDavSettings(settings: WebDavSettings) {
        preferences.edit {
            putString(KEY_BASE_URL, normalizeBaseUrl(settings.baseUrl))
            putString(KEY_USERNAME, settings.username.trim())
            putString(KEY_APP_PASSWORD, settings.appPassword)
            putBoolean(KEY_AUTO_SCAN_AND_SYNC_ON_APP_START, settings.autoScanAndSyncOnAppStart)
            putBoolean(KEY_DETAILED_DEBUG_LOGS, settings.detailedDebugLogsEnabled)
        }
        settingsState.value = readSettings()
    }

    fun setAutoScanAndSyncOnAppStart(enabled: Boolean) {
        preferences.edit {
            putBoolean(KEY_AUTO_SCAN_AND_SYNC_ON_APP_START, enabled)
        }
        settingsState.value = readSettings()
    }

    fun isAutoScanAndSyncOnAppStartEnabled(): Boolean {
        return preferences.getBoolean(KEY_AUTO_SCAN_AND_SYNC_ON_APP_START, false)
    }

    fun setDetailedDebugLogEnabled(enabled: Boolean) {
        preferences.edit {
            putBoolean(KEY_DETAILED_DEBUG_LOGS, enabled)
        }
        settingsState.value = readSettings()
    }

    fun isDetailedDebugLogEnabled(): Boolean {
        return preferences.getBoolean(KEY_DETAILED_DEBUG_LOGS, false)
    }

    fun shouldShowAppEntryDivider(): Boolean {
        val hasSeenDividerBefore = preferences.getBoolean(KEY_HAS_SEEN_APP_ENTRY_DIVIDER, false)
        if (!hasSeenDividerBefore) {
            preferences.edit {
                putBoolean(KEY_HAS_SEEN_APP_ENTRY_DIVIDER, true)
            }
        }
        return hasSeenDividerBefore
    }

    fun readSettings(): WebDavSettings {
        return WebDavSettings(
            baseUrl = preferences.getString(KEY_BASE_URL, DEFAULT_JIANGUOYUN_URL).orEmpty()
                .ifBlank { DEFAULT_JIANGUOYUN_URL },
            username = preferences.getString(KEY_USERNAME, "").orEmpty(),
            appPassword = preferences.getString(KEY_APP_PASSWORD, "").orEmpty(),
            autoScanAndSyncOnAppStart = preferences.getBoolean(KEY_AUTO_SCAN_AND_SYNC_ON_APP_START, false),
            detailedDebugLogsEnabled = preferences.getBoolean(KEY_DETAILED_DEBUG_LOGS, false)
        )
    }

    fun isWebDavConfigured(): Boolean {
        val settings = readSettings()
        return settings.username.isNotBlank() && settings.appPassword.isNotBlank()
    }

    private fun normalizeBaseUrl(value: String): String {
        val trimmed = value.trim().ifBlank { DEFAULT_JIANGUOYUN_URL }
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    private fun createEncryptedPreferences(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    companion object {
        const val DEFAULT_JIANGUOYUN_URL = "https://dav.jianguoyun.com/dav/"

        private const val FILE_NAME = "secure_webdav_settings"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_APP_PASSWORD = "app_password"
        private const val KEY_AUTO_SCAN_AND_SYNC_ON_APP_START = "auto_scan_and_sync_on_app_start"
        private const val KEY_DETAILED_DEBUG_LOGS = "detailed_debug_logs"
        private const val KEY_HAS_SEEN_APP_ENTRY_DIVIDER = "has_seen_app_entry_divider"
    }
}
