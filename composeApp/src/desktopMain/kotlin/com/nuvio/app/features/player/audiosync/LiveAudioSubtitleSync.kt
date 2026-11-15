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
        /** Words whisper heard (media time), when the word match is on; [wordsUntil]: media time transcribed up to. */
        val words = ArrayList<LiveWordMatch.Heard>()
        @Volatile var wordsUntil = -1L

        @Synchronized fun addWords(heard: List<LiveWordMatch.Heard>, until: Long) {
            words += heard
            if (words.size > MAX_WORDS) words.subList(0, words.size - MAX_WORDS).clear()
            wordsUntil = maxOf(wordsUntil, until)
        }

        @Synchronized fun wordsFrom(fromMs: Long): List<LiveWordMatch.Heard> = words.filter { it.atMs >= fromMs }
    }

    /** About two hours of talk. */
    private const val MAX_WORDS = 12_000
    /** Whisper hears 30 s at a time. */
    private const val WORD_CHUNK_MS = 28_000L
    /** After the lock, the words of this long back are matched again to catch an ad break. */
    private const val RECENT_WORDS_MS = 120_000L
    /** A recent word match this far from the current placing is a jump (once it repeats). */
    private const val WORD_JUMP_MS = 1_500.0

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

    /** The opposite of [unshift]: a mapping on the film's own lines as one on lines moved by [offsetMs]. */
    fun reshift(model: SubtitleSyncModel, offsetMs: Long): SubtitleSyncModel =
        SubtitleSyncModel(model.segments.map { it.copy(shiftMs = it.shiftMs + it.scale * offsetMs) })

    /**
     * [current] with [match] from the words heard recently, when they place the film elsewhere (an ad break): a new
     * piece from where the matched words start; null when [match] agrees with [current] there.
     */
    fun jumped(current: SubtitleSyncModel, match: LiveWordMatch.Match): SubtitleSyncModel? {
        val at = match.fromMediaMs
        val film = (at - match.shiftMs) / match.scale
        val segment = current.segmentAt(at)
        if (kotlin.math.abs(segment.scale * film + segment.shiftMs - at) <= WORD_JUMP_MS) return null
        val last = current.segments.last()
        val piece = SubtitleSyncSegment(maxOf(at, last.fromMediaMs + 1_000L), match.scale, match.shiftMs)
        return SubtitleSyncModel(current.segments + piece)
    }

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
     * it is confirmed (a refinement or a jump after the lock is confirmed too). [transcribe]: when the word match is
     * on, at most 30 s of PCM as words timed from its start (null when whisper failed); words heard place the lines
     * at once and catch ad breaks, the speech tracker refines and watches between.
     */
    suspend fun run(
        dialogue: List<Triple<Long, Long, String>>,
        guideOffsetMs: Long,
        heard: Heard,
        positionMs: () -> Long,
        decode: suspend (startMs: Long, endMs: Long) -> FloatArray?,
        transcribe: (suspend (pcm: FloatArray) -> List<LiveWordMatch.Heard>?)? = null,
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
        val script = if (transcribe != null) LiveWordMatch.Script(dialogue) else null
        /** The placing on the film's own lines last handed out, once confirmed. */
        var placed: SubtitleSyncModel? = null
        var pendingJump: LiveWordMatch.Match? = null
        suspend fun emit(model: SubtitleSyncModel, confirmed: Boolean, recheck: Boolean) {
            if (confirmed) placed = model
            onModel(model, confirmed, recheck)
        }
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
                    if (transcribe != null) {
                        // Only what was not transcribed before (a rewind reads some audio again).
                        var chunkFrom = maxOf(from, heard.wordsUntil)
                        var found = 0
                        while (chunkFrom < readUntil - 1_000L) {
                            currentCoroutineContext().ensureActive()
                            val chunkTo = minOf(readUntil, chunkFrom + WORD_CHUNK_MS)
                            val a = ((chunkFrom - from) * DesktopAudioSubtitleSync.SAMPLE_RATE / 1_000L).toInt().coerceIn(0, pcm.size)
                            val b = ((chunkTo - from) * DesktopAudioSubtitleSync.SAMPLE_RATE / 1_000L).toInt().coerceIn(a, pcm.size)
                            val words = transcribe(pcm.copyOfRange(a, b)) ?: break
                            val start = chunkFrom
                            heard.addWords(words.map { it.copy(atMs = start + it.atMs) }, chunkTo)
                            found += words.size
                            chunkFrom = chunkTo
                        }
                        log.d { "words: $found in ${from / 1_000}s..${readUntil / 1_000}s" }
                    }
                } else {
                    log.i { "nothing out of the cache for ${from / 1_000}s..${to / 1_000}s" }
                }
            }
            // The words: they place the lines at once, and catch an ad break after the lock.
            if (script != null) {
                if (lockedOffset == null) {
                    LiveWordMatch.match(script, heard.wordsFrom(Long.MIN_VALUE))?.let { match ->
                        val model = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, match.scale, match.shiftMs)))
                        // The tracker whose speed this is: the film's own, or sped up to 25 frames.
                        val (guess, tracker) = if (kotlin.math.abs(match.scale - 1.0) < 0.01) trackers.first() else trackers.last()
                        tracker.adopt(reshift(model, guess))
                        lockedOffset = guess
                        log.i { "locked by words (${match.votes} runs, next best ${match.runnerUp}): $model" }
                        emit(model, true, false)
                    }
                } else {
                    val current = placed
                    val recent = LiveWordMatch.match(script, heard.wordsFrom(positionMs() - RECENT_WORDS_MS))
                    val moved = if (current != null && recent != null) jumped(current, recent) else null
                    val pending = pendingJump
                    pendingJump = if (moved != null) recent else null
                    if (moved != null && pending != null && recent != null &&
                        kotlin.math.abs(pending.shiftMs - recent.shiftMs) <= WORD_JUMP_MS && pending.scale == recent.scale
                    ) {
                        pendingJump = null
                        trackers.first { it.first == lockedOffset }.second.adopt(reshift(moved, lockedOffset!!))
                        log.i { "jump by words at ${recent.fromMediaMs / 1_000}s: $moved" }
                        emit(moved, true, true)
                    }
                }
            }
            // Every round, not only after a read: speech heard for an earlier subtitle is searched straight away.
            run {
                for ((guess, tracker) in trackers) {
                    if (lockedOffset != null && guess != lockedOffset) continue
                    when (val outcome = tracker.update(timeline, positionMs())) {
                        is AudioSyncTracker.Outcome.Provisional -> if (lockedOffset == null) emit(unshift(outcome.model, guess), false, false)
                        is AudioSyncTracker.Outcome.Locked -> {
                            log.i { "locked at guess $guess: ${outcome.model}" }
                            lockedOffset = guess
                            emit(unshift(outcome.model, guess), true, false)
                        }
                        is AudioSyncTracker.Outcome.Refined -> emit(unshift(outcome.model, guess), true, true)
                        is AudioSyncTracker.Outcome.Jumped -> {
                            log.i { "jump at ${position / 1_000}s: ${outcome.model}" }
                            emit(unshift(outcome.model, guess), true, true)
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
