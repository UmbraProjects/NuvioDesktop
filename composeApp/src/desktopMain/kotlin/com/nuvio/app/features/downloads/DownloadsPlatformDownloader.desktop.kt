package com.nuvio.app.features.downloads

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

private const val DOWNLOAD_PROGRESS_INTERVAL_NANOS = 500_000_000L
private const val PARTIAL_SEGMENT_FILE_COUNT = 4

private val desktopDownloadHttpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(60, TimeUnit.SECONDS)
    // This is an inactivity timeout for each socket read, not a deadline for the complete file.
    // Multi-gigabyte downloads routinely take much longer than the old 60-second request timeout.
    .readTimeout(2, TimeUnit.MINUTES)
    .followRedirects(true)
    .followSslRedirects(true)
    // HTTP/2 would multiplex all four byte ranges over one TCP socket. TorBox's multi-connection
    // acceleration needs independent connections, so keep this dedicated large-file client on h1.
    .protocols(listOf(Protocol.HTTP_1_1))
    .build()

internal actual object DownloadsPlatformDownloader {
    private val log = Logger.withTag("DownloadsDownloader")

    /** Resolves only an explicitly configured Local Library root. */
    private fun baseDirFor(destinationDirOverride: String?): File? {
        val override = destinationDirOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        return File(override)
    }

    actual fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String, isTransient: Boolean) -> Unit,
    ): DownloadsTaskHandle {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)

        scope.launch {
            if (
                request.sourceUrl.hasExecutableDownloadExtension() ||
                request.destinationFileName.hasExecutableDownloadExtension()
            ) {
                onFailure("Executable file formats are not allowed", false)
                return@launch
            }
            if (request.destinationDirOverride.isNullOrBlank()) {
                onFailure("A Local Library destination is required", false)
                return@launch
            }
            val baseDir = baseDirFor(request.destinationDirOverride) ?: return@launch
            val destination = File(baseDir, request.destinationFileName)
            val tempFile = File(baseDir, "${request.destinationFileName}.part")
            // Nested library layouts ("Show (2020)/Season 01/…") need their parent dirs to exist.
            destination.parentFile?.mkdirs()

            try {
                var attempt = 0
                var completedTotalBytes: Long? = null
                while (true) {
                    try {
                        completedTotalBytes = downloadAttempt(
                            request = request,
                            tempFile = tempFile,
                            onProgress = onProgress,
                        )
                        break
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        attempt += 1
                        if (attempt >= MAX_DOWNLOAD_ATTEMPTS || !error.isRetryableDownloadFailure()) {
                            throw error
                        }
                        val retryDelayMs = RETRY_BASE_DELAY_MS * (1L shl (attempt - 1))
                        log.w {
                            "Transient download failure (${error.message}); " +
                                "retry $attempt/$MAX_DOWNLOAD_ATTEMPTS from " +
                                "${downloadedPartialBytes(tempFile)} bytes"
                        }
                        delay(retryDelayMs)
                    }
                }

                if (!tempFile.hasRecognizedVideoSignature()) {
                    throw RejectedDownloadException(
                        "Downloaded content is not a recognized video container",
                    )
                }
                if (destination.exists()) {
                    destination.delete()
                }
                if (!tempFile.renameTo(destination)) {
                    tempFile.copyTo(destination, overwrite = true)
                    tempFile.delete()
                }

                val finalSize = destination.length()
                // Plain path, not a URI — see resolveLocalFileUri: this value is handed straight to
                // mpv and to external players, which both read a file: URI as a relative path.
                onSuccess(destination.absolutePath, completedTotalBytes ?: finalSize)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (error is RejectedDownloadException) {
                    removePartialDownloadFiles(tempFile)
                }
                log.w(error) { "Download failed for ${request.destinationFileName}" }
                onFailure(error.message ?: "Download failed", error.isRetryableDownloadFailure())
            }
        }

        return DesktopDownloadsTaskHandle(job)
    }

    actual fun updateAutomaticBandwidthLimit(bytesPerSecond: Long?) {
        AggregateDownloadRateLimiter.updateLimit(bytesPerSecond)
    }

    actual fun removeFile(localFileUri: String?): Boolean {
        if (localFileUri.isNullOrBlank()) return false
        val file = localFileUri.toLocalFileOrNull() ?: return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    actual fun removePartialFile(destinationFileName: String, destinationDirOverride: String?): Boolean {
        val baseDir = baseDirFor(destinationDirOverride) ?: return true
        val tempFile = File(baseDir, "$destinationFileName.part")
        return removePartialDownloadFiles(tempFile)
    }

    /**
     * Returns a plain absolute path, NOT a `file:` URI.
     *
     * The value travels on to the players as-is: mpv (via the native bridge) and every external
     * player take a filesystem path, and a `file:/A:/…%20…` URI is read as a *relative* path — it
     * gets appended to the app's working directory and fails to open. Android's actual still
     * returns a content:// URI, which is what its player expects; this contract is per-platform.
     *
     * [toLocalFileOrNull] accepts either form, so paths persisted as URIs by earlier builds keep
     * resolving.
     */
    actual fun resolveLocalFileUri(
        localFileUri: String?,
        destinationFileName: String,
        destinationDirOverride: String?,
    ): String? {
        localFileUri
            ?.toLocalFileOrNull()
            ?.takeIf { it.exists() }
            ?.let { return it.absolutePath }

        val fileName = destinationFileName.trim().takeIf { it.isNotBlank() }
            ?: localFileUri?.toLocalFileOrNull()?.name?.takeIf { it.isNotBlank() }
            ?: return null
        val baseDir = baseDirFor(destinationDirOverride) ?: return null
        return File(baseDir, fileName).takeIf { it.exists() }?.absolutePath
    }

    actual fun usableSpaceBytes(destinationDirOverride: String?): Long? {
        val dir = baseDirFor(destinationDirOverride) ?: return null
        // usableSpace resolves against the nearest existing ancestor when the dir isn't created yet.
        val probe = generateSequence(dir) { it.parentFile }.firstOrNull { it.exists() } ?: return null
        return runCatching { probe.usableSpace }.getOrNull()?.takeIf { it > 0L }
    }

    private suspend fun downloadAttempt(
        request: DownloadPlatformRequest,
        tempFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Long? {
        var resumeFromBytes = tempFile.takeIf { it.exists() }?.length()?.coerceAtLeast(0L) ?: 0L
        var attemptedRangeRequest = resumeFromBytes > 0L
        if (attemptedRangeRequest) {
            // A merged sequential partial is authoritative. Segment files can only be leftovers
            // from an interrupted merge and must not be counted alongside it.
            removeSegmentFiles(tempFile)
        }
        var response = sendDownloadRequest(
            request = request,
            rangeStart = if (attemptedRangeRequest) resumeFromBytes else null,
        )

        if (attemptedRangeRequest && response.code == 416) {
            response.close()
            tempFile.delete()
            resumeFromBytes = 0L
            attemptedRangeRequest = false
            response = sendDownloadRequest(request = request)
        }

        return response.use { currentResponse ->
            validateSuccessfulResponse(currentResponse)

            val isPartialResume = attemptedRangeRequest &&
                currentResponse.code == 206 &&
                resumeFromBytes > 0L
            val appendToTemp = isPartialResume
            val startingBytes = if (appendToTemp) resumeFromBytes else 0L
            val totalBytes = resolveTotalBytes(
                startingBytes = startingBytes,
                isPartialResume = isPartialResume,
                contentRangeHeader = currentResponse.header("Content-Range"),
                contentLength = currentResponse.header("Content-Length")?.toLongOrNull(),
            )
            validateDownloadSize(request, startingBytes, totalBytes)
            rejectUnsafeContentType(currentResponse.header("Content-Type"))

            val canTrySegments =
                !appendToTemp &&
                    currentResponse.code == 200 &&
                    totalBytes != null &&
                    totalBytes >= MIN_SEGMENTED_DOWNLOAD_BYTES
            if (canTrySegments) {
                // Do not leave an unconsumed full-body response occupying one of the four allowed
                // TorBox connections while probing and starting the ranged requests.
                currentResponse.close()
                if (supportsByteRanges(request, totalBytes)) {
                    try {
                        // A server that ignored a sequential resume may leave the old contiguous
                        // partial behind. Once ranges are confirmed, the segment set is authoritative.
                        tempFile.delete()
                        downloadInSegments(
                            request = request,
                            tempFile = tempFile,
                            totalBytes = totalBytes,
                            onProgress = onProgress,
                        )
                        return totalBytes
                    } catch (error: RangeDownloadRejectedException) {
                        // Some CDNs advertise ranges but stop honoring them on the actual file.
                        // Discard the sparse pieces and transparently retain the old behavior.
                        removeSegmentFiles(tempFile)
                        log.w { "Server rejected segmented download; falling back to one connection" }
                    }
                } else {
                    removeSegmentFiles(tempFile)
                }

                return sendDownloadRequest(request = request).use { fallbackResponse ->
                    validateSuccessfulResponse(fallbackResponse)
                    val fallbackTotal = resolveTotalBytes(
                        startingBytes = 0L,
                        isPartialResume = false,
                        contentRangeHeader = fallbackResponse.header("Content-Range"),
                        contentLength = fallbackResponse.header("Content-Length")?.toLongOrNull(),
                    )
                    validateDownloadSize(request, startingBytes = 0L, totalBytes = fallbackTotal)
                    rejectUnsafeContentType(fallbackResponse.header("Content-Type"))
                    downloadSequentialResponse(
                        request = request,
                        response = fallbackResponse,
                        tempFile = tempFile,
                        appendToTemp = false,
                        startingBytes = 0L,
                        totalBytes = fallbackTotal,
                        onProgress = onProgress,
                    )
                    fallbackTotal
                }
            }

            removeSegmentFiles(tempFile)
            downloadSequentialResponse(
                request = request,
                response = currentResponse,
                tempFile = tempFile,
                appendToTemp = appendToTemp,
                startingBytes = startingBytes,
                totalBytes = totalBytes,
                onProgress = onProgress,
            )
            totalBytes
        }
    }

    private suspend fun downloadSequentialResponse(
        request: DownloadPlatformRequest,
        response: Response,
        tempFile: File,
        appendToTemp: Boolean,
        startingBytes: Long,
        totalBytes: Long?,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) {
        if (!appendToTemp && tempFile.exists()) {
            tempFile.delete()
        }
        var downloadedBytes = startingBytes
        onProgress(downloadedBytes, totalBytes)
        var lastProgressAtNanos = System.nanoTime()

        val responseBody = response.body
            ?: throw IOException("Download response had no body")
        responseBody.byteStream().use { input ->
            FileOutputStream(tempFile, appendToTemp).use { output ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read <= 0) break
                    request.maximumSizeBytes?.takeIf { it > 0L }?.let { maximum ->
                        if (downloadedBytes + read.toLong() > maximum) {
                            throw RejectedDownloadException(
                                "Source exceeded the configured download limit",
                            )
                        }
                    }
                    if (request.usesAutomaticBandwidthLimit) {
                        AggregateDownloadRateLimiter.acquire(read)
                    }
                    output.write(buffer, 0, read)
                    downloadedBytes += read.toLong()
                    val nowNanos = System.nanoTime()
                    if (nowNanos - lastProgressAtNanos >= DOWNLOAD_PROGRESS_INTERVAL_NANOS) {
                        onProgress(downloadedBytes, totalBytes)
                        lastProgressAtNanos = nowNanos
                    }
                }
                output.flush()
            }
        }
        onProgress(downloadedBytes, totalBytes)
        if (totalBytes != null && downloadedBytes < totalBytes) {
            throw IOException(
                "Download ended early at $downloadedBytes of $totalBytes bytes",
            )
        }
    }

    private fun supportsByteRanges(request: DownloadPlatformRequest, totalBytes: Long): Boolean {
        return sendDownloadRequest(
            request = request,
            rangeStart = 0L,
            rangeEndInclusive = 0L,
        ).use { response ->
            when {
                response.code == 206 -> {
                    val range = parseContentRange(response.header("Content-Range"))
                    range?.start == 0L &&
                        range.endInclusive == 0L &&
                        range.totalBytes == totalBytes
                }
                response.code == 416 -> false
                response.code == 408 ||
                    response.code == 425 ||
                    response.code == 429 ||
                    response.code in 500..599 -> throw DownloadHttpException(response.code)
                else -> false
            }
        }
    }

    private suspend fun downloadInSegments(
        request: DownloadPlatformRequest,
        tempFile: File,
        totalBytes: Long,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) {
        val ranges = planDownloadSegments(totalBytes, SEGMENT_CONNECTION_COUNT)
        prepareSegmentFiles(
            tempFile = tempFile,
            totalBytes = totalBytes,
            sourceUrl = request.sourceUrl,
        )
        val segmentFiles = segmentFiles(tempFile)
        ranges.forEachIndexed { index, range ->
            val segmentFile = segmentFiles[index]
            if (segmentFile.length() > range.length) {
                segmentFile.delete()
            }
        }
        val existingBytes = ranges.indices.sumOf { index ->
            segmentFiles[index].takeIf { it.exists() }?.length()?.coerceAtMost(ranges[index].length) ?: 0L
        }
        val progress = SegmentedProgressReporter(
            initialBytes = existingBytes,
            totalBytes = totalBytes,
            onProgress = onProgress,
        )
        onProgress(existingBytes, totalBytes)

        val errors = supervisorScope {
            ranges.mapIndexed { index, range ->
                async(Dispatchers.IO) {
                    try {
                        downloadSegment(
                            request = request,
                            range = range,
                            segmentFile = segmentFiles[index],
                            progress = progress,
                        )
                        null
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        error
                    }
                }
            }.awaitAll()
        }
        errors.filterNotNull().firstOrNull { it is RangeDownloadRejectedException }?.let { throw it }
        errors.filterNotNull().firstOrNull()?.let { throw it }

        ranges.forEachIndexed { index, range ->
            val actualLength = segmentFiles[index].length()
            if (actualLength != range.length) {
                throw IOException(
                    "Download segment ended early at $actualLength of ${range.length} bytes",
                )
            }
        }
        mergeSegments(tempFile, segmentFiles)
        onProgress(totalBytes, totalBytes)
    }

    private suspend fun downloadSegment(
        request: DownloadPlatformRequest,
        range: DownloadByteRange,
        segmentFile: File,
        progress: SegmentedProgressReporter,
    ) {
        val existingBytes = segmentFile.takeIf { it.exists() }?.length()?.coerceAtLeast(0L) ?: 0L
        if (existingBytes == range.length) return
        if (existingBytes > range.length) {
            throw IOException("Partial segment is larger than its expected range")
        }
        val requestStart = range.start + existingBytes
        sendDownloadRequest(
            request = request,
            rangeStart = requestStart,
            rangeEndInclusive = range.endInclusive,
        ).use { response ->
            if (response.code != 206) {
                if (
                    response.code == 408 ||
                    response.code == 425 ||
                    response.code == 429 ||
                    response.code in 500..599
                ) {
                    throw DownloadHttpException(response.code)
                }
                if (response.code == 200 || response.code == 416) {
                    throw RangeDownloadRejectedException(
                        "Server returned HTTP ${response.code} for range " +
                            "$requestStart-${range.endInclusive}",
                    )
                }
                throw DownloadHttpException(response.code)
            }
            val returnedRange = parseContentRange(response.header("Content-Range"))
            if (
                returnedRange?.start != requestStart ||
                returnedRange.endInclusive != range.endInclusive ||
                returnedRange.totalBytes != progress.totalBytes
            ) {
                throw RangeDownloadRejectedException("Server returned an unexpected Content-Range")
            }
            val responseBody = response.body
                ?: throw IOException("Download response had no body")
            var segmentBytes = existingBytes
            responseBody.byteStream().use { input ->
                FileOutputStream(segmentFile, existingBytes > 0L).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        if (segmentBytes + read.toLong() > range.length) {
                            throw RangeDownloadRejectedException("Server sent bytes beyond the requested range")
                        }
                        if (request.usesAutomaticBandwidthLimit) {
                            AggregateDownloadRateLimiter.acquire(read)
                        }
                        output.write(buffer, 0, read)
                        segmentBytes += read.toLong()
                        progress.record(read)
                    }
                    output.flush()
                }
            }
            if (segmentBytes != range.length) {
                throw IOException(
                    "Download segment ended early at $segmentBytes of ${range.length} bytes",
                )
            }
        }
    }

    private fun mergeSegments(tempFile: File, segmentFiles: List<File>) {
        val mergeFile = mergeFile(tempFile)
        mergeFile.delete()
        try {
            FileOutputStream(mergeFile, false).use { output ->
                segmentFiles.forEach { segmentFile ->
                    FileInputStream(segmentFile).use { input ->
                        input.copyTo(output, DOWNLOAD_BUFFER_BYTES)
                    }
                }
                output.flush()
            }
            tempFile.delete()
            if (!mergeFile.renameTo(tempFile)) {
                mergeFile.copyTo(tempFile, overwrite = true)
                mergeFile.delete()
            }
            segmentFiles.forEach { it.delete() }
            segmentMetadataFile(tempFile).delete()
        } catch (error: Throwable) {
            mergeFile.delete()
            throw error
        }
    }

    private fun validateSuccessfulResponse(response: Response) {
        if (response.code !in 200..299) {
            throw DownloadHttpException(response.code)
        }
    }

    private fun validateDownloadSize(
        request: DownloadPlatformRequest,
        startingBytes: Long,
        totalBytes: Long?,
    ) {
        request.maximumSizeBytes?.takeIf { it > 0L }?.let { maximum ->
            if (totalBytes != null && totalBytes > maximum) {
                throw RejectedDownloadException("Source is larger than the configured download limit")
            }
            if (startingBytes > maximum) {
                throw RejectedDownloadException("Partial download is larger than the configured download limit")
            }
        }
    }

    private fun sendDownloadRequest(
        request: DownloadPlatformRequest,
        rangeStart: Long? = null,
        rangeEndInclusive: Long? = null,
    ): Response {
        val builder = Request.Builder()
            .url(request.sourceUrl)
            .get()
        request.sourceHeaders.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) {
                builder.addHeader(key, value)
            }
        }
        // Range offsets refer to the identity representation. This also prevents transparent gzip
        // from making Content-Length and Content-Range describe different byte streams.
        builder.header("Accept-Encoding", "identity")
        if (rangeStart != null && rangeStart >= 0L) {
            val end = rangeEndInclusive?.toString().orEmpty()
            builder.header("Range", "bytes=$rangeStart-$end")
        }
        return desktopDownloadHttpClient.newCall(builder.build()).execute()
    }

    // Initial request plus three retries at 1s/2s/4s. A debrid endpoint that briefly 5xxes is
    // almost always still 5xxing one second later, so a single fast retry caught nothing.
    // Longer-horizon retries (minutes) are owned by LibraryPvrScheduler, as is candidate failover.
    private const val MAX_DOWNLOAD_ATTEMPTS = 4
    private const val RETRY_BASE_DELAY_MS = 1_000L
    private const val DOWNLOAD_BUFFER_BYTES = 1024 * 1024
    private const val SEGMENT_CONNECTION_COUNT = 4
    private const val MIN_SEGMENTED_DOWNLOAD_BYTES = 16L * 1024L * 1024L
}

