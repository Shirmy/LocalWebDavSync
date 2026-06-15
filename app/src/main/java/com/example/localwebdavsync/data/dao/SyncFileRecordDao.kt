package com.example.localwebdavsync.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.example.localwebdavsync.data.entity.SyncFileRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncFileRecordDao {
    @Query("SELECT * FROM sync_file_records WHERE taskId = :taskId ORDER BY localRelativePath")
    fun observeForTask(taskId: Long): Flow<List<SyncFileRecord>>

    @Query("SELECT * FROM sync_file_records WHERE taskId = :taskId")
    suspend fun getForTask(taskId: Long): List<SyncFileRecord>

    @Upsert
    suspend fun upsertAll(records: List<SyncFileRecord>)

    @Update
    suspend fun update(record: SyncFileRecord)

    @Query("DELETE FROM sync_file_records WHERE id IN (:recordIds)")
    suspend fun deleteByIds(recordIds: List<Long>)

    @Query("DELETE FROM sync_file_records WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: Long)
}
