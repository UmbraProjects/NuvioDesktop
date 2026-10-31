package com.nuvio.app.features.music

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.desktop.DESKTOP_PLAYBACK_FALLBACK_USER_AGENT
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/*
 * fork:music-resolver
 *
 * Verifies a stream URL before the music player is handed it. The idea, and most of the reasoning
 * below, is ported from BitChord's StreamResolver.probe (https://github.com/kushagrasinghx/BitChord,
 * GPL-3.0): a YouTube URL that will 403 is indistinguishable from a good one until something reads
 * from it, and the failure then surfaces as a track that spins and never starts.
 *
 * What a probe has to get right, per BitChord's notes:
 *  - It must ask the way the real fetch asks. mpv/ffmpeg open with an open-ended `Range: bytes=N-`,
 *    which some clients' URLs refuse outright, so the probe is open-ended too.
 *  - It must read past the first megabyte. Some clients' URLs serve the opening and 403 everything
 *    after it, so a probe of the first bytes passes and playback dies about a minute in.
 *  - Real bytes must arrive. A response that sends headers and then stalls is a failure too.
 *  - A 200 that is an error or consent page rather than media is a refusal dressed as a success.
 *
 * The verdict only blames the URL for a definite refusal. Anything else (a timeout, a 429, a 5xx)
 * is [ProbeVerdict.Unreachable], which never stops a track that was already trusted from playing.
 */

private val verifyLog = Logger.withTag("MusicStreamVerify")

internal enum class ProbeVerdict {
    /** Served media bytes from where the player will read; safe to play. */
    Ok,

    /** Answered, but refused this request; the URL (or the client that minted it) is the problem. */
    Refused,

    /** Never got an answer worth interpreting; blame nothing in particular. */
    Unreachable,
}

/** What a probe request came back with. [bytesRead] is how much of the body actually arrived. */
internal class ProbeResponse(val status: Int, val contentType: String?, val bytesRead: Int)

internal interface ProbeTransport {
    /** One GET with [rangeHeader]; reads at most [maxBytes] of the body. Null when it never got there. */
    suspend fun fetch(url: String, rangeHeader: String, userAgent: String, maxBytes: Int): ProbeResponse?
}

/** Where googlevideo stops authorising some clients' URLs ("CDN 403 after 1 MiB"). */
internal const val AUTH_BOUNDARY_BYTES = 1024L * 1024

/** How much of the answer must actually arrive, to catch a stalled body. */
internal const val PROBE_READ_BYTES = 16 * 1024

private const val PROBE_TIMEOUT_MS = 6_000L
private val REFUSAL_CODES = setOf(403, 404, 410)
private val CLEN_REGEX = Regex("""[?&]clen=(\d+)""")

/**
 * Open-ended, starting past [AUTH_BOUNDARY_BYTES] when the file (its `clen`) is long enough to have
 * that much left to read.
 */
internal fun probeRange(url: String): String {
    val length = CLEN_REGEX.find(url)?.groupValues?.getOrNull(1)?.toLongOrNull()
    val start = if (length != null && length > AUTH_BOUNDARY_BYTES + PROBE_READ_BYTES) AUTH_BOUNDARY_BYTES else 0L
    return "bytes=$start-"
}

/** Only direct googlevideo media says anything about the client that minted it; manifests and others are skipped. */
internal fun isProbeable(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
    if (!(host == "googlevideo.com" || host.endsWith(".googlevideo.com"))) return false
    val path = uri.path.orEmpty()
    return !path.contains("/manifest/") && !path.endsWith(".m3u8", ignoreCase = true)
}

internal fun probeVerdict(response: ProbeResponse?): ProbeVerdict = when {
    response == null -> ProbeVerdict.Unreachable
    response.status in REFUSAL_CODES -> ProbeVerdict.Refused
    // 416, 429, 5xx and the rest: the server did not rule on this URL.
    response.status !in 200..299 -> ProbeVerdict.Unreachable
    looksLikeErrorPage(response.contentType) -> ProbeVerdict.Refused
    // Headers can arrive long before a body that never does; insisting on the bytes is the point.
    response.bytesRead < PROBE_READ_BYTES -> ProbeVerdict.Unreachable
    else -> ProbeVerdict.Ok
}

/** A refusal dressed as a success: an error, consent or captcha page rather than media. */
private fun looksLikeErrorPage(contentType: String?): Boolean {
    val type = contentType?.lowercase() ?: return false
    return type.startsWith("text/") || "html" in type || "json" in type || "xml" in type
}

