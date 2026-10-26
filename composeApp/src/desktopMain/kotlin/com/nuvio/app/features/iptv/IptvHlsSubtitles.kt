package com.nuvio.app.features.iptv

import okhttp3.HttpUrl

/*
 * Playlist rewriting, media-sequence normalising and WebVTT de-duplication for
 * [IptvHlsSubtitleServer].
 *
 * Live HLS splits subtitles into short WebVTT segments, and some providers (Pluto TV among them)
 * write a cue that crosses a segment boundary into both segments, with the same timing. A player
 * built for the provider drops the repeat. mpv only drops a packet it has seen at the same position
 * of the same file, so it keeps both and libass stacks them: every boundary-crossing line shows
 * twice. These functions let the server hand mpv a subtitle stream without the repeats.
 *
 * Pluto's stitcher also restarts a live playlist's numbering at some clip changes (sequence
 * 252 -> 77, the next clip's own segment number). FFmpeg takes the lower numbers for segments it
 * has already played and waits for the count to pass where it was: minutes of frozen picture.
 * [HlsSequenceNormalizer] keeps the numbering rising.
 */

private val UriAttribute = Regex("URI=\"([^\"]*)\"")

/** Whether a master playlist offers a subtitle rendition the server could stand in for. */
internal fun hlsMasterHasSubtitles(text: String): Boolean =
    text.lineSequence().any { it.isSubtitleMediaTag() && UriAttribute.containsMatchIn(it) }

/** How many variants (`#EXT-X-STREAM-INF`) a master playlist offers; 0 for a media playlist. */
internal fun hlsVariantCount(text: String): Int =
    text.lineSequence().count { it.startsWith("#EXT-X-STREAM-INF") }

private val BandwidthAttribute = Regex("(?<![-A-Z])BANDWIDTH=(\\d+)")
private val AverageBandwidthAttribute = Regex("AVERAGE-BANDWIDTH=(\\d+)")

private fun String.quotedAttribute(name: String): String? =
    Regex("(?:^|[:,])$name=\"([^\"]*)\"").find(this)?.groupValues?.get(1)

private fun String.plainAttribute(name: String): String? =
    Regex("(?:^|[:,])$name=([^,\"]+)").find(this)?.groupValues?.get(1)

/** An audio rendition describing the picture for blind viewers, by its HLS characteristic or its name. */
private fun String.isAudioDescription(): Boolean =
    quotedAttribute("CHARACTERISTICS")?.contains("describes-video") == true ||
        quotedAttribute("NAME")?.let { name ->
            name.contains("description", ignoreCase = true) || name.contains("descriptive", ignoreCase = true)
        } == true

/**
 * The master playlist cut down to what mpv plays. FFmpeg opens every variant and rendition of a
 * live master before the first frame, one after another (playlist, key, first segment each), which
 * took 15-30 s on Pluto TV; but it never switches variant during playback, and mpv plays the
 * highest-bandwidth one. So this keeps that variant, the subtitle renditions it names, and one
 * audio rendition: its group's default, else the first, never audio description while there is
 * another. Session tags and anything unrecognised pass through. URIs are left as they are.
 */
