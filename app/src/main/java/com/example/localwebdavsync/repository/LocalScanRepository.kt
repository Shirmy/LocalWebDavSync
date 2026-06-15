package com.example.localwebdavsync.repository

import android.content.Context
import com.example.localwebdavsync.util.FileHasher
import java.io.File
import com.example.localwebdavsync.data.dao.SyncFileRecordDao
import com.example.localwebdavsync.data.dao.SyncTaskDao
import com.example.localwebdavsync.data.entity.DeleteModes
import com.example.localwebdavsync.data.entity.DeveloperLogEvents
import com.example.localwebdavsync.data.entity.FileScanStatuses
import com.example.localwebdavsync.data.entity.SyncFileRecord
import com.example.localwebdavsync.data.entity.SyncTask
import com.example.localwebdavsync.sync.LocalScannedFile
import com.example.localwebdavsync.sync.LocalFolderScanner
import com.example.localwebdavsync.sync.ScannedProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class LocalScanSummary(
    val newCount: Int = 0,
    val modifiedCount: Int = 0,
    val skippedCount: Int = 0,
    val syncedCount: Int = 0,
    val localDeletedCount: Int = 0,
    val localDeletedKeptCount: Int = 0,
    val skippedRemoteDeleteCheckCount: Int = 0,
    val lastFailedCount: Int = 0,
    val totalFilesSeen: Int = 0,
    val evaluatedFiles: Int = 0,
    val scanMode: String = ScanModes.FULL,
    val completedAt: Long = System.currentTimeMillis()
)

object ScanModes {
    const val FULL = "FULL"
    const val INCREMENTAL = "INCREMENTAL"
}

