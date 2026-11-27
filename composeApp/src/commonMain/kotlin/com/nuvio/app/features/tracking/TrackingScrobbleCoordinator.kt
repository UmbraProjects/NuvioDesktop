package com.nuvio.app.features.tracking

import co.touchlab.kermit.Logger
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watchprogress.WatchProgressClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

data class TrackingScrobbleFailure(
    val providerId: TrackingProviderId,
    val cause: Throwable,
)

/**
 * Outcome of one fan-out.
 *
 * [sentCount] counts providers that produced a request; a provider that declined the event (see
 * [TrackingScrobbler.scrobble]) is neither sent nor failed. `sentCount == 0 && failures.isEmpty()`
 * therefore means nothing could address this item at all, which is the only case a caller should
 * treat as retryable.
 */
data class TrackingScrobbleDispatch(
    val sentCount: Int = 0,
    val watchedProviderIds: List<TrackingProviderId> = emptyList(),
    val failures: List<TrackingScrobbleFailure> = emptyList(),
) {
    val handledNothing: Boolean
        get() = sentCount == 0 && failures.isEmpty()
}

/**
 * The progress below which no provider records a stop as a watch (Trakt and SIMKL both use 80%).
 * Also the floor for believing a reply that says one did.
 */
internal const val TrackingScrobbleWatchedProgressThresholdPercent = 80.0

/**
 * Below this progress a stop abandons its session (see [TrackingScrobbleEvent.abandonsSession]),
 * and a provider's resume point is not a resume point: about a minute into a feature film.
 */
internal const val TrackingScrobbleAbandonedPercent = 1.0

/** Whether a resume point read back from a provider is too early to be worth resuming. */
internal fun isNegligibleResumeProgress(progressPercent: Float): Boolean =
    progressPercent < TrackingScrobbleAbandonedPercent

/**
 * Whether a provider reply to this request could describe *this* scrobble marking the item watched.
 *
 * The reply parser is deliberately loose — `status: completed`, `watched: true`, any `watched_at` —
 * because each provider words it differently. That looseness has a cost: a provider that echoes the
 * item's existing state answers a pause, or a seek's stop at 11%, with a body that reads as a fresh
 * mark, and the player then announced "marked watched" a few minutes into an episode. Neither request
 * can have caused one: a pause is not a completion, and a stop below the providers' own threshold is
 * resumable progress. So a watched claim is only believed where the request itself could have earned
 * it. Above the threshold the reply stays authoritative, including for a deliberate seek to the end.
 */
internal fun canConfirmWatched(action: TrackingScrobbleAction, event: TrackingScrobbleEvent): Boolean =
    action == TrackingScrobbleAction.STOP &&
        !event.isPauseRatherThanStop &&
        event.progressPercent >= TrackingScrobbleWatchedProgressThresholdPercent

/**
 * Whether a provider that has only `stop` to report a pause with must skip this one.
 *
 * Trakt and SIMKL both turn a stop at or above [TrackingScrobbleWatchedProgressThresholdPercent]
 * into a watch, so sending a pause there marks the title watched on the provider (and drops it
 * from a Continue Watching it sources) though the user only paused. Skipping it leaves their
 * session running, as a pause should; the next start, stop or completion corrects it.
 */
internal fun isPauseThatStopWouldRecordAsWatched(event: TrackingScrobbleEvent): Boolean =
    event.isPauseRatherThanStop &&
        event.progressPercent >= TrackingScrobbleWatchedProgressThresholdPercent

object TrackingScrobbleCoordinator {
    private val log = Logger.withTag("TrackingScrobble")
    private val detachedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Runs a scrobble stop independently of a disposing player Compose scope. */
    fun launchDetached(block: suspend () -> Unit) {
        detachedScope.launch { block() }
    }

    suspend fun scrobble(
        profileId: Int,
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent,
    ): TrackingScrobbleDispatch {
        if (profileId != ProfileRepository.activeProfileId) return TrackingScrobbleDispatch()
        TrackingProviderRegistry.ensureLoaded()
        return dispatchTrackingScrobble(
            scrobblers = TrackingProviderRegistry.connectedScrobblers(),
            profileId = profileId,
            action = action,
            event = event,
        ).also { dispatch -> dispatch.logFailures(action, seek = false) }
    }

    suspend fun scrobbleSeek(
        profileId: Int,
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent,
    ): TrackingScrobbleDispatch {
        if (profileId != ProfileRepository.activeProfileId) return TrackingScrobbleDispatch()
        TrackingProviderRegistry.ensureLoaded()
        return dispatchTrackingSeekScrobble(
            scrobblers = TrackingProviderRegistry.connectedScrobblers(),
            profileId = profileId,
            action = action,
            event = event,
        ).also { dispatch -> dispatch.logFailures(action, seek = true) }
    }