internal fun trimHlsMaster(text: String): String {
    val lines = text.lines()
    data class Variant(val tag: String, val uri: String, val bandwidth: Long)
    val variants = ArrayList<Variant>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        if (line.startsWith("#EXT-X-STREAM-INF")) {
            val uri = lines.drop(index + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            if (uri != null) {
                val bandwidth = BandwidthAttribute.find(line)?.groupValues?.get(1)?.toLongOrNull()
                    ?: AverageBandwidthAttribute.find(line)?.groupValues?.get(1)?.toLongOrNull()
                    ?: 0L
                variants += Variant(line, uri, bandwidth)
            }
        }
        index++
    }
    if (variants.size < 2) return text
    val kept = variants.maxBy { it.bandwidth } // first of equals: maxBy keeps the earliest
    val audioGroup = kept.tag.quotedAttribute("AUDIO")
    val subtitleGroup = kept.tag.quotedAttribute("SUBTITLES")
    val videoGroup = kept.tag.quotedAttribute("VIDEO")

    val audio = lines.filter { it.startsWith("#EXT-X-MEDIA:") && it.plainAttribute("TYPE") == "AUDIO" &&
        it.quotedAttribute("GROUP-ID") == audioGroup }
    val audioCandidates = audio.filterNot { it.isAudioDescription() }.ifEmpty { audio }
    val mainAudio = audioCandidates.firstOrNull { it.plainAttribute("DEFAULT") == "YES" } ?: audioCandidates.firstOrNull()

    val out = ArrayList<String>()
    var skipUri = false
    for (line in lines) {
        when {
            skipUri -> if (line.isNotBlank() && !line.startsWith("#")) skipUri = false
            line.startsWith("#EXT-X-STREAM-INF") -> if (line === kept.tag) out += line else skipUri = true
            // Trick-play playlists: never read by FFmpeg for playback.
            line.startsWith("#EXT-X-I-FRAME-STREAM-INF") -> Unit
            line.startsWith("#EXT-X-MEDIA:") -> {
                val group = line.quotedAttribute("GROUP-ID")
                val keep = when (line.plainAttribute("TYPE")) {
                    "AUDIO" -> line === mainAudio
                    "SUBTITLES" -> group == subtitleGroup
                    "VIDEO" -> group == videoGroup
                    else -> true // CLOSED-CAPTIONS: no URI, nothing to open
                }
                if (keep) out += line
            }
            line.isNotBlank() && !line.startsWith("#") -> if (line === kept.uri) out += line
            else -> out += line
        }
    }
    return out.joinToString("\n")
}

private fun String.isSubtitleMediaTag(): Boolean =
    startsWith("#EXT-X-MEDIA:") && contains("TYPE=SUBTITLES")

/**
 * The master playlist with its media playlists sent to the local server: subtitle renditions to
 * [localSubtitlePlaylist], variants and other renditions (audio, video) to [localMediaPlaylist].
 * Both are given the absolute upstream URL and return the local one. Every other URI (session
 * keys, trick-play) is made absolute against [base], so it still loads straight from the provider.
 */
internal fun rewriteHlsMaster(
    text: String,
    base: HttpUrl,
    localSubtitlePlaylist: (absoluteUrl: String) -> String,
    localMediaPlaylist: (absoluteUrl: String) -> String = { it },
): String = text.lineSequence().joinToString("\n") { line ->
    when {
        line.isSubtitleMediaTag() -> line.replaceUris { localSubtitlePlaylist(base.absolute(it)) }
        line.startsWith("#EXT-X-MEDIA:") -> line.replaceUris { localMediaPlaylist(base.absolute(it)) }
        line.startsWith("#") -> line.replaceUris { base.absolute(it) }
        line.isBlank() -> line
        else -> localMediaPlaylist(base.absolute(line.trim()))
    }
}

/** Tags that belong to the segment after them rather than to the playlist. */
private val SegmentTagPrefixes = listOf(
    "#EXTINF", "#EXT-X-DISCONTINUITY", "#EXT-X-KEY", "#EXT-X-MAP", "#EXT-X-PROGRAM-DATE-TIME",
    "#EXT-X-BYTERANGE", "#EXT-X-DATERANGE", "#EXT-X-GAP", "#EXT-X-CUE", "#EXT-OATCLS",
)

private fun String.isSegmentTag(): Boolean =
    SegmentTagPrefixes.any { startsWith(it) } && !startsWith("#EXT-X-DISCONTINUITY-SEQUENCE")

private fun String.isDiscontinuityTag(): Boolean = this == "#EXT-X-DISCONTINUITY"

