package com.nuvio.app.features.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

private const val MAX_DIAGNOSTIC_VIDEO_BYTES = 8L * 1024L * 1024L
private const val DIAGNOSTIC_VIDEO_TEMP_PREFIX = "nuvio-provider-diagnostic-"

private val diagnosticVideoHttpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(15))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

private val diagnosticVideoTempFiles = ConcurrentHashMap.newKeySet<Path>()

internal actual suspend fun resolveProviderDiagnosticVideo(
    sourceUrl: String,
    sourceHeaders: Map<String, String>,
): ProviderDiagnosticVideo? = withContext(Dispatchers.IO) {
    val normalizedUrl = sourceUrl.trim()
    if (!normalizedUrl.startsWith("http://", ignoreCase = true) &&
        !normalizedUrl.startsWith("https://", ignoreCase = true)
    ) {
        return@withContext null
    }

    runCatching {
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI(normalizedUrl))
            .timeout(Duration.ofSeconds(30))
            .header("Range", "bytes=0-${MAX_DIAGNOSTIC_VIDEO_BYTES - 1L}")
            .header("Accept", "video/*,application/octet-stream;q=0.8,*/*;q=0.2")
            .GET()

        sourceHeaders.forEach { (key, value) ->
            if (key.isBlank() || value.isBlank() || key.equals("Range", ignoreCase = true) ||
                key.equals("Accept", ignoreCase = true) ||
                key.equals("Accept-Encoding", ignoreCase = true) ||
                key.equals("Authorization", ignoreCase = true) ||
                key.equals("Cookie", ignoreCase = true) ||
                key.equals("Proxy-Authorization", ignoreCase = true) ||
                isRestrictedJdkHeader(key)
            ) {
                return@forEach
            }
            requestBuilder.header(key, value)
        }

        val response = diagnosticVideoHttpClient.send(
            requestBuilder.build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        response.body().use { body ->
            if (response.statusCode() !in 200..299) return@runCatching null

            val rangedTotalSize = response.headers().firstValue("Content-Range").orElse("")
                .substringAfterLast('/', missingDelimiterValue = "")
                .toLongOrNull()
            val declaredSize = if (response.statusCode() == 206) {
                // A partial response without a total size could be the first 8 MiB of a real
                // feature. Never save that fragment and misclassify it as diagnostic media.
                rangedTotalSize ?: -1L
            } else {
                response.headers().firstValueAsLong("Content-Length").orElse(-1L)
            }
            if (declaredSize !in 1L..MAX_DIAGNOSTIC_VIDEO_BYTES) return@runCatching null

            val bytes = body.readAtMost(MAX_DIAGNOSTIC_VIDEO_BYTES + 1L)
            if (bytes.size.toLong() != declaredSize) {
                return@runCatching null
            }

            val mediaExtension = diagnosticMediaExtension(
                contentType = response.headers().firstValue("Content-Type").orElse(""),
                bytes = bytes,
            ) ?: return@runCatching null

            val tempFile = Files.createTempFile(DIAGNOSTIC_VIDEO_TEMP_PREFIX, mediaExtension)
            Files.write(tempFile, bytes)
            tempFile.toFile().deleteOnExit()
            diagnosticVideoTempFiles.add(tempFile.toAbsolutePath().normalize())
            ProviderDiagnosticVideo(sourceUrl = tempFile.toAbsolutePath().toString())
        }
    }.getOrNull()
}

internal actual fun releaseProviderDiagnosticVideo(sourceUrl: String) {
    val path = runCatching { Path.of(sourceUrl).toAbsolutePath().normalize() }.getOrNull() ?: return
    if (!diagnosticVideoTempFiles.remove(path)) return
    runCatching { Files.deleteIfExists(path) }
}

private fun isRestrictedJdkHeader(name: String): Boolean =
    name.equals("Connection", ignoreCase = true) ||
        name.equals("Content-Length", ignoreCase = true) ||
        name.equals("Expect", ignoreCase = true) ||
        name.equals("Host", ignoreCase = true) ||
        name.equals("Upgrade", ignoreCase = true)

private fun InputStream.readAtMost(maxBytes: Long): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var remaining = maxBytes
    while (remaining > 0L) {
        val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (read < 0) break
        output.write(buffer, 0, read)
        remaining -= read.toLong()
    }
    return output.toByteArray()
}

private fun diagnosticMediaExtension(contentType: String, bytes: ByteArray): String? {
    val normalizedType = contentType.substringBefore(';').trim().lowercase()
    val isMp4 = bytes.size >= 12 && bytes.copyOfRange(4, 8).contentEquals("ftyp".encodeToByteArray())
    if (isMp4) return ".mp4"

    val isEbml = bytes.size >= 4 &&
        bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() &&
        bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte()
    if (isEbml) return if (normalizedType.contains("webm")) ".webm" else ".mkv"

    val isMpegTs = bytes.size >= 377 &&
        bytes[0] == 0x47.toByte() && bytes[188] == 0x47.toByte() && bytes[376] == 0x47.toByte()
    if (isMpegTs) return ".ts"

    // Do not trust a generic video content type by itself. Magic-byte verification prevents a
    // small JSON/HTML provider error from being mislabeled as playable diagnostic media.
    return null
}
