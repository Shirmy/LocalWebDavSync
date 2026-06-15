package com.example.localwebdavsync.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.localwebdavsync.data.entity.SyncTask
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncTaskDao {
    @Query("SELECT * FROM sync_tasks ORDER BY sortOrder ASC, updatedAt DESC")
    fun observeAll(): Flow<List<SyncTask>>

    @Query("SELECT * FROM sync_tasks WHERE id = :id")
    fun observeById(id: Long): Flow<SyncTask?>

    @Query("SELECT * FROM sync_tasks WHERE id = :id")
    suspend fun getById(id: Long): SyncTask?

    @Query("SELECT MIN(sortOrder) FROM sync_tasks")
    suspend fun getMinSortOrder(): Long?

    @Query("SELECT * FROM sync_tasks WHERE enabled = 1 ORDER BY sortOrder ASC, updatedAt DESC")
    suspend fun getEnabledTasks(): List<SyncTask>

    @Query("SELECT * FROM sync_tasks WHERE runState != 'IDLE' ORDER BY sortOrder ASC, updatedAt DESC")
    suspend fun getTasksWithActiveRunState(): List<SyncTask>

    @Query("UPDATE sync_tasks SET lastSyncTime = :timeMillis, lastRunAt = :timeMillis, updatedAt = :timeMillis WHERE id = :taskId")
    suspend fun updateLastSyncTime(taskId: Long, timeMillis: Long)

    @Query("UPDATE sync_tasks SET currentScanId = :scanId, lastScanTime = :timeMillis, lastRunAt = :timeMillis, updatedAt = :timeMillis WHERE id = :taskId")
    suspend fun updateScanCompletion(taskId: Long, scanId: Long, timeMillis: Long)

    @Query("UPDATE sync_tasks SET runState = :runState, updatedAt = :timeMillis WHERE id = :taskId")
    suspend fun updateRunState(taskId: Long, runState: String, timeMillis: Long)

    @Query("UPDATE sync_tasks SET sortOrder = :sortOrder, updatedAt = :timeMillis WHERE id = :taskId")
    suspend fun updateSortOrder(taskId: Long, sortOrder: Long, timeMillis: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: SyncTask): Long

    @Update
    suspend fun update(task: SyncTask)

    @Delete
    suspend fun delete(task: SyncTask)
}
