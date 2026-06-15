package com.example.localwebdavsync.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.localwebdavsync.data.dao.LogDao
import com.example.localwebdavsync.data.dao.SyncFileRecordDao
import com.example.localwebdavsync.data.dao.SyncTaskDao
import com.example.localwebdavsync.data.entity.LogRecord
import com.example.localwebdavsync.data.entity.SyncFileRecord
import com.example.localwebdavsync.data.entity.SyncTask

@Database(
    entities = [SyncTask::class, SyncFileRecord::class, LogRecord::class],
    version = 16,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun syncTaskDao(): SyncTaskDao
    abstract fun syncFileRecordDao(): SyncFileRecordDao
    abstract fun logDao(): LogDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "local_webdav_sync.db"
                )
                    .addMigrations(
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16
                    )
                    .fallbackToDestructiveMigration(false)
                    .build()
                    .also { instance = it }
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_file_records ADD COLUMN contentHash TEXT")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_file_records ADD COLUMN deletedLocally INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE log_records ADD COLUMN taskName TEXT")
                db.execSQL("ALTER TABLE log_records ADD COLUMN eventType TEXT NOT NULL DEFAULT 'GENERAL'")
                db.execSQL("ALTER TABLE log_records ADD COLUMN filePath TEXT")
                db.execSQL("ALTER TABLE log_records ADD COLUMN remotePath TEXT")
                db.execSQL("ALTER TABLE log_records ADD COLUMN httpStatusCode INTEGER")
                db.execSQL("ALTER TABLE log_records ADD COLUMN errorMessage TEXT")
                db.execSQL("ALTER TABLE log_records ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE log_records ADD COLUMN summary TEXT")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_tasks ADD COLUMN lastScanTime INTEGER")
                db.execSQL("ALTER TABLE sync_tasks ADD COLUMN lastSyncTime INTEGER")
                db.execSQL("ALTER TABLE sync_file_records ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sync_file_records ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sync_file_records ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE sync_file_records SET createdAt = lastSeenAt WHERE createdAt = 0")
                db.execSQL("UPDATE sync_file_records SET updatedAt = lastSeenAt WHERE updatedAt = 0")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_tasks ADD COLUMN localRootPath TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_tasks ADD COLUMN currentScanId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sync_file_records ADD COLUMN lastScanId INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_tasks ADD COLUMN paused INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE log_records_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        taskId INTEGER,
                        eventType TEXT NOT NULL DEFAULT 'GENERAL',
                        filePath TEXT,
                        errorMessage TEXT,
                        retryCount INTEGER NOT NULL DEFAULT 0,
                        summary TEXT,
                        level TEXT NOT NULL DEFAULT 'INFO',
                        message TEXT NOT NULL,
                        detail TEXT,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (taskId) REFERENCES sync_tasks(id) ON DELETE SET NULL
                    )
                """)
                db.execSQL("""
                    INSERT INTO log_records_new (id, taskId, eventType, filePath, errorMessage, retryCount, summary, level, message, detail, createdAt)
                    SELECT id, taskId, eventType, filePath, errorMessage, retryCount, summary, level, message, detail, createdAt
                    FROM log_records
                """)
                db.execSQL("DROP TABLE log_records")
                db.execSQL("ALTER TABLE log_records_new RENAME TO log_records")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_log_records_taskId ON log_records (taskId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_log_records_createdAt ON log_records (createdAt)")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_tasks ADD COLUMN runState TEXT NOT NULL DEFAULT 'IDLE'")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE log_records ADD COLUMN taskName TEXT")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_tasks ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE sync_tasks SET sortOrder = -updatedAt")
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TEMP TABLE sync_file_records_backup AS SELECT * FROM sync_file_records")
                db.execSQL("CREATE TEMP TABLE log_records_backup AS SELECT * FROM log_records")
                db.execSQL("""
                    CREATE TABLE sync_tasks_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        localRootUri TEXT NOT NULL,
                        localRootPath TEXT NOT NULL,
                        localDisplayName TEXT NOT NULL,
                        remoteRootPath TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        deleteMode TEXT NOT NULL,
                        wifiOnly INTEGER NOT NULL,
                        lastScanTime INTEGER,
                        lastSyncTime INTEGER,
                        lastRunAt INTEGER,
                        runState TEXT NOT NULL,
                        currentScanId INTEGER NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """)
                db.execSQL("""
                    INSERT INTO sync_tasks_new (
                        id,
                        name,
                        localRootUri,
                        localRootPath,
                        localDisplayName,
                        remoteRootPath,
                        enabled,
                        deleteMode,
                        wifiOnly,
                        lastScanTime,
                        lastSyncTime,
                        lastRunAt,
                        runState,
                        currentScanId,
                        sortOrder,
                        createdAt,
                        updatedAt
                    )
                    SELECT
                        id,
                        name,
                        localRootUri,
                        localRootPath,
                        localDisplayName,
                        remoteRootPath,
                        enabled,
                        deleteMode,
                        wifiOnly,
                        lastScanTime,
                        lastSyncTime,
                        lastRunAt,
                        runState,
                        currentScanId,
                        sortOrder,
                        createdAt,
                        updatedAt
                    FROM sync_tasks
                """)
                db.execSQL("DROP TABLE sync_tasks")
                db.execSQL("ALTER TABLE sync_tasks_new RENAME TO sync_tasks")
                db.execSQL("DELETE FROM sync_file_records")
                db.execSQL("INSERT INTO sync_file_records SELECT * FROM sync_file_records_backup")
                db.execSQL("DELETE FROM log_records")
                db.execSQL("INSERT INTO log_records SELECT * FROM log_records_backup")
                db.execSQL("DROP TABLE sync_file_records_backup")
                db.execSQL("DROP TABLE log_records_backup")
            }
        }
    }
}