class LocalScanRepository(
    private val context: Context,
    private val syncFileRecordDao: SyncFileRecordDao,
    private val syncTaskDao: SyncTaskDao,
    private val localFolderScanner: LocalFolderScanner,
    private val networkStateProvider: NetworkStateProvider,
    private val developerLogRepository: DeveloperLogRepository
) {
    suspend fun scanTask(
        task: SyncTask,
        forceFullScan: Boolean = false,
        resumeScan: Boolean = false,
        onProgress: (ScannedProgress) -> Unit
    ): LocalScanSummary = withContext(Dispatchers.IO) {
        val existing = syncFileRecordDao.getForTask(task.id)
            .associateBy { it.localRelativePath }
        val fullScan = forceFullScan || task.lastScanTime == null || existing.isEmpty()
        val incrementalThreshold = ((task.lastScanTime ?: 0L) - INCREMENTAL_SCAN_SAFETY_WINDOW_MS)
            .coerceAtLeast(0L)
        val seenAt = System.currentTimeMillis()
        val scanId = task.currentScanId + 1
        val coroutineContext = currentCoroutineContext()
        val scannedFiles = try {
            localFolderScanner.scan(
                task = task,
                onProgress = onProgress,
                checkCancelled = { coroutineContext.ensureActive() }
            )
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
                        message = when {
                            deletedByUser -> "任务已被删除"
                            wifiInterrupted -> WIFI_INTERRUPTED_MESSAGE
                            else -> "应用已退出，同步中断"
                        }
                    )
                }
            }
            throw throwable
        } catch (throwable: Throwable) {
            developerLogRepository.log(
                task = task,
                eventType = DeveloperLogEvents.SYNC_INTERRUPTED,
                errorMessage = throwable.message,
                message = "同步中断",
                summary = "本地扫描失败"
            )
            throw throwable
        }
        coroutineContext.ensureActive()
        val scannedPaths = scannedFiles.mapTo(mutableSetOf()) { it.localRelativePath }
        var incrementallySkippedSyncedCount = 0
        val filesToEvaluate = if (fullScan) {
            scannedFiles.map { scanned ->
                FileEvaluation(scanned = scanned, verifyUnchangedContent = true)
            }
        } else {
            scannedFiles.mapNotNull { scanned ->
                val previous = existing[scanned.localRelativePath]
                val decision = decideIncrementalEvaluation(
                    previous = previous,
                    scanned = scanned,
                    incrementalThreshold = incrementalThreshold
                )
                if (decision.shouldEvaluate) {
                    FileEvaluation(
                        scanned = scanned,
                        previous = previous,
                        precheckDecision = decision,
                        incrementalThreshold = incrementalThreshold,
                        verifyUnchangedContent = decision.verifyUnchangedContent
                    )
                } else {
                    if (previous?.lastSyncedAt != null) {
                        incrementallySkippedSyncedCount += 1
                    }
                    developerLogRepository.debug(
                        task = task,
                        filePath = scanned.localRelativePath,
                        summary = buildScanDebugSummary(
                            scanId = scanId,
                            scanned = scanned,
                            previous = previous,
                            precheckDecision = decision,
                            scanDecision = null,
                            incrementalThreshold = incrementalThreshold
                        )
                    )
                    null
                }
            }
        }

        var newCount = 0
        var modifiedCount = 0
        var skippedCount = scannedFiles.size - filesToEvaluate.size
        var syncedCount = incrementallySkippedSyncedCount
        var lastFailedCount = 0

        val updatedRecords = filesToEvaluate.mapNotNull { evaluation ->
            coroutineContext.ensureActive()
            val scanned = evaluation.scanned
            val previous = existing[scanned.localRelativePath]
            val scanDecision = decideStatus(
                previous = previous,
                scanned = scanned,
                verifyUnchangedContent = evaluation.verifyUnchangedContent
            )
            developerLogRepository.debug(
                task = task,
                filePath = scanned.localRelativePath,
                summary = buildScanDebugSummary(
                    scanId = scanId,
                    scanned = scanned,
                    previous = previous,
                    precheckDecision = evaluation.precheckDecision,
                    scanDecision = scanDecision,
                    incrementalThreshold = evaluation.incrementalThreshold
                )
            )
            when (scanDecision.status) {
                FileScanStatuses.NEW -> newCount += 1
                FileScanStatuses.MODIFIED -> modifiedCount += 1
                FileScanStatuses.SKIPPED -> skippedCount += 1
                FileScanStatuses.SYNCED, FileScanStatuses.OVERWRITE_UPLOADED -> {
                    skippedCount += 1
                    syncedCount += 1
                }
                FileScanStatuses.LAST_FAILED -> lastFailedCount += 1
            }
            if (previous != null && scanDecision.canSkipDatabaseWrite(previous, scanned)) {
                return@mapNotNull null
            }
            SyncFileRecord(
                id = previous?.id ?: 0L,
                taskId = task.id,
                localRelativePath = scanned.localRelativePath,
                remotePath = scanned.remotePath,
                fileSize = scanned.fileSize,
                lastModifiedAt = scanned.lastModifiedAt,
                contentHash = scanDecision.contentHash,
                remoteEtag = previous?.remoteEtag,
                deletedLocally = false,
                lastSeenAt = seenAt,
                lastSyncedAt = previous?.lastSyncedAt,
                status = scanDecision.status,
                errorMessage = previous?.errorMessage.takeIf { scanDecision.status == FileScanStatuses.LAST_FAILED },
                retryCount = previous?.retryCount ?: 0,
                lastScanId = scanId,
                createdAt = previous?.createdAt ?: seenAt,
                updatedAt = seenAt
            )
        }

        val shouldDeleteRemoteOnLocalDelete = DeleteModes.deletesRemoteOnLocalDelete(task.deleteMode)
        val unsyncedMissingRecords = existing.values
            .filter {
                it.localRelativePath !in scannedPaths &&
                    it.lastSyncedAt == null &&
                    it.status in FileScanStatuses.PENDING_UPLOAD_STATUSES
            }
        val unsyncedMissingRecordIds = unsyncedMissingRecords.mapNotNull { record ->
            record.id.takeIf { it > 0L }
        }
        val syncedExistingCount = existing.values.count {
            it.lastSyncedAt != null && it.status != FileScanStatuses.REMOTE_DELETED
        }
        val unsyncedMissingPaths = unsyncedMissingRecords.mapTo(mutableSetOf()) { it.localRelativePath }
        val hasPendingUploadRecovery = existing.values.any {
            it.localRelativePath !in unsyncedMissingPaths &&
                it.status in FileScanStatuses.UPLOAD_FAILURE_STATUSES
        }
        val missingRecords = existing.values
            .filter {
                it.localRelativePath !in scannedPaths &&
                    it.lastSyncedAt != null &&
                    it.status != FileScanStatuses.REMOTE_DELETED &&
                    it.status != FileScanStatuses.LOCAL_DELETED_KEEP_REMOTE &&
                    (!it.deletedLocally ||
                        it.status == FileScanStatuses.LOCAL_DELETED ||
                        it.status == FileScanStatuses.DELETE_FAILED)
            }
        val skipRemoteDeleteCheck = shouldDeleteRemoteOnLocalDelete &&
            hasPendingUploadRecovery &&
            syncedExistingCount > 0 &&
            missingRecords.size.toDouble() / syncedExistingCount.toDouble() > LARGE_DELETE_RATIO
        val skippedRemoteDeleteCheckCount = if (skipRemoteDeleteCheck) missingRecords.size else 0
        val remoteDeleteCandidates = if (skipRemoteDeleteCheck) emptyList() else missingRecords
        if (skipRemoteDeleteCheck) {
            developerLogRepository.debug(
                task = task,
                summary = "扫描ID=$scanId。因存在待恢复的上传失败记录，跳过 ${missingRecords.size} 个已同步缺失文件的远程删除检查。"
            )
        }
        val remoteDeleteRestoredRecords = if (skipRemoteDeleteCheck) {
            missingRecords.mapNotNull { record ->
                coroutineContext.ensureActive()
                if (!record.deletedLocally && record.status !in FileScanStatuses.REMOTE_DELETE_CANDIDATE_STATUSES) {
                    return@mapNotNull null
                }
                record.copy(
                    status = if (record.status in FileScanStatuses.REMOTE_DELETE_CANDIDATE_STATUSES) {
                        FileScanStatuses.SYNCED
                    } else {
                        record.status
                    },
                    deletedLocally = false,
                    errorMessage = if (record.status in FileScanStatuses.REMOTE_DELETE_CANDIDATE_STATUSES) null else record.errorMessage,
                    updatedAt = seenAt
                )
            }
        } else {
            emptyList()
        }
        val localDeleteKeptRecords = if (shouldDeleteRemoteOnLocalDelete) emptyList() else remoteDeleteCandidates.map {
                coroutineContext.ensureActive()
                developerLogRepository.debug(
                    task = task,
                    filePath = it.localRelativePath,
                    summary = "扫描ID=$scanId。判定(状态=${FileScanStatuses.LOCAL_DELETED_KEEP_REMOTE}, 原因=本地扫描未找到文件；复制模式保留云端，无需处理)。历史记录(状态=${it.status}, 大小=${it.fileSize}, 修改时间=${it.lastModifiedAt})，来源=本地文件缺失"
                )
                it.copy(
                    status = FileScanStatuses.LOCAL_DELETED_KEEP_REMOTE,
                    deletedLocally = true,
                    lastSeenAt = seenAt,
                    lastScanId = scanId,
                    updatedAt = seenAt
                )
            }
        val deletedRecords = if (shouldDeleteRemoteOnLocalDelete) remoteDeleteCandidates.map {
                coroutineContext.ensureActive()
                developerLogRepository.debug(
                    task = task,
                    filePath = it.localRelativePath,
                    summary = "扫描ID=$scanId。判定(状态=${FileScanStatuses.LOCAL_DELETED}, 原因=本地扫描未找到文件；同步模式待删除云端)。历史记录(状态=${it.status}, 大小=${it.fileSize}, 修改时间=${it.lastModifiedAt})，来源=本地文件缺失"
                )
                it.copy(
                    status = FileScanStatuses.LOCAL_DELETED,
                    deletedLocally = true,
                    lastSeenAt = seenAt,
                    lastScanId = scanId,
                    updatedAt = seenAt
                )
            } else emptyList()

        val recordsToWrite = updatedRecords + deletedRecords + localDeleteKeptRecords + remoteDeleteRestoredRecords
        if (unsyncedMissingRecordIds.isNotEmpty()) {
            unsyncedMissingRecords.forEach { record ->
                developerLogRepository.debug(
                    task = task,
                    filePath = record.localRelativePath,
                    summary = "扫描ID=$scanId。删除未同步成功且本地已缺失的待上传记录。历史记录(状态=${record.status}, 大小=${record.fileSize}, 修改时间=${record.lastModifiedAt})"
                )
            }
            syncFileRecordDao.deleteByIds(unsyncedMissingRecordIds)
        }
        if (recordsToWrite.isNotEmpty()) {
            syncFileRecordDao.upsertAll(recordsToWrite)
        }

        val summary = LocalScanSummary(
            newCount = newCount,
            modifiedCount = modifiedCount,
            skippedCount = skippedCount,
            syncedCount = syncedCount,
            localDeletedCount = if (shouldDeleteRemoteOnLocalDelete) deletedRecords.size else 0,
            localDeletedKeptCount = localDeleteKeptRecords.size,
            skippedRemoteDeleteCheckCount = skippedRemoteDeleteCheckCount,
            lastFailedCount = lastFailedCount,
            totalFilesSeen = scannedFiles.size,
            evaluatedFiles = filesToEvaluate.size,
            scanMode = if (fullScan) ScanModes.FULL else ScanModes.INCREMENTAL,
            completedAt = seenAt
        )
        syncTaskDao.updateScanCompletion(task.id, scanId, seenAt)
        summary
    }

    private fun decideStatus(
        previous: SyncFileRecord?,
        scanned: LocalScannedFile,
        verifyUnchangedContent: Boolean
    ): ScanDecision {
        if (previous == null) {
            return ScanDecision(
                status = FileScanStatuses.NEW,
                contentHash = null,
                reason = "本地没有同步记录，标记为新增，等待上传"
            )
        }
        if (previous.status == FileScanStatuses.REMOTE_DELETED) {
            return ScanDecision(
                status = FileScanStatuses.NEW,
                contentHash = previous.contentHash,
                reason = "云端曾删除但本地文件仍存在，重新标记为新增"
            )
        }
        if (previous.status == FileScanStatuses.LOCAL_DELETED_KEEP_REMOTE) {
            return ScanDecision(
                status = FileScanStatuses.NEW,
                contentHash = null,
                reason = "复制模式下本地曾删除并保留云端，当前文件重新出现，标记为新增"
            )
        }
        if (previous.deletedLocally) {
            val unchanged = previous.fileSize == scanned.fileSize &&
                previous.lastModifiedAt == scanned.lastModifiedAt &&
                previous.lastSyncedAt != null
            return ScanDecision(
                status = if (unchanged) FileScanStatuses.SKIPPED else FileScanStatuses.MODIFIED,
                contentHash = previous.contentHash.takeIf { unchanged },
                reason = if (unchanged) {
                    "本地文件重新出现且内容元信息未变化，清除本地删除标记"
                } else {
                    "本地文件重新出现且内容元信息变化，标记为修改"
                }
            )
        }
        if (
            previous.lastSyncedAt == null &&
            previous.status in setOf(FileScanStatuses.NEW, FileScanStatuses.MODIFIED)
        ) {
            return ScanDecision(
                status = previous.status,
                contentHash = previous.contentHash,
                reason = "文件尚未成功同步，保留待上传状态"
            )
        }

        if (previous.status == FileScanStatuses.LAST_FAILED || previous.status == FileScanStatuses.UPLOAD_FAILED) {
            return ScanDecision(
                status = FileScanStatuses.LAST_FAILED,
                contentHash = if (
                    previous.fileSize == scanned.fileSize &&
                    previous.lastModifiedAt == scanned.lastModifiedAt
                ) {
                    previous.contentHash
                } else {
                    null
                },
                reason = "上次同步失败，加入重传队列。"
            )
        }

        if (previous.fileSize != scanned.fileSize) {
            return ScanDecision(
                status = FileScanStatuses.MODIFIED,
                contentHash = null,
                reason = "文件大小变化，判定为修改"
            )
        }

        if (previous.lastModifiedAt == scanned.lastModifiedAt) {
            if (verifyUnchangedContent) {
                val currentHash = calculateSha256(scanned)
                return if (previous.contentHash == null || previous.contentHash == currentHash) {
                    ScanDecision(
                        status = unchangedStatus(previous),
                        contentHash = currentHash,
                        reason = if (previous.contentHash == null) {
                            "文件大小和修改时间未变化，已记录哈希，保持已同步。当前哈希=${currentHash.shortHash()}"
                        } else {
                            "文件大小和修改时间未变化，哈希一致，保持已同步。历史哈希=${previous.contentHash.shortHash()}，当前哈希=${currentHash.shortHash()}，来源=${scanned.hashSourceLabel()}"
                        }
                    )
                } else {
                    ScanDecision(
                        status = FileScanStatuses.MODIFIED,
                        contentHash = currentHash,
                        reason = "文件大小和修改时间未变化，但哈希不同，判定为修改。历史哈希=${previous.contentHash.shortHash()}，当前哈希=${currentHash.shortHash()}，来源=${scanned.hashSourceLabel()}"
                    )
                }
            }
            return ScanDecision(
                status = FileScanStatuses.SKIPPED,
                contentHash = previous.contentHash,
                reason = "文件大小和修改时间未变化，跳过"
            )
        }

        val currentHash = calculateSha256(scanned)
        return if (previous.contentHash != null && previous.contentHash == currentHash) {
            ScanDecision(
                status = unchangedStatus(previous),
                contentHash = currentHash,
                reason = "文件大小相同且哈希未变化，更新修改时间并保持已同步。历史哈希=${previous.contentHash.shortHash()}，当前哈希=${currentHash.shortHash()}，来源=${scanned.hashSourceLabel()}"
            )
        } else {
            ScanDecision(
                status = FileScanStatuses.MODIFIED,
                contentHash = currentHash,
                reason = "文件大小相同但修改时间变化，哈希不同，判定为修改。历史哈希=${previous.contentHash.shortHash()}，当前哈希=${currentHash.shortHash()}，来源=${scanned.hashSourceLabel()}"
            )
        }
    }

    private fun decideIncrementalEvaluation(
        previous: SyncFileRecord?,
        scanned: LocalScannedFile,
        incrementalThreshold: Long
    ): IncrementalEvaluationDecision {
        if (previous == null) {
            return IncrementalEvaluationDecision(true, "没有历史记录，按新文件检查。")
        }
        if (previous.deletedLocally) {
            return IncrementalEvaluationDecision(true, "历史记录标记为本地已删除，重新检查。")
        }
        if (previous.fileSize != scanned.fileSize) {
            return IncrementalEvaluationDecision(true, "文件大小已变化，重新检查。")
        }
        if (scanned.lastModifiedAt >= incrementalThreshold) {
            return IncrementalEvaluationDecision(true, "修改时间位于增量扫描安全窗口内，重新检查。")
        }
        if (previous.status in FileScanStatuses.INCREMENTAL_RECHECK_STATUSES) {
            return IncrementalEvaluationDecision(true, "历史状态需要复查，重新检查。")
        }
        return IncrementalEvaluationDecision(false, "文件大小和修改时间未变化，且早于增量扫描安全窗口，跳过内容校验。")
    }

    private fun calculateSha256(scanned: LocalScannedFile): String {
        val inputStream = scanned.filePath
            ?.let { File(it).inputStream() }
            ?: context.contentResolver.openInputStream(scanned.uri)
        return FileHasher.sha256(requireNotNull(inputStream) { "无法打开本地文件，不能计算哈希。" })
    }

    private data class ScanDecision(
        val status: String,
        val contentHash: String?,
        val reason: String
    )

    private data class FileEvaluation(
        val scanned: LocalScannedFile,
        val previous: SyncFileRecord? = null,
        val precheckDecision: IncrementalEvaluationDecision? = null,
        val incrementalThreshold: Long? = null,
        val verifyUnchangedContent: Boolean
    )

    private data class IncrementalEvaluationDecision(
        val shouldEvaluate: Boolean,
        val reason: String,
        val verifyUnchangedContent: Boolean = false
    )

    private fun unchangedStatus(previous: SyncFileRecord): String {
        return if (previous.lastSyncedAt != null) {
            FileScanStatuses.SYNCED
        } else {
            FileScanStatuses.SKIPPED
        }
    }

    private fun buildScanDebugSummary(
        scanId: Long,
        scanned: LocalScannedFile,
        previous: SyncFileRecord?,
        precheckDecision: IncrementalEvaluationDecision?,
        scanDecision: ScanDecision?,
        incrementalThreshold: Long?
    ): String {
        val previousText = if (previous == null) {
            "历史记录=无"
        } else {
            "历史记录(大小=${previous.fileSize}, 修改时间=${previous.lastModifiedAt}, 状态=${previous.status}, 本地已删除=${previous.deletedLocally})"
        }
        val precheckText = precheckDecision?.let {
            "预检查=${if (it.shouldEvaluate) "检查" else "跳过"}(${it.reason})"
        } ?: "预检查=全量扫描"
        val decisionText = scanDecision?.let {
            "判定(状态=${it.status}, 原因=${it.reason}, 当前哈希=${it.contentHash.shortHash()})"
        } ?: "判定=未检查"
        return "扫描ID=$scanId。$precheckText。$decisionText。 " +
            "$previousText，历史哈希=${previous?.contentHash.shortHash()}，" +
            "当前文件(大小=${scanned.fileSize}, 修改时间=${scanned.lastModifiedAt}, 来源=${scanned.hashSourceLabel()})，" +
            "增量阈值=$incrementalThreshold"
    }

    private fun ScanDecision.canSkipDatabaseWrite(
        previous: SyncFileRecord,
        scanned: LocalScannedFile
    ): Boolean {
        return status in FileScanStatuses.UNCHANGED_STATUSES &&
            previous.status == status &&
            previous.deletedLocally == false &&
            previous.fileSize == scanned.fileSize &&
            previous.lastModifiedAt == scanned.lastModifiedAt &&
            previous.remotePath == scanned.remotePath &&
            previous.contentHash == contentHash
    }

    private fun String?.shortHash(): String {
        return this?.take(HASH_DEBUG_PREFIX_LENGTH) ?: "无"
    }

    private fun LocalScannedFile.hashSourceLabel(): String {
        return if (filePath == null) "SAF:$uri" else "文件:$filePath"
    }

    private companion object {
        const val PAUSE_REASON_USER_ALL = "USER_PAUSED_ALL"
        const val PAUSE_REASON_WIFI = "WIFI_INTERRUPTED"
        const val PAUSE_REASON_WIFI_ALL = "WIFI_INTERRUPTED_ALL"
        const val PAUSE_REASON_DELETED = "TASK_DELETED"
        const val PAUSE_REASON_CANCEL_ROUND = "ROUND_CANCELLED"
        const val WIFI_INTERRUPTED_MESSAGE = "WiFi连接中断，请检查网络"
        const val INCREMENTAL_SCAN_SAFETY_WINDOW_MS = 5_000L
        const val HASH_DEBUG_PREFIX_LENGTH = 12
        const val LARGE_DELETE_RATIO = 0.30
    }
}

