package com.example.localwebdavsync.repository

import com.example.localwebdavsync.data.dao.LogDao
import com.example.localwebdavsync.data.entity.DeveloperLogEvents
import com.example.localwebdavsync.data.entity.DeveloperLogEventGroups
import com.example.localwebdavsync.data.entity.LogRecord
import com.example.localwebdavsync.data.entity.SyncTask
import kotlinx.coroutines.flow.Flow

class DeveloperLogRepository(
    private val logDao: LogDao,
    private val settingsRepository: SettingsRepository
) {
    fun observeRecentLogs(): Flow<List<LogRecord>> {
        return logDao.observeRecent(MAX_LOGS)
    }

    suspend fun log(
        task: SyncTask? = null,
        eventType: String = DeveloperLogEvents.GENERAL,
        filePath: String? = null,
        errorMessage: String? = null,
        retryCount: Int = 0,
        summary: String? = null,
        detail: String? = null,
        level: String = if (errorMessage == null) "INFO" else "ERROR",
        message: String
    ) {
        logDao.insert(
            LogRecord(
                taskId = task?.id,
                taskName = task?.name,
                eventType = eventType,
                filePath = sanitizePath(filePath),
                errorMessage = sanitizeMessage(errorMessage),
                retryCount = retryCount,
                summary = sanitizeMessage(summary),
                level = level,
                message = sanitizeMessage(message).orEmpty(),
                detail = sanitizeMessage(detail ?: task?.deleteMode)
            )
        )
        pruneIfNeeded()
    }

    suspend fun debug(
        task: SyncTask,
        filePath: String? = null,
        summary: String
    ) {
        if (!settingsRepository.isDetailedDebugLogEnabled()) return
        log(
            task = task,
            eventType = DeveloperLogEvents.DEBUG_FILE_DECISION,
            filePath = filePath,
            summary = summary,
            level = "DEBUG",
            message = "调试详情：$summary"
        )
    }

    suspend fun clear() {
        logDao.clear()
    }

    private suspend fun pruneIfNeeded() {
        if (logDao.countLogs() > MAX_LOGS) {
            val deletedSuccessCount = logDao.deleteOldestSuccessLog(DeveloperLogEvents.successEvents)
            if (deletedSuccessCount == 0) {
                logDao.deleteOldestPrimaryLog(DeveloperLogEventGroups.primary)
            }
        }
    }

    private fun sanitizePath(value: String?): String? {
        return value
            ?.substringBefore('?')
            ?.substringBefore('#')
    }

    private fun sanitizeMessage(value: String?): String? {
        return value
            ?.replace(Regex("(?i)(password|appPassword|token|authorization|username)=([^\\s&]+)"), "\$1=***")
            ?.replace(Regex("(?i)(Authorization:\\s*Basic\\s+)[A-Za-z0-9+/=]+"), "\$1***")
    }

    companion object {
        const val MAX_LOGS = 1000
    }
}
