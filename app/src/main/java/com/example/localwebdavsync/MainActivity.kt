package com.example.localwebdavsync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.example.localwebdavsync.navigation.AppNavigation
import com.example.localwebdavsync.ui.theme.LocalWebDavSyncTheme
import com.example.localwebdavsync.util.FileManagePermissionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBars()
        FileManagePermissionState.refresh()
        openManageFilesPermissionSettingsOnceIfNeeded()
        setContent {
            LocalWebDavSyncTheme {
                AppNavigation()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        FileManagePermissionState.refresh()
        lifecycleScope.launch {
            delay(PERMISSION_STATE_REFRESH_DELAY_MS)
            FileManagePermissionState.refresh()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun openManageFilesPermissionSettingsOnceIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || FileManagePermissionState.refresh()) return
        val preferences = getSharedPreferences(PERMISSION_PROMPT_PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getBoolean(KEY_OPENED_MANAGE_FILES_PERMISSION_SETTINGS, false)) return
        preferences.edit()
            .putBoolean(KEY_OPENED_MANAGE_FILES_PERMISSION_SETTINGS, true)
            .apply()
        openManageFilesPermissionSettings()
    }

    private fun openManageFilesPermissionSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private companion object {
        const val PERMISSION_PROMPT_PREFERENCES = "permission_prompt_preferences"
        const val KEY_OPENED_MANAGE_FILES_PERMISSION_SETTINGS = "opened_manage_files_permission_settings"
        const val PERMISSION_STATE_REFRESH_DELAY_MS = 300L
    }
}
