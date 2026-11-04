package com.nuvio.app.features.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import kotlin.coroutines.coroutineContext

/**
 * fork:voice-control - downloads a model file to `<target>.part`, hashing it as it is written, and
 * only moves it to [target] when the size and SHA-256 match what the catalogue says. A `.part` left
 * by a failed download is resumed with a Range request; [VoiceModelDownloader.discardPartial] is
 * for a cancel, which should not resume.
 */
internal class VoiceModelDownloader(
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build(),
) {
    class VerificationFailed(message: String) : Exception(message)

    /**
     * Downloads [url] into [target]. [onProgress] gets (bytes so far, total); [onVerifying] is called
     * once the bytes are all in and only the final check is left. Cancelling the coroutine stops the
     * download and leaves the `.part` behind.
     */
    suspend fun download(
        url: String,
        target: File,
        expectedSize: Long,
        expectedSha256: String,
        onProgress: (Long, Long) -> Unit,
        onVerifying: () -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val part = partFile(target)
        val digest = MessageDigest.getInstance("SHA-256")
        var have = if (part.isFile && part.length() <= expectedSize) part.length() else 0L
        if (have == 0L) part.delete()
        if (have > 0) part.inputStream().use { input -> hashInto(digest, input.buffered()) }

        if (have < expectedSize) {
            val request = HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofMinutes(2))
                .header("User-Agent", "NuvioHTPC")
                .apply { if (have > 0) header("Range", "bytes=$have-") }
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { body ->
                val append = when (response.statusCode()) {
                    206 -> true
                    200 -> false
                    else -> throw IllegalStateException("The download server answered ${response.statusCode()}")
                }
                if (!append && have > 0) {
                    // The server ignored the Range: start over.
                    have = 0L
                    digest.reset()
                }
                FileOutputStream(part, append).use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = body.read(buffer)
                        if (read < 0) break
                        if (have + read > expectedSize) throw VerificationFailed("The download is larger than expected")
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        have += read
                        if (have - lastReport >= 512 * 1024 || have == expectedSize) {
                            lastReport = have
                            onProgress(have, expectedSize)
                        }
                    }
                }
            }
        }

        onVerifying()
        if (have != expectedSize) {
            // Short: keep the part so a retry resumes from here.
            throw IllegalStateException("The download stopped early (${have / 1024} of ${expectedSize / 1024} KB)")
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            part.delete()
            throw VerificationFailed("The download was corrupted (checksum mismatch); try again")
        }
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        fun partFile(target: File) = File(target.parentFile, target.name + ".part")

        fun discardPartial(target: File) {
            partFile(target).delete()
        }

        private fun hashInto(digest: MessageDigest, input: java.io.InputStream) {
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) return
                digest.update(buffer, 0, read)
            }
        }
    }
}