private class SegmentedProgressReporter(
    initialBytes: Long,
    val totalBytes: Long,
    private val onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
) {
    private val lock = Any()
    private var downloadedBytes = initialBytes
    private var lastProgressAtNanos = System.nanoTime()

    fun record(byteCount: Int) {
        synchronized(lock) {
            downloadedBytes += byteCount.toLong()
            val nowNanos = System.nanoTime()
            if (nowNanos - lastProgressAtNanos >= DOWNLOAD_PROGRESS_INTERVAL_NANOS) {
                lastProgressAtNanos = nowNanos
                // Serialize callbacks as well as the counter so concurrent readers can never make
                // the UI receive a newer byte count before an older one.
                onProgress(downloadedBytes, totalBytes)
            }
        }
    }
}

internal data class DownloadByteRange(
    val start: Long,
    val endInclusive: Long,
) {
    val length: Long
        get() = endInclusive - start + 1L
}

internal data class DownloadContentRange(
    val start: Long,
    val endInclusive: Long,
    val totalBytes: Long,
)

internal fun planDownloadSegments(
    totalBytes: Long,
    requestedSegmentCount: Int,
): List<DownloadByteRange> {
    require(totalBytes > 0L) { "totalBytes must be positive" }
    require(requestedSegmentCount > 0) { "requestedSegmentCount must be positive" }
    val segmentCount = minOf(requestedSegmentCount.toLong(), totalBytes).toInt()
    val baseLength = totalBytes / segmentCount
    val remainder = totalBytes % segmentCount
    var nextStart = 0L
    return List(segmentCount) { index ->
        val length = baseLength + if (index.toLong() < remainder) 1L else 0L
        DownloadByteRange(
            start = nextStart,
            endInclusive = nextStart + length - 1L,
        ).also {
            nextStart += length
        }
    }
}

