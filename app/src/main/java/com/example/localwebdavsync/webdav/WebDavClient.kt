package com.example.localwebdavsync.webdav

import java.io.IOException
import java.net.MalformedURLException
import java.net.URI
import java.net.URLDecoder
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory

class WebDavClient(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()
) {
    fun buildListRequest(
        baseUrl: String,
        remotePath: String,
        username: String? = null,
        appPassword: String? = null
    ): Request {
        val body = """<?xml version="1.0" encoding="utf-8"?><propfind xmlns="DAV:"/>"""
            .toRequestBody("application/xml; charset=utf-8".toMediaTypeOrNull())
        return authenticatedBuilder(
            url = buildWebDavUrl(baseUrl, remotePath),
            username = username,
            appPassword = appPassword
        )
            .method("PROPFIND", body)
            .header("Depth", "0")
            .build()
    }

    suspend fun testConnection(
        baseUrl: String,
        username: String,
        appPassword: String
    ): WebDavConnectionTestResult {
        if (baseUrl.isBlank()) {
            return WebDavConnectionTestResult.AddressError
        }
        if (username.isBlank() || appPassword.isBlank()) {
            return WebDavConnectionTestResult.AuthError
        }

        return withContext(Dispatchers.IO) {
            try {
                val request = buildListRequest(
                    baseUrl = baseUrl,
                    remotePath = "",
                    username = username,
                    appPassword = appPassword
                )
                executeCancellable(okHttpClient.newCall(request)).use { response ->
                    when (response.code) {
                        200, 207 -> WebDavConnectionTestResult.Success
                        401, 403 -> WebDavConnectionTestResult.AuthError
                        in 300..399 -> WebDavConnectionTestResult.AddressError
                        else -> WebDavConnectionTestResult.ServerError(response.code)
                    }
                }
            } catch (_: IllegalArgumentException) {
                WebDavConnectionTestResult.AddressError
            } catch (_: MalformedURLException) {
                WebDavConnectionTestResult.AddressError
            } catch (_: UnknownHostException) {
                WebDavConnectionTestResult.NetworkError
            } catch (_: SocketTimeoutException) {
                WebDavConnectionTestResult.NetworkError
            } catch (_: IOException) {
                WebDavConnectionTestResult.NetworkError
            }
        }
    }

    suspend fun ensureParentDirectories(
        baseUrl: String,
        remotePath: String,
        username: String,
        appPassword: String
    ): WebDavUploadResult {
        val parentSegments = remotePathSegments(remotePath).dropLast(1)
        var currentPath = ""
        for (segment in parentSegments) {
            currentPath = joinRemotePath(currentPath, segment)
            val result = mkcol(baseUrl, currentPath, username, appPassword)
            if (result is WebDavUploadResult.Failure) {
                return result
            }
        }
        return WebDavUploadResult.Success(remoteEtag = null, statusCode = null)
    }

    suspend fun upload(
        baseUrl: String,
        remotePath: String,
        username: String,
        appPassword: String,
        contentLength: Long,
        bodyFactory: () -> RequestBody
    ): WebDavUploadResult {
        return withContext(Dispatchers.IO) {
            try {
                val directoryResult = ensureParentDirectories(baseUrl, remotePath, username, appPassword)
                if (directoryResult is WebDavUploadResult.Failure) {
                    return@withContext directoryResult
                }

                val request = authenticatedBuilder(
                    url = buildWebDavUrl(baseUrl, remotePath),
                    username = username,
                    appPassword = appPassword
                )
                    .put(bodyFactory())
                    .header("Content-Length", contentLength.toString())
                    .build()

                executeCancellable(okHttpClient.newCall(request)).use { response ->
                    when (response.code) {
                        200, 201, 204 -> WebDavUploadResult.Success(
                            remoteEtag = response.header("ETag"),
                            statusCode = response.code
                        )
                        else -> WebDavUploadResult.Failure(
                            statusCode = response.code,
                            message = httpStatusMessage(response.code, "upload")
                        )
                    }
                }
            } catch (_: SocketTimeoutException) {
                WebDavUploadResult.Failure(statusCode = null, message = "上传超时")
            } catch (_: UnknownHostException) {
                WebDavUploadResult.Failure(statusCode = null, message = "网络错误")
            } catch (_: IOException) {
                WebDavUploadResult.Failure(statusCode = null, message = "网络错误")
            } catch (_: IllegalArgumentException) {
                WebDavUploadResult.Failure(statusCode = null, message = "WebDAV 地址或远程路径无效")
            }
        }
    }

    suspend fun createFolder(
        baseUrl: String,
        parentRemotePath: String,
        folderName: String,
        username: String,
        appPassword: String
    ): WebDavUploadResult {
        val safeName = folderName.trim().trim('/').replace("\\", "/")
        if (safeName.isBlank() || safeName.contains("/")) {
            return WebDavUploadResult.Failure(
                statusCode = null,
                message = "文件夹名称无效"
            )
        }
        return mkcol(
            baseUrl = baseUrl,
            remotePath = joinRemotePath(parentRemotePath, safeName),
            username = username,
            appPassword = appPassword
        )
    }


    suspend fun listFolders(
        baseUrl: String,
        remotePath: String,
        username: String,
        appPassword: String
    ): WebDavFolderListResult {
        return withContext(Dispatchers.IO) {
            try {
                val body = """<?xml version="1.0" encoding="utf-8"?><propfind xmlns="DAV:"><prop><resourcetype/></prop></propfind>"""
                    .toRequestBody("application/xml; charset=utf-8".toMediaTypeOrNull())
                val request = authenticatedBuilder(
                    url = buildWebDavUrl(baseUrl, remotePath),
                    username = username,
                    appPassword = appPassword
                )
                    .method("PROPFIND", body)
                    .header("Depth", "1")
                    .build()

                executeCancellable(okHttpClient.newCall(request)).use { response ->
                    if (response.code == 401 || response.code == 403) {
                        return@withContext WebDavFolderListResult.AuthError
                    }
                    if (response.code == 404) {
                        return@withContext WebDavFolderListResult.AddressError
                    }
                    if (response.code !in listOf(200, 207)) {
                        return@withContext WebDavFolderListResult.ServerError(response.code)
                    }

                    val xml = response.body?.string().orEmpty()
                    WebDavFolderListResult.Success(
                        folders = parseFolderList(
                            baseUrl = baseUrl,
                            currentRemotePath = remotePath,
                            xml = xml
                        )
                    )
                }
            } catch (_: IllegalArgumentException) {
                WebDavFolderListResult.AddressError
            } catch (_: MalformedURLException) {
                WebDavFolderListResult.AddressError
            } catch (_: UnknownHostException) {
                WebDavFolderListResult.NetworkError
            } catch (_: SocketTimeoutException) {
                WebDavFolderListResult.NetworkError
            } catch (_: XmlPullParserException) {
                WebDavFolderListResult.ServerError(207)
            } catch (_: IOException) {
                WebDavFolderListResult.NetworkError
            }
        }
    }

    suspend fun queryRemoteFileSize(
        baseUrl: String,
        remotePath: String,
        username: String,
        appPassword: String
    ): Long? {
        return withContext(Dispatchers.IO) {
            try {
                val body = """<?xml version="1.0" encoding="utf-8"?><propfind xmlns="DAV:"><prop><getcontentlength/></prop></propfind>"""
                    .toRequestBody("application/xml; charset=utf-8".toMediaTypeOrNull())
                val request = authenticatedBuilder(
                    url = buildWebDavUrl(baseUrl, remotePath),
                    username = username,
                    appPassword = appPassword
                )
                    .method("PROPFIND", body)
                    .header("Depth", "0")
                    .build()

                executeCancellable(okHttpClient.newCall(request)).use { response ->
                    if (response.code !in listOf(200, 207)) {
                        return@withContext null
                    }
                    parseRemoteFileSize(response.body?.string().orEmpty())
                }
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: MalformedURLException) {
                null
            } catch (_: UnknownHostException) {
                null
            } catch (_: SocketTimeoutException) {
                null
            } catch (_: XmlPullParserException) {
                null
            } catch (_: IOException) {
                null
            }
        }
    }

    suspend fun delete(
        baseUrl: String,
        remotePath: String,
        username: String,
        appPassword: String
    ): WebDavUploadResult {
        return withContext(Dispatchers.IO) {
            try {
                val request = authenticatedBuilder(
                    url = buildWebDavUrl(baseUrl, remotePath),
                    username = username,
                    appPassword = appPassword
                )
                    .delete()
                    .build()

                executeCancellable(okHttpClient.newCall(request)).use { response ->
                    when (response.code) {
                        200, 202, 204 -> WebDavUploadResult.Success(
                            remoteEtag = response.header("ETag"),
                            statusCode = response.code
                        )
                        else -> WebDavUploadResult.Failure(
                            statusCode = response.code,
                            message = httpStatusMessage(response.code, "delete")
                        )
                    }
                }
            } catch (_: SocketTimeoutException) {
                WebDavUploadResult.Failure(statusCode = null, message = "删除超时")
            } catch (_: UnknownHostException) {
                WebDavUploadResult.Failure(statusCode = null, message = "网络错误")
            } catch (_: IOException) {
                WebDavUploadResult.Failure(statusCode = null, message = "网络错误")
            } catch (_: IllegalArgumentException) {
                WebDavUploadResult.Failure(statusCode = null, message = "WebDAV 地址或远程路径无效")
            }
        }
    }

    suspend fun exists(
        baseUrl: String,
        remotePath: String,
        username: String,
        appPassword: String
    ): WebDavExistenceResult {
        return withContext(Dispatchers.IO) {
            try {
                val request = buildListRequest(
                    baseUrl = baseUrl,
                    remotePath = remotePath,
                    username = username,
                    appPassword = appPassword
                )
                executeCancellable(okHttpClient.newCall(request)).use { response ->
                    when (response.code) {
                        200 -> WebDavExistenceResult.Exists
                        207 -> {
                            val body = response.body?.string().orEmpty()
                            if (responseMentionsTarget(baseUrl, remotePath, body)) {
                                WebDavExistenceResult.Exists
                            } else {
                                WebDavExistenceResult.Missing
                            }
                        }
                        404 -> WebDavExistenceResult.Missing
                        401, 403 -> WebDavExistenceResult.Failure("WebDAV 认证失败")
                        else -> WebDavExistenceResult.Failure("检查远程文件时返回 HTTP ${response.code}。")
                    }
                }
            } catch (_: IllegalArgumentException) {
                WebDavExistenceResult.Failure("WebDAV 地址或远程路径无效")
            } catch (_: MalformedURLException) {
                WebDavExistenceResult.Failure("WebDAV 地址无效")
            } catch (_: UnknownHostException) {
                WebDavExistenceResult.Failure("网络错误")
            } catch (_: SocketTimeoutException) {
                WebDavExistenceResult.Failure("远程文件检查超时")
            } catch (_: XmlPullParserException) {
                WebDavExistenceResult.Failure("WebDAV 响应格式无效。")
            } catch (_: IOException) {
                WebDavExistenceResult.Failure("网络错误")
            }
        }
    }

    private fun responseMentionsTarget(baseUrl: String, remotePath: String, xml: String): Boolean {
        if (xml.isBlank()) return false
        val target = normalizeRemotePath(remotePath)
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xml.reader())
        var readingHref = false
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name.substringAfter(':') == "href") {
                        readingHref = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (readingHref) {
                        val candidate = hrefToRemotePath(baseUrl, parser.text.orEmpty())
                        if (candidate == target) return true
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.substringAfter(':') == "href") {
                        readingHref = false
                    }
                }
            }
            eventType = parser.next()
        }
        return false
    }

    private fun parseRemoteFileSize(xml: String): Long? {
        if (xml.isBlank()) return null
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xml.reader())
        var readingSize = false
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name.substringAfter(':') == "getcontentlength") {
                        readingSize = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (readingSize) {
                        parser.text.orEmpty().trim().toLongOrNull()?.let { return it }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.substringAfter(':') == "getcontentlength") {
                        readingSize = false
                    }
                }
            }
            eventType = parser.next()
        }
        return null
    }

    fun inputStreamRequestBody(
        contentLength: Long,
        openInputStream: () -> java.io.InputStream
    ): RequestBody {
        return object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
            override fun contentLength() = contentLength

            override fun writeTo(sink: BufferedSink) {
                openInputStream().use { input ->
                    sink.writeAll(input.source())
                }
            }
        }
    }

    suspend fun list(remotePath: String): WebDavResult {
        return WebDavResult.NotImplemented("列表功能暂未实现：$remotePath")
    }

    suspend fun download(remotePath: String, localPath: String): WebDavResult {
        return WebDavResult.NotImplemented("下载功能暂未实现：$remotePath -> $localPath")
    }

    private suspend fun mkcol(
        baseUrl: String,
        remotePath: String,
        username: String,
        appPassword: String
    ): WebDavUploadResult {
        return withContext(Dispatchers.IO) {
            try {
                val request = authenticatedBuilder(
                    url = buildWebDavUrl(baseUrl, remotePath),
                    username = username,
                    appPassword = appPassword
                )
                    .method("MKCOL", EMPTY_BODY)
                    .build()

                executeCancellable(okHttpClient.newCall(request)).use { response ->
                    when (response.code) {
                        200, 201, 204, 405 -> WebDavUploadResult.Success(
                            remoteEtag = null,
                            statusCode = response.code
                        )
                        else -> WebDavUploadResult.Failure(
                            statusCode = response.code,
                            message = httpStatusMessage(response.code, "upload")
                        )
                    }
                }
            } catch (_: SocketTimeoutException) {
                WebDavUploadResult.Failure(statusCode = null, message = "创建远程目录超时")
            } catch (_: IOException) {
                WebDavUploadResult.Failure(statusCode = null, message = "创建远程目录时网络错误")
            } catch (_: IllegalArgumentException) {
                WebDavUploadResult.Failure(statusCode = null, message = "远程目录路径无效")
            }
        }
    }

    private fun authenticatedBuilder(
        url: String,
        username: String?,
        appPassword: String?
    ): Request.Builder {
        val builder = Request.Builder().url(url)
        if (!username.isNullOrBlank() && !appPassword.isNullOrBlank()) {
            builder.header("Authorization", Credentials.basic(username.trim(), appPassword))
        }
        return builder
    }

    private fun buildWebDavUrl(baseUrl: String, remotePath: String): String {
        val normalizedBase = baseUrl.trim().trimEnd('/')
        val encodedPath = remotePathSegments(remotePath).joinToString("/") { it.percentEncodePathSegment() }
        return if (encodedPath.isBlank()) normalizedBase else "$normalizedBase/$encodedPath"
    }

    private fun remotePathSegments(remotePath: String): List<String> {
        return remotePath
            .replace("\\", "/")
            .split("/")
            .filter { it.isNotBlank() }
    }

    private fun joinRemotePath(parent: String, child: String): String {
        return listOf(parent, child)
            .flatMap { remotePathSegments(it) }
            .joinToString(prefix = "/", separator = "/")
    }

    private fun parseFolderList(
        baseUrl: String,
        currentRemotePath: String,
        xml: String
    ): List<WebDavFolder> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xml.reader())
        val currentPath = normalizeRemotePath(currentRemotePath)
        val folders = mutableListOf<WebDavFolder>()
        var inResponse = false
        var readingHref = false
        var href: String? = null
        var isCollection = false

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.substringAfter(':')) {
                        "response" -> {
                            inResponse = true
                            href = null
                            isCollection = false
                        }
                        "href" -> if (inResponse) readingHref = true
                        "collection" -> if (inResponse) isCollection = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (readingHref) href = parser.text
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name.substringAfter(':')) {
                        "href" -> readingHref = false
                        "response" -> {
                            val remotePath = href?.let { hrefToRemotePath(baseUrl, it) }
                            if (isCollection && !remotePath.isNullOrBlank() && remotePath != currentPath) {
                                folders += WebDavFolder(
                                    name = remotePath.trim('/').substringAfterLast('/').ifBlank { "/" },
                                    path = remotePath
                                )
                            }
                            inResponse = false
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        return folders.distinctBy { it.path }.sortedBy { it.name.lowercase() }
    }

    private fun hrefToRemotePath(baseUrl: String, href: String): String {
        val basePath = URI(baseUrl.trim()).path.orEmpty().trimEnd('/')
        val hrefPath = try {
            val uri = URI(href)
            if (uri.isAbsolute) uri.path.orEmpty() else URI("https://local").resolve(href).path.orEmpty()
        } catch (_: IllegalArgumentException) {
            href
        }
        val decoded = URLDecoder.decode(hrefPath, Charsets.UTF_8.name()).trimEnd('/')
        val withoutBase = if (basePath.isNotBlank() && decoded.startsWith(basePath)) {
            decoded.removePrefix(basePath)
        } else {
            decoded
        }
        return normalizeRemotePath(withoutBase)
    }

    private fun normalizeRemotePath(value: String): String {
        val normalized = remotePathSegments(value).joinToString("/")
        return if (normalized.isBlank()) "/" else "/$normalized"
    }

    private fun String.percentEncodePathSegment(): String {
        val bytes = toByteArray(Charsets.UTF_8)
        val builder = StringBuilder()
        bytes.forEach { raw ->
            val value = raw.toInt() and 0xff
            val char = value.toChar()
            if (
                char in 'A'..'Z' ||
                char in 'a'..'z' ||
                char in '0'..'9' ||
                char == '-' ||
                char == '_' ||
                char == '.' ||
                char == '~'
            ) {
                builder.append(char)
            } else {
                builder.append('%')
                builder.append(value.toString(16).uppercase().padStart(2, '0'))
            }
        }
        return builder.toString()
    }

    private fun httpStatusMessage(statusCode: Int, operation: String): String {
        val opText = when (operation) {
            "upload" -> "上传"
            "delete" -> "删除"
            else -> operation
        }
        return when (statusCode) {
            401 -> "401 未授权：用户名或应用密码错误。"
            403 -> "403 禁止访问：账户无权限${opText}到此路径。"
            404 -> "404 未找到：WebDAV 地址或远程路径不存在。"
            409 -> "409 冲突：无法${opText}远程路径。"
            423 -> "423 已锁定：远程文件或文件夹已被锁定。"
            429 -> "429 请求过多：已达到服务器速率限制。"
            500 -> "500 服务器错误。"
            502 -> "502 网关错误。"
            503 -> "503 请求次数过多，请稍后再试"
            504 -> "504 网关超时。"
            else -> "HTTP $statusCode ${opText}错误。"
        }
    }

    private suspend fun executeCancellable(call: Call): okhttp3.Response {
        coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) {
                call.cancel()
            }
        }
        try {
            return call.execute()
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            throw e
        }
    }

    private companion object {
        val EMPTY_BODY = ByteArray(0).toRequestBody(null)
    }
}

