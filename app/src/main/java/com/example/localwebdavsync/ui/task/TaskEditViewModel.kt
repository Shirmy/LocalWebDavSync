package com.example.localwebdavsync.ui.task

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localwebdavsync.data.entity.DeleteModes
import com.example.localwebdavsync.data.entity.SyncTask
import com.example.localwebdavsync.repository.SettingsRepository
import com.example.localwebdavsync.repository.SyncTaskRepository
import com.example.localwebdavsync.webdav.WebDavClient
import com.example.localwebdavsync.webdav.WebDavFolder
import com.example.localwebdavsync.webdav.WebDavFolderListResult
import com.example.localwebdavsync.webdav.WebDavUploadResult
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TaskEditUiState(
    val taskId: Long? = null,
    val name: String = "",
    val localRootUri: String = "",
    val localRootPath: String = "",
    val localDisplayName: String = "",
    val remoteRootPath: String = "",
    val enabled: Boolean = true,
    val deleteMode: String = DeleteModes.KEEP_REMOTE_ON_LOCAL_DELETE,
    val wifiOnly: Boolean = true,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: TaskEditError? = null,
    val localBrowserOpen: Boolean = false,
    val localBrowserPath: String = "",
    val localFolders: List<LocalFolderItem> = emptyList(),
    val localBrowserMessage: String? = null,
    val remoteBrowserOpen: Boolean = false,
    val remoteBrowserPath: String = "/",
    val remoteFolders: List<WebDavFolder> = emptyList(),
    val remoteBrowserLoading: Boolean = false,
    val remoteBrowserPathLoaded: Boolean = false,
    val remoteBrowserMessage: String? = null,
    val newRemoteFolderName: String = "",
    val creatingRemoteFolder: Boolean = false,
    val remoteCreateFolderOpen: Boolean = false,
    val remoteCreateFolderMessage: String? = null
)

data class LocalFolderItem(
    val name: String,
    val path: String
)

enum class TaskEditError {
    NAME_REQUIRED,
    LOCAL_FOLDER_REQUIRED,
    REMOTE_FOLDER_REQUIRED
}

