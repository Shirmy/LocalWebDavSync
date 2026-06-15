package com.example.localwebdavsync.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.localwebdavsync.data.entity.DeveloperLogEvents
import com.example.localwebdavsync.data.entity.LogRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {
    @Query("SELECT * FROM log_records ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int = 1000): Flow<List<LogRecord>>

    @Query("SELECT * FROM log_records WHERE taskId = :taskId ORDER BY createdAt DESC")
    fun observeForTask(taskId: Long): Flow<List<LogRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: LogRecord): Long

    @Query("SELECT COUNT(*) FROM log_records")
    suspend fun countLogs(): Int

    @Query(
        """
        DELETE FROM log_records
        WHERE id IN (
              SELECT id FROM log_records
              WHERE eventType IN (:successEvents)
              ORDER BY createdAt ASC, id ASC
              LIMIT 1
          )
        """
    )
    suspend fun deleteOldestSuccessLog(successEvents: Set<String>): Int

    @Query(
        """
        DELETE FROM log_records
        WHERE id IN (
              SELECT id FROM log_records
              WHERE eventType IN (:primaryEvents)
              ORDER BY createdAt ASC, id ASC
              LIMIT 1
          )
        """
    )
    suspend fun deleteOldestPrimaryLog(primaryEvents: Set<String>): Int

    @Query("DELETE FROM log_records")
    suspend fun clear()
}