internal fun parseContentRange(headerValue: String?): DownloadContentRange? {
    val value = headerValue?.trim().orEmpty()
    if (!value.startsWith("bytes ", ignoreCase = true)) return null
    val rangeAndTotal = value.substringAfter(' ', missingDelimiterValue = "")
    val rangePart = rangeAndTotal.substringBefore('/', missingDelimiterValue = "")
    val totalPart = rangeAndTotal.substringAfter('/', missingDelimiterValue = "")
    if (rangePart.isBlank() || totalPart.isBlank() || totalPart == "*") return null
    val start = rangePart.substringBefore('-', missingDelimiterValue = "").toLongOrNull() ?: return null
    val endInclusive = rangePart.substringAfter('-', missingDelimiterValue = "").toLongOrNull() ?: return null
    val totalBytes = totalPart.toLongOrNull() ?: return null
    if (start < 0L || endInclusive < start || totalBytes <= endInclusive) return null
    return DownloadContentRange(
        start = start,
        endInclusive = endInclusive,
        totalBytes = totalBytes,
    )
}

private fun segmentFiles(tempFile: File): List<File> =
    List(PARTIAL_SEGMENT_FILE_COUNT) { index ->
        File("${tempFile.absolutePath}.segment-$index")
    }

private fun mergeFile(tempFile: File): File =
    File("${tempFile.absolutePath}.merge")

