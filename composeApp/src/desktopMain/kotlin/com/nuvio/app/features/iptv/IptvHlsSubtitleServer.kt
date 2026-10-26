package com.nuvio.app.features.iptv

import co.touchlab.kermit.Logger
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * Serves a live IPTV channel's HLS to mpv in a form FFmpeg plays without stalling: the master cut
 * down to the variant it plays, playlists renumbered so they only ever rise, subtitles without the
 * cues the provider repeats across segments (see IptvHlsSubtitles.kt), and, for a channel whose
 * audio is inside its video playlist, video segments decrypted and re-timed onto one continuous
 * clock so a stitched channel's ad breaks are not timestamp jumps (see IptvHlsTimeline.kt).
 *
 * [playbackUrlFor] fetches a channel's master playlist before mpv opens it. When it is a master
 * playlist, mpv gets a local, trimmed copy instead, and every playlist it names comes through here.
 * A media-playlist or `.ts` URL plays from the original URL as before.
 *
 * Loopback only, and every path carries a random per-run token and a random per-session id, so no
 * other local program can use this as a fetcher. Upstream requests use the IPTV client, and so the
 * IPTV proxy when one is set, with the channel's own request headers.
 */
internal object IptvHlsSubtitleServer {
    private val log = Logger.withTag("IptvSubs")
    private const val MAX_PLAYLIST_BYTES = 2L * 1024 * 1024
    private const val MAX_SESSIONS = 4
    private const val MAX_SEGMENTS_PER_SESSION = 1_024
    private const val PROBE_TIMEOUT_SECONDS = 6L

    private val random = SecureRandom()
    private val token = randomId()

    @Volatile private var server: ServerSocket? = null

