package com.example.localwebdavsync.repository

import com.example.localwebdavsync.data.dao.SyncFileRecordDao
import com.example.localwebdavsync.data.dao.SyncTaskDao
import com.example.localwebdavsync.data.entity.SyncFileRecord
import com.example.localwebdavsync.data.entity.SyncTask
import kotlinx.coroutines.flow.Flow

class SyncTaskRepository(
    private val syncTaskDao: SyncTaskDao,
    private val syncFileRecordDao: SyncFileRecordDao
) {
    val tasks: Flow<List<SyncTask>> = syncTaskDao.observeAll()

    fun observeTask(id: Long): Flow<SyncTask?> {
        return syncTaskDao.observeById(id)
    }

    suspend fun getTask(id: Long): SyncTask? {
        return syncTaskDao.getById(id)
    }

    suspend fun getEnabledTasks(): List<SyncTask> {
        return syncTaskDao.getEnabledTasks()
    }

    suspend fun getTasksWithActiveRunState(): List<SyncTask> {
        return syncTaskDao.getTasksWithActiveRunState()
    }

    suspend fun updateTaskOrder(tasks: List<SyncTask>) {
        val now = System.currentTimeMillis()
        tasks.forEachIndexed { index, task ->
            syncTaskDao.updateSortOrder(task.id, index.toLong(), now)
        }
    }

    fun observeFiles(taskId: Long): Flow<List<SyncFileRecord>> {
        return syncFileRecordDao.observeForTask(taskId)
    }

    suspend fun saveTask(task: SyncTask): Long {
        val now = System.currentTimeMillis()
        return if (task.id == 0L) {
            syncTaskDao.insert(task.copy(sortOrder = newTaskSortOrder(), createdAt = now, updatedAt = now))
        } else {
            val existing = syncTaskDao.getById(task.id)
            syncTaskDao.update(
                task.copy(
                    lastScanTime = existing?.lastScanTime ?: task.lastScanTime,
                    lastSyncTime = existing?.lastSyncTime ?: task.lastSyncTime,
                    lastRunAt = existing?.lastRunAt ?: task.lastRunAt,
                    runState = existing?.runState ?: task.runState,
                    currentScanId = existing?.currentScanId ?: task.currentScanId,
                    sortOrder = existing?.sortOrder ?: task.sortOrder,
                    createdAt = existing?.createdAt ?: task.createdAt,
                    updatedAt = now
                )
            )
            task.id
        }
    }

    private suspend fun newTaskSortOrder(): Long {
        return (syncTaskDao.getMinSortOrder() ?: 0L) - 1L
    }

    suspend fun markRunState(taskId: Long, runState: String) {
        syncTaskDao.updateRunState(taskId, runState, System.currentTimeMillis())
    }

    suspend fun deleteTask(task: SyncTask) {
        syncTaskDao.delete(task)
    }
}