/**
 * A live media playlist renumbered by [normalizer] (`EXT-X-MEDIA-SEQUENCE` and
 * `EXT-X-DISCONTINUITY-SEQUENCE` rewritten, `#EXT-X-DISCONTINUITY` added where the upstream count
 * restarted), every URI made absolute against [base], and segments sent to [localSegment] when
 * given. A key with no `IV` is spelled out per segment with the IV it implied (the segment's
 * upstream sequence number), since the renumbering would otherwise change it.
 *
 * [localSegment] is ignored for encrypted or fMP4 (`EXT-X-MAP`) segments: the server can read
 * neither, so they stay with mpv. A playlist with no segments is only made absolute.
 */
internal fun rewriteHlsMediaPlaylist(
    text: String,
    base: HttpUrl,
    normalizer: HlsSequenceNormalizer,
    localSegment: ((absoluteUrl: String, upstreamSequence: Long) -> String)? = null,
    localMediaSegment: ((HlsMediaSegment) -> String)? = null,
    holdBack: Int = 0,
): Pair<String, HlsSequenceNormalizer.Numbering?> {
    val lines = text.lines()
    if (lines.none { it.startsWith("#EXTINF") }) {
        return lines.joinToString("\n") { line ->
            when {
                line.startsWith("#") -> line.replaceUris { base.absolute(it) }
                line.isBlank() -> line
                else -> base.absolute(line.trim())
            }
        } to null
    }
    val readable = lines.none { line ->
        line.startsWith("#EXT-X-MAP") || (line.startsWith("#EXT-X-KEY") && !line.contains("METHOD=NONE"))
    }
    // Segments the server can decrypt and re-time: MPEG-TS whole files, clear or AES-128.
    val retimable = localMediaSegment != null && lines.none { line ->
        line.startsWith("#EXT-X-MAP") || line.startsWith("#EXT-X-BYTERANGE") ||
            (line.startsWith("#EXT-X-KEY") && !line.contains("METHOD=NONE") && !line.contains("METHOD=AES-128"))
    }

    class Segment(val tags: List<String>, val uri: String)
    val header = ArrayList<String>()
    val segments = ArrayList<Segment>()
    val footer = ArrayList<String>()
    var pending = ArrayList<String>()
    var upstreamSequence = 0L
    var upstreamDiscontinuities = 0L
    for (line in lines) {
        when {
            line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                upstreamSequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
            line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:") ->
                upstreamDiscontinuities = line.substringAfter(':').trim().toLongOrNull() ?: 0L
            line.isBlank() -> Unit
            !line.startsWith("#") -> {
                segments += Segment(pending, base.absolute(line.trim()))
                pending = ArrayList()
            }
            segments.isEmpty() && pending.isEmpty() && !line.isSegmentTag() -> header += line
            else -> pending += line
        }
    }
    footer += pending // after the last segment: ENDLIST, provider comments
    // A live playlist's newest [holdBack] segments are left for the next refresh (at least one
    // segment stays): see [rewriteHlsMediaPlaylist]'s callers for why.
    if (holdBack > 0 && lines.none { it.startsWith("#EXT-X-ENDLIST") }) {
        repeat(segments.size - maxOf(1, segments.size - holdBack)) { segments.removeAt(segments.lastIndex) }
    }

    val numbering = normalizer.number(
        upstreamSequence = upstreamSequence,
        upstreamDiscontinuitySequence = upstreamDiscontinuities,
        uris = segments.map { it.uri },
        upstreamDiscontinuity = segments.map { segment -> segment.tags.any { it.isDiscontinuityTag() } },
    )
    if (numbering.stale) return "" to numbering // the caller keeps what it served last

    val out = ArrayList<String>()
    out += header
    out += "#EXT-X-MEDIA-SEQUENCE:${numbering.mediaSequence}"
    out += "#EXT-X-DISCONTINUITY-SEQUENCE:${numbering.discontinuitySequence}"
    var key: String? = null
    segments.forEachIndexed { index, segment ->
        val addedDiscontinuity = index in numbering.addDiscontinuityBefore
        if (addedDiscontinuity) out += "#EXT-X-DISCONTINUITY"
        var durationMs = 0L
        for (tag in segment.tags) {
            if (tag.startsWith("#EXT-X-KEY")) {
                key = tag.replaceUris { base.absolute(it) }
            } else {
                if (tag.startsWith("#EXTINF:")) {
                    durationMs = ((tag.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0) * 1000).toLong()
                }
                out += tag.replaceUris { base.absolute(it) }
            }
        }
        val effectiveKey = key?.let { current ->
            if (current.contains("METHOD=AES-128") && !current.contains("IV=")) {
                // Not format() on the line itself: its URI may hold %-escapes.
                current + ",IV=0x" + "%032x".format(upstreamSequence + index)
            } else {
                current
            }
        }
        if (retimable && localMediaSegment != null) {
            // Served decrypted, so no key line: the server reads it from the segment.
            out += localMediaSegment(
                HlsMediaSegment(
                    uri = segment.uri,
                    sequence = numbering.mediaSequence + index,
                    upstreamSequence = upstreamSequence + index,
                    durationMs = durationMs,
                    discontinuity = addedDiscontinuity || segment.tags.any { it.isDiscontinuityTag() },
                    key = effectiveKey?.let(::parseHlsKey),
                ),
            )
        } else {
            effectiveKey?.let { out += it }
            out += if (readable && localSegment != null) localSegment(segment.uri, upstreamSequence + index) else segment.uri
        }
    }
    out += footer
    return out.joinToString("\n") to numbering
}

