package com.example.localwebdavsync.util

import com.example.localwebdavsync.data.entity.DeleteModes
import com.example.localwebdavsync.data.entity.SyncTask
import com.example.localwebdavsync.repository.DeveloperLogRepository
import com.example.localwebdavsync.repository.SyncStopReason
import com.example.localwebdavsync.repository.UploadSummary

data class SyncResultLogEntry(
    val task: SyncTask,
    val uploadSummary: UploadSummary? = null,
    val failedTask: Boolean = false,
    val notStarted: Boolean = false,
    val stopReason: SyncStopReason? = null
)

fun SyncTask.syncResultTaskLabel(): String {
    val modeIcon = if (DeleteModes.deletesRemoteOnLocalDelete(deleteMode)) SYNC_MODE_ICON else COPY_MODE_ICON
    return "$modeIcon $name"
}

suspend fun DeveloperLogRepository.logSyncResultSummary(
    entries: List<SyncResultLogEntry>,
    endTimeMillis: Long = System.currentTimeMillis()
) {
    if (entries.isEmpty()) return

    val pendingDeleteLines = entries.mapNotNull { entry ->
        entry.uploadSummary?.toPendingDeleteLine(entry.task)
    }
    if (pendingDeleteLines.isNotEmpty()) {
        log(
            message = buildList {
                add("待确认删除")
                addAll(pendingDeleteLines)
            }.joinToString("\n")
        )
        return
    }

    val resultLines = entries
        .sortedBy { it.syncResultSortOrder() }
        .mapNotNull { it.toSyncResultLine() }
    if (resultLines.isNotEmpty()) {
        log(
            message = buildList {
                add("本轮同步结果")
                addAll(resultLines)
            }.joinToString("\n")
        )
    } else {
        log(message = "本轮同步结果：无变更")
    }
    log(message = "结束 · ${formatDateTime(endTimeMillis)}")
}

fun UploadSummary.toSyncResultLine(task: SyncTask): String? {
    val completedParts = completedBreakdown()
    val failedParts = failedBreakdown()
    val parts = buildList {
        if (failedParts.isNotEmpty()) add("❌ ${failedParts.joinToString("，")}")
        if (failedParts.isEmpty() && stopReason == SyncStopReason.LOCAL_FOLDER_UNAVAILABLE) {
            add(SyncStopReason.LOCAL_FOLDER_UNAVAILABLE.toSyncResultText())
        }
        if (completedParts.isNotEmpty()) add("✅ ${completedParts.joinToString("，")}")
    }
    return parts
        .takeIf { it.isNotEmpty() }
        ?.joinToString("，")
        ?.let { "[${task.syncResultTaskLabel()}] $it" }
}

fun UploadSummary.toPendingDeleteLine(task: SyncTask): String? {
    val pendingDeleteCount = remoteDeletePausedCount.takeIf { it > 0 } ?: return null
    return "[${task.syncResultTaskLabel()}] ${pendingDeleteCount}个文件等待确认删除"
}

fun UploadSummary.failedFileCount(): Int {
    val categorizedUploadFailures = uploadFailedCount + modifiedFailedCount
    val uncategorizedUploadFailures = (failedCount - categorizedUploadFailures).coerceAtLeast(0)
    return categorizedUploadFailures + uncategorizedUploadFailures + deleteFailedCount
}

fun UploadSummary.completedFileCount(): Int {
    return uploadedNewCount + overwrittenCount + remoteDeletedCount
}

private fun SyncResultLogEntry.syncResultSortOrder(): Int {
    val summary = uploadSummary ?: return if (failedTask) 0 else 1
    val failedCount = summary.failedFileCount()
    val completedCount = summary.completedFileCount()
    return when {
        failedCount > 0 && completedCount == 0 -> 0
        failedCount > 0 -> 1
        completedCount > 0 -> 2
        else -> 3
    }
}

private fun SyncResultLogEntry.toSyncResultLine(): String? {
    val summary = uploadSummary
    if (summary == null) {
        return when {
            failedTask -> "[${task.syncResultTaskLabel()}] ❌ 任务失败"
            notStarted && stopReason == SyncStopReason.SERVICE_UNAVAILABLE -> "[${task.syncResultTaskLabel()}] 尚未同步"
            notStarted -> "[${task.syncResultTaskLabel()}] 尚未处理"
            stopReason != null -> "[${task.syncResultTaskLabel()}] ${stopReason.toSyncResultText()}"
            else -> null
        }
    }
    return summary.toSyncResultLine(task) ?: run {
        val reason = summary.stopReason ?: stopReason
        if (reason != null) {
            "[${task.syncResultTaskLabel()}] ${reason.toSyncResultText()}"
        } else {
            null
        }
    }
}

private fun SyncStopReason.toSyncResultText(): String {
    return when (this) {
        SyncStopReason.WIFI_INTERRUPTED -> "WiFi中断"
        SyncStopReason.SERVICE_UNAVAILABLE -> "服务器503，稍后重试"
        SyncStopReason.USER_PAUSED -> "任务已暂停"
        SyncStopReason.TASK_DELETED -> "任务已删除"
        SyncStopReason.CANCELLED -> "本轮已取消"
        SyncStopReason.APP_INTERRUPTED -> "同步中断"
        SyncStopReason.LOCAL_FOLDER_UNAVAILABLE -> "本地文件夹不可访问"
    }
}

private fun UploadSummary.completedBreakdown(): List<String> {
    return buildList {
        if (uploadedNewCount > 0) add("新增${uploadedNewCount}")
        if (overwrittenCount > 0) add("修改${overwrittenCount}")
        if (remoteDeletedCount > 0) add("云端删除${remoteDeletedCount}")
    }
}

private fun UploadSummary.failedBreakdown(): List<String> {
    val categorizedUploadFailures = uploadFailedCount + modifiedFailedCount
    val uncategorizedUploadFailures = (failedCount - categorizedUploadFailures).coerceAtLeast(0)
    return buildList {
        val totalUploadFailures = uploadFailedCount + uncategorizedUploadFailures
        if (totalUploadFailures > 0) add("上传失败${totalUploadFailures}")
        if (modifiedFailedCount > 0) add("修改失败${modifiedFailedCount}")
        if (deleteFailedCount > 0) add("云端删除失败${deleteFailedCount}")
    }
}

private const val COPY_MODE_ICON = "📋"
private const val SYNC_MODE_ICON = "🔁"
