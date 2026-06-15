package com.example.localwebdavsync.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object FileScanStatuses {
    const val NEW = "NEW"
    const val MODIFIED = "MODIFIED"
    const val SKIPPED = "SKIPPED"
    const val LOCAL_DELETED_KEEP_REMOTE = "LOCAL_DELETED_KEEP_REMOTE"
    const val LOCAL_DELETED = "LOCAL_DELETED"
    const val LAST_FAILED = "LAST_FAILED"
    const val SYNCED = "SYNCED"
    const val OVERWRITE_UPLOADED = "OVERWRITE_UPLOADED"
    const val UPLOAD_FAILED = "UPLOAD_FAILED"
    const val REMOTE_DELETED = "REMOTE_DELETED"
    const val DELETE_FAILED = "DELETE_FAILED"

    val PENDING_UPLOAD_STATUSES = setOf(NEW, MODIFIED, LAST_FAILED, UPLOAD_FAILED)
    val REMOTE_DELETE_CANDIDATE_STATUSES = setOf(LOCAL_DELETED, DELETE_FAILED)
    val UPLOAD_FAILURE_STATUSES = setOf(LAST_FAILED, UPLOAD_FAILED)
    val UNCHANGED_STATUSES = setOf(SKIPPED, SYNCED, OVERWRITE_UPLOADED)
    val INCREMENTAL_RECHECK_STATUSES = setOf(
        NEW,
        MODIFIED,
        LAST_FAILED,
        UPLOAD_FAILED,
        DELETE_FAILED,
        LOCAL_DELETED,
        LOCAL_DELETED_KEEP_REMOTE
    )
    val CURRENT_SCAN_VISIBLE_STATUSES = setOf(
        NEW,
        MODIFIED,
        OVERWRITE_UPLOADED,
        LOCAL_DELETED_KEEP_REMOTE,
        LOCAL_DELETED,
        REMOTE_DELETED,
        LAST_FAILED,
        UPLOAD_FAILED,
        DELETE_FAILED,
        SYNCED
    )
    val SUMMARY_PENDING_RESULT_STATUSES = setOf(NEW, MODIFIED, LOCAL_DELETED)
    val DETAIL_PENDING_STATUSES = setOf(NEW, MODIFIED, LAST_FAILED, LOCAL_DELETED)
    val DETAIL_SYNCED_STATUSES = setOf(SYNCED, OVERWRITE_UPLOADED, REMOTE_DELETED)
    val DETAIL_FAILED_STATUSES = setOf(UPLOAD_FAILED, DELETE_FAILED)
    val DETAIL_IGNORED_STATUSES = setOf(LOCAL_DELETED_KEEP_REMOTE)
}

@Entity(
    tableName = "sync_file_records",
    foreignKeys = [
        ForeignKey(
            entity = SyncTask::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("taskId"), Index(value = ["taskId", "localRelativePath"], unique = true)]
)
data class SyncFileRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val taskId: Long,
    val localRelativePath: String,
    val remotePath: String,
    val fileSize: Long,
    val lastModifiedAt: Long,
    val contentHash: String? = null,
    val remoteEtag: String? = null,
    val deletedLocally: Boolean = false,
    val lastSeenAt: Long = System.currentTimeMillis(),
    val lastSyncedAt: Long? = null,
    val status: String = FileScanStatuses.NEW,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val lastScanId: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