/** A media segment as the server needs it to fetch, decrypt and place it ([HlsTimeline]). */
internal class HlsMediaSegment(
    /** Absolute upstream URL. */
    val uri: String,
    /** The number it is served under (normalised). */
    val sequence: Long,
    /** The number the provider gave it. */
    val upstreamSequence: Long,
    val durationMs: Long,
    /** A new file starts here (marked by the provider, or a numbering restart). */
    val discontinuity: Boolean,
    /** Its AES-128 key, or null when it is clear. */
    val key: HlsKey?,
)

/** An AES-128 segment key: where to fetch it and the IV (explicit, or implied by the sequence). */
internal class HlsKey(val uri: String, val iv: ByteArray)

/** An `#EXT-X-KEY` line (URI absolute, IV always present) as an [HlsKey]; null for `METHOD=NONE`. */
internal fun parseHlsKey(line: String): HlsKey? {
    if (!line.contains("METHOD=AES-128")) return null
    val uri = line.quotedAttribute("URI") ?: return null
    val hex = line.plainAttribute("IV")?.removePrefix("0x")?.removePrefix("0X") ?: return null
    val iv = ByteArray(16)
    val digits = hex.padStart(32, '0').takeLast(32)
    for (i in 0 until 16) iv[i] = digits.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    return HlsKey(uri, iv)
}

/**
 * Keeps one live media playlist's numbering rising across refreshes, whatever the provider does.
 *
 * A segment keeps the number it was first served under, so a refresh that overlaps the last one
 * lines up with it. A refresh with no segment seen before is either the first (upstream's numbers
 * are kept) or a restart: its segments carry on from the last number served, the first one marked
 * as a discontinuity, and the discontinuity count carries on too. Not thread-safe: one per
 * playlist, called under its session's lock.
 */
internal class HlsSequenceNormalizer(private val memory: Int = 512) {
    class Numbering(
        val mediaSequence: Long,
        val discontinuitySequence: Long,
        /** Segment indexes to precede with `#EXT-X-DISCONTINUITY` that upstream did not mark. */
        val addDiscontinuityBefore: Set<Int>,
        /** Upstream restarted its numbering in this refresh. */
        val restarted: Boolean,
        val upstreamSequence: Long,
        /**
         * An older copy of the playlist than one already numbered (a lagging CDN node): it ends
         * before segments already listed, and serving it would send the player back in time.
         * Nothing was remembered from it.
         */
        val stale: Boolean = false,
    )

    private val numberOf = LinkedHashMap<String, Long>()
    private val discontinuities = java.util.TreeSet<Long>()
    private var nextSequence = -1L
    private var discontinuitiesBefore = 0L

