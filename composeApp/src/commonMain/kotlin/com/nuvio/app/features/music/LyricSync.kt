package com.nuvio.app.features.music

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToLong

/** Lyrics with timing moved by [offsetMs]; plain and instrumental lyrics have no timing to move. */
internal fun Lyrics.shiftedBy(offsetMs: Long): Lyrics = when {
    offsetMs == 0L -> this
    this is Lyrics.Synced -> Lyrics.Synced(lines.shiftedBy(offsetMs))
    else -> this
}

/** Where the offset of one stream (see [lyricSyncKey]) stands. */
sealed interface LyricSyncState {
    /** Nothing asked yet; the lyrics play at their own timing. */
    data object Idle : LyricSyncState

    /** The stream is being analysed. */
    data object Checking : LyricSyncState

    /** The offset to apply, in ms; [auto] when the analysis found it (the person's nudges are added to it). */
    data class Offset(val offsetMs: Long, val auto: Boolean) : LyricSyncState

    /** Analysed without a trustworthy answer: the lyrics keep their own timing. */
    data object Unsure : LyricSyncState
}

/** What the platform needs to analyse one stream. */
internal class LyricAudioRequest(
    val lines: List<LyricLine>,
    val range: LongRange,
    val playedDurationMs: Long,
    /** Which load of the stream this is; a sampler for another load is not this audio. */
    val loadId: Long,
)

/** One offset per stream: the same song as audio and as video are different streams. */
fun lyricSyncKey(track: MusicTrack, realVideo: Boolean): String =
    "${track.videoId}:${if (realVideo) "video" else "audio"}"

/**
 * Offsets per stream for the session. Hand it the lyrics and the lengths with [sync] and read
 * [states]; [nudge] adds the person's own correction on top.
 */
object MusicLyricSync {
    private val log = Logger.withTag("LyricSync")
    private val lock = Mutex()
    private val _states = MutableStateFlow<Map<String, LyricSyncState>>(emptyMap())
    val states: StateFlow<Map<String, LyricSyncState>> = _states.asStateFlow()

    /**
     * Installed by the platform that can read the stream's audio: gives the alignment of the lyrics
     * on the audio, or null when it could not read it. Desktop installs it with the player surface.
     */
    @Volatile
    internal var audioEstimator: (suspend (LyricAudioRequest) -> LyricAlignment?)? = null

    fun stateOf(key: String): LyricSyncState = _states.value[key] ?: LyricSyncState.Idle

    /**
     * Works out the offset of [lines] on the stream [key] unless that is already known. The same cut
     * as the album is settled without reading any audio. A stream that could not be read (no
     * estimator yet, another load) stays [LyricSyncState.Idle] so a later call tries again.
     */
    internal suspend fun sync(
        key: String,
        lines: List<LyricLine>,
        trackDurationMs: Long,
        playedDurationMs: Long,
        loadId: Long,
    ) {
        if (stateOf(key) !is LyricSyncState.Idle) return
        val range = lyricSearchRange(trackDurationMs, playedDurationMs)
        if (range == null) {
            set(key, LyricSyncState.Offset(0L, auto = true))
            return
        }
        val estimator = audioEstimator ?: return
        lock.withLock {
            if (stateOf(key) !is LyricSyncState.Idle) return
            set(key, LyricSyncState.Checking)
            val alignment = try {
                estimator(LyricAudioRequest(lines, range, playedDurationMs, loadId))
            } catch (cancelled: CancellationException) {
                set(key, LyricSyncState.Idle)
                throw cancelled
            } catch (error: Throwable) {
                log.w { "analysis of $key failed: ${error.message}" }
                null
            }
            if (alignment == null) {
                // Not readable now: try again next time rather than settle on nothing.
                set(key, LyricSyncState.Idle)
                return
            }
            val offset = acceptLyricAlignment(alignment)
            log.i {
                "$key range=${range.first}..${range.last}ms shift=${alignment.shiftMs.roundToLong()}ms " +
                    "peak=${round3(alignment.peak)} prominence=${round3(alignment.prominence)} " +
                    "lines=${alignment.lineCount} voice=${alignment.voiceSeconds.roundToLong()}s " +
                    "edge=${alignment.atSearchEdge} -> ${offset?.let { "${it}ms" } ?: "unsure"}"
            }
            // A nudge made while this ran is the person's word over the analysis.
            if (stateOf(key) is LyricSyncState.Checking) {
                set(key, if (offset == null) LyricSyncState.Unsure else LyricSyncState.Offset(offset, auto = true))
            }
        }
    }

    /** Moves the lyrics of [key] by [deltaMs] (the person's own fix, on top of whatever was found). */
    fun nudge(key: String, deltaMs: Long) {
        _states.update { current ->
            val base = (current[key] as? LyricSyncState.Offset)?.offsetMs ?: 0L
            current + (key to LyricSyncState.Offset(base + deltaMs, auto = false))
        }
    }

    /** Back to the lyrics' own timing for [key], and ready to be analysed again. */
    fun reset(key: String) {
        _states.update { it - key }
    }

    private fun set(key: String, state: LyricSyncState) {
        _states.update { it + (key to state) }
    }
}

private fun round3(value: Double): Double = (value * 1000.0).roundToLong() / 1000.0

/** The offset in force for a state: what was found or set, else none. */
internal fun LyricSyncState.offsetMs(): Long = (this as? LyricSyncState.Offset)?.offsetMs ?: 0L

