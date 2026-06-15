package com.example.localwebdavsync.util

import android.os.Build
import android.os.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object FileManagePermissionState {
    private val _granted = MutableStateFlow(hasManageFilesPermission())
    val granted: StateFlow<Boolean> = _granted.asStateFlow()

    fun refresh(): Boolean {
        val value = hasManageFilesPermission()
        _granted.value = value
        return value
    }
}

fun hasManageFilesPermission(): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
}
