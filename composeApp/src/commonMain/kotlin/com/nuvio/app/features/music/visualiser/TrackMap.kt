package com.nuvio.app.features.music.visualiser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.floor

/*
 * fork:visualiser - what the Now Playing visualiser draws from. mpv plays the stream and the app never sees its samples
 * while they play, but the loudness measurement already decodes the whole track in the background (MusicPlayerSurface).
 * The same samples are analysed once into a track map: per 25 ms frame, 16 band levels, the overall, left and right level,
 * and which kinds of hit start in it; plus the beat grid when the song has a steady one. The visualiser then draws the
 * frame under the playhead, so a seek is only another frame and a second play reacts from the start (the map is kept).
 */

/** Length of one frame of the map. */
internal const val MAP_FRAME_MS = 25

/** Band levels per frame, log-spaced from 40 Hz to 11 kHz. */
internal const val MAP_BANDS = 16

/** Bit flags in [TrackMap.onsets]: a hit of that kind starts in the frame. */
internal object Onset {
    const val KICK = 1
    const val SNARE = 2
    const val HATS = 4
    const val MELODY = 8
}

/** A steady beat: [periodMs] between beats, and the time of a downbeat (the first beat of a bar of four). */
internal data class BeatGrid(val periodMs: Double, val downbeatMs: Double) {
    val bpm: Double get() = 60_000.0 / periodMs
    val stepMs: Double get() = periodMs / 4.0

    /** The sixteenth-note step [atMs] falls in, counted from the downbeat (negative before it). */
    fun stepAt(atMs: Double): Long = floor((atMs - downbeatMs) / stepMs).toLong()

    /** When step [step] starts. */
    fun stepStartMs(step: Long): Double = downbeatMs + step * stepMs
}

/** The analysed track. Values are stored as unsigned bytes (0..255 for 0..1). Frames past [frames] are not analysed yet. */
internal class TrackMap(
    val frames: Int,
    val bands: ByteArray,
    val level: ByteArray,
    val left: ByteArray,
    val right: ByteArray,
    val onsets: ByteArray,
    val beat: BeatGrid?,
    /** True once the whole track (or its first quarter of an hour) has been analysed. */
    val complete: Boolean,
) {
    val analysedMs: Long get() = frames.toLong() * MAP_FRAME_MS

    fun frameAt(ms: Long): Int = if (ms < 0) -1 else (ms / MAP_FRAME_MS).toInt()

    fun has(frame: Int): Boolean = frame in 0 until frames

    fun band(frame: Int, band: Int): Float = if (has(frame)) u(bands[frame * MAP_BANDS + band]) else 0f

    fun level(frame: Int): Float = if (has(frame)) u(level[frame]) else 0f

    fun left(frame: Int): Float = if (has(frame)) u(left[frame]) else 0f

    fun right(frame: Int): Float = if (has(frame)) u(right[frame]) else 0f

    fun hits(frame: Int, kind: Int): Boolean = has(frame) && (onsets[frame].toInt() and kind) != 0

    /** Milliseconds since the last [kind] hit at or before [atMs], looking back at most [withinMs]; null when none. */
    fun sinceLast(kind: Int, atMs: Long, withinMs: Long): Long? {
        val now = frameAt(atMs)
        if (!has(now)) return null
        val back = (withinMs / MAP_FRAME_MS).toInt()
        for (f in now downTo maxOf(0, now - back)) {
            if (hits(f, kind)) return atMs - f.toLong() * MAP_FRAME_MS
        }
        return null
    }

    /** True when any frame in [fromMs, toMs) holds a [kind] hit. */
    fun hitsBetween(kind: Int, fromMs: Double, toMs: Double): Boolean {
        val a = maxOf(0, floor(fromMs / MAP_FRAME_MS).toInt())
        val b = minOf(frames, kotlin.math.ceil(toMs / MAP_FRAME_MS).toInt())
        for (f in a until b) if (hits(f, kind)) return true
        return false
    }

    /** The loudest frame level in [fromMs, toMs), or null when none of it is analysed. */
    fun peakLevel(fromMs: Long, toMs: Long): Float? {
        val a = maxOf(0, (fromMs / MAP_FRAME_MS).toInt())
        val b = minOf(frames, ((toMs + MAP_FRAME_MS - 1) / MAP_FRAME_MS).toInt())
        if (a >= b) return null
        var peak = 0f
        for (f in a until b) peak = maxOf(peak, level(f))
        return peak
    }

    /** The mean of [values] over the [windowMs] before [atMs]: how a meter with that integration time would read. */
    fun meanOver(values: ByteArray, atMs: Long, windowMs: Long): Float {
        val b = frameAt(atMs)
        if (!has(b)) return 0f
        val a = maxOf(0, b - (windowMs / MAP_FRAME_MS).toInt() + 1)
        var sum = 0f
        for (f in a..b) sum += u(values[f])
        return sum / (b - a + 1)
    }

    /** [band] held like a meter with a fast rise and a slow fall: the strongest recent value, faded by its age. */
    fun heldBand(band: Int, atMs: Long, frames: Int = 10, fall: Float = 0.86f): Float {
        val now = frameAt(atMs)
        var best = 0f
        var weight = 1f
        for (k in 0 until frames) {
            val f = now - k
            if (f < 0) break
            best = maxOf(best, band(f, band) * weight)
            weight *= fall
        }
        return best
    }

    companion object {
        private fun u(b: Byte): Float = (b.toInt() and 0xFF) / 255f
    }
}

