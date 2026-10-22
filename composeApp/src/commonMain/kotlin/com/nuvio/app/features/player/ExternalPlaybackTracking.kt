package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.simkl.WatchProgressSourceSimkl
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleCoordinator
import com.nuvio.app.features.tracking.TrackingScrobbleDispatch
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import com.nuvio.app.features.tracking.buildTrackingMediaReference
import com.nuvio.app.features.watchprogress.WatchProgressClock
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.abs

private val externalTrackingLog = Logger.withTag("ExternalPlaybackTracking")

/** Everything needed to record an external player's playback the way the in-app player does. */
data class ExternalPlaybackTracking(
    val session: WatchProgressPlaybackSession,
    val media: TrackingMediaReference,
    val resumePositionMs: Long = 0L,
    val resumeProgressFraction: Float? = null,
)

/** One reading taken from an external player. */
data class ExternalPlaybackSample(
    val positionMs: Long,
    val durationMs: Long,
    val isPaused: Boolean,
)

/** Null when the launch asked for no progress tracking (direct-play of an unmatched file). */
internal fun PlayerLaunch.externalPlaybackTracking(): ExternalPlaybackTracking? {
    if (disableProgressTracking || parentMetaId.isBlank()) return null
    val type = contentType ?: parentMetaType
    val media = buildTrackingMediaReference(
        contentType = type,
        parentMetaId = parentMetaId,
        videoId = videoId,
        title = title,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        episodeTitle = episodeTitle,
    )
    // Same anime correction the in-app player applies in snapshotTrackingScrobbleMedia.
    val isAnime = AnimeContentCache.isAnime(parentMetaId) ||
        (watchProgressSource == WatchProgressSourceSimkl && parentMetaId.startsWith("simkl:", ignoreCase = true))
    return ExternalPlaybackTracking(
        session = WatchProgressPlaybackSession(
            contentType = type,
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            videoId = videoId ?: parentMetaId,
            title = title,
            logo = logo,
            poster = poster,
            background = background,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
            episodeThumbnail = episodeThumbnail,
            providerName = providerName,
            providerAddonId = providerAddonId,
            lastStreamTitle = streamTitle,
            lastStreamSubtitle = streamSubtitle,
            pauseDescription = pauseDescription,
            lastSourceUrl = sourceUrl,
        ),
        media = if (isAnime && media.kind != TrackingMediaKind.MOVIE) media.copy(kind = TrackingMediaKind.ANIME) else media,
        resumePositionMs = initialPositionMs,
        resumeProgressFraction = initialProgressFraction,
    )
}

/** Where a tracking session's writes go; the real one is [DefaultExternalPlaybackSink]. */
internal interface ExternalPlaybackSink {
    suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent, viaSeek: Boolean): TrackingScrobbleDispatch
    suspend fun refresh(event: TrackingScrobbleEvent)
    fun writeProgress(session: WatchProgressPlaybackSession, snapshot: PlayerPlaybackSnapshot, syncRemote: Boolean)
    fun knownDurationMs(videoId: String): Long?
}

private class DefaultExternalPlaybackSink(private val profileId: Int) : ExternalPlaybackSink {
    override suspend fun scrobble(
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent,
        viaSeek: Boolean,
    ): TrackingScrobbleDispatch = if (viaSeek) {
        TrackingScrobbleCoordinator.scrobbleSeek(profileId, action, event)
    } else {
        TrackingScrobbleCoordinator.scrobble(profileId, action, event)
    }

    override suspend fun refresh(event: TrackingScrobbleEvent) {
        TrackingScrobbleCoordinator.scrobbleProgressRefresh(profileId, event)
    }

    override fun writeProgress(session: WatchProgressPlaybackSession, snapshot: PlayerPlaybackSnapshot, syncRemote: Boolean) {
        WatchProgressRepository.upsertPlaybackProgress(session = session, snapshot = snapshot, syncRemote = syncRemote)
    }

    override fun knownDurationMs(videoId: String): Long? =
        WatchProgressRepository.progressForVideo(videoId)?.durationMs?.takeIf { it > 0L }
}

object ExternalPlaybackTracker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun begin(tracking: ExternalPlaybackTracking): ExternalPlaybackTrackingSession =
        ExternalPlaybackTrackingSession(
            tracking = tracking,
            sink = DefaultExternalPlaybackSink(ProfileRepository.activeProfileId),
            scope = scope,
        )
}

/** A jump this far from where the clock says playback should be is a seek, not drift. */
internal const val ExternalPlaybackSeekToleranceMs = 15_000L

/** How long to wait for a resume seek to land before treating the live position as the truth. */
internal const val ExternalPlaybackResumeGraceMs = 30_000L