sealed interface WebDavConnectionTestResult {
    data object Success : WebDavConnectionTestResult
    data object AddressError : WebDavConnectionTestResult
    data object AuthError : WebDavConnectionTestResult
    data object NetworkError : WebDavConnectionTestResult
    data class ServerError(val httpStatusCode: Int) : WebDavConnectionTestResult
}

data class WebDavFolder(
    val name: String,
    val path: String
)

sealed interface WebDavFolderListResult {
    data class Success(val folders: List<WebDavFolder>) : WebDavFolderListResult
    data object AddressError : WebDavFolderListResult
    data object AuthError : WebDavFolderListResult
    data object NetworkError : WebDavFolderListResult
    data class ServerError(val httpStatusCode: Int) : WebDavFolderListResult
}

sealed interface WebDavUploadResult {
    data class Success(val remoteEtag: String?, val statusCode: Int? = null) : WebDavUploadResult
    data class Failure(val statusCode: Int?, val message: String) : WebDavUploadResult
}

sealed interface WebDavExistenceResult {
    data object Exists : WebDavExistenceResult
    data object Missing : WebDavExistenceResult
    data class Failure(val message: String) : WebDavExistenceResult
}

sealed interface WebDavResult {
    data object Success : WebDavResult
    data class NotImplemented(val message: String) : WebDavResult
    data class Failure(val message: String, val throwable: Throwable? = null) : WebDavResult
}