    fun number(
        upstreamSequence: Long,
        upstreamDiscontinuitySequence: Long,
        uris: List<String>,
        upstreamDiscontinuity: List<Boolean>,
    ): Numbering {
        val known = uris.indexOfFirst { it in numberOf }
        val first = nextSequence < 0
        val restarted = known < 0 && !first
        val mediaSequence = when {
            known >= 0 -> numberOf.getValue(uris[known]) - known
            first -> upstreamSequence
            else -> nextSequence
        }
        if (known >= 0 && mediaSequence + uris.size < nextSequence) {
            return Numbering(mediaSequence, discontinuitiesBefore, emptySet(), false, upstreamSequence, stale = true)
        }
        if (first) discontinuitiesBefore = upstreamDiscontinuitySequence
        uris.forEachIndexed { index, uri ->
            val number = mediaSequence + index
            numberOf.remove(uri)
            numberOf[uri] = number
            if (upstreamDiscontinuity[index]) discontinuities += number
        }
        if (restarted && uris.isNotEmpty()) discontinuities += mediaSequence
        while (numberOf.size > memory) numberOf.remove(numberOf.keys.first())
        nextSequence = maxOf(nextSequence, mediaSequence + uris.size)
        // Discontinuities that scrolled off the front are what EXT-X-DISCONTINUITY-SEQUENCE counts.
        while (discontinuities.isNotEmpty() && discontinuities.first() < mediaSequence) {
            discontinuities.pollFirst()
            discontinuitiesBefore++
        }
        val added = uris.indices.filterTo(HashSet()) { index ->
            !upstreamDiscontinuity[index] && (mediaSequence + index) in discontinuities
        }
        return Numbering(mediaSequence, discontinuitiesBefore, added, restarted, upstreamSequence)
    }
}

private fun HttpUrl.absolute(reference: String): String = resolve(reference)?.toString() ?: reference

private fun String.replaceUris(transform: (String) -> String): String =
    UriAttribute.replace(this) { match -> "URI=\"${transform(match.groupValues[1])}\"" }

/**
 * Drops WebVTT cues that an earlier segment of the same subtitle stream already carried. A cue is
 * its start, end and text; the cue settings are left out because the repeat can differ there
 * (Pluto adds `align:center` to the copy). Times are compared on the stream's clock, using each
 * segment's `X-TIMESTAMP-MAP`, so a provider that maps every segment differently still matches.
 *
 * The segment that first carried a cue keeps it: mpv fetching the same segment again (a retry, a
 * reload) gets the same cues back rather than an empty file. Remembers the last [capacity] cues.
 */
internal class WebVttCueDeduper(private val capacity: Int = 4_000) {
    private val firstSeenIn = LinkedHashMap<String, String>()

    /** [vtt] without the cues already sent by another segment, and how many were dropped. */
    @Synchronized
    fun filter(segmentKey: String, vtt: String): Pair<String, Int> {
        val text = vtt.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = text.split(BlankLines).filter { it.isNotBlank() }
        if (blocks.isEmpty() || !blocks.first().trimStart('﻿').startsWith("WEBVTT")) return vtt to 0
        val offsetMs = timestampMapOffsetMs(blocks.first())
        var dropped = 0
        val kept = blocks.filterIndexed { index, block ->
            if (index == 0) return@filterIndexed true
            val key = cueKey(block, offsetMs) ?: return@filterIndexed true
            val owner = firstSeenIn[key]
            when {
                owner == null -> {
                    firstSeenIn[key] = segmentKey
                    if (firstSeenIn.size > capacity) firstSeenIn.remove(firstSeenIn.keys.first())
                    true
                }
                owner == segmentKey -> true
                else -> {
                    dropped++
                    false
                }
            }
        }
        if (dropped == 0) return vtt to 0
        return kept.joinToString("\n\n", postfix = "\n") { it.trimEnd('\n') } to dropped
    }