internal object StreamProbe {
    suspend fun probe(
        url: String,
        transport: ProbeTransport = JdkProbeTransport,
        userAgent: String = DESKTOP_PLAYBACK_FALLBACK_USER_AGENT,
    ): ProbeVerdict {
        if (!isProbeable(url)) return ProbeVerdict.Unreachable
        val verdict = try {
            probeVerdict(transport.fetch(url, probeRange(url), userAgent, PROBE_READ_BYTES))
        } catch (error: TimeoutCancellationException) {
            // The probe's own deadline, not the caller being cancelled.
            ProbeVerdict.Unreachable
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            verifyLog.w { "probe failed: ${error.message}" }
            ProbeVerdict.Unreachable
        }
        if (verdict != ProbeVerdict.Ok) {
            // Describes the URL without anything that could be replayed.
            val shape = runCatching { URI(url) }.getOrNull()?.let { uri ->
                "host=${uri.host} itag=${paramOf(uri, "itag")} clen=${paramOf(uri, "clen")} c=${paramOf(uri, "c")}"
            }
            verifyLog.w { "probe $verdict ${probeRange(url)} $shape" }
        }
        return verdict
    }

    private fun paramOf(uri: URI, name: String): String? =
        uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
}

/** The JDK client, with a short leash: waiting out a stall is indistinguishable from the stall being tested for. */
internal object JdkProbeTransport : ProbeTransport {
    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(PROBE_TIMEOUT_MS))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    override suspend fun fetch(url: String, rangeHeader: String, userAgent: String, maxBytes: Int): ProbeResponse? =
        withTimeout(PROBE_TIMEOUT_MS) {
            runInterruptible(Dispatchers.IO) {
                val request = HttpRequest.newBuilder(URI(url))
                    .timeout(Duration.ofMillis(PROBE_TIMEOUT_MS))
                    .header("Range", rangeHeader)
                    .header("User-Agent", userAgent)
                    .GET()
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                response.body().use { stream ->
                    // Only the first bytes are wanted; closing the stream drops the rest of the transfer.
                    val read = if (response.statusCode() in 200..299) stream.readNBytes(maxBytes).size else 0
                    ProbeResponse(
                        status = response.statusCode(),
                        contentType = response.headers().firstValue("Content-Type").orElse(null),
                        bytesRead = read,
                    )
                }
            }
        }
}

/**
 * The URLs the player will actually read for [source]: the picture and the sound when the video is
 * shown, otherwise only what the audio-only surface plays (`audioUrl`, else `videoUrl`).
 */
internal fun urlsToVerify(source: TrailerPlaybackSource, needsVideo: Boolean): List<String> =
    if (needsVideo) {
        listOfNotNull(source.videoUrl, source.audioUrl?.takeIf { it.isNotBlank() })
    } else {
        listOf(source.audioUrl?.takeIf { it.isNotBlank() } ?: source.videoUrl)
    }

/**
 * Walks [source] and its fallback chain and returns the first one that is fit to play, or null.
 *
 * - A definite refusal (any URL) moves on to the next link in the chain.
 * - A source the extractor trusted ([TrailerPlaybackSource.reliable]) is kept when the probe could
 *   not reach an answer, so an offline blip never costs a track that would have played.
 * - A source it did not trust (a client whose URLs can be cut off) used to be dropped outright; it
 *   is now accepted when the probe proves bytes arrive from where the player will read, and marked
 *   reliable. That is BitChord's rule: probe, don't guess from the client's name.
 */
/** Whether this candidate is the extractor's own answer (the split streams), not one of the fallbacks it links. */
private fun TrailerPlaybackSource.isFirstRung(source: TrailerPlaybackSource): Boolean = this === source

internal suspend fun verifiedSource(
    source: TrailerPlaybackSource,
    needsVideo: Boolean,
    probe: suspend (String) -> ProbeVerdict = { StreamProbe.probe(it) },
): TrailerPlaybackSource? {
    val trusted = source.reliable
    var candidate: TrailerPlaybackSource? = source
    while (candidate != null) {
        val urls = urlsToVerify(candidate, needsVideo)
        val verdicts = coroutineScope { urls.map { url -> async { probe(url) } }.map { it.await() } }
        val verdict = when {
            verdicts.any { it == ProbeVerdict.Refused } -> ProbeVerdict.Refused
            verdicts.all { it == ProbeVerdict.Ok } -> ProbeVerdict.Ok
            else -> ProbeVerdict.Unreachable
        }
        when {
            // A restricted client's source the probe proved still cannot be opened from the middle (see openableMidway).
            verdict == ProbeVerdict.Ok -> return candidate.copy(reliable = true, openableMidway = trusted || !candidate.isFirstRung(source))
            verdict == ProbeVerdict.Unreachable && trusted -> return candidate
        }
        verifyLog.i { "source not fit to play ($verdict); trying its fallback" }
        candidate = candidate.fallback
    }
    return null
}