private fun segmentMetadataFile(tempFile: File): File =
    File("${tempFile.absolutePath}.segments")

private fun prepareSegmentFiles(
    tempFile: File,
    totalBytes: Long,
    sourceUrl: String,
) {
    val metadataFile = segmentMetadataFile(tempFile)
    val expectedMetadata = "$totalBytes:${sourceUrl.hashCode()}"
    val savedMetadata = runCatching {
        metadataFile.takeIf { it.exists() }?.readText()
    }.getOrNull()
    if (savedMetadata != expectedMetadata) {
        segmentFiles(tempFile).forEach { it.delete() }
        metadataFile.writeText(expectedMetadata)
    }
}

private fun partialDownloadFiles(tempFile: File): List<File> =
    listOf(tempFile, mergeFile(tempFile), segmentMetadataFile(tempFile)) + segmentFiles(tempFile)

private fun removeSegmentFiles(tempFile: File): Boolean =
    (listOf(mergeFile(tempFile), segmentMetadataFile(tempFile)) + segmentFiles(tempFile))
        .map { file -> !file.exists() || runCatching { file.delete() }.getOrDefault(false) }
        .all { it }

private fun removePartialDownloadFiles(tempFile: File): Boolean =
    partialDownloadFiles(tempFile)
        .map { file -> !file.exists() || runCatching { file.delete() }.getOrDefault(false) }
        .all { it }

