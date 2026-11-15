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
 * offset, film time at media time 0, before the tracker sees them. The guide is only a rough start: a channel with ads
 * is behind it by every break so far (a quarter of the time on air, at worst) and a film can start minutes late or
 * early, so the tracker searches that whole stretch ([Guess]), not the minute either way a downloaded file needs, and
 * after the lock looks up to [JUMP_AHEAD_MS] ahead for the next ad break. The offset is tried as it is and as a
 * 25-frame broadcast of a 24-frame film runs (4% fast); whichever locks first wins.
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

    /** A film started up to this long after the guide said, and [EARLY_MS] before it. */
    private const val LATE_MS = 5 * 60_000L
    private const val EARLY_MS = 5 * 60_000L
    /** The ads so far: at most this share of the time on air, and never more than [MAX_ADS_MS]. */
    private const val AD_SHARE = 0.25
    private const val MAX_ADS_MS = 40 * 60_000L
    /** Room past the ends of the search, so a film exactly at an end is not taken for a peak at the search's edge. */
    private const val EDGE_MS = 30_000L
    /** How far after the lock an ad break may move the film at once. */
    const val JUMP_AHEAD_MS = 10 * 60_000.0

    /**
     * One guess for the tracker: the lines moved by [offsetMs], the earliest film time at media time 0 that is
     * plausible, so every line still to be heard stays in (none falls before 0); the true place is then a shift of
     * [minShiftMs]..0 from there.
     */
    data class Guess(val offsetMs: Long, val minShiftMs: Double, val maxShiftMs: Double)

    /**
     * The speech heard on one channel during one programme. Kept across subtitle picks, so trying another file searches
     * at once with everything already heard instead of starting over (a minute or two each time).
     */
    class Heard internal constructor(val key: String, val guideOffsetMs: Long) {
        val timeline = SpeechTimeline()
        @Volatile var readUntil = -1L
    }

    private var lastHeard: Heard? = null

    /**
     * The speech heard so far for [key] (channel and programme) on this tune-in; a new key starts afresh, and so does
     * tuning in again, which restarts media time (the guide's offset at media time 0 then moves on).
     */
    @Synchronized
    fun heardFor(key: String, guideOffsetMs: Long): Heard =
        lastHeard?.takeIf { it.key == key && kotlin.math.abs(it.guideOffsetMs - guideOffsetMs) <= SAME_TUNE_MS }
            ?: Heard(key, guideOffsetMs).also { lastHeard = it }

    /** The guide's offset is read afresh at each pick, a second or two apart on the same tune-in. */
    private const val SAME_TUNE_MS = 10_000L

    /** The lines as the tracker should see them for one guess: film time minus [offsetMs]; what falls before 0 goes. */
    fun shifted(dialogue: List<Triple<Long, Long, String>>, offsetMs: Long): List<Triple<Long, Long, String>> =
        dialogue.mapNotNull { (start, end, text) -> if (end - offsetMs <= 0) null else Triple(maxOf(0L, start - offsetMs), end - offsetMs, text) }

    /**
     * A mapping found on lines moved by [offsetMs] (media = scale * moved + shift) as one on the film's own lines:
     * media = scale * film + (shift - scale * offset).
     */
    fun unshift(model: SubtitleSyncModel, offsetMs: Long): SubtitleSyncModel =
        SubtitleSyncModel(model.segments.map { it.copy(shiftMs = it.shiftMs - it.scale * offsetMs) })

    /** The guide's guesses at each speed: film time at media time 0 is somewhere from the ads behind to [EARLY_MS] ahead. */
    fun guesses(guideOffsetMs: Long): List<Guess> = SPEEDS.map { (guideOffsetMs * it).toLong() }.distinct().map { onGuide ->
        val behind = LATE_MS + (maxOf(0L, onGuide) * AD_SHARE).toLong().coerceAtMost(MAX_ADS_MS)
        Guess(
            offsetMs = onGuide - behind,
            minShiftMs = -(behind + EARLY_MS + EDGE_MS).toDouble(),
            maxShiftMs = EDGE_MS.toDouble(),
        )
    }

    /**
     * Runs until cancelled. [guideOffsetMs]: film time at media time 0 by the guide. [decode]: 16 kHz mono PCM of media
     * time [startMs, endMs) out of the rewind buffer, or null. [onModel]: a mapping on the film's own lines and whether
     * it is confirmed (a refinement or a jump after the lock is confirmed too).
     */
    suspend fun run(
        dialogue: List<Triple<Long, Long, String>>,
        guideOffsetMs: Long,
        heard: Heard,
        positionMs: () -> Long,
        decode: suspend (startMs: Long, endMs: Long) -> FloatArray?,
        onModel: suspend (model: SubtitleSyncModel, confirmed: Boolean, recheck: Boolean) -> Unit,
    ) {
        val timeline = heard.timeline
        val analyzer = SpeechAnalyzer(SileroVad(DesktopAudioSubtitleSync.weights()), timeline)
        val trackers = guesses(guideOffsetMs).map { guess ->
            guess.offsetMs to AudioSyncTracker(
                track = SubtitleSpeechTrack.fromCues(shifted(dialogue, guess.offsetMs)),
                searchMinShiftMs = guess.minShiftMs,
                searchMaxShiftMs = guess.maxShiftMs,
                jumpAheadMs = JUMP_AHEAD_MS,
            )
        }
        var lockedOffset: Long? = null
        var readUntil = heard.readUntil
        log.i { "start lines=${dialogue.size} guideOffsetMs=$guideOffsetMs heard=${(timeline.knownFrameCount() * SpeechTimeline.FRAME_DURATION_MS / 1_000).toLong()}s guesses=${guesses(guideOffsetMs)}" }
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
                    heard.readUntil = readUntil
                    log.d { "read ${from / 1_000}s..${readUntil / 1_000}s known=${timeline.knownFrameCount()}" }
                } else {
                    log.i { "nothing out of the cache for ${from / 1_000}s..${to / 1_000}s" }
                }
            }
            // Every round, not only after a read: speech heard for an earlier subtitle is searched straight away.
            run {
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