/**
 * Turns an external player's readings into the progress writes and scrobbles the in-app player
 * makes, with the same rules: a start when playback runs, a pause-flavoured stop when it pauses,
 * a stop/restart pair around a seek, periodic progress while playing, and a final stop on exit.
 *
 * Feeding is thread-agnostic: every call only queues an event, and one coroutine applies them in
 * order, so scrobbles reach providers in the order they happened.
 */
class ExternalPlaybackTrackingSession internal constructor(
    private val tracking: ExternalPlaybackTracking,
    private val sink: ExternalPlaybackSink,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher? = null,
    private val clock: () -> Long = WatchProgressClock::nowEpochMs,
) {
    private sealed interface Event {
        val atMs: Long
        data class Sample(val sample: ExternalPlaybackSample, override val atMs: Long) : Event
        data class UnobservedStart(override val atMs: Long) : Event
        data class UnobservedEnd(val elapsedMs: Long, override val atMs: Long) : Event
        data class End(override val atMs: Long) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)

    private var last: ExternalPlaybackSample? = null
    private var lastAtMs = 0L
    private var firstSampleAtMs: Long? = null
    private var resumeSettled = false
    private var scrobbleRunning = false
    private var completionSent = false
    private var lastPersistAtMs = 0L
    private var estimatedDurationMs: Long? = null

    init {
        val context = dispatcher ?: kotlin.coroutines.EmptyCoroutineContext
        scope.launch(context) {
            for (event in events) {
                try {
                    handle(event)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    externalTrackingLog.w(error) { "External playback event failed: ${event::class.simpleName}" }
                }
            }
        }
    }

    /** A live reading. Readings without a duration (nothing loaded yet) are ignored. */
    fun onSample(sample: ExternalPlaybackSample) {
        events.trySend(Event.Sample(sample, clock()))
    }

    /** The player is running but cannot be read: scrobble a start at the resume point. */
    fun onUnobservedPlaybackStarted() {
        events.trySend(Event.UnobservedStart(clock()))
    }

    /**
     * The unreadable player closed after [elapsedMs]. Its position is estimated as resume point plus
     * elapsed time, which over-counts any pause; there is nothing better to go on.
     */
    fun onUnobservedPlaybackEnded(elapsedMs: Long) {
        events.trySend(Event.UnobservedEnd(elapsedMs, clock()))
        events.close()
    }

    /** The player closed. The last reading is recorded as where playback stopped. */
    fun onEnded() {
        events.trySend(Event.End(clock()))
        events.close()
    }

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.Sample -> onSampleEvent(event.sample, event.atMs)
            is Event.UnobservedStart -> {
                val duration = sink.knownDurationMs(tracking.session.videoId)
                estimatedDurationMs = duration
                val position = resumePositionFor(duration)
                start(
                    ExternalPlaybackSample(position, duration ?: 0L, isPaused = false),
                    fallbackPercent = tracking.resumeProgressFraction?.times(100f),
                )
            }
            is Event.UnobservedEnd -> {
                val duration = estimatedDurationMs ?: sink.knownDurationMs(tracking.session.videoId) ?: run {
                    // No runtime to measure against: close the provider session without a position.
                    if (scrobbleRunning) stop(percent = null, paused = false, viaSeek = false, sample = null)
                    return
                }
                val position = (resumePositionFor(duration) + event.elapsedMs).coerceAtMost(duration)
                finish(ExternalPlaybackSample(position, duration, isPaused = false))
            }
            is Event.End -> last?.let { finish(it) } ?: run {
                if (scrobbleRunning) stop(percent = null, paused = false, viaSeek = false, sample = null)
            }
        }
    }

    private suspend fun onSampleEvent(sample: ExternalPlaybackSample, atMs: Long) {
        if (sample.durationMs <= 0L || sample.positionMs < 0L) return
        val firstAt = firstSampleAtMs ?: atMs.also { firstSampleAtMs = it }
        if (!resumeSettled) {
            // A player can read 0 for a moment before its start-position switch takes effect.
            // Waiting keeps that from being reported as a start at 0% followed by a seek.
            resumeSettled = isResumeReached(
                trustedPositionMs = sample.positionMs,
                durationMs = sample.durationMs,
                initialPositionMs = tracking.resumePositionMs,
                initialProgressFraction = tracking.resumeProgressFraction,
            ) || atMs - firstAt >= ExternalPlaybackResumeGraceMs
            if (!resumeSettled) return
        }

        val previous = last
        val previousAt = lastAtMs
        last = sample
        lastAtMs = atMs

        if (previous == null) {
            if (!sample.isPaused) start(sample)
            persist(sample, atMs, force = true)
            return
        }

        val expectedAdvance = if (previous.isPaused) 0L else atMs - previousAt
        val drift = sample.positionMs - previous.positionMs - expectedAdvance
        if (abs(drift) > ExternalPlaybackSeekToleranceMs) {
            onSeek(sample, atMs)
            return
        }
        when {
            !previous.isPaused && sample.isPaused -> pause(sample, atMs)
            previous.isPaused && !sample.isPaused -> start(sample)
        }
        if (!sample.isPaused) {
            persist(sample, atMs, force = false)
        }
    }

    private suspend fun start(sample: ExternalPlaybackSample, fallbackPercent: Float? = null) {
        if (scrobbleRunning) return
        scrobbleRunning = true
        TrackingScrobbleCoordinator.onScrobbleItemChanged()
        val percent = sample.percent() ?: fallbackPercent ?: 0f
        val dispatch = sink.scrobble(TrackingScrobbleAction.START, sample.event(percent), viaSeek = false)
        // No provider could address the item: let the next start retry, as the in-app player does.
        if (dispatch.handledNothing) scrobbleRunning = false
    }

    private suspend fun pause(sample: ExternalPlaybackSample, atMs: Long) {
        flushStop(sample, paused = true)
        persist(sample, atMs, force = true)
    }

    private suspend fun onSeek(sample: ExternalPlaybackSample, atMs: Long) {
        persist(sample, atMs, force = true)
        val percent = sample.percent() ?: return
        if (percent < 1f) return
        if (scrobbleRunning) stop(percent, paused = false, viaSeek = true, sample = sample)
        if (!sample.isPaused) {
            scrobbleRunning = true
            val dispatch = sink.scrobble(TrackingScrobbleAction.START, sample.event(percent), viaSeek = true)
            if (dispatch.handledNothing) scrobbleRunning = false
        }
    }

    private suspend fun finish(sample: ExternalPlaybackSample) {
        flushStop(sample, paused = false)
        val percent = sample.percent() ?: 0f
        sink.writeProgress(
            session = tracking.session,
            snapshot = sample.snapshot(isEnded = percent >= 99f),
            syncRemote = true,
        )
    }

    private suspend fun flushStop(sample: ExternalPlaybackSample, paused: Boolean) {
        val percent = sample.percent() ?: return
        when (flushScrobbleStopKind(percent, paused, sessionRunning = scrobbleRunning, completionSent = completionSent)) {
            FlushScrobbleStopKind.NONE -> Unit
            FlushScrobbleStopKind.PROGRESS -> stop(percent, paused = paused, viaSeek = false, sample = sample)
            FlushScrobbleStopKind.COMPLETION -> {
                completionSent = true
                stop(percent, paused = false, viaSeek = false, sample = sample)
            }
        }
    }

    private suspend fun stop(percent: Float?, paused: Boolean, viaSeek: Boolean, sample: ExternalPlaybackSample?) {
        scrobbleRunning = false
        val event = sample?.event(percent ?: 0f, paused = paused)
            ?: TrackingScrobbleEvent(media = tracking.media, progressPercent = (percent ?: 0f).toDouble())
        sink.scrobble(TrackingScrobbleAction.STOP, event, viaSeek = viaSeek)
    }

    private suspend fun persist(sample: ExternalPlaybackSample, atMs: Long, force: Boolean) {
        if (!force && atMs - lastPersistAtMs < PlaybackProgressPersistIntervalMs) return
        lastPersistAtMs = atMs
        sink.writeProgress(tracking.session, sample.snapshot(isEnded = false), syncRemote = force)
        if (!force && scrobbleRunning && !sample.isPaused) {
            sample.percent()?.takeIf { it >= 1f }?.let { sink.refresh(sample.event(it)) }
        }
    }

    private fun resumePositionFor(durationMs: Long?): Long =
        requestedResumePositionMs(
            durationMs = durationMs ?: 0L,
            initialPositionMs = tracking.resumePositionMs,
            initialProgressFraction = tracking.resumeProgressFraction,
        )

    private fun ExternalPlaybackSample.percent(): Float? =
        durationMs.takeIf { it > 0L }?.let { (positionMs.toFloat() / it.toFloat() * 100f).coerceIn(0f, 100f) }

    private fun ExternalPlaybackSample.event(percent: Float, paused: Boolean = false) = TrackingScrobbleEvent(
        media = tracking.media,
        progressPercent = percent.toDouble(),
        positionSeconds = positionMs.takeIf { it > 0L }?.div(1000L),
        durationSeconds = durationMs.takeIf { it > 0L }?.div(1000L),
        isPauseRatherThanStop = paused,
    )

    private fun ExternalPlaybackSample.snapshot(isEnded: Boolean) = PlayerPlaybackSnapshot(
        isLoading = false,
        isPlaying = !isPaused && !isEnded,
        isEnded = isEnded,
        durationMs = durationMs,
        positionMs = positionMs.coerceAtMost(durationMs),
    )
}
