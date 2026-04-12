package com.nuvio.app.features.downloads

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSURL
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

private val downloadHttpClient = HttpClient(Darwin) {
    install(HttpTimeout) {
        requestTimeoutMillis = 60_000
        connectTimeoutMillis = 60_000
        socketTimeoutMillis = 60_000
    }
    expectSuccess = false
}

@OptIn(ExperimentalForeignApi::class)
internal actual object DownloadsPlatformDownloader {
    actual fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
    ): DownloadsTaskHandle {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)

        scope.launch {
            val downloadsDirectory = downloadsDirectoryPath()
            val destinationPath = "$downloadsDirectory/${request.destinationFileName}"
            val tempPath = "$downloadsDirectory/${request.destinationFileName}.part"

            try {
                var resumeFromBytes = fileSizeOrNull(tempPath)?.coerceAtLeast(0L) ?: 0L

                suspend fun performRequest(rangeStart: Long?) = downloadHttpClient.get(request.sourceUrl) {
                    request.sourceHeaders.forEach { (key, value) ->
                        header(key, value)
                    }
                    if (rangeStart != null && rangeStart > 0L) {
                        header("Range", "bytes=$rangeStart-")
                    }
                }

                var attemptedRangeRequest = resumeFromBytes > 0L
                var response = performRequest(if (attemptedRangeRequest) resumeFromBytes else null)

                if (attemptedRangeRequest && response.status.value == 416) {
                    removePathIfExists(tempPath)
                    resumeFromBytes = 0L
                    attemptedRangeRequest = false
                    response = performRequest(null)
                }

                if (!response.status.isSuccess()) {
                    error("Request failed with HTTP ${response.status.value}")
                }

                val isPartialResume = attemptedRangeRequest && response.status.value == 206 && resumeFromBytes > 0L
                val appendToTemp = isPartialResume
                val startingBytes = if (appendToTemp) resumeFromBytes else 0L

                if (!appendToTemp) {
                    removePathIfExists(tempPath)
                }

                val totalBytes = resolveTotalBytes(
                    startingBytes = startingBytes,
                    isPartialResume = isPartialResume,
                    contentRangeHeader = response.headers["Content-Range"],
                    contentLength = response.headers["Content-Length"]?.toLongOrNull()?.takeIf { it > 0L },
                )
                val channel = response.bodyAsChannel()
                val wrote = writeChannelToFile(
                    channel = channel,
                    path = tempPath,
                    append = appendToTemp,
                    initialDownloadedBytes = startingBytes,
                    totalBytes = totalBytes,
                    onProgress = onProgress,
                )
                if (!wrote) {
                    error("Failed to write download file")
                }

                removePathIfExists(destinationPath)
                val moved = NSFileManager.defaultManager.moveItemAtPath(
                    srcPath = tempPath,
                    toPath = destinationPath,
                    error = null,
                )
                if (!moved) {
                    error("Failed to finalize download file")
                }

                val localFileUri = NSURL.fileURLWithPath(destinationPath).absoluteString ?: "file://$destinationPath"
                val finalSize = fileSizeOrNull(destinationPath)
                onSuccess(localFileUri, totalBytes ?: finalSize)
            } catch (error: Throwable) {
                onFailure(error.message ?: "Download failed")
            }
        }

        return IosDownloadsTaskHandle(job)
    }

    actual fun removeFile(localFileUri: String?): Boolean {
        if (localFileUri.isNullOrBlank()) return false
        val path = localFileUri.toLocalPath() ?: return false
        return removePathIfExists(path)
    }

    actual fun removePartialFile(destinationFileName: String): Boolean {
        val tempPath = "${downloadsDirectoryPath()}/$destinationFileName.part"
        return removePathIfExists(tempPath)
    }
}

private class IosDownloadsTaskHandle(
    private val job: Job,
) : DownloadsTaskHandle {
    override fun cancel() {
        job.cancel()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun downloadsDirectoryPath(): String {
    val root = NSHomeDirectory().trimEnd('/')
    val path = "$root/Documents/nuvio_downloads"
    NSFileManager.defaultManager.createDirectoryAtPath(
        path = path,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    return path
}

@OptIn(ExperimentalForeignApi::class)
private fun removePathIfExists(path: String): Boolean {
    if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return true
    return NSFileManager.defaultManager.removeItemAtPath(path, null)
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun writeChannelToFile(
    channel: ByteReadChannel,
    path: String,
    append: Boolean,
    initialDownloadedBytes: Long,
    totalBytes: Long?,
    onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
): Boolean {
    val file = fopen(path, if (append) "ab" else "wb") ?: return false
    val buffer = ByteArray(16 * 1024)
    var downloadedBytes = initialDownloadedBytes
    onProgress(downloadedBytes, totalBytes)

    return try {
        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read < 0) break
            if (read == 0) continue

            val wroteChunk = buffer.usePinned { pinned ->
                val written = fwrite(
                    pinned.addressOf(0),
                    1.convert(),
                    read.convert(),
                    file,
                )
                written.toInt() == read
            }
            if (!wroteChunk) {
                return false
            }

            downloadedBytes += read.toLong()
            onProgress(downloadedBytes, totalBytes)
        }
        true
    } finally {
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun fileSizeOrNull(path: String): Long? {
    val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)
    val value = attrs?.get("NSFileSize")
    return when (value) {
        is Long -> value
        is Number -> value.toLong()
        else -> null
    }
}

private fun String.toLocalPath(): String? {
    if (startsWith("file://")) {
        return removePrefix("file://")
    }
    return takeIf { it.isNotBlank() }
}

private fun resolveTotalBytes(
    startingBytes: Long,
    isPartialResume: Boolean,
    contentRangeHeader: String?,
    contentLength: Long?,
): Long? {
    parseContentRangeTotal(contentRangeHeader)?.let { return it }
    val normalizedLength = contentLength?.takeIf { it > 0L } ?: return null
    return if (isPartialResume && startingBytes > 0L) {
        startingBytes + normalizedLength
    } else {
        normalizedLength
    }
}

private fun parseContentRangeTotal(headerValue: String?): Long? {
    val value = headerValue?.trim().orEmpty()
    if (value.isBlank()) return null
    val slashIndex = value.lastIndexOf('/')
    if (slashIndex == -1 || slashIndex == value.lastIndex) return null
    val totalPart = value.substring(slashIndex + 1).trim()
    if (totalPart == "*") return null
    return totalPart.toLongOrNull()?.takeIf { it > 0L }
}