/** The map for the track loaded as [loadId], as far as it has been analysed. */
internal data class LiveTrackMap(
    val loadId: Long,
    val map: TrackMap?,
    /** The track's measured integrated loudness, when known. */
    val lufs: Double?,
    /** Still being analysed (the map grows as each window is read). */
    val analysing: Boolean,
    /** This track cannot be analysed here (no decode on this computer, or the stream refused a second connection). */
    val unavailable: Boolean = false,
)

/** Shared between the player surface, which analyses the track, and the Now Playing stage, which draws it. */
internal object TrackMapLive {
    private val _state = MutableStateFlow<LiveTrackMap?>(null)
    val state: StateFlow<LiveTrackMap?> = _state

    fun publish(live: LiveTrackMap) {
        _state.value = live
    }
}

// ---- storage format ----

private const val MAGIC = 0x4E564D31 // "NVM1"

/** The map as bytes for the disk cache. */
internal fun encodeTrackMap(map: TrackMap): ByteArray {
    val n = map.frames
    val out = ByteArray(4 + 4 + 1 + 1 + 8 + 8 + n * (MAP_BANDS + 4))
    var at = 0
    fun int(v: Int) { for (s in 24 downTo 0 step 8) out[at++] = (v ushr s).toByte() }
    fun long(v: Long) { for (s in 56 downTo 0 step 8) out[at++] = (v ushr s).toByte() }
    int(MAGIC)
    int(n)
    out[at++] = if (map.complete) 1 else 0
    out[at++] = if (map.beat != null) 1 else 0
    long((map.beat?.periodMs ?: 0.0).toRawBits())
    long((map.beat?.downbeatMs ?: 0.0).toRawBits())
    map.bands.copyInto(out, at, 0, n * MAP_BANDS); at += n * MAP_BANDS
    map.level.copyInto(out, at, 0, n); at += n
    map.left.copyInto(out, at, 0, n); at += n
    map.right.copyInto(out, at, 0, n); at += n
    map.onsets.copyInto(out, at, 0, n)
    return out
}

/** The map from [bytes], or null when they are not a map this version wrote. */
internal fun decodeTrackMap(bytes: ByteArray): TrackMap? {
    if (bytes.size < 26) return null
    var at = 0
    fun int(): Int { var v = 0; repeat(4) { v = (v shl 8) or (bytes[at++].toInt() and 0xFF) }; return v }
    fun long(): Long { var v = 0L; repeat(8) { v = (v shl 8) or (bytes[at++].toLong() and 0xFF) }; return v }
    if (int() != MAGIC) return null
    val n = int()
    if (n < 0 || bytes.size != 26 + n * (MAP_BANDS + 4)) return null
    val complete = bytes[at++] == 1.toByte()
    val hasBeat = bytes[at++] == 1.toByte()
    val period = Double.fromBits(long())
    val downbeat = Double.fromBits(long())
    fun take(size: Int): ByteArray = bytes.copyOfRange(at, at + size).also { at += size }
    val bands = take(n * MAP_BANDS)
    val level = take(n)
    val left = take(n)
    val right = take(n)
    val onsets = take(n)
    val beat = if (hasBeat && period > 0.0) BeatGrid(period, downbeat) else null
    return TrackMap(n, bands, level, left, right, onsets, beat, complete)
}
