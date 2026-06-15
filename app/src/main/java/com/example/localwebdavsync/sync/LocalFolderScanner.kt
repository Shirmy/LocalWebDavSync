package com.example.localwebdavsync.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.localwebdavsync.data.entity.SyncTask
import java.io.File

data class LocalScannedFile(
    val localRelativePath: String,
    val remotePath: String,
    val uri: Uri,
    val filePath: String? = null,
    val fileSize: Long,
    val lastModifiedAt: Long
)

class LocalFolderScanner(
    private val context: Context
) {
    fun scan(
        task: SyncTask,
        onProgress: (ScannedProgress) -> Unit = {},
        checkCancelled: () -> Unit = {}
    ): List<LocalScannedFile> {
        checkCancelled()
        if (task.localRootPath.isNotBlank()) {
            return scanFileRoot(task, onProgress, checkCancelled)
        }
        val rootUri = Uri.parse(task.localRootUri)
        val root = DocumentFile.fromTreeUri(context, rootUri)
            ?: throw LocalScanException("无法打开本地文件夹。")
        if (!root.exists() || !root.isDirectory) {
            throw LocalScanException("本地文件夹不可用。")
        }

        val totalFiles = countDocumentFiles(root, checkCancelled)
        onProgress(ScannedProgress(filesScanned = 0, totalFiles = totalFiles, latestPath = null))
        val files = mutableListOf<LocalScannedFile>()
        walk(
            task = task,
            folder = root,
            relativePrefix = "",
            files = files,
            totalFiles = totalFiles,
            onProgress = onProgress,
            checkCancelled = checkCancelled
        )
        checkCancelled()
        onProgress(ScannedProgress(filesScanned = files.size, totalFiles = totalFiles, latestPath = null))
        return files
    }

    private fun scanFileRoot(
        task: SyncTask,
        onProgress: (ScannedProgress) -> Unit,
        checkCancelled: () -> Unit
    ): List<LocalScannedFile> {
        val root = File(task.localRootPath)
        if (!root.exists() || !root.isDirectory) {
            throw LocalScanException("本地文件夹不可用。")
        }
        val rootFile = root.canonicalFile
        val totalFiles = countDiskFiles(rootFile, checkCancelled)
        onProgress(ScannedProgress(filesScanned = 0, totalFiles = totalFiles, latestPath = null))
        val files = mutableListOf<LocalScannedFile>()
        rootFile.walkTopDown()
            .onEnter {
                checkCancelled()
                true
            }
            .filter { it.isFile }
            .forEach { file ->
                checkCancelled()
                val relativePath = file.relativeTo(rootFile).path
                    .replace(File.separatorChar, '/')
                if (relativePath.isBlank()) return@forEach
                files += LocalScannedFile(
                    localRelativePath = relativePath,
                    remotePath = buildRemotePath(task.remoteRootPath, relativePath),
                    uri = Uri.fromFile(file),
                    filePath = file.absolutePath,
                    fileSize = file.length(),
                    lastModifiedAt = file.lastModified().takeIf { it > 0L } ?: 0L
                )
                if (files.size % PROGRESS_BATCH_SIZE == 0) {
                    onProgress(
                        ScannedProgress(
                            filesScanned = files.size,
                            totalFiles = totalFiles,
                            latestPath = relativePath
                        )
                    )
                }
            }
        checkCancelled()
        onProgress(ScannedProgress(filesScanned = files.size, totalFiles = totalFiles, latestPath = null))
        return files
    }

    private fun walk(
        task: SyncTask,
        folder: DocumentFile,
        relativePrefix: String,
        files: MutableList<LocalScannedFile>,
        totalFiles: Int,
        onProgress: (ScannedProgress) -> Unit,
        checkCancelled: () -> Unit
    ) {
        checkCancelled()
        folder.listFiles().forEach { child ->
            checkCancelled()
            val name = child.name.orEmpty()
            if (name.isBlank()) return@forEach

            val relativePath = if (relativePrefix.isBlank()) {
                name
            } else {
                "$relativePrefix/$name"
            }

            when {
                child.isDirectory -> walk(task, child, relativePath, files, totalFiles, onProgress, checkCancelled)
                child.isFile -> {
                    files += LocalScannedFile(
                        localRelativePath = relativePath,
                        remotePath = buildRemotePath(task.remoteRootPath, relativePath),
                        uri = child.uri,
                        filePath = null,
                        fileSize = child.length(),
                        lastModifiedAt = child.lastModified().takeIf { it > 0L } ?: 0L
                    )
                    if (files.size % PROGRESS_BATCH_SIZE == 0) {
                        onProgress(
                            ScannedProgress(
                                filesScanned = files.size,
                                totalFiles = totalFiles,
                                latestPath = relativePath
                            )
                        )
                    }
                }
            }
        }
    }

    private fun countDiskFiles(root: File, checkCancelled: () -> Unit): Int {
        return root.walkTopDown()
            .onEnter {
                checkCancelled()
                true
            }
            .count { file ->
                checkCancelled()
                file.isFile
            }
    }

    private fun countDocumentFiles(folder: DocumentFile, checkCancelled: () -> Unit): Int {
        checkCancelled()
        var count = 0
        folder.listFiles().forEach { child ->
            checkCancelled()
            when {
                child.isDirectory -> count += countDocumentFiles(child, checkCancelled)
                child.isFile -> count += 1
            }
        }
        return count
    }

    private fun buildRemotePath(remoteRootPath: String, localRelativePath: String): String {
        val root = remoteRootPath.trim().trim('/').replace("\\", "/")
        val relative = localRelativePath.trim('/').replace("\\", "/")
        return if (root.isBlank()) {
            "/$relative"
        } else {
            "/$root/$relative"
        }
    }

    private companion object {
        const val PROGRESS_BATCH_SIZE = 50
    }
}

data class ScannedProgress(
    val filesScanned: Int,
    val totalFiles: Int?,
    val latestPath: String?
)

class LocalScanException(message: String) : Exception(message)