    private fun cueKey(block: String, offsetMs: Long): String? {
        val lines = block.trim('\n').lines()
        val timingIndex = lines.indexOfFirst { "-->" in it }.takeIf { it >= 0 } ?: return null
        val timing = lines[timingIndex]
        val start = parseVttTimestampMs(timing.substringBefore("-->").trim()) ?: return null
        val end = parseVttTimestampMs(timing.substringAfter("-->").trim().substringBefore(' ')) ?: return null
        val payload = lines.drop(timingIndex + 1).joinToString("\n") { it.trim() }.trim()
        return "${start + offsetMs}|${end + offsetMs}|$payload"
    }

    private companion object {
        val BlankLines = Regex("\n[ \t]*\n")
    }
}

private val CueTiming = Regex("""^(\S+)\s+-->\s+(\S+)(.*)$""")

/**
 * [vtt] with its `X-TIMESTAMP-MAP` applied to the cue times and the map line dropped. HLS says
 * cue time LOCAL is video timestamp MPEGTS, but FFmpeg's WebVTT reader ignores the map and takes
 * cue times as video time: Pluto's `MPEGTS:90000,LOCAL:0` (cue 0 = 1 s) put every line a second
 * early. Shifting the cues does what the map asked, and dropping it keeps it from counting twice
 * should a reader start honouring it. Unchanged when there is no map or it maps 0 to 0.
 */
internal fun applyWebVttTimestampMap(vtt: String, extraShiftMs: Long = 0L): String {
    val text = vtt.replace("\r\n", "\n").replace('\r', '\n')
    val lines = text.lines()
    val headerEnd = lines.indexOfFirst { it.isBlank() }.let { if (it < 0) lines.size else it }
    // [extraShiftMs]: the re-timed video's offset for this stretch (HlsTimeline), so cues follow it.
    val offsetMs = timestampMapOffsetMs(lines.take(headerEnd).joinToString("\n")) + extraShiftMs
    if (offsetMs == 0L) return vtt
    return lines.mapIndexedNotNull { index, line ->
        when {
            index < headerEnd && line.startsWith("X-TIMESTAMP-MAP=") -> null
            "-->" in line -> CueTiming.find(line)?.let { match ->
                val start = parseVttTimestampMs(match.groupValues[1])
                val end = parseVttTimestampMs(match.groupValues[2])
                if (start == null || end == null) {
                    line
                } else {
                    "${formatVttTimestamp(start + offsetMs)} --> ${formatVttTimestamp(end + offsetMs)}${match.groupValues[3]}"
                }
            } ?: line
            else -> line
        }
    }.joinToString("\n")
}

/** How far a subtitle segment's first cue may sit from its video segment and still count as placed (10 s). */
private const val SUBTITLE_PLACEMENT_SLACK_MS = 10_000L

/**
 * [vtt] placed on the re-timed video's clock, its map applied. [shiftMs] (the video's own offset)
 * is used when it puts the first cue in or near the matching video segment, which starts at
 * [videoStartMs] and runs [segmentMs]: right whenever the provider's map is. When it does not
 * (Pluto's cues count from the film's start while some programmes' video counts from near the
 * 26-hour wrap, which put cues minutes behind the video, and FFmpeg, reading whichever playlist is
 * furthest behind, then fed on subtitles and starved the picture), the segment's window (its first
 * cue rounded down to a whole segment) is moved onto the video segment's start instead.
 */
internal fun placeWebVttSegment(vtt: String, videoStartMs: Long, segmentMs: Long, shiftMs: Long): String {
    val text = vtt.replace("\r\n", "\n").replace('\r', '\n')
    val lines = text.lines()
    val headerEnd = lines.indexOfFirst { it.isBlank() }.let { if (it < 0) lines.size else it }
    val mapMs = timestampMapOffsetMs(lines.take(headerEnd).joinToString("\n"))
    val firstCue = lines.asSequence().filter { "-->" in it }
        .mapNotNull { line -> CueTiming.find(line)?.groupValues?.get(1)?.let(::parseVttTimestampMs) }
        .minOrNull()?.plus(mapMs)
        ?: return applyWebVttTimestampMap(vtt, shiftMs)
    val window = segmentMs.coerceAtLeast(1_000L)
    val placed = firstCue + shiftMs
    if (placed >= videoStartMs - SUBTITLE_PLACEMENT_SLACK_MS && placed <= videoStartMs + window + SUBTITLE_PLACEMENT_SLACK_MS) {
        return applyWebVttTimestampMap(vtt, shiftMs)
    }
    val windowStart = firstCue / window * window
    return applyWebVttTimestampMap(vtt, videoStartMs - windowStart)
}