private fun downloadedPartialBytes(tempFile: File): Long {
    if (tempFile.exists()) return tempFile.length().coerceAtLeast(0L)
    return segmentFiles(tempFile).sumOf { file ->
        file.takeIf { it.exists() }?.length()?.coerceAtLeast(0L) ?: 0L
    }
}

/**
 * One reservation timeline shared by capped downloads, so the preference limits their combined
 * throughput rather than granting the full cap independently to every concurrent transfer.
 */
private object AggregateDownloadRateLimiter {
    private val lock = Any()
    private var nextAvailableNanos = 0L
    @Volatile
    private var bytesPerSecond: Long? = null

    fun updateLimit(newBytesPerSecond: Long?) {
        synchronized(lock) {
            bytesPerSecond = newBytesPerSecond?.takeIf { it > 0L }
            // Do not carry reservations made under the old rate into the new preference.
            nextAvailableNanos = System.nanoTime() - BURST_WINDOW_NANOS
        }
    }

    suspend fun acquire(byteCount: Int) {
        val activeBytesPerSecond = bytesPerSecond ?: return
        if (byteCount <= 0) return
        val waitNanos = synchronized(lock) {
            val now = System.nanoTime()
            // A one-second token-bucket equivalent prevents timer granularity and simultaneous
            // readers from throttling a connection that is already slower than the chosen cap.
            nextAvailableNanos = maxOf(nextAvailableNanos, now - BURST_WINDOW_NANOS)
            val reservationDuration = (
                byteCount.toDouble() * 1_000_000_000.0 / activeBytesPerSecond.toDouble()
            ).toLong().coerceAtLeast(1L)
            nextAvailableNanos += reservationDuration
            (nextAvailableNanos - now).coerceAtLeast(0L)
        }
        if (waitNanos > 0L) {
            delay((waitNanos + 999_999L) / 1_000_000L)
        }
    }

