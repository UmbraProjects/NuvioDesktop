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
    this is Lyrics.Synced -> copy(lines = lines.shiftedBy(offsetMs))
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

    /** Where the person's offsets are kept; tests swap in memory. */
    internal var loadSavedPayload: () -> String? = LyricSyncStorage::load
    internal var saveSavedPayload: (String) -> Unit = LyricSyncStorage::save

    // Offsets the person set, kept on disk (read once, when first needed). Everything here runs on the UI thread.
    private var saved: Map<String, SavedLyricOffset>? = null

    // The length of the lyrics each stream's offset is relative to, noted by [sync] so a saved offset can say so.
    private val lyricsLengths = HashMap<String, Long>()

    private fun savedOffsets(): Map<String, SavedLyricOffset> =
        saved ?: decodeSavedOffsets(runCatching { loadSavedPayload() }.getOrNull()).also { saved = it }

    private fun writeSaved(next: Map<String, SavedLyricOffset>) {
        saved = next
        runCatching { saveSavedPayload(encodeSavedOffsets(next)) }
            .onFailure { log.w { "could not save lyric offsets: ${it.message}" } }
    }

    /** The offset the person saved for [key], whatever lyrics it was set against (for views that only read). */
    fun savedOffsetOf(key: String): Long? = savedOffsets()[key]?.offsetMs

    fun stateOf(key: String): LyricSyncState = _states.value[key] ?: LyricSyncState.Idle

    /**
     * Works out the offset of [lines] on the stream [key] unless that is already known. An offset the
     * person saved earlier wins over any analysis. The same cut as the lyrics ([trackDurationMs] is the
     * length of the recording they were timed to) is settled without reading any audio. A stream that
     * could not be read (no estimator yet, another load) stays [LyricSyncState.Idle] so a later call tries again.
     */
    internal suspend fun sync(
        key: String,
        lines: List<LyricLine>,
        trackDurationMs: Long,
        playedDurationMs: Long,
        loadId: Long,
    ) {
        lyricsLengths[key] = trackDurationMs
        if (stateOf(key) !is LyricSyncState.Idle) return
        savedOffsetFor(savedOffsets(), key, trackDurationMs)?.let { offset ->
            set(key, LyricSyncState.Offset(offset, auto = false))
            return
        }
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
        var offset = 0L
        _states.update { current ->
            val base = (current[key] as? LyricSyncState.Offset)?.offsetMs ?: 0L
            offset = base + deltaMs
            current + (key to LyricSyncState.Offset(offset, auto = false))
        }
        keepOffset(key, offset)
    }

    /**
     * The person tapped the line they hear being sung: [shownLineTimeMs] is that line's time as it is shown now,
     * [positionMs] where playback was at the tap. The lines move so it starts there (see [tapSyncOffsetMs]).
     */
    fun tapSync(key: String, shownLineTimeMs: Long, positionMs: Long) {
        val offset = tapSyncOffsetMs(stateOf(key).offsetMs(), shownLineTimeMs, positionMs)
        set(key, LyricSyncState.Offset(offset, auto = false))
        keepOffset(key, offset)
    }

    /** Back to the lyrics' own timing for [key], and ready to be analysed again. The saved offset is dropped. */
    fun reset(key: String) {
        _states.update { it - key }
        if (key in savedOffsets()) writeSaved(savedOffsets() - key)
    }

    /** Keeps the person's own fix, so the same song does not need fixing again next time. */
    private fun keepOffset(key: String, offsetMs: Long) {
        writeSaved(withSavedOffset(savedOffsets(), key, offsetMs, lyricsLengths[key] ?: 0L))
    }

    /** A new run of the app, as far as this object knows: nothing in memory, the saved offsets read again. */
    internal fun restartSession() {
        _states.value = emptyMap()
        lyricsLengths.clear()
        saved = null
    }

    private fun set(key: String, state: LyricSyncState) {
        _states.update { it + (key to state) }
    }
}

private fun round3(value: Double): Double = (value * 1000.0).roundToLong() / 1000.0

/** The offset in force for a state: what was found or set, else none. */
internal fun LyricSyncState.offsetMs(): Long = (this as? LyricSyncState.Offset)?.offsetMs ?: 0L

