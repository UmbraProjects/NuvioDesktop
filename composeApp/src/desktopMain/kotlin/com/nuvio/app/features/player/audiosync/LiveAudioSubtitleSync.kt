package com.nuvio.app.features.player.audiosync

import co.touchlab.kermit.Logger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.math.min

/**
 * fork:live-tv-subtitles - audio subtitle sync for a live channel. A live stream cannot be read again from another
 * point the way [DesktopAudioSubtitleSync] samples a film, and a second connection would cost the provider's only
 * one, so this listens to what has already played: every so often the stretch played since the last read is copied
 * out of the player's rewind buffer ([decode]), its speech found ([SpeechAnalyzer]) and handed to [AudioSyncTracker],
 * which estimates, locks, refines and notices a jump (an ad break) as it does for films.
 *
 * Media time starts when the channel was tuned in, so the lines (timed to the film) are first moved by the guide's
 * offset, film time at media time 0, before the tracker sees them: its search reaches a minute either way, not the
 * forty minutes a film can be in. The offset is tried as it is and as a 25-frame broadcast of a 24-frame film runs
 * (4% fast, so forty minutes in it is already a minute and a half out); whichever locks first wins.
 */
internal object LiveAudioSubtitleSync {
    private val log = Logger.withTag("LiveSubSync")

    /** The film's own speed, and a 23.976 film broadcast at 25 frames (the usual PAL speed-up). */
    val SPEEDS = doubleArrayOf(1.0, 25.0 / 23.976)

    /** How far back a read may reach: inside the 2-minute rewind buffer Live TV keeps at least. */
    private const val BACK_MS = 100_000L
    /** Never right up to the playhead: the newest seconds may not be whole in the cache yet. */
    private const val LAG_MS = 3_000L
    private const val MIN_READ_MS = 10_000L
    private const val SEARCH_EVERY_MS = 30_000L
    private const val WATCH_EVERY_MS = 90_000L

    /** The lines as the tracker should see them for one guess: film time minus [offsetMs]; what falls before 0 goes. */
    fun shifted(dialogue: List<Triple<Long, Long, String>>, offsetMs: Long): List<Triple<Long, Long, String>> =
        dialogue.mapNotNull { (start, end, text) -> if (end - offsetMs <= 0) null else Triple(maxOf(0L, start - offsetMs), end - offsetMs, text) }

    /**
     * A mapping found on lines moved by [offsetMs] (media = scale * moved + shift) as one on the film's own lines:
     * media = scale * film + (shift - scale * offset).
     */
    fun unshift(model: SubtitleSyncModel, offsetMs: Long): SubtitleSyncModel =
        SubtitleSyncModel(model.segments.map { it.copy(shiftMs = it.shiftMs - it.scale * offsetMs) })

    /** The guide's guesses: film time at media time 0 at each speed. */
    fun guesses(guideOffsetMs: Long): List<Long> = SPEEDS.map { (guideOffsetMs * it).toLong() }.distinct()

    /**
     * Runs until cancelled. [guideOffsetMs]: film time at media time 0 by the guide. [decode]: 16 kHz mono PCM of media
     * time [startMs, endMs) out of the rewind buffer, or null. [onModel]: a mapping on the film's own lines and whether
     * it is confirmed (a refinement or a jump after the lock is confirmed too).
     */
    suspend fun run(
        dialogue: List<Triple<Long, Long, String>>,
        guideOffsetMs: Long,
        positionMs: () -> Long,
        decode: suspend (startMs: Long, endMs: Long) -> FloatArray?,
        onModel: suspend (model: SubtitleSyncModel, confirmed: Boolean, recheck: Boolean) -> Unit,
    ) {
        val timeline = SpeechTimeline()
        val analyzer = SpeechAnalyzer(SileroVad(DesktopAudioSubtitleSync.weights()), timeline)
        val trackers = guesses(guideOffsetMs).map { offset -> offset to AudioSyncTracker(SubtitleSpeechTrack.fromCues(shifted(dialogue, offset))) }
        var lockedOffset: Long? = null
        var readUntil = -1L
        log.i { "start lines=${dialogue.size} guideOffsetMs=$guideOffsetMs guesses=${trackers.map { it.first }}" }
        while (true) {
            currentCoroutineContext().ensureActive()
            val position = positionMs()
            val to = position - LAG_MS
            // After a jump back (rewind) or forward (Go live) the old read point means nothing.
            val from = if (readUntil < 0 || readUntil > to || to - readUntil > BACK_MS) maxOf(0L, to - BACK_MS) else readUntil
            if (to - from >= MIN_READ_MS) {
                val pcm = decode(from, to)
                if (pcm != null && pcm.isNotEmpty()) {
                    if (from != readUntil) analyzer.reset()
                    var offset = 0
                    while (offset < pcm.size) {
                        currentCoroutineContext().ensureActive()
                        val count = min(DesktopAudioSubtitleSync.SAMPLE_RATE * 5, pcm.size - offset)
                        val timeUs = from * 1_000L + offset * 1_000_000L / DesktopAudioSubtitleSync.SAMPLE_RATE
                        analyzer.accept(pcm.copyOfRange(offset, offset + count), count, DesktopAudioSubtitleSync.SAMPLE_RATE, timeUs)
                        offset += count
                    }
                    readUntil = from + pcm.size * 1_000L / DesktopAudioSubtitleSync.SAMPLE_RATE
                    log.d { "read ${from / 1_000}s..${readUntil / 1_000}s known=${timeline.knownFrameCount()}" }
                } else {
                    log.i { "nothing out of the cache for ${from / 1_000}s..${to / 1_000}s" }
                }
                for ((guess, tracker) in trackers) {
                    if (lockedOffset != null && guess != lockedOffset) continue
                    when (val outcome = tracker.update(timeline, positionMs())) {
                        is AudioSyncTracker.Outcome.Provisional -> if (lockedOffset == null) onModel(unshift(outcome.model, guess), false, false)
                        is AudioSyncTracker.Outcome.Locked -> {
                            log.i { "locked at guess $guess: ${outcome.model}" }
                            lockedOffset = guess
                            onModel(unshift(outcome.model, guess), true, false)
                        }
                        is AudioSyncTracker.Outcome.Refined -> onModel(unshift(outcome.model, guess), true, true)
                        is AudioSyncTracker.Outcome.Jumped -> {
                            log.i { "jump at ${position / 1_000}s: ${outcome.model}" }
                            onModel(unshift(outcome.model, guess), true, true)
                        }
                        else -> Unit
                    }
                    if (lockedOffset != null) break
                }
            }
            delay(if (lockedOffset == null) SEARCH_EVERY_MS else WATCH_EVERY_MS)
        }
    }
}
