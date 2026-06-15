package com.example.localwebdavsync.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

object DeleteModes {
    const val KEEP_REMOTE_ON_LOCAL_DELETE = "KEEP_REMOTE_ON_LOCAL_DELETE"
    const val DELETE_REMOTE_ON_LOCAL_DELETE = "DELETE_REMOTE_ON_LOCAL_DELETE"

    fun normalize(value: String): String {
        return when (value) {
            DELETE_REMOTE_ON_LOCAL_DELETE -> DELETE_REMOTE_ON_LOCAL_DELETE
            else -> KEEP_REMOTE_ON_LOCAL_DELETE
        }
    }

    fun deletesRemoteOnLocalDelete(value: String): Boolean {
        return normalize(value) == DELETE_REMOTE_ON_LOCAL_DELETE
    }
}

object SyncRunStates {
    const val IDLE = "IDLE"
    const val SCANNING = "SCANNING"
    const val SYNCING = "SYNCING"
}

@Entity(tableName = "sync_tasks")
data class SyncTask(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val localRootUri: String,
    val localRootPath: String = "",
    val localDisplayName: String,
    val remoteRootPath: String,
    val enabled: Boolean = true,
    val deleteMode: String = DeleteModes.KEEP_REMOTE_ON_LOCAL_DELETE,
    val wifiOnly: Boolean = true,
    val lastScanTime: Long? = null,
    val lastSyncTime: Long? = null,
    val lastRunAt: Long? = null,
    val runState: String = SyncRunStates.IDLE,
    val currentScanId: Long = 0,
    val sortOrder: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
