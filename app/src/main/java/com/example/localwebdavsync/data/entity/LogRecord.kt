package com.example.localwebdavsync.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "log_records",
    foreignKeys = [
        ForeignKey(
            entity = SyncTask::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("taskId"), Index("createdAt")]
)
data class LogRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val taskId: Long? = null,
    val taskName: String? = null,
    val eventType: String = DeveloperLogEvents.GENERAL,
    val filePath: String? = null,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val summary: String? = null,
    val level: String = "INFO",
    val message: String,
    val detail: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

object DeveloperLogEvents {
    const val GENERAL = "GENERAL"

    const val UPLOAD_SUCCESS = "UPLOAD_SUCCESS"
    const val OVERWRITE_UPLOAD_SUCCESS = "OVERWRITE_UPLOAD_SUCCESS"
    const val UPLOAD_FAILED = "UPLOAD_FAILED"
    const val REMOTE_DELETE_SUCCESS = "REMOTE_DELETE_SUCCESS"
    const val REMOTE_DELETE_FAILED = "REMOTE_DELETE_FAILED"
    const val TASK_CANCELLED = "TASK_CANCELLED"
    const val SYNC_INTERRUPTED = "SYNC_INTERRUPTED"
    const val DEBUG_FILE_DECISION = "DEBUG_FILE_DECISION"

    val successEvents = setOf(
        UPLOAD_SUCCESS,
        OVERWRITE_UPLOAD_SUCCESS,
        REMOTE_DELETE_SUCCESS
    )
}

object DeveloperLogEventGroups {
    val primary = setOf(
        DeveloperLogEvents.GENERAL,

        DeveloperLogEvents.TASK_CANCELLED,
        DeveloperLogEvents.SYNC_INTERRUPTED
    )

    val success = DeveloperLogEvents.successEvents

    val failed = setOf(
        DeveloperLogEvents.UPLOAD_FAILED,
        DeveloperLogEvents.REMOTE_DELETE_FAILED
    )

    val debug = setOf(
        DeveloperLogEvents.DEBUG_FILE_DECISION
    )
}
