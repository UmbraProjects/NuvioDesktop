package com.nuvio.app.features.tracking

enum class TrackingListStatus(val wireValue: String) {
    WATCHING("watching"),
    PLAN_TO_WATCH("plantowatch"),
    ON_HOLD("hold"),
    COMPLETED("completed"),
    DROPPED("dropped");

    companion object {
        internal fun fromWireValue(value: String?): TrackingListStatus? =
            entries.firstOrNull { status -> status.wireValue.equals(value, ignoreCase = true) }
    }
}

enum class TrackingScrobbleAction(val wireValue: String) {
    START("start"),
    PAUSE("pause"),
    STOP("stop"),
}

/**
 * What a provider needs after the user seeks.
 *
 * Providers do not receive a position while playback runs — they infer it from the last scrobble.
 * A seek therefore has to be reported explicitly, or the provider keeps extrapolating from the
 * position it was last told about.
 */
enum class TrackingSeekScrobblePolicy {
    /** Ignores seeks entirely. */
    NONE,

    /** The session must be torn down and recreated: receives both the stop and the restart. */
    STOP_AND_RESTART,

    /**
     * A fresh `start` replaces the running session, so the restart alone is enough — and the stop
     * must be withheld, because for some providers a stop is a durable history write rather than
     * a session teardown.
     */
    RESTART_ONLY,
}

data class TrackingHistoryItem(
    val media: TrackingMediaReference,
    val watchedAtEpochMs: Long? = null,
)

data class TrackingScrobbleEvent(
    val media: TrackingMediaReference,
    val progressPercent: Double,
    /**
     * Absolute playback position and runtime, when the player knows them.
     *
     * Most providers take only a percentage, but some want real seconds — Yamtrack's live
     * "Now Playing" card renders position against duration, and its completion heuristic is
     * defined in seconds. Optional because a provider must never depend on them being present.
     */
    val positionSeconds: Long? = null,
    val durationSeconds: Long? = null,
    /**
     * True when a `STOP` is really "the user paused", not "playback ended".
     *
     * The player has always reported a pause as a stop, because that is all Trakt and SIMKL offer.
     * Providers with a distinct pause action can send it instead — Yamtrack's `stop` is a durable
     * history write and its `pause` is not, and MDBList's `stop` ends the session where `pause`
     * keeps it resumable. Providers without one ignore this and behave exactly as before.
     */
    val isPauseRatherThanStop: Boolean = false,
)

data class TrackingMutationResult(
    val attemptedCount: Int,
    val notFoundCount: Int = 0,
    val resolutions: List<TrackingMutationResolution> = emptyList(),
) {
    val resolvedListStatuses: List<TrackingListStatus>
        get() = resolutions.mapNotNull(TrackingMutationResolution::listStatus)

    val isComplete: Boolean
        get() = notFoundCount == 0
}

data class TrackingMutationResolution(
    val listStatus: TrackingListStatus? = null,
    val mediaKind: TrackingMediaKind? = null,
    val providerSubtype: String? = null,
)

interface TrackingListWriter {
    val providerId: TrackingProviderId

    suspend fun moveToList(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
        destination: TrackingListStatus,
    ): TrackingMutationResult

    suspend fun removeFromList(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
    ): TrackingMutationResult
}

interface TrackingHistoryWriter {
    val providerId: TrackingProviderId

    suspend fun addToHistory(
        profileId: Int,
        items: Collection<TrackingHistoryItem>,
    ): TrackingMutationResult

    suspend fun removeFromHistory(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
    ): TrackingMutationResult
}

/** Provider-backed personal rating mutation. Ratings are normalized to the shared 0-10 scale. */
interface TrackingRatingWriter {
    val providerId: TrackingProviderId

    suspend fun setRating(
        profileId: Int,
        media: TrackingMediaReference,
        rating: Int?,
    ): TrackingMutationResult
}

interface TrackingScrobbler {
    val providerId: TrackingProviderId
    val seekScrobblePolicy: TrackingSeekScrobblePolicy
        get() = TrackingSeekScrobblePolicy.NONE

    /**
     * How often a running session should be re-anchored with a fresh `START`, or null to never.
     *
     * Providers extrapolate the current position from the last scrobble they received, at ordinary
     * speed. Anything that breaks that assumption — a playback-speed change above 1x most of all —
     * makes their view of the session drift for as long as playback continues, and only the final
     * stop corrects it. Re-anchoring periodically bounds that drift.
     *
     * Opt in only where a repeated `START` is cheap and idempotent. It is not free: every refresh
     * is a request, and a provider with a tight request budget should leave this null and accept
     * the drift. A short per-user overlap lock (SIMKL's 20 seconds) is not that: intervals are
     * minutes apart, so the lock only ever costs a refresh that collided with a real start or stop.
     */
    val progressRefreshIntervalMs: Long?
        get() = null

    /**
     * Returns true when this provider produced a request, false when it declined the event.
     *
     * Declining is normal and is not a failure: a provider skips an event it cannot address safely
     * — no ids it accepts, or anime whose coordinates cannot be mapped into its numbering system.
     * Callers need to tell "nobody could address this" apart from "sent and it failed", because
     * only the former is worth retrying when the same item is offered again.
     */
    suspend fun scrobble(
        profileId: Int,
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent,
    ): Boolean
}