    /**
     * Re-anchors long-running sessions so a provider's extrapolated position cannot drift far from
     * the real one — most visibly when playback speed is not 1x.
     *
     * Safe to call on the ordinary progress cadence: each provider is throttled to its own declared
     * [TrackingScrobbler.progressRefreshIntervalMs], and providers that did not opt in are skipped
     * entirely.
     */
    suspend fun scrobbleProgressRefresh(
        profileId: Int,
        event: TrackingScrobbleEvent,
    ): TrackingScrobbleDispatch {
        if (profileId != ProfileRepository.activeProfileId) return TrackingScrobbleDispatch()
        TrackingProviderRegistry.ensureLoaded()
        val now = WatchProgressClock.nowEpochMs()
        val due = TrackingProviderRegistry.connectedScrobblers().filter { scrobbler ->
            val interval = scrobbler.progressRefreshIntervalMs ?: return@filter false
            val last = synchronized(refreshLock) { lastRefreshAtMs[scrobbler.providerId] }
            last == null || now - last >= interval
        }
        if (due.isEmpty()) return TrackingScrobbleDispatch()

        synchronized(refreshLock) {
            due.forEach { scrobbler -> lastRefreshAtMs[scrobbler.providerId] = now }
        }
        return dispatchTrackingScrobble(
            scrobblers = due,
            profileId = profileId,
            action = TrackingScrobbleAction.START,
            event = event,
        ).also { dispatch ->
            dispatch.failures.forEach { failure ->
                log.w(failure.cause) { "${failure.providerId.storageId} progress refresh failed" }
            }
        }
    }

    /** Forgets refresh throttling so a new item is re-anchored immediately rather than waiting. */
    fun onScrobbleItemChanged() = synchronized(refreshLock) { lastRefreshAtMs.clear() }

    private val refreshLock = Any()
    private val lastRefreshAtMs = mutableMapOf<TrackingProviderId, Long>()

    private fun TrackingScrobbleDispatch.logFailures(
        action: TrackingScrobbleAction,
        seek: Boolean,
    ) {
        failures.forEach { failure ->
            log.w(failure.cause) {
                val kind = if (seek) "seek scrobble" else "scrobble"
                "${failure.providerId.storageId} $kind ${action.wireValue} failed"
            }
        }
    }
}

internal suspend fun dispatchTrackingSeekScrobble(
    scrobblers: Collection<TrackingScrobbler>,
    profileId: Int,
    action: TrackingScrobbleAction,
    event: TrackingScrobbleEvent,
): TrackingScrobbleDispatch = dispatchTrackingScrobble(
    scrobblers = scrobblers.filter { scrobbler -> scrobbler.participatesInSeek(action) },
    profileId = profileId,
    action = action,
    event = event,
)

/**
 * Which half of a seek's stop/restart pair this provider should receive.
 *
 * A `RESTART_ONLY` provider takes the restart but not the stop: its `start` replaces the running
 * session, while its `stop` would be a durable write.
 *
 * Position is deliberately not consulted. A seek near the end is reported like any other, and if a
 * provider records that as watched, that matches what the user just did.
 */
internal fun TrackingScrobbler.participatesInSeek(action: TrackingScrobbleAction): Boolean =
    when (seekScrobblePolicy) {
        TrackingSeekScrobblePolicy.NONE -> false
        TrackingSeekScrobblePolicy.STOP_AND_RESTART -> true
        TrackingSeekScrobblePolicy.RESTART_ONLY -> action == TrackingScrobbleAction.START
    }

internal suspend fun dispatchTrackingScrobble(
    scrobblers: Collection<TrackingScrobbler>,
    profileId: Int,
    action: TrackingScrobbleAction,
    event: TrackingScrobbleEvent,
): TrackingScrobbleDispatch = supervisorScope {
    val results = scrobblers.map { scrobbler ->
        async {
            try {
                scrobbler.scrobble(profileId = profileId, action = action, event = event) to null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                TrackingScrobbleResult.Declined to TrackingScrobbleFailure(
                    providerId = scrobbler.providerId,
                    cause = error,
                )
            }
        }
    }.awaitAll()
    val watchedPossible = canConfirmWatched(action, event)
    TrackingScrobbleDispatch(
        sentCount = results.count { (result, _) -> result.handled },
        watchedProviderIds = results.mapIndexedNotNull { index, (result, _) ->
            scrobblers.elementAt(index).providerId.takeIf { watchedPossible && result.confirmsWatched }
        },
        failures = results.mapNotNull { (_, failure) -> failure },
    )
}
