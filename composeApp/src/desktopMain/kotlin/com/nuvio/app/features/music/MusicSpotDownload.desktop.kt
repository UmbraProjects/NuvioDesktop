package com.nuvio.app.features.music

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

private val log = Logger.withTag("MusicSpotDownload")

private const val MAX_BYTES = 80L * 1024 * 1024
private val RETRY_DELAYS_MS = longArrayOf(5_000L, 15_000L, 30_000L, 60_000L, 60_000L)

/**
 * fork:visualiser - fetches the audio of the playing track once, as one extra connection, so the Song Map and loudness reads
 * can decode from the file instead of opening a new connection for every slice (the music server answered those with HTTP 429).
 * Null when it cannot be had (a playlist stream, too big, refused after every try); the caller then reads the stream as before.
 */
internal suspend fun downloadSpotFile(url: String, headers: Map<String, String>): File? {
    // A YouTube stream is read slice by slice as before; only the addon's kind of link is downloaded (see SpotSource.kt).
    if (!com.nuvio.app.features.music.visualiser.spotDownloadWanted(url)) return null
    for (attempt in 0..RETRY_DELAYS_MS.size) {
        currentCoroutineContext().ensureActive()
        val outcome = withContext(Dispatchers.IO) { fetch(url, headers) }
        if (outcome.file != null) return outcome.file
        if (!outcome.retry || attempt == RETRY_DELAYS_MS.size) return null
        log.i { "download refused (HTTP ${outcome.code}); trying again in ${RETRY_DELAYS_MS[attempt] / 1000}s" }
        delay(RETRY_DELAYS_MS[attempt])
    }
    return null
}

private class Fetched(val file: File?, val retry: Boolean, val code: Int)

private fun fetch(url: String, headers: Map<String, String>): Fetched {
    var connection: HttpURLConnection? = null
    val file = File.createTempFile("track-", ".audio", File(System.getProperty("java.io.tmpdir"), "nuvio-autosync").apply { mkdirs() })
    try {
        connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            file.delete()
            return Fetched(null, code == 429 || code == 503 || code in 500..599, code)
        }
        if (connection.contentLengthLong > MAX_BYTES) {
            log.i { "audio is ${connection.contentLengthLong / 1_048_576} MB; reading the stream instead" }
            file.delete()
            return Fetched(null, false, code)
        }
        val expected = connection.contentLengthLong
        var total = 0L
        connection.inputStream.use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BYTES) { file.delete(); return Fetched(null, false, code) }
                    output.write(buffer, 0, read)
                }
            }
        }
        // A connection that ended early looks like a short file: only the whole file is used, else it is asked for again.
        if (!com.nuvio.app.features.music.visualiser.spotDownloadComplete(total, expected)) {
            log.w { "download ended at ${total / 1024} KB of ${expected / 1024} KB; not using it" }
            file.delete()
            return Fetched(null, true, code)
        }
        log.i { "audio kept for analysis: ${total / 1024} KB" }
        return Fetched(file, false, code)
    } catch (error: Exception) {
        log.w { "download failed: ${error.message}" }
        file.delete()
        return Fetched(null, true, -1)
    } finally {
        connection?.disconnect()
    }
}
