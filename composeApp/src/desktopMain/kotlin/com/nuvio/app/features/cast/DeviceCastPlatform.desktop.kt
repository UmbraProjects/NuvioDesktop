package com.nuvio.app.features.cast

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.ExternalPlaybackSample
import com.nuvio.app.features.player.ExternalPlaybackTracker
import com.nuvio.app.features.player.ExternalPlaybackTrackingSession
import java.io.File
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val castLog = Logger.withTag("Cast")

internal actual object DeviceCastPlatform {
    private val store = DesktopStorage.store("nuvio_cast")
    private val renderers = ConcurrentHashMap<String, DlnaRenderer>()
    private val relay = CastRelayServer()
    @Volatile private var active: ActiveCast? = null

    actual val isSupported: Boolean = true

    actual suspend fun discover(): List<CastDevice> = withContext(Dispatchers.IO) {
        DlnaDiscovery.discover()
            .onEach { renderers[it.device.id] = it }
            .map { it.device }
    }

    actual fun dolbyVisionRemuxAvailable(): Boolean = Ffmpeg.available

    actual var dolbyVisionEnabled: Boolean
        get() = store.getBoolean(KEY_DOLBY_VISION) ?: false
        set(value) = store.putBoolean(KEY_DOLBY_VISION, value)

    actual suspend fun cast(device: CastDevice, request: CastRequest, dolbyVision: Boolean): CastStartResult =
        withContext(Dispatchers.IO) {
            try {
                start(device, request, dolbyVision && Ffmpeg.available)
            } catch (error: Exception) {
                castLog.w(error) { "Cast to ${device.name} failed" }
                CastStartResult.Failed("Couldn't play on ${device.name}: ${error.message ?: error::class.simpleName}")
            }
        }

    actual fun stopCasting() {
        active?.finish(stopRenderer = true)
    }

    private fun start(device: CastDevice, request: CastRequest, dolbyVision: Boolean): CastStartResult {
        val renderer = renderers[device.id]
            ?: return CastStartResult.Failed("${device.name} is no longer available. Search again.")
        val scheme = runCatching { URI(request.sourceUrl).scheme?.lowercase(Locale.ROOT) }.getOrNull()
        if (scheme != "http" && scheme != "https") {
            return CastStartResult.Failed("Only online streams can be played on another device for now.")
        }
        active?.finish(stopRenderer = false)
        if (dolbyVision && renderer.isKodi) {
            castLog.i { "${device.name} is Kodi; sending the source as-is instead of the Dolby Vision remux" }
            return start(device, request, dolbyVision = false)
        }

        val source = probeSource(request)
        castLog.i {
            "Casting to ${device.name} mode=${if (dolbyVision) "dv-remux" else "passthrough"} " +
                "mime=${source.mime} size=${source.sizeBytes} host=${URI(source.url).host}"
        }
        val durationS = if (Ffmpeg.available) Ffmpeg.probeDurationS(source.url, request.sourceHeaders) else null
        var spoolDir: File? = null
        val media: CastMedia
        val extension: String
        val features: String
        // Where the cast's timeline begins in the source. The remux starts at the resume point
        // itself instead of asking the renderer to seek there: the CX could not seek in the remux,
        // but plays one that simply starts at 0:00. Progress tracking adds this back.
        var castStartS = 0.0
        var castDurationS = durationS
        if (dolbyVision) {
            val duration = durationS ?: return CastStartResult.Failed("Couldn't read the stream's length for Dolby Vision mode.")
            val byteRate = (source.sizeBytes ?: return CastStartResult.Failed("The stream didn't report its size.")) / duration
            if (request.resumePositionMs >= RESUME_MIN_MS) {
                castStartS = Ffmpeg.keyframeAtOrBeforeS(source.url, request.sourceHeaders, request.resumePositionMs / 1000.0)
            }
            castDurationS = duration - castStartS
            val dir = Files.createTempDirectory("nuvio-cast").toFile().also { spoolDir = it }
            val sessionsOpened = AtomicInteger()
            val startS = castStartS
            val router = DvRemuxRouter(
                byteRate = byteRate,
                sessionFactory = { requestedS ->
                    openRemuxSession(
                        url = source.url,
                        headers = request.sourceHeaders,
                        requestedS = requestedS,
                        castStartS = startS,
                        dir = dir,
                        castDurationS = duration - startS,
                        index = sessionsOpened.getAndIncrement(),
                    )
                },
            )
            media = CastMedia.Remux(router, duration - castStartS)
            extension = "mp4"
            features = DLNA_FEATURES_TIMESEEK
        } else {
            media = CastMedia.Passthrough(source.url, request.sourceHeaders, source.mime)
            extension = extensionFor(source.mime)
            features = DLNA_FEATURES_RANGE
        }

        val url = relay.publish(media, renderer.host, extension)
        val transport = DlnaAvTransport(renderer.avTransportControl)
        runCatching { transport.stop() } // "transition not available" when nothing is loaded; harmless
        transport.setUri(
            url,
            didlMetadata(
                title = request.title,
                url = url,
                mime = media.mime,
                dlnaFeatures = features,
                sizeBytes = source.sizeBytes.takeIf { media is CastMedia.Passthrough },
                durationMs = castDurationS?.let { (it * 1000).toLong() },
            ),
        )
        try {
            transport.play()
        } catch (_: HttpTimeoutException) {
            // LG replies to Play only after buffering, sometimes past any sane timeout; it still plays.
            castLog.i { "Play did not answer in time; watching the renderer instead" }
        } catch (error: UpnpException) {
            relay.clear()
            spoolDir?.deleteRecursively()
            return CastStartResult.Failed("${device.name} refused to play the stream (${error.message}).")
        }

        active = ActiveCast(
            deviceName = device.name,
            transport = transport,
            media = media,
            spoolDir = spoolDir,
            // Already there when the cast itself starts at the resume point.
            resumePositionMs = if (castStartS > 0) 0 else request.resumePositionMs,
            timelineOffsetMs = (castStartS * 1000).toLong(),
            tracking = request.tracking?.let(ExternalPlaybackTracker::begin),
        ).also { it.watch() }
        return CastStartResult.Started
    }

    /**
     * A remux session on the cast's own timeline, which begins at [castStartS] in the source:
     * [requestedS] and the session's keyS count from there, so the renderer sees a file that
     * starts at 0:00 however far in the cast began.
     */
    private fun openRemuxSession(
        url: String,
        headers: Map<String, String>,
        requestedS: Double,
        castStartS: Double,
        dir: File,
        castDurationS: Double,
        index: Int,
    ): RemuxSession {
        val sourceKeyS = if (requestedS <= 0) castStartS else Ffmpeg.keyframeAtOrBeforeS(url, headers, castStartS + requestedS)
        val keyS = (sourceKeyS - castStartS).coerceAtLeast(0.0)
        val process = Ffmpeg.startRemux(url, headers, sourceKeyS)
        castLog.i {
            "Remux session requested=${"%.1f".format(Locale.ROOT, requestedS)}s " +
                "source keyframe=${"%.3f".format(Locale.ROOT, sourceKeyS)}s (cast starts at ${"%.3f".format(Locale.ROOT, castStartS)}s)"
        }
        return SpooledRemuxSession(
            requestedS = requestedS,
            keyS = keyS,
            source = process.inputStream,
            spoolDir = dir,
            onClose = { process.destroyForcibly() },
            durationS = castDurationS,
            // A film has at most a few thousand fragments, so 100k apart never overlaps.
            sequenceOffset = index * 100_000L,
        )
    }

    private class SourceInfo(val url: String, val mime: String, val sizeBytes: Long?)

    /**
     * One ranged request for the redirect target, size and type. The redirect is followed once
     * here so the renderer's many range requests do not each go through the addon again.
     */
    private fun probeSource(request: CastRequest): SourceInfo {
        val probe = HttpRequest.newBuilder(URI(request.sourceUrl)).timeout(Duration.ofSeconds(30)).GET().apply {
            request.sourceHeaders.forEach { (key, value) -> runCatching { header(key, value) } }
            header("Range", "bytes=0-0")
        }.build()
        val response = dlnaHttp.send(probe, HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() !in 200..299) error("the stream answered HTTP ${response.statusCode()}")
        val finalUrl = response.uri().toString()
        val size = response.headers().firstValue("Content-Range").orElse(null)
            ?.substringAfterLast('/')?.toLongOrNull()
            ?: response.headers().firstValue("Content-Length").orElse(null)?.toLongOrNull()?.takeIf { response.statusCode() == 200 }
        val declared = response.headers().firstValue("Content-Type").orElse("").substringBefore(';').trim().lowercase(Locale.ROOT)
        val mime = when {
            declared.startsWith("video/") -> declared
            else -> mimeForPath(URI(finalUrl).path.orEmpty())
        }
        return SourceInfo(finalUrl, mime, size)
    }

    private fun mimeForPath(path: String): String = when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "mp4", "m4v" -> "video/mp4"
        "ts", "m2ts" -> "video/mp2t"
        "avi" -> "video/x-msvideo"
        "webm" -> "video/webm"
        else -> "video/x-matroska"
    }

    private fun extensionFor(mime: String): String = when (mime) {
        "video/mp4" -> "mp4"
        "video/mp2t" -> "ts"
        "video/x-msvideo" -> "avi"
        "video/webm" -> "webm"
        else -> "mkv"
    }

    /** One cast in progress: polls the renderer, feeds progress tracking, tidies up at the end. */
    private class ActiveCast(
        val deviceName: String,
        val transport: DlnaAvTransport,
        val media: CastMedia,
        val spoolDir: File?,
        val resumePositionMs: Long,
        /** Source time at which the renderer's 0:00 sits (a remux started at the resume point). */
        val timelineOffsetMs: Long,
        val tracking: ExternalPlaybackTrackingSession?,
    ) {
        @Volatile private var finished = false

        fun watch() {
            thread(isDaemon = true, name = "cast-monitor") {
                val startedAt = System.currentTimeMillis()
                var sawPlaying = false
                var resumeSent = resumePositionMs < RESUME_MIN_MS
                var failures = 0
                while (!finished) {
                    Thread.sleep(POLL_MS)
                    val state: String?
                    val position: Pair<Long?, Long?>
                    try {
                        state = transport.transportState()
                        position = transport.position()
                        failures = 0
                    } catch (error: Exception) {
                        if (++failures * POLL_MS >= UNREACHABLE_MS) {
                            castLog.w(error) { "$deviceName stopped answering; ending the cast" }
                            break
                        }
                        continue
                    }
                    val (positionMs, durationMs) = position
                    when (state) {
                        "PLAYING" -> {
                            sawPlaying = true
                            if (!resumeSent) {
                                resumeSent = true
                                runCatching { transport.seek(resumePositionMs) }
                                    .onFailure { castLog.w(it) { "Resume seek on $deviceName failed" } }
                                continue
                            }
                            sample(positionMs, durationMs, paused = false)
                        }
                        "PAUSED_PLAYBACK" -> sample(positionMs, durationMs, paused = true)
                        "STOPPED", "NO_MEDIA_PRESENT" -> if (sawPlaying || System.currentTimeMillis() - startedAt > START_TIMEOUT_MS) {
                            castLog.i { "$deviceName reports $state; cast ended (played=$sawPlaying)" }
                            break
                        }
                        else -> Unit // TRANSITIONING and vendor variants: wait
                    }
                }
                finish(stopRenderer = false)
            }
        }

        private fun sample(positionMs: Long?, durationMs: Long?, paused: Boolean) {
            if (positionMs == null || durationMs == null || durationMs <= 0) return
            tracking?.onSample(
                ExternalPlaybackSample(
                    positionMs = positionMs + timelineOffsetMs,
                    durationMs = durationMs + timelineOffsetMs,
                    isPaused = paused,
                ),
            )
        }

        @Synchronized
        fun finish(stopRenderer: Boolean) {
            if (finished) return
            finished = true
            tracking?.onEnded()
            if (stopRenderer) runCatching { transport.stop() }
            if (active === this) {
                relay.clear()
                active = null
            } else {
                (media as? CastMedia.Remux)?.router?.close()
            }
            spoolDir?.deleteRecursively()
        }
    }

    private const val KEY_DOLBY_VISION = "dolby_vision"
    private const val POLL_MS = 2_000L
    private const val UNREACHABLE_MS = 60_000L
    private const val START_TIMEOUT_MS = 90_000L
    private const val RESUME_MIN_MS = 30_000L
}
