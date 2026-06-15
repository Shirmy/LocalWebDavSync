package com.example.localwebdavsync.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localwebdavsync.data.entity.DeleteModes
import com.example.localwebdavsync.data.entity.DeveloperLogEvents
import com.example.localwebdavsync.data.entity.FileScanStatuses
import com.example.localwebdavsync.data.entity.LogRecord
import com.example.localwebdavsync.data.entity.SyncFileRecord
import com.example.localwebdavsync.data.entity.SyncTask
import com.example.localwebdavsync.repository.DeveloperLogRepository
import com.example.localwebdavsync.repository.LocalScanRepository
import com.example.localwebdavsync.repository.LocalScanSummary
import com.example.localwebdavsync.repository.NetworkStateProvider
import com.example.localwebdavsync.repository.ScanModes
import com.example.localwebdavsync.repository.SettingsRepository
import com.example.localwebdavsync.repository.SyncStopReason
import com.example.localwebdavsync.repository.SyncTaskRepository
import com.example.localwebdavsync.repository.UploadProgress
import com.example.localwebdavsync.repository.UploadSummary
import com.example.localwebdavsync.repository.WebDavUploadRepository
import com.example.localwebdavsync.data.entity.SyncRunStates
import com.example.localwebdavsync.sync.ScannedProgress
import com.example.localwebdavsync.util.formatDateTime
import com.example.localwebdavsync.util.logSyncResultSummary
import com.example.localwebdavsync.util.SyncResultLogEntry
import com.example.localwebdavsync.util.syncResultTaskLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AutoSyncTaskStatus(
    val taskId: Long,
    val taskName: String,
    val stage: AutoSyncStage,
    val filesScanned: Int = 0,
    val filesTotal: Int? = null,
    val latestPath: String? = null,
    val message: String? = null,
    val needsLargeDeleteConfirmation: Boolean = false,
    val pausedFromStage: AutoSyncStage? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

enum class AutoSyncStage {
    QUEUED,
    SCANNING,
    SYNCING,
    NOT_STARTED,
    NO_CHANGES,
    COMPLETED,
    WAITING_CONFIRMATION,
    WAITING_RETRY,
    CANCELLED,
    PAUSED,
    FAILED
}

data class AutoSyncUiState(
    val running: Boolean = false,
    val currentTaskId: Long? = null,
    val taskStatuses: List<AutoSyncTaskStatus> = emptyList()
)

internal const val TASK_PAUSED_MESSAGE = "任务已暂停"
internal const val WIFI_NOT_CONNECTED_MESSAGE = "未连接 WiFi，请开启 WiFi 连接"
internal const val WIFI_INTERRUPTED_MESSAGE = "WiFi连接中断，请检查网络"
internal const val SERVICE_UNAVAILABLE_MESSAGE = "服务器503，稍后重试"
internal const val NOT_SYNCED_MESSAGE = "尚未同步"
internal const val ROUND_CANCELLED_MESSAGE = "本轮已取消"
internal const val LARGE_DELETE_CONFIRMATION_MESSAGE = "待确认删除"

internal fun AutoSyncUiState.hasManuallyPausedTasks(): Boolean {
    return hasManualResumeTasks()
}

internal fun AutoSyncUiState.hasManualResumeTasks(): Boolean {
    return taskStatuses.any { status ->
        (status.stage == AutoSyncStage.PAUSED || status.stage == AutoSyncStage.NOT_STARTED) &&
            status.message == TASK_PAUSED_MESSAGE
    }
}

internal fun AutoSyncUiState.hasResumeableTasks(): Boolean {
    return taskStatuses.any { status ->
        status.needsLargeDeleteConfirmation ||
            status.stage == AutoSyncStage.WAITING_CONFIRMATION ||
            (
                (status.stage == AutoSyncStage.PAUSED || status.stage == AutoSyncStage.NOT_STARTED) &&
                    status.message in RESUME_BLOCKING_MESSAGES
            )
    }
}

private val RESUME_BLOCKING_MESSAGES = setOf(
    TASK_PAUSED_MESSAGE,
    WIFI_NOT_CONNECTED_MESSAGE,
    WIFI_INTERRUPTED_MESSAGE,
    LARGE_DELETE_CONFIRMATION_MESSAGE
)

private data class AutoTaskRunResult(
    val task: SyncTask,
    val scanSummary: LocalScanSummary? = null,
    val uploadSummary: UploadSummary? = null,
    val noChanges: Boolean = false,
    val notStarted: Boolean = false,
    val stopReason: SyncStopReason? = null,
    val failedTask: Boolean = false
)

class HomeViewModel(
    private val repository: SyncTaskRepository,
    private val localScanRepository: LocalScanRepository,
    private val webDavUploadRepository: WebDavUploadRepository,
    private val developerLogRepository: DeveloperLogRepository,
    private val settingsRepository: SettingsRepository,
    private val networkStateProvider: NetworkStateProvider
) : ViewModel() {
    val tasks: StateFlow<List<SyncTask>> = repository.tasks.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    private val _autoSyncState = MutableStateFlow(AutoSyncUiState())
    val autoSyncState: StateFlow<AutoSyncUiState> = _autoSyncState.asStateFlow()
    private val runningJobs = mutableMapOf<Long, Job>()
    private val pauseJobs = mutableMapOf<Long, Job>()
    private val retryJobs = mutableMapOf<Long, Job>()
    private var wifiResumeJob: Job? = null
    private val confirmedLargeDeleteTaskIds = mutableSetOf<Long>()
    private val uploadProgressByTaskId = mutableMapOf<Long, UploadProgress>()
    private var lastNetworkWarningMessage: String? = null
    private var lastNetworkWarningLoggedAt: Long = 0L
    private var shouldLogWifiConnectedSuccess = false

    val logs: StateFlow<List<LogRecord>> = developerLogRepository.observeRecentLogs().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    fun observeFiles(taskId: Long): Flow<List<SyncFileRecord>> {
        return repository.observeFiles(taskId)
    }

    init {
        viewModelScope.launch {
            startAutoScanAndSyncOnce()
        }
        networkStateProvider.registerWifiCallback(
            onAvailable = { handleWifiAvailable() },
            onLost = { cancelWifiOnlyRunningJobs() }
        )
    }

    override fun onCleared() {
        super.onCleared()
        networkStateProvider.unregisterWifiCallback()
    }

    private fun startAutoScanAndSyncOnce() {
        viewModelScope.launch {
            developerLogRepository.clear()
            recoverInterruptedRuns()
            if (!settingsRepository.isAutoScanAndSyncOnAppStartEnabled()) return@launch
            if (!settingsRepository.isWebDavConfigured()) return@launch
            val startupTasks = repository.getEnabledTasks()
            if (startupTasks.isEmpty()) return@launch
            if (!markScanAllStarting()) return@launch
            currentCoroutineContext()[Job]?.let { runningJobs[SCAN_ALL_JOB_ID] = it }
            try {
                runBatchTasks(
                    tasks = startupTasks,
                    queuedMessage = "等待自动扫描",
                    logWifiNotConnected = true
                )
            } finally {
                runningJobs.remove(SCAN_ALL_JOB_ID)
                pauseJobs.remove(SCAN_ALL_JOB_ID)
                _autoSyncState.value = _autoSyncState.value.copy(
                    running = _autoSyncState.value.taskStatuses.any { it.stage in RUNNING_STAGES }
                )
            }
        }
    }

    private fun retryScanAndSyncAll() {
        resumeTasksByStatus(
            preserveOtherStatuses = true,
            shouldResumeStatus = { it.isServiceUnavailableResumeCandidate() },
            retryScan = true
        )
    }

    fun scanAndSyncAllNow() {
        if (_autoSyncState.value.hasResumeableTasks()) return
        if (!settingsRepository.isWebDavConfigured()) return
        scanAndSyncAllNow(forceFullScan = false)
    }

    fun fullScanAndSyncAllNow() {
        if (_autoSyncState.value.hasResumeableTasks()) return
        if (!settingsRepository.isWebDavConfigured()) return
        scanAndSyncAllNow(forceFullScan = true)
    }

    private fun scanAndSyncAllNow(
        forceFullScan: Boolean,
        retryScan: Boolean = false
    ): Boolean {
        if (!retryScan && _autoSyncState.value.hasResumeableTasks()) return false
        if (!settingsRepository.isWebDavConfigured()) return false
        if (!markScanAllStarting()) return false
        val job = viewModelScope.launch {
            try {
                val enabledTasks = repository.getEnabledTasks()
                if (enabledTasks.isEmpty()) return@launch
                enabledTasks.forEach { task -> retryJobs.remove(task.id)?.cancel() }
                runBatchTasks(
                    tasks = enabledTasks,
                    forceFullScan = forceFullScan,
                    retryScan = retryScan,
                    logWifiNotConnected = false
                )
            } finally {
                runningJobs.remove(SCAN_ALL_JOB_ID)
                pauseJobs.remove(SCAN_ALL_JOB_ID)
                _autoSyncState.value = _autoSyncState.value.copy(running = false)
            }
        }
        runningJobs[SCAN_ALL_JOB_ID] = job
        return true
    }

    fun setTaskEnabled(task: SyncTask, enabled: Boolean) {
        viewModelScope.launch {
            repository.saveTask(task.copy(enabled = enabled))
        }
    }

    fun moveTask(taskId: Long, direction: Int) {
        if (direction == 0) return
        viewModelScope.launch {
            val currentTasks = tasks.value
            val fromIndex = currentTasks.indexOfFirst { it.id == taskId }
            if (fromIndex == -1) return@launch
            val toIndex = (fromIndex + direction).coerceIn(0, currentTasks.lastIndex)
            if (fromIndex == toIndex) return@launch
            val reordered = currentTasks.toMutableList()
            val moved = reordered.removeAt(fromIndex)
            reordered.add(toIndex, moved)
            repository.updateTaskOrder(reordered)
        }
    }

    fun pauseAllTasks() {
        viewModelScope.launch {
            val stateBeforePause = _autoSyncState.value
            val activeTaskIds = buildSet {
                addAll(runningJobs.keys.filter { it != SCAN_ALL_JOB_ID })
                addAll(stateBeforePause.taskStatuses.filter { it.stage in RUNNING_STAGES }.map { it.taskId })
            }
            activeTaskIds.forEach { taskId ->
                val task = repository.getTask(taskId) ?: return@forEach
                val statusBeforePause = stateBeforePause.taskStatuses.firstOrNull { it.taskId == taskId }
                ensureTaskStatus(task)
                updateAutoTaskStatus(
                    task = task,
                    stage = AutoSyncStage.PAUSED,
                    message = TASK_PAUSED_MESSAGE,
                    pausedFromStageOverride = statusBeforePause?.manualPauseResumeStage()
                )
            }
            if (activeTaskIds.isNotEmpty()) {
                logPauseAllSummary(stateBeforePause)
            }
            cancelAll(PAUSE_REASON_USER_ALL)
            _autoSyncState.value = _autoSyncState.value.copy(
                running = _autoSyncState.value.taskStatuses.any { it.stage in RUNNING_STAGES }
            )
        }
    }

    fun cancelCurrentRoundTasks() {
        viewModelScope.launch {
            val stateBeforeCancel = _autoSyncState.value
            if (!stateBeforeCancel.running) return@launch
            val now = System.currentTimeMillis()
            val cancellingTaskIds = stateBeforeCancel.taskStatuses
                .filter { it.stage in RUNNING_STAGES }
                .mapTo(mutableSetOf()) { it.taskId }
            cancellingTaskIds.forEach { uploadProgressByTaskId.remove(it) }
            _autoSyncState.value = stateBeforeCancel.copy(
                running = false,
                currentTaskId = null,
                taskStatuses = stateBeforeCancel.taskStatuses.map { status ->
                    if (status.stage in RUNNING_STAGES) {
                        status.copy(
                            stage = AutoSyncStage.CANCELLED,
                            message = ROUND_CANCELLED_MESSAGE,
                            needsLargeDeleteConfirmation = false,
                            pausedFromStage = status.stage,
                            updatedAt = now
                        )
                    } else {
                        status
                    }
                }
            )
            cancelAll(PAUSE_REASON_CANCEL_ROUND)
            developerLogRepository.log(message = "⛔ 本轮任务已取消")
        }
    }

    fun resumeAllPausedTasks() {
        resumeTasksByStatus(
            preserveOtherStatuses = true,
            shouldResumeStatus = { it.isManualResumeCandidate() }
        )
    }

    private fun resumeWifiPausedTasks(): Boolean {
        return resumeTasksByStatus(
            preserveOtherStatuses = true,
            shouldResumeStatus = { it.isWifiResumeCandidate() },
            logResumeStart = false
        )
    }

    private fun resumeLargeDeleteConfirmationTasks(confirmedTask: SyncTask): Boolean {
        return resumeTasksByStatus(
            preserveOtherStatuses = true,
            shouldResumeStatus = { it.isLargeDeleteConfirmationResumeCandidate(confirmedTask.id) },
            queuedMessage = LARGE_DELETE_CONFIRMATION_MESSAGE
        )
    }

    private fun resumeTasksByStatus(
        preserveOtherStatuses: Boolean,
        shouldResumeStatus: (AutoSyncTaskStatus) -> Boolean,
        shouldResumeTask: (SyncTask) -> Boolean = { true },
        retryScan: Boolean = false,
        queuedMessage: String = "等待扫描",
        logResumeStart: Boolean = true
    ): Boolean {
        if (_autoSyncState.value.taskStatuses.none(shouldResumeStatus)) return false
        if (!markScanAllStarting()) return false
        val job = viewModelScope.launch {
            try {
                val enabledTasks = repository.getEnabledTasks()
                if (enabledTasks.isEmpty()) return@launch
                val resumeStatuses = _autoSyncState.value.taskStatuses
                    .filter(shouldResumeStatus)
                val resumeTaskIds = resumeStatuses.mapTo(mutableSetOf()) { it.taskId }
                val tasksToResume = enabledTasks.filter { task ->
                    task.id in resumeTaskIds && shouldResumeTask(task)
                }
                if (tasksToResume.isEmpty()) return@launch
                val tasksToResumeIds = tasksToResume.mapTo(mutableSetOf()) { it.id }
                val syncResumeTaskIds = resumeStatuses
                    .filter { it.taskId in tasksToResumeIds && it.isSyncResumeCandidate() }
                    .mapTo(mutableSetOf()) { it.taskId }
                val syncTasksToResume = tasksToResume.filter { it.id in syncResumeTaskIds }
                val scanTasksToResume = tasksToResume.filter { it.id !in syncResumeTaskIds }

                tasksToResume.forEach { task -> retryJobs.remove(task.id)?.cancel() }
                if (syncTasksToResume.isNotEmpty()) {
                    runBatchTasks(
                        tasks = syncTasksToResume,
                        resumeScan = true,
                        resumeSync = true,
                        retryScan = retryScan,
                        queuedMessage = WAITING_UPLOAD_MESSAGE,
                        logWifiNotConnected = false,
                        preserveOtherStatuses = preserveOtherStatuses,
                        logBatchStart = logResumeStart
                    )
                }
                if (scanTasksToResume.isNotEmpty()) {
                    runBatchTasks(
                        tasks = scanTasksToResume,
                        resumeScan = true,
                        resumeSync = false,
                        retryScan = retryScan,
                        queuedMessage = queuedMessage,
                        logWifiNotConnected = false,
                        preserveOtherStatuses = preserveOtherStatuses,
                        logBatchStart = logResumeStart
                    )
                }
            } finally {
                runningJobs.remove(SCAN_ALL_JOB_ID)
                pauseJobs.remove(SCAN_ALL_JOB_ID)
                _autoSyncState.value = _autoSyncState.value.copy(running = false)
            }
        }
        runningJobs[SCAN_ALL_JOB_ID] = job
        return true
    }

    fun deleteTask(task: SyncTask) {
        viewModelScope.launch {
            pauseJobs.remove(task.id)?.cancel(CancellationException(PAUSE_REASON_DELETED))
            runningJobs.remove(task.id)?.cancel(CancellationException(PAUSE_REASON_DELETED))
            retryJobs.remove(task.id)?.cancel()
            developerLogRepository.log(
                task = task,
                eventType = DeveloperLogEvents.TASK_CANCELLED,
                filePath = task.localDisplayName.ifBlank { task.localRootPath.ifBlank { task.name } },
                message = "任务已被删除"
            )
            confirmedLargeDeleteTaskIds.remove(task.id)
            repository.deleteTask(task)
            _autoSyncState.value = _autoSyncState.value.copy(
                taskStatuses = _autoSyncState.value.taskStatuses.filterNot { it.taskId == task.id },
                currentTaskId = _autoSyncState.value.currentTaskId.takeIf { it != task.id },
                running = _autoSyncState.value.taskStatuses.any { it.taskId != task.id && it.stage in RUNNING_STAGES }
            )
        }
    }

    fun cancelAll(reason: String? = null) {
        runningJobs.values.forEach { job ->
            if (reason == null) job.cancel() else job.cancel(CancellationException(reason))
        }
        pauseJobs.values.forEach { job ->
            if (reason == null) job.cancel() else job.cancel(CancellationException(reason))
        }
        retryJobs.values.forEach { it.cancel() }
        runningJobs.clear()
        pauseJobs.clear()
        retryJobs.clear()
        wifiResumeJob?.cancel()
        wifiResumeJob = null
    }

    fun confirmLargeDeleteAndSync(task: SyncTask) {
        val currentState = _autoSyncState.value
        val currentStatus = currentState.taskStatuses.firstOrNull { it.taskId == task.id }
        if (currentState.running || currentState.hasManuallyPausedTasks()) return
        if (currentStatus?.needsLargeDeleteConfirmation != true) return
        viewModelScope.launch {
            val latestTask = repository.getTask(task.id) ?: return@launch
            if (!latestTask.enabled) return@launch
            if (latestTask.wifiOnly && !networkStateProvider.isWifiConnected()) {
                logNetworkWarning(WIFI_INTERRUPTED_MESSAGE)
                updateAutoTaskStatus(
                    task = latestTask,
                    stage = AutoSyncStage.WAITING_CONFIRMATION,
                    message = LARGE_DELETE_CONFIRMATION_MESSAGE,
                    needsLargeDeleteConfirmation = true
                )
                return@launch
            }
            confirmedLargeDeleteTaskIds += latestTask.id
            val started = resumeLargeDeleteConfirmationTasks(latestTask)
            if (!started) {
                confirmedLargeDeleteTaskIds.remove(latestTask.id)
            }
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            developerLogRepository.clear()
        }
    }

    private suspend fun runBatchTasks(
        tasks: List<SyncTask>,
        forceFullScan: Boolean = false,
        resumeScan: Boolean = false,
        resumeSync: Boolean = false,
        retryScan: Boolean = false,
        queuedMessage: String = "等待扫描",
        logWifiNotConnected: Boolean,
        preserveOtherStatuses: Boolean = false,
        logBatchStart: Boolean = true
    ) {
        if (tasks.isEmpty()) return
        val queuedStatuses = tasks.map { task ->
            AutoSyncTaskStatus(
                taskId = task.id,
                taskName = task.name,
                stage = AutoSyncStage.QUEUED,
                message = queuedMessage
            )
        }
        val currentState = _autoSyncState.value
        _autoSyncState.value = if (preserveOtherStatuses) {
            val taskIds = tasks.mapTo(mutableSetOf()) { it.id }
            currentState.copy(
                running = true,
                currentTaskId = queuedStatuses.firstOrNull()?.taskId ?: currentState.currentTaskId,
                taskStatuses = queuedStatuses + currentState.taskStatuses.filterNot { it.taskId in taskIds }
            )
        } else {
            AutoSyncUiState(
                running = true,
                taskStatuses = queuedStatuses
            )
        }

        val runnableTasks = prepareWifiBlockedTasks(
            tasks = tasks,
            logWifiNotConnected = logWifiNotConnected
        )
        if (runnableTasks.isEmpty()) return
        if (!resumeSync) {
            tasks.forEach { uploadProgressByTaskId.remove(it.id) }
        }

        val batchResults = tasks.map { AutoTaskRunResult(task = it, notStarted = true) }.toMutableList()
        if (logBatchStart) {
            logBatchScanStart(
                resumeScan = resumeScan,
                resumeSync = resumeSync,
                retryScan = retryScan
            )
        }
        val parentJob = currentCoroutineContext()[Job]
        if (!resumeSync) {
            for ((index, task) in runnableTasks.withIndex()) {
                val latestTask = repository.getTask(task.id) ?: continue
                if (!latestTask.enabled || isTaskManuallyPaused(latestTask.id)) {
                    updateAutoTaskStatus(latestTask, stage = AutoSyncStage.PAUSED, message = TASK_PAUSED_MESSAGE)
                    continue
                }
                val pauseJob = Job(parentJob)
                pauseJobs[latestTask.id] = pauseJob
                runningJobs[latestTask.id] = pauseJob
                var shouldStopBatch = false
                var stopReason: SyncStopReason? = null
                try {
                    val result = withContext(pauseJob) {
                        runBatchTaskScan(
                            task = latestTask,
                            forceFullScan = forceFullScan,
                            resumeScan = resumeScan
                        )
                    }
                    batchResults.replaceBatchResult(result)
                    shouldStopBatch = result.shouldStopBatch()
                    stopReason = result.stopReason
                } finally {
                    pauseJobs.remove(latestTask.id)
                    runningJobs.remove(latestTask.id)
                }
                if (shouldStopBatch) {
                    val remainingTasks = runnableTasks.drop(index + 1)
                    markTasksStoppedBeforeRun(
                        tasks = remainingTasks,
                        stopReason = stopReason,
                        pausedFromStage = AutoSyncStage.SCANNING
                    )
                    remainingTasks
                        .toStoppedBeforeRunResults(stopReason)
                        .forEach { batchResults.replaceBatchResult(it) }
                    break
                }
            }
        }
        val shouldStopBatch = batchResults.any { it.shouldStopBatch() }
        val uploadCandidates = if (resumeSync) {
            runnableTasks.map { AutoTaskRunResult(task = it) }
        } else {
            batchResults.filter { it.shouldUploadAfterBatchScan() }
        }
        if (batchResults.hasInterruptedPauseStop()) {
            clearFinishedLargeDeleteConfirmations(batchResults)
            return
        }
        if (uploadCandidates.isEmpty() && !shouldStopBatch) {
            if (!resumeSync) {
                logBatchScanSummary(batchResults)
            }
            logBatchEnd()
            clearFinishedLargeDeleteConfirmations(batchResults)
            return
        }

        if (!resumeSync) {
            logBatchScanSummary(batchResults)
        }
        if (shouldStopBatch) {
            logBatchSummary(batchResults)
            clearFinishedLargeDeleteConfirmations(batchResults)
            return
        }

        if (uploadCandidates.isNotEmpty() && !(resumeSync && logBatchStart)) {
            logBatchUploadStart(resumeSync = resumeSync)
        }
        for ((index, result) in uploadCandidates.withIndex()) {
            val latestTask = repository.getTask(result.task.id) ?: continue
            if (!latestTask.enabled || isTaskManuallyPaused(latestTask.id)) {
                val pausedResult = result.copy(task = latestTask, stopReason = SyncStopReason.USER_PAUSED)
                updateAutoTaskStatus(latestTask, stage = AutoSyncStage.PAUSED, message = TASK_PAUSED_MESSAGE)
                batchResults.replaceBatchResult(pausedResult)
                val remainingTasks = uploadCandidates.drop(index + 1).map { it.task }
                markTasksStoppedBeforeRun(
                    tasks = remainingTasks,
                    stopReason = SyncStopReason.USER_PAUSED,
                    pausedFromStage = AutoSyncStage.SYNCING
                )
                remainingTasks.forEach { task ->
                    batchResults.firstOrNull { it.task.id == task.id }?.let { remainingResult ->
                        batchResults.replaceBatchResult(remainingResult.copy(stopReason = SyncStopReason.USER_PAUSED))
                    }
                }
                break
            }
            val pauseJob = Job(parentJob)
            pauseJobs[latestTask.id] = pauseJob
            runningJobs[latestTask.id] = pauseJob
            var uploadResult = result
            try {
                uploadResult = withContext(pauseJob) {
                    runBatchTaskUpload(result.copy(task = latestTask))
                }
                batchResults.replaceBatchResult(uploadResult)
            } finally {
                pauseJobs.remove(latestTask.id)
                runningJobs.remove(latestTask.id)
            }
            if (uploadResult.shouldStopBatch()) {
                val remainingTasks = uploadCandidates.drop(index + 1).map { it.task }
                val remainingMessage = when {
                    (uploadResult.uploadSummary?.remoteDeletePausedCount ?: 0) > 0 -> LARGE_DELETE_CONFIRMATION_MESSAGE
                    uploadResult.stopReason == SyncStopReason.SERVICE_UNAVAILABLE -> NOT_SYNCED_MESSAGE
                    else -> null
                }
                markTasksStoppedBeforeRun(
                    tasks = remainingTasks,
                    stopReason = uploadResult.stopReason,
                    messageOverride = remainingMessage,
                    pausedFromStage = AutoSyncStage.SYNCING
                )
                remainingTasks.forEach { task ->
                    batchResults.firstOrNull { it.task.id == task.id }?.let { remainingResult ->
                        batchResults.replaceBatchResult(
                            remainingResult.copy(
                                stopReason = uploadResult.stopReason,
                                notStarted = uploadResult.stopReason == SyncStopReason.SERVICE_UNAVAILABLE
                            )
                        )
                    }
                }
                break
            }
        }
        if (batchResults.hasServiceUnavailableStop()) {
            settleServiceUnavailableRunningStatuses()
        }
        if (batchResults.hasInterruptedPauseStop()) {
            clearFinishedLargeDeleteConfirmations(batchResults)
            return
        }
        logBatchSummary(batchResults)
        clearFinishedLargeDeleteConfirmations(batchResults)
    }

    private suspend fun runBatchTaskScan(
        task: SyncTask,
        forceFullScan: Boolean,
        resumeScan: Boolean
    ): AutoTaskRunResult {
        try {
            if (task.wifiOnly && !networkStateProvider.isWifiConnected()) {
                logNetworkWarning(WIFI_INTERRUPTED_MESSAGE)
                updateAutoTaskStatus(
                    task = task,
                    stage = AutoSyncStage.PAUSED,
                    message = WIFI_INTERRUPTED_MESSAGE
                )
                return AutoTaskRunResult(
                    task = task,
                    notStarted = true,
                    stopReason = SyncStopReason.WIFI_INTERRUPTED
                )
            }
            repository.markRunState(task.id, SyncRunStates.SCANNING)
            updateAutoTaskStatus(
                task = task,
                stage = AutoSyncStage.SCANNING,
                message = "正在扫描"
            )

            var lastProgressUpdateAt = 0L
            val scanSummary = localScanRepository.scanTask(
                task = task,
                forceFullScan = forceFullScan,
                resumeScan = resumeScan
            ) { progress ->
                val now = System.currentTimeMillis()
                if (shouldPublishProgress(progress, now, lastProgressUpdateAt)) {
                    lastProgressUpdateAt = now
                    updateAutoTaskStatus(
                        task = task,
                        stage = AutoSyncStage.SCANNING,
                        filesScanned = progress.filesScanned,
                        filesTotal = progress.totalFiles,
                        latestPath = progress.latestPath,
                        message = progress.toScanProgressMessage()
                    )
                }
            }

            if (task.wifiOnly && !networkStateProvider.isWifiConnected()) {
                logNetworkWarning(WIFI_INTERRUPTED_MESSAGE)
                updateAutoTaskStatus(
                    task = task,
                    stage = AutoSyncStage.PAUSED,
                    filesScanned = scanSummary.totalFilesSeen,
                    filesTotal = scanSummary.totalFilesSeen,
                    message = WIFI_INTERRUPTED_MESSAGE
                )
                return AutoTaskRunResult(
                    task = task,
                    scanSummary = scanSummary,
                    stopReason = SyncStopReason.WIFI_INTERRUPTED
                )
            }

            val hasChanges = scanSummary.hasChangesToSync()
            updateAutoTaskStatus(
                task = task,
                stage = if (hasChanges) AutoSyncStage.QUEUED else AutoSyncStage.NO_CHANGES,
                filesScanned = scanSummary.totalFilesSeen,
                filesTotal = scanSummary.totalFilesSeen,
                message = if (hasChanges) WAITING_UPLOAD_MESSAGE else "无变化"
            )
            return AutoTaskRunResult(
                task = task,
                scanSummary = scanSummary,
                noChanges = !hasChanges
            )
        } catch (throwable: CancellationException) {
            val message = cancellationMessage(task, throwable.message)
            val stopReason = cancellationStopReason(task, throwable.message)
            if (stopReason == SyncStopReason.WIFI_INTERRUPTED) {
                logNetworkWarning(WIFI_INTERRUPTED_MESSAGE)
            }
            updateAutoTaskStatus(
                task = task,
                stage = cancellationStage(throwable.message),
                message = message
            )
            return AutoTaskRunResult(
                task = task,
                stopReason = stopReason
            )
        } catch (throwable: Throwable) {
            updateAutoTaskStatus(
                task = task,
                stage = AutoSyncStage.FAILED,
                message = throwable.message ?: "自动同步失败"
            )
            return AutoTaskRunResult(task = task, failedTask = true)
        } finally {
            repository.markRunState(task.id, SyncRunStates.IDLE)
        }
    }

    private suspend fun runBatchTaskUpload(result: AutoTaskRunResult): AutoTaskRunResult {
        val task = result.task
        val scanSummary = result.scanSummary
        try {
            if (task.wifiOnly && !networkStateProvider.isWifiConnected()) {
                logNetworkWarning(WIFI_INTERRUPTED_MESSAGE)
                updateAutoTaskStatus(
                    task = task,
                    stage = AutoSyncStage.PAUSED,
                    message = WIFI_INTERRUPTED_MESSAGE
                )
                return result.copy(stopReason = SyncStopReason.WIFI_INTERRUPTED)
            }
            repository.markRunState(task.id, SyncRunStates.SYNCING)
            val existingUploadProgress = uploadProgressByTaskId[task.id]
            updateAutoTaskStatus(
                task = task,
                stage = AutoSyncStage.SYNCING,
                filesScanned = scanSummary?.totalFilesSeen ?: 0,
                message = existingUploadProgress?.toSyncProgressMessage() ?: "正在同步"
            )

            var lastProgressUpdateAt = 0L
            val uploadSummary = webDavUploadRepository.uploadPendingFiles(
                task = task,
                confirmLargeDelete = task.id in confirmedLargeDeleteTaskIds
            ) { progress ->
                val now = System.currentTimeMillis()
                if (now - lastProgressUpdateAt >= PROGRESS_THROTTLE_MS || progress.completed == progress.total) {
                    lastProgressUpdateAt = now
                    uploadProgressByTaskId[task.id] = progress
                    updateAutoTaskStatus(
                        task = task,
                        stage = AutoSyncStage.SYNCING,
                        filesScanned = scanSummary?.totalFilesSeen ?: 0,
                        latestPath = progress.latestPath,
                        message = progress.toSyncProgressMessage()
                    )
                }
            }

            val uploadStage = uploadSummary.toUploadStage()
            updateAutoTaskStatus(
                task = task,
                stage = uploadStage,
                filesScanned = scanSummary?.totalFilesSeen ?: 0,
                message = uploadSummary.toStatusMessage(),
                needsLargeDeleteConfirmation = uploadSummary.remoteDeletePausedCount > 0,
                pausedFromStageOverride = if (uploadSummary.stopReason == SyncStopReason.SERVICE_UNAVAILABLE) {
                    AutoSyncStage.SYNCING
                } else {
                    null
                }
            )
            if (uploadSummary.stopReason == SyncStopReason.SERVICE_UNAVAILABLE) {
                scheduleRetryAfterServiceUnavailable(task)
            }
            if (uploadSummary.stopReason == SyncStopReason.WIFI_INTERRUPTED) {
                logNetworkWarning(WIFI_INTERRUPTED_MESSAGE)
            }
            if (uploadSummary.remoteDeletePausedCount > 0 ||
                uploadSummary.stopReason !in LARGE_DELETE_CONFIRMATION_KEEP_REASONS
            ) {
                confirmedLargeDeleteTaskIds.remove(task.id)
            }
            if (uploadSummary.stopReason !in BATCH_STOPS_THAT_KEEP_UPLOAD_PROGRESS) {
                uploadProgressByTaskId.remove(task.id)
            }
            return result.copy(
                uploadSummary = uploadSummary,
                stopReason = uploadSummary.stopReason
            )
        } catch (throwable: CancellationException) {
            val message = cancellationMessage(task, throwable.message)
            val stopReason = cancellationStopReason(task, throwable.message)
            if (stopReason == SyncStopReason.WIFI_INTERRUPTED) {
                logNetworkWarning(WIFI_INTERRUPTED_MESSAGE)
            }
            updateAutoTaskStatus(
                task = task,
                stage = cancellationStage(throwable.message),
                message = message
            )
            return result.copy(stopReason = stopReason)
        } catch (throwable: Throwable) {
            updateAutoTaskStatus(
                task = task,
                stage = AutoSyncStage.FAILED,
                message = throwable.message ?: "自动同步失败"
            )
            return result.copy(failedTask = true)
        } finally {
            repository.markRunState(task.id, SyncRunStates.IDLE)
        }
    }

    private suspend fun recoverInterruptedRuns() {
        repository.getTasksWithActiveRunState().forEach { task ->
            val summary = when (task.runState) {
                SyncRunStates.SCANNING -> "APP异常退出，上次扫描异常中断"
                SyncRunStates.SYNCING -> "APP异常退出，上次上传异常中断"
                else -> "APP异常退出，上次同步异常中断"
            }
            developerLogRepository.log(
                task = task,
                eventType = DeveloperLogEvents.SYNC_INTERRUPTED,
                message = "同步中断",
                summary = summary
            )
            repository.markRunState(task.id, SyncRunStates.IDLE)
        }
    }

    private suspend fun prepareWifiBlockedTasks(
        tasks: List<SyncTask>,
        logWifiNotConnected: Boolean
    ): List<SyncTask> {
        if (networkStateProvider.isWifiConnected()) return tasks
        val blockedTasks = tasks.filter { it.wifiOnly }
        if (blockedTasks.isEmpty()) return tasks

        val blockedMessage = if (logWifiNotConnected) {
            WIFI_NOT_CONNECTED_MESSAGE
        } else {
            WIFI_INTERRUPTED_MESSAGE
        }
        logNetworkWarning(blockedMessage)
        blockedTasks.forEach { task ->
            ensureTaskStatus(task)
            updateAutoTaskStatus(
                task = task,
                stage = AutoSyncStage.PAUSED,
                message = blockedMessage
            )
        }
        return tasks.filterNot { it.wifiOnly }
    }

    private suspend fun logBatchScanStart(
        resumeScan: Boolean = false,
        resumeSync: Boolean = false,
        retryScan: Boolean = false
    ) {
        val message = when {
            retryScan -> "🔄 尝试自动同步任务"
            resumeSync -> "🔄 同步任务已恢复"
            resumeScan -> "🔄 扫描任务已恢复"
            else -> "🔍 开始扫描"
        }
        developerLogRepository.log(message = message)
    }

    private suspend fun logBatchScanSummary(results: List<AutoTaskRunResult>) {
        if (results.isEmpty()) return

        val scannedResults = results.filter { it.scanSummary != null }
        val totalFiles = scannedResults.sumOf { it.scanSummary?.totalFilesSeen ?: 0 }
        developerLogRepository.log(
            message = buildList {
                add("扫描完成：${results.size} 个任务，共 ${totalFiles} 个文件")
                addAll(results.map { it.toBatchScanLine() })
            }.joinToString("\n")
        )
    }

    private suspend fun logBatchUploadStart(resumeSync: Boolean = false) {
        developerLogRepository.log(message = if (resumeSync) "⬆️ 继续同步" else "⬆️ 开始同步")
    }

    private suspend fun logBatchSummary(results: List<AutoTaskRunResult>) {
        if (results.isEmpty()) return

        developerLogRepository.logSyncResultSummary(
            results.map { it.toSyncResultLogEntry() }
        )
    }

    private suspend fun logBatchEnd() {
        developerLogRepository.log(message = "结束 · ${formatDateTime(System.currentTimeMillis())}")
    }

    private suspend fun logNetworkWarning(message: String) {
        val now = System.currentTimeMillis()
        if (message == WIFI_NOT_CONNECTED_MESSAGE || message == WIFI_INTERRUPTED_MESSAGE) {
            shouldLogWifiConnectedSuccess = true
        }
        if (lastNetworkWarningMessage == message && now - lastNetworkWarningLoggedAt < NETWORK_WARNING_DEBOUNCE_MS) {
            return
        }
        lastNetworkWarningMessage = message
        lastNetworkWarningLoggedAt = now
        developerLogRepository.log(
            task = null,
            eventType = DeveloperLogEvents.SYNC_INTERRUPTED,
            message = message,
            summary = message
        )
    }

    private fun handleWifiAvailable() {
        wifiResumeJob?.cancel()
        wifiResumeJob = viewModelScope.launch {
            logWifiConnectedSuccessIfNeeded()
            repeat(WIFI_RESUME_RETRY_ATTEMPTS) { attempt ->
                if (networkStateProvider.isWifiConnected() && resumeWifiPausedTasks()) return@launch
                if (attempt < WIFI_RESUME_RETRY_ATTEMPTS - 1) {
                    delay(WIFI_RESUME_RETRY_DELAY_MS)
                }
            }
        }
    }

    private suspend fun logWifiConnectedSuccessIfNeeded() {
        if (!shouldLogWifiConnectedSuccess) return
        shouldLogWifiConnectedSuccess = false
        lastNetworkWarningMessage = null
        lastNetworkWarningLoggedAt = 0L
        developerLogRepository.log(message = "$WIFI_CONNECTED_ICON $WIFI_CONNECTED_MESSAGE")
    }

    private fun AutoTaskRunResult.toBatchScanLine(): String {
        val summary = scanSummary ?: return if (failedTask) {
            "[${task.batchScanLogLabel()}] 任务失败"
        } else if (notStarted) {
            "[${task.batchScanLogLabel()}] 尚未处理"
        } else {
            "[${task.batchScanLogLabel()}] 已暂停"
        }
        val pendingDetails = summary.toPendingActionParts(task.deleteMode)
        val pendingText = pendingDetails
            .takeIf { it.isNotEmpty() }
            ?.joinToString("，")
            ?: "无需处理"
        return "[${summary.scanModeLogLabel()}][${task.batchScanLogLabel()}] $pendingText"
    }

    private fun LocalScanSummary.toPendingActionParts(deleteMode: String): List<String> {
        val localDeletedLabel = if (DeleteModes.deletesRemoteOnLocalDelete(deleteMode)) {
            "待删除云端 ${localDeletedCount}".takeIf { localDeletedCount > 0 }
        } else {
            "本地已删除 ${localDeletedKeptCount}个，无需处理".takeIf { localDeletedKeptCount > 0 }
        }
        return listOfNotNull(
            "待新增 ${newCount}".takeIf { newCount > 0 },
            "待修改 ${modifiedCount}".takeIf { modifiedCount > 0 },
            "待重传 ${lastFailedCount}".takeIf { lastFailedCount > 0 },
            localDeletedLabel
        )
    }

    private fun AutoTaskRunResult.shouldStopBatch(): Boolean {
        return (uploadSummary?.remoteDeletePausedCount ?: 0) > 0 ||
            stopReason in BATCH_STOP_REASONS
    }

    private fun List<AutoTaskRunResult>.hasInterruptedPauseStop(): Boolean {
        return any { result ->
            result.stopReason in BATCH_STOPS_WITHOUT_COMPLETION_LOG ||
                result.uploadSummary?.stopReason?.let { it in BATCH_STOPS_WITHOUT_COMPLETION_LOG } == true
        }
    }

    private fun List<AutoTaskRunResult>.hasServiceUnavailableStop(): Boolean {
        return any { result ->
            result.stopReason == SyncStopReason.SERVICE_UNAVAILABLE ||
                result.uploadSummary?.stopReason == SyncStopReason.SERVICE_UNAVAILABLE
        }
    }

    private fun settleServiceUnavailableRunningStatuses() {
        val now = System.currentTimeMillis()
        val current = _autoSyncState.value
        _autoSyncState.value = current.copy(
            running = false,
            taskStatuses = current.taskStatuses.map { status ->
                if (status.stage in RUNNING_STAGES) {
                    status.copy(
                        stage = AutoSyncStage.NOT_STARTED,
                        message = NOT_SYNCED_MESSAGE,
                        pausedFromStage = AutoSyncStage.SYNCING,
                        updatedAt = now
                    )
                } else {
                    status
                }
            }
        )
    }

    private fun UploadSummary.toUploadStage(): AutoSyncStage {
        return when {
            remoteDeletePausedCount > 0 -> AutoSyncStage.WAITING_CONFIRMATION
            stopReason == SyncStopReason.LOCAL_FOLDER_UNAVAILABLE -> AutoSyncStage.FAILED
            stopReason == SyncStopReason.SERVICE_UNAVAILABLE -> AutoSyncStage.WAITING_RETRY
            stopReason in STOP_REASONS_THAT_PAUSE -> AutoSyncStage.PAUSED
            failedCount > 0 || deleteFailedCount > 0 -> AutoSyncStage.FAILED
            else -> AutoSyncStage.COMPLETED
        }
    }

    private fun AutoTaskRunResult.shouldUploadAfterBatchScan(): Boolean {
        return scanSummary?.hasChangesToSync() == true &&
            stopReason == null &&
            !failedTask
    }

    private fun MutableList<AutoTaskRunResult>.replaceBatchResult(result: AutoTaskRunResult) {
        val index = indexOfFirst { it.task.id == result.task.id }
        if (index >= 0) {
            this[index] = result
        } else {
            add(result)
        }
    }

    private fun clearFinishedLargeDeleteConfirmations(results: List<AutoTaskRunResult>) {
        results.forEach { result ->
            if (result.task.id !in confirmedLargeDeleteTaskIds) return@forEach
            val stopReason = result.uploadSummary?.stopReason ?: result.stopReason
            if (stopReason !in LARGE_DELETE_CONFIRMATION_KEEP_REASONS) {
                confirmedLargeDeleteTaskIds.remove(result.task.id)
            }
        }
    }

    private fun List<SyncTask>.toStoppedBeforeRunResults(stopReason: SyncStopReason?): List<AutoTaskRunResult> {
        return map { task ->
            AutoTaskRunResult(
                task = task,
                notStarted = true,
                stopReason = stopReason
            )
        }
    }

    private fun markTasksStoppedBeforeRun(
        tasks: List<SyncTask>,
        stopReason: SyncStopReason?,
        messageOverride: String? = null,
        pausedFromStage: AutoSyncStage? = null
    ) {
        tasks.forEach { task ->
            updateAutoTaskStatus(
                task = task,
                stage = if (stopReason == SyncStopReason.CANCELLED) {
                    AutoSyncStage.CANCELLED
                } else {
                    AutoSyncStage.NOT_STARTED
                },
                message = messageOverride ?: when (stopReason) {
                    SyncStopReason.USER_PAUSED -> TASK_PAUSED_MESSAGE
                    SyncStopReason.WIFI_INTERRUPTED -> WIFI_INTERRUPTED_MESSAGE
                    SyncStopReason.SERVICE_UNAVAILABLE -> SERVICE_UNAVAILABLE_MESSAGE
                    SyncStopReason.TASK_DELETED -> "任务已被删除"
                    SyncStopReason.CANCELLED -> ROUND_CANCELLED_MESSAGE
                    SyncStopReason.APP_INTERRUPTED -> "同步中断"
                    SyncStopReason.LOCAL_FOLDER_UNAVAILABLE,
                    null -> "尚未处理"
                },
                pausedFromStageOverride = pausedFromStage.takeUnless { stopReason == SyncStopReason.CANCELLED }
            )
        }
    }

    private suspend fun logPauseAllSummary(stateBeforePause: AutoSyncUiState) {
        val message = if (stateBeforePause.taskStatuses.any { it.stage == AutoSyncStage.SYNCING }) {
            "⏸️ 同步任务已暂停"
        } else {
            "⏸️ 扫描任务已暂停"
        }
        developerLogRepository.log(message = message)
    }

    private fun markScanAllStarting(): Boolean {
        if (_autoSyncState.value.running || runningJobs.containsKey(SCAN_ALL_JOB_ID)) return false
        _autoSyncState.value = _autoSyncState.value.copy(running = true)
        return true
    }

    private fun scheduleRetryAfterServiceUnavailable(task: SyncTask) {
        retryJobs.remove(task.id)?.cancel()
        retryJobs[task.id] = viewModelScope.launch {
            delay(SERVICE_UNAVAILABLE_RETRY_DELAY_MS)
            retryJobs.remove(task.id)
            repository.getTask(task.id) ?: return@launch
            if (_autoSyncState.value.taskStatuses.any { it.taskId == task.id && it.stage in RUNNING_STAGES }) return@launch
            retryScanAndSyncAll()
        }
    }

    private fun AutoTaskRunResult.toSyncResultLogEntry(): SyncResultLogEntry {
        return SyncResultLogEntry(
            task = task,
            uploadSummary = uploadSummary,
            failedTask = failedTask,
            notStarted = notStarted,
            stopReason = stopReason
        )
    }

    private fun LocalScanSummary.scanModeLogLabel(): String {
        return when (scanMode) {
            ScanModes.INCREMENTAL -> "增量"
            ScanModes.FULL -> "全量"
            else -> "扫描"
        }
    }

    private fun SyncTask.batchScanLogLabel(): String {
        return syncResultTaskLabel()
    }

    private fun updateAutoTaskStatus(
        task: SyncTask,
        stage: AutoSyncStage,
        filesScanned: Int = 0,
        filesTotal: Int? = null,
        latestPath: String? = null,
        message: String? = null,
        needsLargeDeleteConfirmation: Boolean = false,
        pausedFromStageOverride: AutoSyncStage? = null
    ) {
        val current = _autoSyncState.value
        val nextCurrentTaskId = when {
            stage in RUNNING_STAGES -> task.id
            stage == AutoSyncStage.PAUSED && current.currentTaskId == null -> task.id
            else -> current.currentTaskId
        }
        _autoSyncState.value = current.copy(
            currentTaskId = nextCurrentTaskId,
            taskStatuses = current.taskStatuses.map { status ->
                if (status.taskId == task.id) {
                    if (status.isManualResumeCandidate() && !stage.isManualPauseUpdate(message)) {
                        return@map status
                    }
                    val keepPauseProgress = stage in setOf(AutoSyncStage.PAUSED, AutoSyncStage.CANCELLED) && filesScanned == 0
                    val pausedFromStage = when {
                        pausedFromStageOverride != null -> pausedFromStageOverride
                        stage == AutoSyncStage.PAUSED ||
                            stage == AutoSyncStage.WAITING_RETRY ||
                            stage == AutoSyncStage.CANCELLED -> status.pausedFromStage ?: status.stage
                        else -> null
                    }
                    status.copy(
                        stage = stage,
                        filesScanned = if (keepPauseProgress) status.filesScanned else filesScanned,
                        filesTotal = if (keepPauseProgress && filesTotal == null) status.filesTotal else filesTotal,
                        latestPath = if (keepPauseProgress && latestPath == null) status.latestPath else latestPath,
                        message = message,
                        needsLargeDeleteConfirmation = needsLargeDeleteConfirmation,
                        pausedFromStage = pausedFromStage,
                        updatedAt = System.currentTimeMillis()
                    )
                } else {
                    status
                }
            }
        )
    }

    private fun cancelWifiOnlyRunningJobs() {
        viewModelScope.launch {
            val runningWifiOnlyTaskIds = runningJobs.keys.filter { taskId ->
                taskId != SCAN_ALL_JOB_ID && repository.getTask(taskId)?.wifiOnly == true
            }
            val statusWifiOnlyTaskIds = _autoSyncState.value.taskStatuses
                .filter { it.stage in WIFI_WARNING_STAGES }
                .mapNotNull { status ->
                    repository.getTask(status.taskId)?.takeIf { it.wifiOnly }?.id
                }
            val warningWifiOnlyTaskIds = (runningWifiOnlyTaskIds + statusWifiOnlyTaskIds).toSet()
            if (warningWifiOnlyTaskIds.isNotEmpty()) {
                logNetworkWarning(WIFI_INTERRUPTED_MESSAGE)
            }
            runningWifiOnlyTaskIds.forEach { taskId ->
                runningJobs[taskId]?.cancel(CancellationException(PAUSE_REASON_WIFI_ALL))
            }
        }
    }

    private suspend fun cancellationMessage(task: SyncTask, reason: String?): String {
        return when {
            reason == PAUSE_REASON_USER_ALL -> TASK_PAUSED_MESSAGE
            reason == PAUSE_REASON_CANCEL_ROUND -> ROUND_CANCELLED_MESSAGE
            reason == PAUSE_REASON_WIFI || reason == PAUSE_REASON_WIFI_ALL -> WIFI_INTERRUPTED_MESSAGE
            reason == PAUSE_REASON_DELETED -> "任务已被删除"
            task.wifiOnly && !networkStateProvider.isWifiConnected() -> WIFI_INTERRUPTED_MESSAGE
            else -> TASK_PAUSED_MESSAGE
        }
    }

    private suspend fun cancellationStopReason(task: SyncTask, reason: String?): SyncStopReason {
        return when {
            reason == PAUSE_REASON_USER_ALL -> SyncStopReason.USER_PAUSED
            reason == PAUSE_REASON_CANCEL_ROUND -> SyncStopReason.CANCELLED
            reason == PAUSE_REASON_WIFI || reason == PAUSE_REASON_WIFI_ALL -> SyncStopReason.WIFI_INTERRUPTED
            reason == PAUSE_REASON_DELETED -> SyncStopReason.TASK_DELETED
            task.wifiOnly && !networkStateProvider.isWifiConnected() -> SyncStopReason.WIFI_INTERRUPTED
            else -> SyncStopReason.APP_INTERRUPTED
        }
    }

    private fun cancellationStage(reason: String?): AutoSyncStage {
        return if (reason == PAUSE_REASON_CANCEL_ROUND) AutoSyncStage.CANCELLED else AutoSyncStage.PAUSED
    }

    private fun isTaskManuallyPaused(taskId: Long): Boolean {
        return _autoSyncState.value.taskStatuses.any { it.taskId == taskId && it.isManualResumeCandidate() }
    }

    private fun AutoSyncTaskStatus.isWifiResumeCandidate(): Boolean {
        return stage in RESUME_CANDIDATE_STAGES &&
            (message == WIFI_INTERRUPTED_MESSAGE || message == WIFI_NOT_CONNECTED_MESSAGE)
    }

    private fun AutoSyncTaskStatus.isManualResumeCandidate(): Boolean {
        return stage in RESUME_CANDIDATE_STAGES && message == TASK_PAUSED_MESSAGE
    }

    private fun AutoSyncStage.isManualPauseUpdate(message: String?): Boolean {
        return this in RESUME_CANDIDATE_STAGES && message == TASK_PAUSED_MESSAGE
    }

    private fun AutoSyncTaskStatus.isServiceUnavailableResumeCandidate(): Boolean {
        return (stage == AutoSyncStage.WAITING_RETRY && message == SERVICE_UNAVAILABLE_MESSAGE) ||
            (
                stage in RESUME_CANDIDATE_STAGES &&
                    (stage == AutoSyncStage.NOT_STARTED && pausedFromStage == AutoSyncStage.SYNCING && message == NOT_SYNCED_MESSAGE)
            )
    }

    private fun AutoSyncTaskStatus.isSyncResumeCandidate(): Boolean {
        return stage == AutoSyncStage.WAITING_RETRY ||
            (stage == AutoSyncStage.PAUSED && pausedFromStage == AutoSyncStage.SYNCING) ||
            (stage == AutoSyncStage.NOT_STARTED && pausedFromStage == AutoSyncStage.SYNCING) ||
            (stage in RESUME_CANDIDATE_STAGES && message == LARGE_DELETE_CONFIRMATION_MESSAGE) ||
            stage == AutoSyncStage.WAITING_CONFIRMATION ||
            needsLargeDeleteConfirmation
    }

    private fun AutoSyncTaskStatus.manualPauseResumeStage(): AutoSyncStage? {
        return when {
            stage == AutoSyncStage.QUEUED && message == WAITING_UPLOAD_MESSAGE -> AutoSyncStage.SYNCING
            stage in RUNNING_STAGES -> stage
            else -> null
        }
    }

    private fun AutoSyncTaskStatus.isLargeDeleteConfirmationResumeCandidate(confirmedTaskId: Long): Boolean {
        return (taskId == confirmedTaskId && needsLargeDeleteConfirmation) ||
            (stage in RESUME_CANDIDATE_STAGES && message == LARGE_DELETE_CONFIRMATION_MESSAGE)
    }

    private fun ensureTaskStatus(task: SyncTask) {
        val current = _autoSyncState.value
        if (current.taskStatuses.any { it.taskId == task.id }) return
        _autoSyncState.value = current.copy(
            taskStatuses = listOf(
                AutoSyncTaskStatus(
                    taskId = task.id,
                    taskName = task.name,
                    stage = AutoSyncStage.QUEUED,
                    message = "等待扫描"
                )
            ) + current.taskStatuses
        )
    }

    private fun shouldPublishProgress(
        progress: ScannedProgress,
        now: Long,
        lastProgressUpdateAt: Long
    ): Boolean {
        return progress.latestPath == null || now - lastProgressUpdateAt >= PROGRESS_THROTTLE_MS
    }

    private fun ScannedProgress.toScanProgressMessage(): String {
        return "正在扫描"
    }

    private fun LocalScanSummary.hasChangesToSync(): Boolean {
        return newCount > 0 || modifiedCount > 0 || lastFailedCount > 0 || localDeletedCount > 0
    }

    private fun UploadProgress.toSyncProgressMessage(): String {
        return buildString {
            append("正在同步 $completed/$total")
            if (failedCount > 0) {
                append("，失败 $failedCount")
            }
        }
    }

    private fun UploadSummary.toStatusMessage(): String? {
        return when {
            remoteDeletePausedCount > 0 -> LARGE_DELETE_CONFIRMATION_MESSAGE
            stopReason == SyncStopReason.SERVICE_UNAVAILABLE -> SERVICE_UNAVAILABLE_MESSAGE
            stopReason != null -> message
            else -> null
        }
    }

    class Factory(
        private val repository: SyncTaskRepository,
        private val localScanRepository: LocalScanRepository,
        private val webDavUploadRepository: WebDavUploadRepository,
        private val developerLogRepository: DeveloperLogRepository,
        private val settingsRepository: SettingsRepository,
        private val networkStateProvider: NetworkStateProvider
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                return HomeViewModel(
                    repository = repository,
                    localScanRepository = localScanRepository,
                    webDavUploadRepository = webDavUploadRepository,
                    developerLogRepository = developerLogRepository,
                    settingsRepository = settingsRepository,
                    networkStateProvider = networkStateProvider
                ) as T
            }
            throw IllegalArgumentException("未知 ViewModel 类型：${modelClass.name}")
        }
    }

    private companion object {
        const val PAUSE_REASON_USER_ALL = "USER_PAUSED_ALL"
        const val PAUSE_REASON_WIFI = "WIFI_INTERRUPTED"
        const val PAUSE_REASON_WIFI_ALL = "WIFI_INTERRUPTED_ALL"
        const val PAUSE_REASON_DELETED = "TASK_DELETED"
        const val PAUSE_REASON_CANCEL_ROUND = "ROUND_CANCELLED"
        const val WIFI_CONNECTED_MESSAGE = "WiFi已连接成功"
        const val WIFI_CONNECTED_ICON = "📶"
        const val WAITING_UPLOAD_MESSAGE = "等待上传"
        const val SCAN_ALL_JOB_ID = -1L
        const val PROGRESS_THROTTLE_MS = 600L
        const val NETWORK_WARNING_DEBOUNCE_MS = 2_000L
        const val WIFI_RESUME_RETRY_ATTEMPTS = 20
        const val WIFI_RESUME_RETRY_DELAY_MS = 500L
        const val SERVICE_UNAVAILABLE_RETRY_DELAY_MS = 30L * 60L * 1000L
        val RUNNING_STAGES = setOf(AutoSyncStage.QUEUED, AutoSyncStage.SCANNING, AutoSyncStage.SYNCING)
        val RESUME_CANDIDATE_STAGES = setOf(AutoSyncStage.PAUSED, AutoSyncStage.NOT_STARTED)
        val WIFI_WARNING_STAGES = RUNNING_STAGES + AutoSyncStage.PAUSED + AutoSyncStage.NOT_STARTED + AutoSyncStage.WAITING_CONFIRMATION
        val STOP_REASONS_THAT_PAUSE = setOf(
            SyncStopReason.WIFI_INTERRUPTED,
            SyncStopReason.USER_PAUSED
        )
        val LARGE_DELETE_CONFIRMATION_KEEP_REASONS = setOf<SyncStopReason?>(
            SyncStopReason.WIFI_INTERRUPTED,
            SyncStopReason.SERVICE_UNAVAILABLE,
            SyncStopReason.USER_PAUSED
        )
        val BATCH_STOP_REASONS = setOf(
            SyncStopReason.WIFI_INTERRUPTED,
            SyncStopReason.SERVICE_UNAVAILABLE,
            SyncStopReason.USER_PAUSED,
            SyncStopReason.TASK_DELETED,
            SyncStopReason.CANCELLED,
            SyncStopReason.APP_INTERRUPTED
        )
        val BATCH_STOPS_WITHOUT_COMPLETION_LOG = setOf(
            SyncStopReason.WIFI_INTERRUPTED,
            SyncStopReason.USER_PAUSED,
            SyncStopReason.CANCELLED
        )
        val BATCH_STOPS_THAT_KEEP_UPLOAD_PROGRESS = setOf(
            SyncStopReason.WIFI_INTERRUPTED,
            SyncStopReason.SERVICE_UNAVAILABLE,
            SyncStopReason.USER_PAUSED
        )
    }
}
