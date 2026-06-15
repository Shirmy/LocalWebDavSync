package com.example.localwebdavsync.util

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.example.localwebdavsync.data.entity.DeveloperLogEvents
import com.example.localwebdavsync.data.entity.DeleteModes
import com.example.localwebdavsync.data.entity.LogRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun LogRecord.toConsoleText(): String {
    val summaryText = summary ?: message
    val fileName = filePath?.substringAfterLast('/')?.ifBlank { filePath }.orEmpty()
    val displayName = fileName.ifBlank { "未知文件" }
    val taskPrefixPart = taskLogLabel()?.let { "$it " }.orEmpty()
    return when (eventType) {
        DeveloperLogEvents.GENERAL -> message
        DeveloperLogEvents.UPLOAD_SUCCESS -> buildList {
            add("✅ 已上传")
            add(listOf(displayName, uploadSizeText()).filter { it.isNotBlank() }.joinToString(" "))
            if (retryCount > 0) add("重传次数：$retryCount")
        }.joinToString("\n")
        DeveloperLogEvents.OVERWRITE_UPLOAD_SUCCESS -> buildList {
            add("✅ 已覆盖上传")
            add(displayName)
            val details = listOfNotNull(
                summary
                    ?.takeIf { it.isNotBlank() }
                    ?.toCompactOverwriteSummary(),
                "重传次数：$retryCount".takeIf { retryCount > 0 }
            ).joinToString("，")
            if (details.isNotBlank()) add(details)
        }.joinToString("\n")
        DeveloperLogEvents.REMOTE_DELETE_SUCCESS -> buildList {
            add("✅ 已远端删除")
            add(displayName)
        }.joinToString("\n")
        DeveloperLogEvents.UPLOAD_FAILED -> buildList {
            add("❌ 上传失败")
            add(displayName)
            if (!errorMessage.isNullOrBlank()) add(errorMessage)
        }.joinToString("\n")
        DeveloperLogEvents.REMOTE_DELETE_FAILED -> buildList {
            add("❌ 云端删除失败")
            add(displayName)
            if (!errorMessage.isNullOrBlank()) add(errorMessage)
        }.joinToString("\n")
        DeveloperLogEvents.TASK_CANCELLED -> {
            val emoji = when (message) {
                "任务已被删除" -> "🗑️ "
                else -> ""
            }
            buildList {
                add("$emoji${taskPrefixPart}$summaryText".trim())
                filePath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { add(it) }
            }.joinToString("\n")
        }
        DeveloperLogEvents.SYNC_INTERRUPTED -> {
            val emoji = when {
                summaryText.contains("Wi-Fi", ignoreCase = true) ||
                    summaryText.contains("Wifi", ignoreCase = true) ||
                    summaryText.contains("无线") -> "⚠️ "
                summaryText.contains("暂停") -> "⏸️ "
                summaryText.contains("删除") -> "🗑️ "
                else -> "⚠️ "
            }
            buildList {
                add("$emoji${taskPrefixPart}$summaryText".trim())
                filePath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { add(it) }
            }.joinToString("\n")
        }
        DeveloperLogEvents.DEBUG_FILE_DECISION -> "$displayName\n$summaryText"
        else -> message
    }
}

fun LogRecord.toConsoleAnnotatedText(): AnnotatedString {
    val text = toConsoleText()
    val iconStyles = text.highlightedIconStyles()
    return buildAnnotatedString {
        var start = 0
        while (start < text.length) {
            val nextIcon = iconStyles
                .mapNotNull { iconStyle ->
                    val iconStart = text.indexOf(iconStyle.icon, start)
                    if (iconStart >= 0) iconStyle.copy(start = iconStart) else null
                }
                .minByOrNull { it.start }
            if (nextIcon == null) {
                append(text.substring(start))
                break
            }
            append(text.substring(start, nextIcon.start))
            withStyle(nextIcon.spanStyle) {
                append(nextIcon.icon)
            }
            start = nextIcon.start + nextIcon.icon.length
        }
    }
}

fun LogRecord.isVisualLogSeparator(): Boolean {
    return false
}

private fun LogRecord.uploadSizeText(): String {
    return summary
        ?.substringAfter("文件大小：", missingDelimiterValue = "")
        ?.ifBlank { summary?.substringAfter("本地大小：", missingDelimiterValue = "") }
        .orEmpty()
}

private fun String.toCompactOverwriteSummary(): String {
    return replace(Regex("\\s*\\n\\s*修改后大小："), "，修改后大小：")
}

private fun LogRecord.taskLogLabel(): String? {
    val task = taskName?.takeIf { it.isNotBlank() } ?: return null
    return "[${taskModeIcon()} $task]"
}

private fun LogRecord.taskModeIcon(): String {
    val mode = detail ?: return COPY_MODE_ICON
    return if (DeleteModes.deletesRemoteOnLocalDelete(mode)) SYNC_MODE_ICON else COPY_MODE_ICON
}

private data class HighlightedIconStyle(
    val icon: String,
    val spanStyle: SpanStyle,
    val start: Int = -1
)

private fun String.highlightedIconStyles(): List<HighlightedIconStyle> {
    val iconStyles = mutableListOf(
        HighlightedIconStyle(RESUME_ICON, SpanStyle(color = SyncModeBlue)),
        HighlightedIconStyle(SYNC_MODE_ICON, SpanStyle(color = SyncModeBlue))
    )
    if (this == "⏸️ 扫描任务已暂停" || this == "⏸️ 同步任务已暂停") {
        iconStyles += HighlightedIconStyle(PAUSE_ICON, SpanStyle(color = PauseYellow))
    }
    if (contains(FAILURE_ICON)) {
        iconStyles += HighlightedIconStyle(FAILURE_ICON, SpanStyle(fontSize = FailureIconSize))
    }
    return iconStyles
}

fun formatDateTime(value: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(value))
}

private const val COPY_MODE_ICON = "📋"
private const val RESUME_ICON = "🔄"
private const val PAUSE_ICON = "⏸️"
private const val FAILURE_ICON = "❌"
private const val SYNC_MODE_ICON = "🔁"
private val SyncModeBlue = Color(0xFF1565C0)
private val PauseYellow = Color(0xFFF9A825)
private val FailureIconSize = 13.sp
