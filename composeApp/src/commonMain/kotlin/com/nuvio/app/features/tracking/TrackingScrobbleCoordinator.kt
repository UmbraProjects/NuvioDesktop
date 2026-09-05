package com.nuvio.app.features.tracking

import co.touchlab.kermit.Logger
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watchprogress.WatchProgressClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

object TrackingScrobbleCoordinator {
    private val log = Logger.withTag("TrackingScrobble")

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
    TrackingScrobbleDispatch(
        sentCount = results.count { (result, _) -> result.handled },
        watchedProviderIds = results.mapIndexedNotNull { index, (result, _) ->
            scrobblers.elementAt(index).providerId.takeIf { result.confirmsWatched }
        },
        failures = results.mapNotNull { (_, failure) -> failure },
    )
}