    private const val BURST_WINDOW_NANOS = 1_000_000_000L
}

private class DesktopDownloadsTaskHandle(
    private val job: Job,
) : DownloadsTaskHandle {
    override fun cancel() {
        job.cancel()
    }
}

private class DownloadHttpException(
    val statusCode: Int,
) : IOException("Download failed with HTTP $statusCode")

private class RejectedDownloadException(message: String) : IOException(message)

private class RangeDownloadRejectedException(message: String) : IOException(message)

private fun Throwable.isRetryableDownloadFailure(): Boolean =
    this is IOException &&
        this !is RejectedDownloadException && (
        this !is DownloadHttpException ||
            statusCode == 408 ||
            statusCode == 425 ||
            statusCode == 429 ||
            statusCode in 500..599
    )

private fun rejectUnsafeContentType(rawContentType: String?) {
    val contentType = rawContentType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        ?: return
    if (
        rawContentType.isExecutableDownloadContentType() ||
        contentType == "text/html" ||
        contentType == "application/json"
    ) {
        throw RejectedDownloadException("Server returned non-video content ($contentType)")
    }
}

/**
 * Conservative container sniffing. Files are never executed, and only these established video
 * container signatures are promoted from the temporary `.part` file into the media library.
 */
internal fun File.hasRecognizedVideoSignature(): Boolean {
    if (!isFile || length() < 4L) return false
    val header = ByteArray(1024)
    val count = FileInputStream(this).use { it.read(header) }
    if (count < 4) return false

    fun matches(offset: Int, vararg bytes: Int): Boolean =
        offset >= 0 &&
            offset + bytes.size <= count &&
            bytes.indices.all { index -> (header[offset + index].toInt() and 0xff) == bytes[index] }

    fun ascii(offset: Int, value: String): Boolean =
        offset >= 0 &&
            offset + value.length <= count &&
            value.indices.all { index -> header[offset + index].toInt() == value[index].code }

    // ISO base media: MP4/MOV/M4V/F4V/3GP. QuickTime may begin with another valid atom.
    if (
        ascii(4, "ftyp") ||
        ascii(4, "moov") ||
        ascii(4, "mdat") ||
        ascii(4, "wide") ||
        ascii(4, "free")
    ) return true
    // Matroska/WebM.
    if (matches(0, 0x1a, 0x45, 0xdf, 0xa3)) return true
    // AVI/DivX and other RIFF AVI containers.
    if (ascii(0, "RIFF") && ascii(8, "AVI ")) return true
    // Ogg video, FLV, RealMedia and ASF/WMV.
    if (ascii(0, "OggS") || ascii(0, "FLV") || ascii(0, ".RMF")) return true
    if (matches(0, 0x30, 0x26, 0xb2, 0x75, 0x8e, 0x66, 0xcf, 0x11)) return true
    // MPEG program/video streams and MPEG transport streams (188 or 192-byte packets).
    if (matches(0, 0x00, 0x00, 0x01, 0xba) || matches(0, 0x00, 0x00, 0x01, 0xb3)) return true
    if (matches(0, 0x47) && matches(188, 0x47) && matches(376, 0x47)) return true
    if (matches(4, 0x47) && matches(196, 0x47) && matches(388, 0x47)) return true

    return false
}

private fun String.toLocalFileOrNull(): File? =
    runCatching {
        if (startsWith("file:")) {
            File(URI(this))
        } else {
            File(this)
        }
    }.getOrNull()

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
    return parseContentRange(headerValue)?.totalBytes
}
