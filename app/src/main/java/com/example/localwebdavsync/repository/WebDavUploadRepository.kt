package com.example.localwebdavsync.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.localwebdavsync.data.dao.SyncFileRecordDao
import com.example.localwebdavsync.data.dao.SyncTaskDao
import com.example.localwebdavsync.data.entity.DeleteModes
import com.example.localwebdavsync.data.entity.DeveloperLogEvents
import com.example.localwebdavsync.data.entity.FileScanStatuses
import com.example.localwebdavsync.data.entity.SyncFileRecord
import com.example.localwebdavsync.data.entity.SyncTask
import com.example.localwebdavsync.util.FileHasher
import com.example.localwebdavsync.util.formatFileSize
import com.example.localwebdavsync.webdav.WebDavClient
import com.example.localwebdavsync.webdav.WebDavUploadResult
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class UploadProgress(
    val completed: Int,
    val total: Int,
    val latestPath: String?,
    val failedCount: Int = 0
)

enum class SyncStopReason {
    WIFI_INTERRUPTED,
    SERVICE_UNAVAILABLE,
    USER_PAUSED,
    TASK_DELETED,
    CANCELLED,
    APP_INTERRUPTED,
    LOCAL_FOLDER_UNAVAILABLE
}

data class UploadSummary(
    val uploadedCount: Int = 0,
    val uploadedNewCount: Int = 0,
    val overwrittenCount: Int = 0,
    val failedCount: Int = 0,
    val uploadFailedCount: Int = 0,
    val modifiedFailedCount: Int = 0,
    val skippedCount: Int = 0,
    val remoteDeletedCount: Int = 0,
    val deleteFailedCount: Int = 0,
    val localDeleteKeptCount: Int = 0,
    val remoteDeletePausedCount: Int = 0,
    val retryAfterServiceUnavailable: Boolean = false,
    val stopReason: SyncStopReason? = null,
    val message: String? = null
)