/** [vtt]'s header block alone (`WEBVTT` and its header lines, map dropped): a segment with no cues. */
internal fun webVttHeaderOnly(vtt: String): String {
    val lines = vtt.replace("\r\n", "\n").replace('\r', '\n').lines()
    val headerEnd = lines.indexOfFirst { it.isBlank() }.let { if (it < 0) lines.size else it }
    return lines.take(headerEnd).filterNot { it.startsWith("X-TIMESTAMP-MAP=") }
        .ifEmpty { listOf("WEBVTT") }.joinToString("\n", postfix = "\n")
}

/**
 * [vtt] with an invisible cue (1 ms of a zero-width space) at [atMs] when it has none, so every
 * subtitle segment moves the subtitle stream on. FFmpeg's HLS reader always reads next from the
 * stream that is furthest behind; a run of cue-less segments (an ad break) left the subtitle stream
 * stuck at the last line before it, so FFmpeg kept reading subtitle segments, reached the live
 * edge of that playlist and waited there in real time while the picture starved.
 */
internal fun withFillerCue(vtt: String, atMs: Long): String {
    if (firstCueSeconds(vtt) != null) return vtt
    val head = vtt.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n').ifBlank { "WEBVTT" }
    return "$head\n\n${formatVttTimestamp(atMs)} --> ${formatVttTimestamp(atMs + 1)}\n​\n"
}

/** The earliest cue start in [vtt], in seconds (no map applied); null when it has no cues. */
internal fun firstCueSeconds(vtt: String): Double? =
    vtt.lineSequence().filter { "-->" in it }
        .mapNotNull { line -> CueTiming.find(line)?.groupValues?.get(1)?.let(::parseVttTimestampMs) }
        .minOrNull()?.div(1000.0)

/** [ms] as `hh:mm:ss.ttt`, hours unbounded; never negative. */
internal fun formatVttTimestamp(ms: Long): String {
    val clamped = ms.coerceAtLeast(0L)
    return "%02d:%02d:%02d.%03d".format(
        clamped / 3_600_000, clamped / 60_000 % 60, clamped / 1_000 % 60, clamped % 1_000,
    )
}

/** `X-TIMESTAMP-MAP=MPEGTS:<90 kHz ticks>,LOCAL:<cue time>` as (stream time - cue time) in ms; 0 without one. */
internal fun timestampMapOffsetMs(header: String): Long {
    val line = header.lines().firstOrNull { it.startsWith("X-TIMESTAMP-MAP=") } ?: return 0L
    val fields = line.substringAfter('=').split(',').associate { field ->
        field.substringBefore(':').trim().uppercase() to field.substringAfter(':').trim()
    }
    val mpegts = fields["MPEGTS"]?.toLongOrNull() ?: return 0L
    val local = fields["LOCAL"]?.let(::parseVttTimestampMs) ?: return 0L
    return mpegts / 90 - local
}

/** `hh:mm:ss.ttt` or `mm:ss.ttt` in milliseconds; null if it is neither. */
internal fun parseVttTimestampMs(value: String): Long? {
    val parts = value.split(':')
    if (parts.size !in 2..3) return null
    val seconds = parts.last().split('.')
    if (seconds.size != 2) return null
    val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
    val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
    val secs = seconds[0].toLongOrNull() ?: return null
    val millis = seconds[1].padEnd(3, '0').take(3).toLongOrNull() ?: return null
    return ((hours * 60 + minutes) * 60 + secs) * 1000 + millis
}
