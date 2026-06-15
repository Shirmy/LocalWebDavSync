package com.example.localwebdavsync

import android.content.Context
import com.example.localwebdavsync.data.database.AppDatabase
import com.example.localwebdavsync.repository.DeveloperLogRepository
import com.example.localwebdavsync.repository.LocalScanRepository
import com.example.localwebdavsync.repository.NetworkStateProvider
import com.example.localwebdavsync.repository.SettingsRepository
import com.example.localwebdavsync.repository.SyncTaskRepository
import com.example.localwebdavsync.repository.WebDavUploadRepository
import com.example.localwebdavsync.sync.LocalFolderScanner
import com.example.localwebdavsync.webdav.WebDavClient

class AppContainer(context: Context) {
    private val database = AppDatabase.getInstance(context)

    val settingsRepository = SettingsRepository(context)
    val networkStateProvider = NetworkStateProvider(context.applicationContext)
    val developerLogRepository = DeveloperLogRepository(
        logDao = database.logDao(),
        settingsRepository = settingsRepository
    )
    val webDavClient = WebDavClient()
    val localFolderScanner = LocalFolderScanner(context.applicationContext)
    val syncTaskRepository = SyncTaskRepository(
        syncTaskDao = database.syncTaskDao(),
        syncFileRecordDao = database.syncFileRecordDao()
    )
    val localScanRepository = LocalScanRepository(
        context = context.applicationContext,
        syncFileRecordDao = database.syncFileRecordDao(),
        syncTaskDao = database.syncTaskDao(),
        localFolderScanner = localFolderScanner,
        networkStateProvider = networkStateProvider,
        developerLogRepository = developerLogRepository
    )
    val webDavUploadRepository = WebDavUploadRepository(
        context = context.applicationContext,
        settingsRepository = settingsRepository,
        syncFileRecordDao = database.syncFileRecordDao(),
        syncTaskDao = database.syncTaskDao(),
        networkStateProvider = networkStateProvider,
        webDavClient = webDavClient,
        developerLogRepository = developerLogRepository
    )}
