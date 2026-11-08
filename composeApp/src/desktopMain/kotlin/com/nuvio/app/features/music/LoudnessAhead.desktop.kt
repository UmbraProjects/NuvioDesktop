package com.nuvio.app.features.music

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.desktop.DESKTOP_PLAYBACK_FALLBACK_USER_AGENT
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import com.nuvio.app.features.preroll.formatDb
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private val log = Logger.withTag("LoudnessAhead")

/**
 * fork:loudness - measures a track's loudness before it plays, so it starts at the right level and nothing moves once it
 * is playing. The player asks for the next track in the queue as soon as its stream is resolved (minutes before it
 * starts), and the surface waits for it when a track it is about to open has no figure at all (a stream YouTube gave no
 * loudness for, a first play of an addon track). The answer is kept in [TrackLoudnessBook] like the surface's own.
 *
 * The decode is a throwaway headless mpv on its own connection ([NativePlayerBridge.decodeAudioUrl]), one at a time.
 */
internal object LoudnessAhead {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One measurement at a time: each is a whole extra download. */
    private val permits = Semaphore(1)

    private val running = HashMap<String, Deferred<Double?>>()

    /**
     * Starts measuring [source] unless it is measured already or under way. The answer is the track's loudness in LUFS,
     * or null when it could not be read. Null (nothing started) for a source with no loudness identity or one that
     * cannot be read this way (an HLS manifest).
     */
    fun start(source: TrailerPlaybackSource): Deferred<Double?>? {
        val key = source.loudnessKey ?: return null
        TrackLoudnessBook.get(key)?.let { return CompletableDeferred(it) }
        val url = (source.audioUrl?.takeIf { it.isNotBlank() } ?: source.videoUrl).takeIf { it.isNotBlank() } ?: return null
        if ("/manifest/" in url || ".m3u8" in url) return null
        synchronized(running) {
            running[key]?.takeIf { !it.isCancelled }?.let { return it }
            val job = scope.async { permits.withPermit { measure(key, url, source.headers) } }
            running[key] = job
            job.invokeOnCompletion { synchronized(running) { if (running[key] === job) running.remove(key) } }
            return job
        }
    }

    /** [start], waiting at most [timeoutMs] for the answer. */
    suspend fun measureNow(source: TrailerPlaybackSource, timeoutMs: Long): Double? {
        val job = start(source) ?: return null
        return withTimeoutOrNull(timeoutMs) { job.await() }
    }

    private suspend fun measure(key: String, url: String, headers: Map<String, String>): Double? {
        TrackLoudnessBook.get(key)?.let { return it }
        val startedAt = System.currentTimeMillis()
        // The addon's links are fetched whole once (its server refuses repeated connections); a YouTube stream is read
        // window by window as the surface reads it.
        val downloaded = try {
            downloadSpotFile(url, headers)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            null
        }
        try {
            val readFrom = downloaded?.absolutePath ?: url
            val headerLines = if (downloaded != null) emptyArray() else headerLines(headers)
            val meter = LoudnessMeter(SAMPLE_RATE, channels = 2)
            var at = 0.0
            var failures = 0
            while (at < MAX_SECONDS) {
                currentCoroutineContext().ensureActive()
                val window = decodeWindow(readFrom, headerLines, at, WINDOW_SECONDS)
                val got = (window?.samples?.size ?: 0) / 2.0 / SAMPLE_RATE
                if (window == null || got < 0.5) {
                    // Past the end (a track whose length is a whole number of windows) reads as an empty window.
                    if (window?.ended == true && at > 0.0) break
                    if (++failures > RETRIES) {
                        log.w { "$key: could not read it at ${at.toInt()}s; it starts from what else is known" }
                        return null
                    }
                    delay(RETRY_DELAY_MS * failures)
                    continue
                }
                failures = 0
                withContext(Dispatchers.Default) { meter.add(window.samples) }
                at += got
                if (window.ended && got < WINDOW_SECONDS - 0.5) break
            }
            if (at < MIN_SECONDS) return null
            val lufs = (meter.integratedLufs() ?: return null) + MEASURED_LOUDNESS_COMPENSATION_LU
            TrackLoudnessBook.put(key, lufs)
            log.i { "$key: measured ${formatDb(lufs)} LUFS before it plays (${at.toInt()}s in ${(System.currentTimeMillis() - startedAt) / 1000}s)" }
            return lufs
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // UnsatisfiedLinkError on a native bridge without the decode (macOS, an older build).
            log.w { "$key: measuring ahead failed: ${error.message}" }
            return null
        } finally {
            downloaded?.delete()
        }
    }

    private class Window(val samples: FloatArray, val ended: Boolean)

    private suspend fun decodeWindow(url: String, headerLines: Array<String>, at: Double, length: Double): Window? =
        withContext(Dispatchers.IO) {
            val directory = File(System.getProperty("java.io.tmpdir"), "nuvio-autosync").apply { mkdirs() }
            val file = File.createTempFile("ahead-", ".f32", directory)
            try {
                val result = NativePlayerBridge.decodeAudioUrl(url, headerLines, at, length, SAMPLE_RATE, 2, file.absolutePath)
                // 0: read to the window's end, or the track's. -1 with something written: the native 90 s limit, and what
                // was written by then is real audio (the next window carries on from its end).
                if ((result != 0 && result != -1) || file.length() < 4) {
                    return@withContext if (result == 0) Window(FloatArray(0), ended = true) else null
                }
                val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                Window(FloatArray(buffer.remaining()).also(buffer::get), ended = result == 0)
            } finally {
                file.delete()
            }
        }

    private fun headerLines(headers: Map<String, String>): Array<String> {
        val withAgent = if (headers.keys.any { it.trim().equals("User-Agent", ignoreCase = true) }) headers
        else headers + ("User-Agent" to DESKTOP_PLAYBACK_FALLBACK_USER_AGENT)
        return withAgent.mapNotNull { (name, value) ->
            "${name.trim()}: ${value.trim()}".takeIf { name.isNotBlank() && value.isNotBlank() }
        }.toTypedArray()
    }

    /** As the surface's measurement (see MusicPlayerSurface.desktop.kt). */
    private const val SAMPLE_RATE = 48_000
    private const val WINDOW_SECONDS = 120.0
    private const val MAX_SECONDS = 900.0
    private const val MIN_SECONDS = 15.0
    private const val RETRIES = 3
    private const val RETRY_DELAY_MS = 2_000L
}

internal actual fun measureLoudnessAhead(source: TrailerPlaybackSource) {
    LoudnessAhead.start(source)
}
