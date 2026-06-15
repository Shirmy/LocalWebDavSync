package com.example.localwebdavsync.util

import java.util.Locale

fun formatFileSize(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0L)
    val oneMb = 1024L * 1024L
    val unitBytes = if (safeBytes > oneMb) oneMb else 1024L
    val unit = if (safeBytes > oneMb) "MB" else "KB"
    return String.format(Locale.getDefault(), "%.1f %s", safeBytes.toDouble() / unitBytes, unit)
}