class TaskEditViewModel(
    private val repository: SyncTaskRepository,
    private val settingsRepository: SettingsRepository,
    private val webDavClient: WebDavClient,
    private val taskId: Long?
) : ViewModel() {
    private val _uiState = MutableStateFlow(TaskEditUiState(taskId = taskId))
    val uiState: StateFlow<TaskEditUiState> = _uiState.asStateFlow()

    init {
        if (taskId != null && taskId > 0L) {
            viewModelScope.launch {
                repository.getTask(taskId)?.let { task ->
                    _uiState.value = TaskEditUiState(
                        taskId = task.id,
                        name = task.name,
                        localRootUri = task.localRootUri,
                        localRootPath = task.localRootPath,
                        localDisplayName = task.localDisplayName,
                        remoteRootPath = task.remoteRootPath,
                        enabled = task.enabled,
                        deleteMode = DeleteModes.normalize(task.deleteMode),
                        wifiOnly = task.wifiOnly
                    )
                }
            }
        }
    }

    fun updateName(value: String) {
        _uiState.value = _uiState.value.copy(name = value, error = null)
    }

    fun updateLocalRootPath(value: String) {
        _uiState.value = _uiState.value.copy(
            localRootPath = value,
            localRootUri = "",
            localDisplayName = value,
            error = null
        )
    }

    fun updateRemoteRootPath(value: String) {
        _uiState.value = _uiState.value.copy(remoteRootPath = value, error = null)
    }

    fun openLocalBrowser(startPath: String) {
        val requestedPath = startPath.trim().ifBlank { DEFAULT_LOCAL_ROOT }
        val path = requestedPath.takeIf { File(it).isDirectory } ?: DEFAULT_LOCAL_ROOT
        _uiState.value = _uiState.value.copy(
            localBrowserOpen = true,
            localBrowserPath = path,
            localBrowserMessage = null
        )
        loadLocalFolders(path)
    }

    fun closeLocalBrowser() {
        _uiState.value = _uiState.value.copy(localBrowserOpen = false, localBrowserMessage = null)
    }

    fun chooseCurrentLocalFolder() {
        _uiState.value = _uiState.value.copy(
            localRootPath = _uiState.value.localBrowserPath,
            localDisplayName = _uiState.value.localBrowserPath,
            localRootUri = "",
            localBrowserOpen = false,
            localBrowserMessage = null,
            error = null
        )
    }

    fun enterLocalFolder(path: String) {
        _uiState.value = _uiState.value.copy(localBrowserPath = path, localBrowserMessage = null)
        loadLocalFolders(path)
    }

    fun goLocalParent() {
        val parent = File(_uiState.value.localBrowserPath).parentFile
        if (parent != null) {
            enterLocalFolder(parent.absolutePath)
        }
    }

    fun openRemoteBrowser() {
        val startPath = _uiState.value.remoteRootPath.ifBlank { "/" }
        _uiState.value = _uiState.value.copy(
            remoteBrowserOpen = true,
            remoteBrowserPath = normalizeRemotePath(startPath),
            remoteBrowserPathLoaded = false,
            remoteBrowserMessage = null
        )
        loadRemoteFolders(_uiState.value.remoteBrowserPath)
    }

    fun closeRemoteBrowser() {
        _uiState.value = _uiState.value.copy(
            remoteBrowserOpen = false,
            remoteBrowserMessage = null,
            remoteCreateFolderOpen = false,
            remoteCreateFolderMessage = null
        )
    }

    fun chooseCurrentRemoteFolder() {
        if (!_uiState.value.remoteBrowserPathLoaded) {
            _uiState.value = _uiState.value.copy(remoteBrowserMessage = "请先成功读取当前 WebDAV 文件夹。")
            return
        }
        _uiState.value = _uiState.value.copy(
            remoteRootPath = normalizeRemotePath(_uiState.value.remoteBrowserPath),
            remoteBrowserOpen = false,
            remoteBrowserMessage = null,
            remoteCreateFolderOpen = false,
            remoteCreateFolderMessage = null,
            error = null
        )
    }

    fun enterRemoteFolder(path: String) {
        val normalized = normalizeRemotePath(path)
        _uiState.value = _uiState.value.copy(
            remoteBrowserPath = normalized,
            remoteBrowserPathLoaded = false,
            remoteBrowserMessage = null
        )
        loadRemoteFolders(normalized)
    }

    fun goRemoteParent() {
        val current = _uiState.value.remoteBrowserPath.trim('/')
        val parent = current.substringBeforeLast('/', missingDelimiterValue = "")
        enterRemoteFolder(if (parent.isBlank()) "/" else "/$parent")
    }

    fun updateNewRemoteFolderName(value: String) {
        _uiState.value = _uiState.value.copy(
            newRemoteFolderName = value,
            remoteCreateFolderMessage = null
        )
    }

    fun openCreateRemoteFolderDialog() {
        if (!_uiState.value.remoteBrowserPathLoaded) {
            _uiState.value = _uiState.value.copy(remoteBrowserMessage = "请先成功读取当前 WebDAV 文件夹。")
            return
        }
        _uiState.value = _uiState.value.copy(
            remoteCreateFolderOpen = true,
            remoteCreateFolderMessage = null,
            remoteBrowserMessage = null,
            newRemoteFolderName = ""
        )
    }

    fun closeCreateRemoteFolderDialog() {
        if (_uiState.value.creatingRemoteFolder) return
        _uiState.value = _uiState.value.copy(
            remoteCreateFolderOpen = false,
            remoteCreateFolderMessage = null
        )
    }

    fun createRemoteFolder() {
        val state = _uiState.value
        val folderName = state.newRemoteFolderName.trim()
        if (folderName.isBlank()) {
            _uiState.value = state.copy(remoteCreateFolderMessage = "请输入新文件夹名称。")
            return
        }
        if (folderName.contains("/") || folderName.contains("\\")) {
            _uiState.value = state.copy(remoteCreateFolderMessage = "文件夹名称不能包含斜杠。")
            return
        }

        val settings = settingsRepository.readSettings()
        if (settings.username.isBlank() || settings.appPassword.isBlank()) {
            _uiState.value = state.copy(remoteCreateFolderMessage = "请先在 WebDAV 设置页保存用户名和应用密码。")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                creatingRemoteFolder = true,
                remoteCreateFolderMessage = null
            )
            val result = webDavClient.createFolder(
                baseUrl = settings.baseUrl,
                parentRemotePath = state.remoteBrowserPath,
                folderName = folderName,
                username = settings.username,
                appPassword = settings.appPassword
            )
            when (result) {
                is WebDavUploadResult.Success -> {
                    val newPath = normalizeRemotePath("${state.remoteBrowserPath.trimEnd('/')}/$folderName")
                    _uiState.value = _uiState.value.copy(
                        creatingRemoteFolder = false,
                        remoteCreateFolderOpen = false,
                        remoteCreateFolderMessage = null,
                        newRemoteFolderName = "",
                        remoteBrowserPath = newPath,
                        remoteBrowserPathLoaded = false,
                        remoteBrowserMessage = "文件夹已创建。"
                    )
                    loadRemoteFolders(newPath)
                }
                is WebDavUploadResult.Failure -> {
                    _uiState.value = _uiState.value.copy(
                        creatingRemoteFolder = false,
                        remoteCreateFolderMessage = result.message
                    )
                }
            }
        }
    }

    fun updateDeleteMode(value: String) {
        _uiState.value = _uiState.value.copy(deleteMode = value)
    }

    fun updateWifiOnly(value: Boolean) {
        _uiState.value = _uiState.value.copy(wifiOnly = value)
    }

    fun save() {
        val state = _uiState.value
        val trimmedName = state.name.trim()
        val trimmedRemoteRootPath = state.remoteRootPath.trim()
        val isExistingTask = (state.taskId ?: 0L) > 0L
        when {
            trimmedName.isBlank() -> {
                _uiState.value = state.copy(error = TaskEditError.NAME_REQUIRED)
                return
            }
            !isExistingTask && state.localRootPath.trim().isBlank() -> {
                _uiState.value = state.copy(error = TaskEditError.LOCAL_FOLDER_REQUIRED)
                return
            }
            !isExistingTask && trimmedRemoteRootPath.isBlank() -> {
                _uiState.value = state.copy(error = TaskEditError.REMOTE_FOLDER_REQUIRED)
                return
            }
        }

        viewModelScope.launch {
            _uiState.value = state.copy(saving = true, error = null)
            val existingTask = state.taskId
                ?.takeIf { it > 0L }
                ?.let { repository.getTask(it) }
            repository.saveTask(
                SyncTask(
                    id = state.taskId ?: 0L,
                    name = trimmedName,
                    localRootUri = existingTask?.localRootUri ?: "",
                    localRootPath = existingTask?.localRootPath ?: state.localRootPath.trim(),
                    localDisplayName = existingTask?.localDisplayName ?: state.localRootPath.trim(),
                    remoteRootPath = existingTask?.remoteRootPath ?: normalizeRemotePath(trimmedRemoteRootPath),
                    enabled = existingTask?.enabled ?: state.enabled,
                    deleteMode = DeleteModes.normalize(state.deleteMode),
                    wifiOnly = state.wifiOnly
                )
            )
            _uiState.value = _uiState.value.copy(saving = false, saved = true)
        }
    }

    private fun normalizeRemotePath(value: String): String {
        val normalized = value.trim().trim('/').replace("\\", "/")
        return if (normalized.isBlank()) "/" else "/$normalized"
    }

    private fun loadRemoteFolders(path: String) {
        val settings = settingsRepository.readSettings()
        if (settings.username.isBlank() || settings.appPassword.isBlank()) {
            _uiState.value = _uiState.value.copy(
                remoteFolders = emptyList(),
                remoteBrowserLoading = false,
                remoteBrowserPathLoaded = false,
                remoteBrowserMessage = "请先在 WebDAV 设置页保存用户名和应用密码。"
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                remoteBrowserLoading = true,
                remoteBrowserPathLoaded = false,
                remoteBrowserMessage = null
            )
            val result = webDavClient.listFolders(
                baseUrl = settings.baseUrl,
                remotePath = path,
                username = settings.username,
                appPassword = settings.appPassword
            )
            _uiState.value = when (result) {
                is WebDavFolderListResult.Success -> _uiState.value.copy(
                    remoteFolders = result.folders,
                    remoteBrowserLoading = false,
                    remoteBrowserPathLoaded = true,
                    remoteBrowserMessage = if (result.folders.isEmpty()) "当前目录没有子文件夹。" else null
                )
                WebDavFolderListResult.AddressError -> _uiState.value.copy(
                    remoteFolders = emptyList(),
                    remoteBrowserLoading = false,
                    remoteBrowserPathLoaded = false,
                    remoteBrowserMessage = "WebDAV 地址或路径错误。"
                )
                WebDavFolderListResult.AuthError -> _uiState.value.copy(
                    remoteFolders = emptyList(),
                    remoteBrowserLoading = false,
                    remoteBrowserPathLoaded = false,
                    remoteBrowserMessage = "用户名或应用密码错误。"
                )
                WebDavFolderListResult.NetworkError -> _uiState.value.copy(
                    remoteFolders = emptyList(),
                    remoteBrowserLoading = false,
                    remoteBrowserPathLoaded = false,
                    remoteBrowserMessage = "网络错误，请稍后重试。"
                )
                is WebDavFolderListResult.ServerError -> _uiState.value.copy(
                    remoteFolders = emptyList(),
                    remoteBrowserLoading = false,
                    remoteBrowserPathLoaded = false,
                    remoteBrowserMessage = "服务器错误，HTTP ${result.httpStatusCode}。"
                )
            }
        }
    }

    private fun loadLocalFolders(path: String) {
        val folder = File(path)
        if (!folder.exists() || !folder.isDirectory) {
            _uiState.value = _uiState.value.copy(
                localFolders = emptyList(),
                localBrowserMessage = "当前路径不可访问或不是文件夹"
            )
            return
        }
        val folders = folder.listFiles()
            ?.filter { it.isDirectory && !it.isHidden }
            ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            ?.map { LocalFolderItem(name = it.name, path = it.absolutePath) }
            .orEmpty()
        _uiState.value = _uiState.value.copy(
            localFolders = folders,
            localBrowserMessage = if (folders.isEmpty()) "当前文件夹没有可进入的子文件夹" else null
        )
    }

    class Factory(
        private val repository: SyncTaskRepository,
        private val settingsRepository: SettingsRepository,
        private val webDavClient: WebDavClient,
        private val taskId: Long?
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(TaskEditViewModel::class.java)) {
                return TaskEditViewModel(repository, settingsRepository, webDavClient, taskId) as T
            }
            throw IllegalArgumentException("未知 ViewModel 类型：${modelClass.name}")
        }
    }

    private companion object {
        const val DEFAULT_LOCAL_ROOT = "/storage/emulated/0"
    }
}