    private class Session(
        val id: String,
        val headers: List<Pair<String, String>>,
        val host: String,
    ) {
        @Volatile var master: String? = null
        val subtitlePlaylists = ArrayList<String>()
        val mediaPlaylists = ArrayList<String>()
        /** One per served media playlist (`media/0.m3u8`, `sub/0.m3u8`), keyed by that path. */
        val normalizers = HashMap<String, HlsSequenceNormalizer>()
        /** The last good rewritten copy of each, served when a refresh fails. */
        val lastPlaylists = java.util.concurrent.ConcurrentHashMap<String, String>()
        val segments = LinkedHashMap<String, Int>()
        val segmentUrls = HashMap<Int, String>()
        /** Subtitle segment id -> the number the provider gave it, to find the video's offset there. */
        val segmentUpstream = HashMap<Int, Long>()
        var nextSegment = 0
        val deduper = WebVttCueDeduper()
        @Volatile var droppedCues = 0

        /**
         * Video segments are served decrypted and re-timed ([MpegTsContinuity]) when the channel has
         * one media playlist, audio inside it. Separate audio playlists would each need re-timing in
         * step, so those channels keep their segments upstream.
         */
        var retime = false
        /** Fed only from [retimer], segment by segment in sequence order. */
        val continuity = MpegTsContinuity()
        val mediaSegments = object : LinkedHashMap<Long, HlsMediaSegment>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, HlsMediaSegment>) = size > MAX_SEGMENTS_PER_SESSION
        }
        val keys = object : LinkedHashMap<String, ByteArray>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) = size > 64
        }
        @Volatile var joins = 0
        /** Video segments downloaded and re-timed (or on the way), by sequence: see [prefetch]. */
        val segmentsReady = java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.CompletableFuture<ByteArray?>>()
        /** The video's output offset in each segment, by the number the provider gave it, for subtitles. */
        val videoOffsets = java.util.concurrent.ConcurrentHashMap<Long, Long>()
        @Volatile var lastVideoOffset: Long? = null
        /** Where each video segment starts on the output clock (90 kHz), by the provider's number, for subtitles. */
        val videoStarts = java.util.concurrent.ConcurrentHashMap<Long, Long>()
        /** The provider's number and output start of the latest re-timed video segment. */
        @Volatile var lastVideoStart: Pair<Long, Long>? = null
    }

    /**
     * A subtitle segment's length where the server has no better figure: the window
     * [placeWebVttSegment] rounds cues to. Pluto's are 5 s, and HLS live segments are mostly 2-10 s.
     */
    private const val SEGMENT_MS_ESTIMATE = 5_000L

    /**
     * How long a subtitle segment waits, at the very start only, for the first video segment to
     * be re-timed before going out without cues. Kept short: FFmpeg can read nothing else meanwhile.
     */
    private const val SUBTITLE_VIDEO_WAIT_MS = 1_500L

    /** Newest video segments left out of the video playlist on channels with subtitles (2: about 10 s). */
    private const val VIDEO_HOLD_BACK_SEGMENTS = 2

    private const val PREFETCH_KEEP = 8
    private const val PREFETCH_PARALLEL = 2
    private const val SEGMENT_WAIT_SECONDS = 25L
    private const val MAX_SEGMENT_BYTES = 64L * 1024 * 1024

    private val prefetchPool = java.util.concurrent.Executors.newFixedThreadPool(PREFETCH_PARALLEL) { runnable ->
        Thread(runnable, "iptv-segment-prefetch").apply { isDaemon = true }
    }

    /** One thread for all re-timing: a channel's streams must advance one segment at a time, in order. */
    private val retimer = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "iptv-segment-retime").apply { isDaemon = true }
    }

    private val sessions = object : LinkedHashMap<String, Session>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Session>) = size > MAX_SESSIONS
    }

    /**
     * The URL mpv should open for [sourceUrl]: a local master playlist when the channel is an HLS
     * master playlist, otherwise [sourceUrl] itself. Blocks for one playlist fetch (at most a few
     * seconds), so call it off the UI thread. Any failure plays the original URL, as if this did not
     * exist.
     */
    fun playbackUrlFor(sourceUrl: String, headerLines: List<String>): String {
        val path = sourceUrl.substringBefore('?').substringBefore('#').lowercase()
        // Only playlists: a `.ts` URL is an endless stream, and probing it would open a second
        // connection to a provider that may allow just one.
        if (!path.endsWith(".m3u8") && !path.endsWith(".m3u")) return sourceUrl
        val headers = headerLines.mapNotNull { line ->
            val colon = line.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
            (line.substring(0, colon).trim() to line.substring(colon + 1).trim())
                .takeIf { (name, _) -> name.lowercase() !in SkippedRequestHeaders }
        }
        return runCatching {
            val (text, finalUrl) = fetch(sourceUrl, headers, timeoutSeconds = PROBE_TIMEOUT_SECONDS) ?: return sourceUrl
            if (!text.trimStart('﻿').startsWith("#EXTM3U")) return sourceUrl
            // A media playlist URL (no variants) is a single stream mpv reads directly as before.
            val variants = hlsVariantCount(text)
            if (variants < 1) return sourceUrl
            val session = Session(randomId(), headers, finalUrl.host)
            session.master = rewriteHlsMaster(
                trimHlsMaster(text),
                finalUrl,
                localSubtitlePlaylist = { upstream -> localUrl(session, playlistPath(session, session.subtitlePlaylists, "sub", upstream)) },
                localMediaPlaylist = { upstream -> localUrl(session, playlistPath(session, session.mediaPlaylists, "media", upstream)) },
            )
            session.retime = session.mediaPlaylists.size == 1
            val port = ensureStarted().localPort
            synchronized(sessions) { sessions[session.id] = session }
            log.i {
                "local master for ${finalUrl.host}: 1 of $variants variant(s) kept, " +
                    "${session.mediaPlaylists.size} media playlist(s) renumbered, " +
                    "segments ${if (session.retime) "re-timed" else "left upstream (separate audio)"}, " +
                    "subtitle de-duplication ${if (session.subtitlePlaylists.isEmpty()) "off (no subtitles)" else "on"}"
            }
            "http://127.0.0.1:$port/$token/${session.id}/master.m3u8"
        }.getOrElse { error ->
            log.w { "subtitle probe failed, playing the original URL: ${describeIptvFailure(error)}" }
            sourceUrl
        }
    }

    /** Whether [url] is one of this server's, which must not go through the IPTV proxy. */
    fun isLocalUrl(url: HttpUrl): Boolean {
        val port = server?.localPort ?: return false
        return url.port == port && url.host == "127.0.0.1" && url.encodedPath.startsWith("/$token/")
    }

    private fun playlistPath(session: Session, playlists: MutableList<String>, kind: String, upstream: String): String =
        synchronized(session) {
            val index = playlists.indexOf(upstream).takeIf { it >= 0 } ?: playlists.apply { add(upstream) }.lastIndex
            "$kind/$index.m3u8"
        }

    private fun segmentPath(session: Session, upstream: String, upstreamSequence: Long): String = synchronized(session) {
        val id = session.segments[upstream] ?: session.nextSegment++.also { id ->
            session.segments[upstream] = id
            session.segmentUrls[id] = upstream
            if (session.segments.size > MAX_SEGMENTS_PER_SESSION) {
                val oldest = session.segments.keys.first()
                session.segments.remove(oldest)?.let { old ->
                    session.segmentUrls.remove(old)
                    session.segmentUpstream.remove(old)
                }
            }
        }
        session.segmentUpstream[id] = upstreamSequence
        "vtt/$id.vtt"
    }

    /** A video segment's local path, recorded for serving and fetched ahead. */
    private fun mediaSegmentPath(session: Session, segment: HlsMediaSegment): String {
        synchronized(session) { session.mediaSegments[segment.sequence] = segment }
        prefetch(session, segment)
        return "ts/${segment.sequence}.ts"
    }

    private fun localUrl(session: Session, path: String): String {
        val port = ensureStarted().localPort
        return "http://127.0.0.1:$port/$token/${session.id}/$path"
    }

    @Synchronized
    private fun ensureStarted(): ServerSocket {
        server?.let { return it }
        val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        server = socket
        thread(name = "iptv-subs-accept", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                thread(name = "iptv-subs-conn", isDaemon = true) {
                    try {
                        handle(client)
                    } catch (e: IOException) {
                        // mpv closing a connection (channel change, stop) lands here.
                    } catch (e: Exception) {
                        log.w { "subtitle request failed: ${describeIptvFailure(e)}" }
                    } finally {
                        runCatching { client.close() }
                    }
                }
            }
        }
        return socket
    }

    private fun handle(client: Socket) {
        client.soTimeout = 30_000
        val input = client.getInputStream()
        val output = client.getOutputStream()
        val requestLine = readLine(input) ?: return
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
        }
        val parts = requestLine.split(' ')
        if (parts.size < 2 || !(parts[0] == "GET" || parts[0] == "HEAD")) return output.respond(405, "Method Not Allowed")
        // A request through the relay is in absolute form; one straight from mpv is a path.
        val path = parts[1].let { target -> if (target.startsWith("http://")) target.toHttpUrlOrNull()?.encodedPath ?: "" else target.substringBefore('?') }
        val segments = path.trim('/').split('/')
        if (segments.size < 3 || segments[0] != token) return output.respond(404, "Not Found")
        val session = synchronized(sessions) { sessions[segments[1]] } ?: return output.respond(404, "Not Found")
        val resource = segments.drop(2).joinToString("/")
        if (resource.startsWith("ts/")) return serveMediaSegment(session, resource, output, headOnly = parts[0] == "HEAD")
        val body = when {
            // The copy fetched at the probe: it only points at the provider absolutely, so any
            // later reader (a reopen of the same attempt) can have it too.
            resource == "master.m3u8" -> session.master ?: return output.respond(404, "Not Found")
            resource.startsWith("media/") -> mediaPlaylist(session, resource, session.mediaPlaylists, subtitles = false)
                ?: return output.respond(502, "Bad Gateway")
            resource.startsWith("sub/") -> mediaPlaylist(session, resource, session.subtitlePlaylists, subtitles = true)
                ?: return output.respond(502, "Bad Gateway")
            // A subtitle segment that cannot be had goes out empty rather than as an error, for the
            // same reason as a playlist that fails to refresh: mpv must not wait on its packets.
            resource.startsWith("vtt/") -> runCatching { subtitleSegment(session, resource) }
                .onFailure { log.w { "subtitle segment from ${session.host} failed: ${describeIptvFailure(it)}" } }
                .getOrNull() ?: "WEBVTT\n"
            else -> return output.respond(404, "Not Found")
        }
        val type = if (resource.endsWith(".vtt")) "text/vtt" else "application/vnd.apple.mpegurl"
        output.respond(200, "OK", body.toByteArray(Charsets.UTF_8), type, headOnly = parts[0] == "HEAD")
    }

    /**
     * A video, audio or subtitle playlist, renumbered so its media sequence only ever rises (see
     * [HlsSequenceNormalizer]). Subtitle segments come through here for de-duplication, and video
     * segments for re-timing when the session re-times ([Session.retime]); others stay upstream.
     */
    private fun mediaPlaylist(session: Session, resource: String, playlists: List<String>, subtitles: Boolean): String? {
        val index = resource.substringAfter('/').removeSuffix(".m3u8").toIntOrNull() ?: return null
        val upstream = synchronized(session) { playlists.getOrNull(index) } ?: return null
        val fetched = runCatching { fetch(upstream, session.headers) }
            .onFailure { log.w { "playlist refresh from ${session.host} failed: ${describeIptvFailure(it)}" } }
            .getOrNull()
        if (fetched == null) {
            // A refresh that fails (Pluto answers the odd one 503) gets the last good copy: to
            // FFmpeg that reads as no new segments yet, and it asks again next time. An error here
            // made FFmpeg give up on the playlist; for subtitles mpv then waited on packets that
            // would never come, and the picture stalled with half a minute of video buffered.
            return session.lastPlaylists[resource]?.also { log.i { "serving the last good $resource from ${session.host}" } }
        }
        val (text, finalUrl) = fetched
        val normalizer = synchronized(session) { session.normalizers.getOrPut(resource) { HlsSequenceNormalizer() } }
        // One refresh at a time per playlist: the numbering depends on the one before.
        val (body, numbering) = synchronized(normalizer) {
            rewriteHlsMediaPlaylist(
                text,
                finalUrl,
                normalizer,
                localSegment = if (subtitles) {
                    { segment, upstreamSequence -> localUrl(session, segmentPath(session, segment, upstreamSequence)) }
                } else {
                    null
                },
                localMediaSegment = if (!subtitles && session.retime) {
                    { segment -> localUrl(session, mediaSegmentPath(session, segment)) }
                } else {
                    null
                },
                // FFmpeg reads a packet from every playlist before it returns any, and waits in a
                // loop for the subtitle playlist's next segment: at the live edge the video could
                // not get ahead of subtitles the provider publishes a few seconds after it, and the
                // buffer drained. Holding the newest video segments back keeps FFmpeg that much
                // behind the edge, where the subtitle segments it needs already exist.
                holdBack = if (subtitles || session.subtitlePlaylists.isEmpty()) 0 else VIDEO_HOLD_BACK_SEGMENTS,
            )
        }
        if (numbering?.stale == true) {
            // Pluto once answered with the playlist from four minutes earlier: renumbered as it
            // was then, FFmpeg replayed those segments. To FFmpeg the last copy is "nothing new".
            log.i { "${session.host} sent an older $resource (upstream ${numbering.upstreamSequence}); serving the last good copy" }
            return session.lastPlaylists[resource]
        }
        if (numbering?.restarted == true) {
            log.i {
                "${session.host} restarted ${if (subtitles) "subtitle" else "media"} playlist numbering " +
                    "at ${numbering.upstreamSequence}; continuing at ${numbering.mediaSequence}"
            }
        }
        session.lastPlaylists[resource] = body
        return body
    }

    private fun subtitleSegment(session: Session, resource: String): String? {
        val id = resource.removePrefix("vtt/").removeSuffix(".vtt").toIntOrNull() ?: return null
        val upstream = synchronized(session) { session.segmentUrls[id] } ?: return null
        val (text, _) = fetch(upstream, session.headers) ?: return null
        val (deduplicated, dropped) = session.deduper.filter(upstream, text)
        if (dropped > 0) {
            if (session.droppedCues == 0) log.i { "dropping repeated subtitle cues from ${session.host}" }
            session.droppedCues += dropped
        }
        // After the de-duplication, which compares cues through the map itself.
        if (!session.retime) return applyWebVttTimestampMap(deduplicated)
        // Re-timed video: the cues go with the video segment the provider numbered the same, by its
        // offset where that fits and by its start time where it does not (placeWebVttSegment).
        val upstreamSequence = synchronized(session) { session.segmentUpstream[id] }
        // Never hold FFmpeg here for long: it reads every playlist on one thread, so while a
        // subtitle request waits it neither reads video nor refreshes the video playlist (an 8 s
        // wait for a video segment the subtitle playlist had got ahead of starved the picture).
        // Only before any video has been placed at all is there nothing to estimate from.
        if (session.lastVideoStart == null) {
            val deadline = System.nanoTime() + SUBTITLE_VIDEO_WAIT_MS * 1_000_000
            while (session.lastVideoStart == null && System.nanoTime() < deadline) Thread.sleep(50)
        }
        val shiftMs = (upstreamSequence?.let { session.videoOffsets[it] } ?: session.lastVideoOffset)?.let { signedWrap(it) / 90 } ?: 0L
        val exact = upstreamSequence?.let { session.videoStarts[it] }?.let { it / 90 }
        val videoStartMs = exact ?: upstreamSequence?.let { estimateVideoStartMs(session, it) }
        if (videoStartMs == null) {
            // Nothing to place the cues against. Cues on their own clock can sit far behind the
            // video, and FFmpeg, reading whichever playlist is furthest behind, then starves the
            // picture: losing these few seconds of subtitles is the lesser harm.
            log.d { "subtitles for upstream $upstreamSequence: no video position yet, cues dropped" }
            return webVttHeaderOnly(deduplicated)
        }
        val placed = withFillerCue(placeWebVttSegment(deduplicated, videoStartMs, SEGMENT_MS_ESTIMATE, shiftMs), videoStartMs)
        log.d {
            "subtitles for upstream $upstreamSequence: video at ${"%.2f".format(videoStartMs / 1000.0)}s" +
                "${if (exact == null) " (estimated)" else ""}, first cue at ${firstCueSeconds(placed)?.let { "%.2f".format(it) } ?: "none"}s" +
                if ("​" in placed) " (filler)" else ""
        }
        return placed
    }

    /**
     * Where the video segment the provider numbered [upstreamSequence] will start on the output
     * clock, before it has been re-timed: the latest re-timed segment's start, moved on by the
     * durations the video playlist gives for the segments between (5 s each where it has not
     * listed them yet). Exact while the clock is continuous, which re-timing keeps it.
     */
    private fun estimateVideoStartMs(session: Session, upstreamSequence: Long): Long? {
        val (lastUpstream, lastStart) = session.lastVideoStart ?: return null
        val durations = synchronized(session) {
            session.mediaSegments.values.associate { it.upstreamSequence to it.durationMs }
        }
        val step = if (upstreamSequence >= lastUpstream) 1L else -1L
        var ms = lastStart / 90
        var s = lastUpstream
        while (s != upstreamSequence) {
            // Moving forward adds the segments from the latest up to this one; back, subtracts.
            ms += if (step > 0) step * (durations[s] ?: SEGMENT_MS_ESTIMATE) else step * (durations[s + step] ?: SEGMENT_MS_ESTIMATE)
            s += step
            if (abs(s - lastUpstream) > 1_000) return null // numbering restarted: no relation
        }
        return ms
    }

    /**
     * Starts downloading and decrypting [segment] in the background, so it is ready by the time
     * mpv asks for it. Through a proxy each request is a chain of hops; with the playlist reload,
     * the segment request and the download all in FFmpeg's path a 5 s segment could take longer
     * than 5 s, and the stream slipped behind the live window. Called as playlists name segments
     * (in order); keeps the newest [PREFETCH_KEEP] and runs at most [PREFETCH_PARALLEL] downloads.
     *
     * Each download is then re-timed on [retimer] once the segment before it has been, so the
     * channel's streams advance in sequence order whatever order FFmpeg asks in. The re-timed bytes
     * are kept: a retry gets the same, rather than moving the clock on again.
     */
    private fun prefetch(session: Session, segment: HlsMediaSegment) {
        synchronized(session.segmentsReady) {
            if (session.segmentsReady.containsKey(segment.sequence)) return
            val download = java.util.concurrent.CompletableFuture.supplyAsync({ downloadSegment(session, segment) }, prefetchPool)
            val previous = session.segmentsReady[segment.sequence - 1]
                ?: java.util.concurrent.CompletableFuture.completedFuture(null)
            // handle(), not thenCombine(): a failed neighbour must not hold this one back.
            val ready = previous.handle { _, _ -> Unit }
                .thenCombineAsync(download, { _, bytes -> bytes?.let { retime(session, segment, it) } }, retimer)
            session.segmentsReady[segment.sequence] = ready
            val oldest = session.segmentsReady.keys.maxOrNull()?.minus(PREFETCH_KEEP) ?: return
            session.segmentsReady.keys.removeIf { it < oldest }
        }
    }

    /** [segment]'s body, decrypted; null when it could not be fetched (logged). */
    private fun downloadSegment(session: Session, segment: HlsMediaSegment): ByteArray? {
        val keyBytes = segment.key?.let { key ->
            synchronized(session) { session.keys[key.uri] }
                ?: fetchBytes(key.uri, session.headers, maxBytes = 64)?.takeIf { it.size == 16 }?.also { bytes ->
                    synchronized(session) { session.keys[key.uri] = bytes }
                }
                ?: return null
        }
        val body = fetchBytes(segment.uri, session.headers, maxBytes = MAX_SEGMENT_BYTES) ?: return null
        val key = segment.key ?: return body
        return runCatching {
            javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(
                    javax.crypto.Cipher.DECRYPT_MODE,
                    javax.crypto.spec.SecretKeySpec(keyBytes, "AES"),
                    javax.crypto.spec.IvParameterSpec(key.iv),
                )
            }.doFinal(body)
        }.getOrElse { error ->
            log.w { "segment ${segment.sequence} did not decrypt: ${describeIptvFailure(error)}" }
            null
        }
    }

    /**
     * A video segment, decrypted and re-timed. Usually made ready by [prefetch] before mpv asks;
     * otherwise started now. A body that is not MPEG-TS is served decrypted but otherwise untouched.
     */
    private fun serveMediaSegment(session: Session, resource: String, output: OutputStream, headOnly: Boolean) {
        val sequence = resource.removePrefix("ts/").removeSuffix(".ts").toLongOrNull()
            ?: return output.respond(404, "Not Found")
        val segment = synchronized(session) { session.mediaSegments[sequence] } ?: return output.respond(404, "Not Found")
        val started = System.nanoTime()
        if (!session.segmentsReady.containsKey(sequence)) prefetch(session, segment)
        val pending = session.segmentsReady[sequence]
        val wasReady = pending?.isDone == true
        val body = runCatching { pending?.get(SEGMENT_WAIT_SECONDS, TimeUnit.SECONDS) }.getOrNull()
        if (body == null) {
            // Let a retry start a fresh download rather than meet the same failure.
            session.segmentsReady.remove(sequence)
            return output.respond(502, "Bad Gateway")
        }
        log.d { "segment $sequence served (${if (wasReady) "ready" else "waited ${(System.nanoTime() - started) / 1_000_000}ms"})" }
        output.respond(200, "OK", body, "video/mp2t", headOnly)
    }

    /**
     * [bytes] re-timed onto the channel's continuous clock ([MpegTsContinuity]), as a new array.
     * Runs on [retimer], segment after segment in sequence order.
     */
    private fun retime(session: Session, segment: HlsMediaSegment, bytes: ByteArray): ByteArray {
        val body = bytes.copyOf()
        if (body.isEmpty() || body[0] != 0x47.toByte()) return body
        val result = session.continuity.retime(body)
        result.videoOffset?.let { offset ->
            session.videoOffsets[segment.upstreamSequence] = offset
            session.lastVideoOffset = offset
            if (session.videoOffsets.size > MAX_SEGMENTS_PER_SESSION) {
                session.videoOffsets.keys.minOrNull()?.let { session.videoOffsets.remove(it) }
            }
        }
        result.videoStart?.let { start ->
            session.videoStarts[segment.upstreamSequence] = start
            session.lastVideoStart = segment.upstreamSequence to start
            if (session.videoStarts.size > MAX_SEGMENTS_PER_SESSION) {
                session.videoStarts.keys.minOrNull()?.let { session.videoStarts.remove(it) }
            }
        }
        log.d {
            "segment ${segment.sequence} (upstream ${segment.upstreamSequence}) re-timed: " +
                result.ranges.entries.sortedBy { it.key }.joinToString(" ") { (kind, range) ->
                    "${kind.name.lowercase()} ${"%.2f".format(range.first / 90_000.0)}..${"%.2f".format(range.last / 90_000.0)}"
                }
        }
        for (jump in result.joins) {
            session.joins++
            log.i { "re-timed a join in ${session.host} at segment ${segment.sequence}: video timestamps jumped ${"%.1f".format(jump)}s (join ${session.joins})" }
        }
        return body
    }

    /** A body (a key, a segment) as bytes; null, logged, on a failure or when it exceeds [maxBytes]. */
    private fun fetchBytes(url: String, headers: List<Pair<String, String>>, maxBytes: Long): ByteArray? {
        val request = Request.Builder().url(url).apply { headers.forEach { (name, value) -> addHeader(name, value) } }.build()
        val client = IptvProxiedClients.clientFor(IptvRepository.settings.value.proxy).newBuilder()
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
        return runCatching {
            retryRelayConnect { client.newCall(request).execute() }.use { response ->
                if (!response.isSuccessful) {
                    log.w { "fetch from ${request.url.host} returned HTTP ${response.code}" }
                    return@use null
                }
                val source = response.body?.source() ?: return@use null
                source.request(maxBytes + 1)
                source.buffer.takeIf { it.size <= maxBytes }?.readByteArray()
            }
        }.getOrElse { error ->
            log.w { "fetch from ${request.url.host} failed: ${describeIptvFailure(error)}" }
            null
        }
    }

    /** The body as text, and the URL it finally came from (redirects followed); null on an HTTP error, throws on a network one. */
    private fun fetch(url: String, headers: List<Pair<String, String>>, timeoutSeconds: Long = 20): Pair<String, HttpUrl>? {
        val request = Request.Builder().url(url).apply { headers.forEach { (name, value) -> addHeader(name, value) } }.build()
        val client = IptvProxiedClients.clientFor(IptvRepository.settings.value.proxy).newBuilder()
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
        return retryRelayConnect { client.newCall(request).execute() }.use { response ->
            if (!response.isSuccessful) {
                log.w { "fetch from ${response.request.url.host} returned HTTP ${response.code}" }
                return null
            }
            val body = response.body ?: return null
            if (body.contentLength() > MAX_PLAYLIST_BYTES) return null
            val bytes = body.source().apply { request(MAX_PLAYLIST_BYTES + 1) }.buffer
            if (bytes.size > MAX_PLAYLIST_BYTES) return null
            bytes.readUtf8() to response.request.url
        }
    }

    private fun OutputStream.respond(
        code: Int,
        message: String,
        body: ByteArray = ByteArray(0),
        contentType: String = "text/plain",
        headOnly: Boolean = false,
    ) {
        val head = "HTTP/1.1 $code $message\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-cache\r\nConnection: close\r\n\r\n"
        write(head.toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) write(body)
        flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 16_384) return null
        }
    }

    private fun randomId(): String =
        ByteArray(16).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private val SkippedRequestHeaders = setOf("host", "connection", "accept-encoding", "content-length", "transfer-encoding")
}

/**
 * HTTP/1.1 chunked transfer coding, in chunks of up to 64 KiB. [close] writes the last chunk, so a
 * body cut short by an error must not be closed: unterminated, it reads as incomplete. FFmpeg's
 * HTTP reader needs this or a `Content-Length`: a body that just runs to the connection's close
 * counts as cut short, and the segment is fetched again and again.
 */
internal class ChunkedOutput(private val out: OutputStream) : OutputStream() {
    private val buffer = java.io.ByteArrayOutputStream(64 * 1024)
    override fun write(b: Int) {
        buffer.write(b)
        if (buffer.size() >= 64 * 1024) flushChunk()
    }
    override fun write(b: ByteArray, off: Int, len: Int) {
        buffer.write(b, off, len)
        if (buffer.size() >= 64 * 1024) flushChunk()
    }
    private fun flushChunk() {
        if (buffer.size() == 0) return
        out.write("${Integer.toHexString(buffer.size())}\r\n".toByteArray(Charsets.ISO_8859_1))
        buffer.writeTo(out)
        out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()
        buffer.reset()
    }
    override fun flush() = flushChunk()
    override fun close() {
        flushChunk()
        out.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }
}