class WebDavUploadRepository(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val syncFileRecordDao: SyncFileRecordDao,
    private val syncTaskDao: SyncTaskDao,
    private val networkStateProvider: NetworkStateProvider,
    private val webDavClient: WebDavClient,
    private val developerLogRepository: DeveloperLogRepository
) {
    suspend fun uploadPendingFiles(
        task: SyncTask,
        confirmLargeDelete: Boolean = false,
        onProgress: (UploadProgress) -> Unit
    ): UploadSummary = withContext(Dispatchers.IO) {
        val coroutineContext = currentCoroutineContext()
        val settings = settingsRepository.readSettings()
        val allRecords = syncFileRecordDao.getForTask(task.id)
        val pending = allRecords
            .filter { it.status in FileScanStatuses.PENDING_UPLOAD_STATUSES }
            .prioritizeUploadRetries()
        val localDeletedRecords = allRecords
            .filter { it.isRemoteDeleteCandidate() }
            .prioritizeRemoteDeleteRetries()
        val totalRecords = allRecords.size

        val fileRoot = task.localRootPath.takeIf { it.isNotBlank() }?.let { File(it) }
        val root = if (fileRoot == null) openAccessibleRoot(task.localRootUri) else null
        if (fileRoot == null && root == null) {
            developerLogRepository.log(
                task = task,
                eventType = DeveloperLogEvents.SYNC_INTERRUPTED,
                errorMessage = "本地文件夹不可访问或 SAF 权限失效",
                message = "同步中断",
                summary = "本地根目录不可访问，已暂停上传和远程删除"
            )
            return@withContext failAll(
                records = pending,
                message = LOCAL_FOLDER_UNAVAILABLE_MESSAGE
            )
        }

        val localFileResolver = try {
            when {
                fileRoot != null -> LocalFileResolver.FileRoot(fileRoot.canonicalFile)
                root != null -> LocalFileResolver.SafRoot(
                    index = root.indexFilesByRelativePath { coroutineContext.ensureActive() },
                    context = context
                )
                else -> null
            }
        } catch (_: SecurityException) {
            developerLogRepository.log(
                task = task,
                eventType = DeveloperLogEvents.SYNC_INTERRUPTED,
                errorMessage = "SAF 权限失效",
                message = "同步中断",
                summary = "无法读取本地根目录，已暂停上传和远程删除"
            )
            return@withContext failAll(
                records = pending,
                message = LOCAL_FOLDER_UNAVAILABLE_MESSAGE
            )
        }

        if (localFileResolver == null) {
            return@withContext failAll(
                records = pending,
                message = LOCAL_FOLDER_UNAVAILABLE_MESSAGE
            )
        }

        var uploadedCount = 0
        var uploadedNewCount = 0
        var overwrittenCount = 0
        var failedCount = 0
        var uploadFailedCount = 0
        var modifiedFailedCount = 0
        var remoteDeletedCount = 0
        var deleteFailedCount = 0
        var localDeleteKeptCount = 0
        var remoteDeletePausedCount = 0
        var retryAfterServiceUnavailable = false
        var stopReason: SyncStopReason? = null
        var summaryMessage: String? = null
        var uploadInterrupted = false
        val skippedCount = (totalRecords - pending.size).coerceAtLeast(0)
        val completedUploadCountBeforeThisRun = allRecords.completedUploadCountSinceCurrentScan(task)
        val uploadProgressTotal = completedUploadCountBeforeThisRun + pending.size
        fun reportUploadProgress(processedThisRun: Int, latestPath: String?) {
            if (uploadProgressTotal <= 0) return
            onProgress(
                UploadProgress(
                    completed = completedUploadCountBeforeThisRun + processedThisRun,
                    total = uploadProgressTotal,
                    latestPath = latestPath,
                    failedCount = failedCount
                )
            )
        }
        reportUploadProgress(processedThisRun = 0, latestPath = null)

        for ((index, record) in pending.withIndex()) {
            coroutineContext.ensureActive()
            try {
                val localFile = localFileResolver.resolve(record.localRelativePath)
                if (localFile == null) {
                    val message = LOCAL_FILE_MISSING_BEFORE_UPLOAD_MESSAGE
                    failedCount += 1
                    if (record.isModifiedUploadAttempt()) {
                        modifiedFailedCount += 1
                    } else {
                        uploadFailedCount += 1
                    }
                    syncFileRecordDao.update(
                        record.copy(
                            status = FileScanStatuses.LAST_FAILED,
                            errorMessage = message,
                            retryCount = record.retryCount + 1,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    logTransferFailure(
                        task = task,
                        eventType = DeveloperLogEvents.UPLOAD_FAILED,
                        filePath = record.localRelativePath,
                        retryCount = record.retryCount + 1,
                        message = message,
                        defaultMessage = "上传失败"
                    )
                    reportUploadProgress(index + 1, record.localRelativePath)
                    continue
                }

                val localSnapshot = localFile.snapshot()
                val isModifiedUploadAttempt = record.isModifiedUploadAttempt()
                val remoteSizeBeforeUpload = if (isModifiedUploadAttempt) {
                    webDavClient.queryRemoteFileSize(
                        baseUrl = settings.baseUrl,
                        remotePath = record.remotePath,
                        username = settings.username,
                        appPassword = settings.appPassword
                    )
                } else {
                    null
                }
                val uploadResult = webDavClient.upload(
                    baseUrl = settings.baseUrl,
                    remotePath = record.remotePath,
                    username = settings.username,
                    appPassword = settings.appPassword,
                    contentLength = localSnapshot.fileSize,
                    bodyFactory = {
                        webDavClient.inputStreamRequestBody(localSnapshot.fileSize) {
                            localFile.openInputStream()
                        }
                    }
                )
                coroutineContext.ensureActive()

                when (uploadResult) {
                    is WebDavUploadResult.Success -> {
                        uploadedCount += 1
                        if (isModifiedUploadAttempt) {
                            overwrittenCount += 1
                        } else {
                            uploadedNewCount += 1
                        }
                        val successEvent = if (isModifiedUploadAttempt) {
                            DeveloperLogEvents.OVERWRITE_UPLOAD_SUCCESS
                        } else {
                            DeveloperLogEvents.UPLOAD_SUCCESS
                        }
                        syncFileRecordDao.update(
                            record.copy(
                                status = if (isModifiedUploadAttempt) {
                                    FileScanStatuses.OVERWRITE_UPLOADED
                                } else {
                                    FileScanStatuses.SYNCED
                                },
                                fileSize = localSnapshot.fileSize,
                                lastModifiedAt = localSnapshot.lastModifiedAt,
                                contentHash = localSnapshot.contentHash,
                                remoteEtag = uploadResult.remoteEtag,
                                lastSyncedAt = System.currentTimeMillis(),
                                errorMessage = null,
                                retryCount = 0,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        developerLogRepository.log(
                            task = task,
                            eventType = successEvent,
                            filePath = record.localRelativePath,
                            summary = if (isModifiedUploadAttempt) {
                                "修改前大小：${remoteSizeBeforeUpload?.let { formatFileSize(it) } ?: "未知"}，" +
                                    "修改后大小：${formatFileSize(localSnapshot.fileSize)}"
                            } else {
                                "文件大小：${formatFileSize(localSnapshot.fileSize)}"
                            },
                            message = if (isModifiedUploadAttempt) "覆盖上传成功" else "上传成功"
                        )
                    }
                    is WebDavUploadResult.Failure -> {
                        if (isWifiInterruptionFailure(task, uploadResult)) {
                            summaryMessage = WIFI_INTERRUPTED_MESSAGE
                            stopReason = SyncStopReason.WIFI_INTERRUPTED
                            uploadInterrupted = true
                            reportUploadProgress(index, record.localRelativePath)
                            break
                        }
                        failedCount += 1
                        if (record.isModifiedUploadAttempt()) {
                            modifiedFailedCount += 1
                        } else {
                            uploadFailedCount += 1
                        }
                        val isServiceUnavailable = uploadResult.statusCode == HTTP_SERVICE_UNAVAILABLE
                        syncFileRecordDao.update(
                            record.copy(
                                status = FileScanStatuses.LAST_FAILED,
                                errorMessage = uploadResult.message,
                                retryCount = record.retryCount + 1,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        logTransferFailure(
                            task = task,
                            eventType = DeveloperLogEvents.UPLOAD_FAILED,
                            filePath = record.localRelativePath,
                            retryCount = record.retryCount + 1,
                            message = uploadResult.message,
                            defaultMessage = "上传失败"
                        )
                        if (isServiceUnavailable) {
                            retryAfterServiceUnavailable = true
                            stopReason = SyncStopReason.SERVICE_UNAVAILABLE
                            summaryMessage = "服务器返回 503，已暂停本轮上传，稍后重试。"
                        }
                        if (uploadResult.statusCode != 413) {
                            uploadInterrupted = true
                            reportUploadProgress(
                                processedThisRun = if (isServiceUnavailable) index else index + 1,
                                latestPath = record.localRelativePath
                            )
                            break
                        }
                    }
                }

                reportUploadProgress(index + 1, record.localRelativePath)
                coroutineContext.ensureActive()
            } catch (throwable: CancellationException) {
                withContext(NonCancellable) {
                    val cancellationReason = throwable.message
                    val pausedAllByUser = cancellationReason == PAUSE_REASON_USER_ALL
                    val deletedByUser = cancellationReason == PAUSE_REASON_DELETED
                    val wifiInterrupted = cancellationReason == PAUSE_REASON_WIFI ||
                        cancellationReason == PAUSE_REASON_WIFI_ALL ||
                        (task.wifiOnly && !networkStateProvider.isWifiConnected())
                    val logAlreadyMerged = pausedAllByUser ||
                        cancellationReason == PAUSE_REASON_WIFI_ALL ||
                        cancellationReason == PAUSE_REASON_CANCEL_ROUND
                    if (!logAlreadyMerged) {
                        developerLogRepository.log(
                            task = if (wifiInterrupted) null else task,
                            eventType = when {
                                deletedByUser -> DeveloperLogEvents.TASK_CANCELLED
                                else -> DeveloperLogEvents.SYNC_INTERRUPTED
                            },
                            filePath = record.localRelativePath.takeUnless { wifiInterrupted },
                            message = when {
                                deletedByUser -> "任务已被删除"
                                wifiInterrupted -> WIFI_INTERRUPTED_MESSAGE
                                else -> "应用已退出，同步中断"
                            }
                        )
                    }
                }
                throw throwable
            }
        }

        if (!DeleteModes.deletesRemoteOnLocalDelete(task.deleteMode)) {
            localDeleteKeptCount = localDeletedRecords.size
        } else if (!uploadInterrupted && !retryAfterServiceUnavailable && summaryMessage != WIFI_INTERRUPTED_MESSAGE) {
            coroutineContext.ensureActive()
            val syncedTotal = allRecords.count { it.lastSyncedAt != null && it.status != FileScanStatuses.REMOTE_DELETED }
            if (shouldPauseRemoteDelete(localDeletedRecords.size, syncedTotal, confirmLargeDelete)) {
                remoteDeletePausedCount = localDeletedRecords.size
                summaryMessage = "本次扫描发现大量本地文件消失，已暂停远程删除。确认无误后再执行删除。"
            } else {
                for (record in localDeletedRecords) {
                    coroutineContext.ensureActive()
                    when (
                        val deleteResult = webDavClient.delete(
                            baseUrl = settings.baseUrl,
                            remotePath = record.remotePath,
                            username = settings.username,
                            appPassword = settings.appPassword
                        )
                    ) {
                        is WebDavUploadResult.Success -> {
                            remoteDeletedCount += 1
                            syncFileRecordDao.update(
                                record.copy(
                                    status = FileScanStatuses.REMOTE_DELETED,
                                    deletedLocally = false,
                                    errorMessage = null,
                                    retryCount = 0,
                                    lastSeenAt = System.currentTimeMillis(),
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                            developerLogRepository.log(
                                task = task,
                                eventType = DeveloperLogEvents.REMOTE_DELETE_SUCCESS,
                                filePath = record.localRelativePath,
                                message = "删除远程成功"
                            )
                        }
                        is WebDavUploadResult.Failure -> {
                            if (isWifiInterruptionFailure(task, deleteResult)) {
                                summaryMessage = WIFI_INTERRUPTED_MESSAGE
                                stopReason = SyncStopReason.WIFI_INTERRUPTED
                                break
                            }
                            val isServiceUnavailable = deleteResult.statusCode == HTTP_SERVICE_UNAVAILABLE
                            deleteFailedCount += 1
                            syncFileRecordDao.update(
                                record.copy(
                                    status = FileScanStatuses.DELETE_FAILED,
                                    errorMessage = deleteResult.message,
                                    retryCount = record.retryCount + 1,
                                    lastSeenAt = System.currentTimeMillis(),
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                            logTransferFailure(
                                task = task,
                                eventType = DeveloperLogEvents.REMOTE_DELETE_FAILED,
                                filePath = record.localRelativePath,
                                retryCount = record.retryCount + 1,
                                message = deleteResult.message,
                                defaultMessage = "删除远程失败"
                            )
                            if (isServiceUnavailable) {
                                retryAfterServiceUnavailable = true
                                stopReason = SyncStopReason.SERVICE_UNAVAILABLE
                                summaryMessage = "服务器返回 503，已暂停本轮云端删除，稍后重试。"
                                break
                            }
                        }
                    }
                    coroutineContext.ensureActive()
                }
            }
        }

        val summary = UploadSummary(
            uploadedCount = uploadedCount,
            uploadedNewCount = uploadedNewCount,
            overwrittenCount = overwrittenCount,
            failedCount = failedCount,
            uploadFailedCount = uploadFailedCount,
            modifiedFailedCount = modifiedFailedCount,
            skippedCount = skippedCount,
            remoteDeletedCount = remoteDeletedCount,
            deleteFailedCount = deleteFailedCount,
            localDeleteKeptCount = localDeleteKeptCount,
            remoteDeletePausedCount = remoteDeletePausedCount,
            retryAfterServiceUnavailable = retryAfterServiceUnavailable,
            stopReason = stopReason,
            message = summaryMessage
        )
        if (summaryMessage != WIFI_INTERRUPTED_MESSAGE) {
            syncTaskDao.updateLastSyncTime(task.id, System.currentTimeMillis())
        }
        summary
    }

    private suspend fun failAll(
        records: List<SyncFileRecord>,
        message: String
    ): UploadSummary {
        records.forEach { record ->
            syncFileRecordDao.update(
                record.copy(
                    status = FileScanStatuses.LAST_FAILED,
                    errorMessage = message,
                    retryCount = record.retryCount + 1,
                    lastSeenAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        val modifiedFailures = records.count { it.isModifiedUploadAttempt() }
        val uploadFailures = records.size - modifiedFailures
        return UploadSummary(
            failedCount = records.size,
            uploadFailedCount = uploadFailures,
            modifiedFailedCount = modifiedFailures,
            stopReason = SyncStopReason.LOCAL_FOLDER_UNAVAILABLE,
            message = message
        )
    }

    private fun DocumentFile.indexFilesByRelativePath(checkCancelled: () -> Unit): Map<String, DocumentFile> {
        val index = linkedMapOf<String, DocumentFile>()
        fun walk(folder: DocumentFile, prefix: String) {
            checkCancelled()
            folder.listFiles().forEach { child ->
                checkCancelled()
                val name = child.name.orEmpty()
                if (name.isBlank()) return@forEach
                val relativePath = if (prefix.isBlank()) name else "$prefix/$name"
                when {
                    child.isDirectory -> walk(child, relativePath)
                    child.isFile -> index[relativePath] = child
                }
            }
        }
        walk(this, "")
        return index
    }

    private fun openAccessibleRoot(localRootUri: String): DocumentFile? {
        return try {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(localRootUri))
            if (root != null && root.exists() && root.isDirectory) root else null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private sealed interface LocalFileResolver {
        fun resolve(relativePath: String): LocalReadableFile?

        data class SafRoot(
            val index: Map<String, DocumentFile>,
            val context: Context
        ) : LocalFileResolver {
            override fun resolve(relativePath: String): LocalReadableFile? {
                val file = index[relativePath]?.takeIf { it.isFile } ?: return null
                return LocalReadableFile.Saf(
                    context = context,
                    uri = file.uri,
                    fileSize = file.length().takeIf { it >= 0L } ?: 0L,
                    lastModifiedAt = file.lastModified().takeIf { it > 0L } ?: 0L
                )
            }
        }

        data class FileRoot(
            val root: File
        ) : LocalFileResolver {
            override fun resolve(relativePath: String): LocalReadableFile? {
                val file = File(root, relativePath).canonicalFile
                if ((file != root && !file.toPath().startsWith(root.toPath())) || !file.isFile) return null
                return LocalReadableFile.Disk(file)
            }
        }
    }

    private sealed interface LocalReadableFile {
        fun openInputStream(): java.io.InputStream
        fun length(): Long
        fun lastModified(): Long

        data class Saf(
            val context: Context,
            val uri: Uri,
            val fileSize: Long,
            val lastModifiedAt: Long
        ) : LocalReadableFile {
            override fun openInputStream(): java.io.InputStream {
                return context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("无法打开本地文件流。")
            }

            override fun length(): Long = fileSize

            override fun lastModified(): Long = lastModifiedAt
        }

        data class Disk(val file: File) : LocalReadableFile {
            override fun openInputStream(): java.io.InputStream = file.inputStream()
            override fun length(): Long = file.length()
            override fun lastModified(): Long = file.lastModified().takeIf { it > 0L } ?: 0L
        }
    }

    private data class LocalFileSnapshot(
        val fileSize: Long,
        val lastModifiedAt: Long,
        val contentHash: String
    )

    private fun LocalReadableFile.snapshot(): LocalFileSnapshot {
        return LocalFileSnapshot(
            fileSize = length(),
            lastModifiedAt = lastModified(),
            contentHash = calculateSha256(this)
        )
    }

    private fun calculateSha256(file: LocalReadableFile): String {
        return FileHasher.sha256(file.openInputStream())
    }

    private fun SyncFileRecord.isRemoteDeleteCandidate(): Boolean {
        return deletedLocally &&
            lastSyncedAt != null &&
            remotePath.isNotBlank() &&
            status in FileScanStatuses.REMOTE_DELETE_CANDIDATE_STATUSES
    }

    private suspend fun isWifiInterruptionFailure(
        task: SyncTask,
        result: WebDavUploadResult.Failure
    ): Boolean {
        if (!task.wifiOnly || result.statusCode != null) return false
        if (networkStateProvider.wasWifiRecentlyDisconnected(WIFI_INTERRUPTION_RECENT_WINDOW_MS)) return true
        repeat(WIFI_INTERRUPTION_RECHECK_ATTEMPTS) { attempt ->
            if (!networkStateProvider.isWifiConnected()) return true
            if (attempt < WIFI_INTERRUPTION_RECHECK_ATTEMPTS - 1) {
                delay(WIFI_INTERRUPTION_RECHECK_DELAY_MS)
            }
        }
        return !networkStateProvider.isWifiConnected()
    }

    private fun SyncFileRecord.isModifiedUploadAttempt(): Boolean {
        return status == FileScanStatuses.MODIFIED || lastSyncedAt != null
    }

    private fun List<SyncFileRecord>.completedUploadCountSinceCurrentScan(task: SyncTask): Int {
        val scanCompletedAt = task.lastScanTime ?: return 0
        return count { record ->
            record.lastScanId == task.currentScanId &&
                record.lastSyncedAt != null &&
                record.lastSyncedAt >= scanCompletedAt &&
                record.status in COMPLETED_UPLOAD_STATUSES
        }
    }

    private fun List<SyncFileRecord>.prioritizeUploadRetries(): List<SyncFileRecord> {
        return withIndex()
            .sortedWith(compareBy<IndexedValue<SyncFileRecord>> { it.value.uploadRetryPriority() }.thenBy { it.index })
            .map { it.value }
    }

    private fun SyncFileRecord.uploadRetryPriority(): Int {
        return if (status in FileScanStatuses.UPLOAD_FAILURE_STATUSES) 0 else 1
    }

    private fun List<SyncFileRecord>.prioritizeRemoteDeleteRetries(): List<SyncFileRecord> {
        return withIndex()
            .sortedWith(compareBy<IndexedValue<SyncFileRecord>> { it.value.remoteDeleteRetryPriority() }.thenBy { it.index })
            .map { it.value }
    }

    private fun SyncFileRecord.remoteDeleteRetryPriority(): Int {
        return if (status == FileScanStatuses.DELETE_FAILED) 0 else 1
    }

    private fun shouldPauseRemoteDelete(
        deletedCount: Int,
        syncedTotal: Int,
        confirmLargeDelete: Boolean
    ): Boolean {
        if (confirmLargeDelete || deletedCount == 0 || syncedTotal <= 0) return false
        return deletedCount.toDouble() / syncedTotal.toDouble() > LARGE_DELETE_RATIO
    }

    private suspend fun logTransferFailure(
        task: SyncTask,
        eventType: String,
        filePath: String,
        retryCount: Int,
        message: String,
        defaultMessage: String
    ) {
        developerLogRepository.log(
            task = task,
            eventType = eventType,
            filePath = filePath,
            errorMessage = message,
            retryCount = retryCount,
            message = defaultMessage
        )
    }

    private companion object {
        const val PAUSE_REASON_USER_ALL = "USER_PAUSED_ALL"
        const val PAUSE_REASON_WIFI = "WIFI_INTERRUPTED"
        const val PAUSE_REASON_WIFI_ALL = "WIFI_INTERRUPTED_ALL"
        const val PAUSE_REASON_DELETED = "TASK_DELETED"
        const val PAUSE_REASON_CANCEL_ROUND = "ROUND_CANCELLED"
        const val LARGE_DELETE_RATIO = 0.30
        const val HTTP_SERVICE_UNAVAILABLE = 503
        const val WIFI_INTERRUPTION_RECHECK_ATTEMPTS = 4
        const val WIFI_INTERRUPTION_RECHECK_DELAY_MS = 500L
        const val WIFI_INTERRUPTION_RECENT_WINDOW_MS = 15_000L
        const val LOCAL_FILE_MISSING_BEFORE_UPLOAD_MESSAGE = "上传前本地文件已不存在"
        const val LOCAL_FOLDER_UNAVAILABLE_MESSAGE = "本地文件夹不可访问"
        const val WIFI_INTERRUPTED_MESSAGE = "WiFi连接中断，请检查网络"
        val COMPLETED_UPLOAD_STATUSES = setOf(
            FileScanStatuses.SYNCED,
            FileScanStatuses.OVERWRITE_UPLOADED
        )
    }
}


