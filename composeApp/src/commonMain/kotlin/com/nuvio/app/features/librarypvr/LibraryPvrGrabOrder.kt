package com.nuvio.app.features.librarypvr

/** What the strict in-order walk should do when it reaches a given missing episode. */
internal enum class GrabWalkDecision {
    /** Start this episode now. */
    GRAB,

    /** This episode is unfinished; nothing behind it may start yet. */
    BLOCK,

    /** Given up on (or deliberately skipped); the walk may move past it. */
    SKIP,
}

/**
 * Decides how the strict in-order walk should treat one episode, given every grab record recorded
 * for that coordinate.
 *
 * The rule that matters: an episode only stops blocking the rest of the series once it has actually
 * been given up on. A transient provider failure keeps it blocking (and pending a retry) so a later
 * episode cannot overtake it and leave a permanent hole in the numbering.
 */
internal fun grabWalkDecision(
    episodeGrabs: List<GrabRecord>,
    nowEpochMs: Long,
): GrabWalkDecision {
    // In flight, or deliberately cancelled by the user: hold the line either way. A cancel is a
    // decision not to have this episode, not an invitation to grab the next one instead.
    if (episodeGrabs.any { it.isInFlight || it.status == GrabStatus.CANCELLED }) return GrabWalkDecision.BLOCK

    val dueAt = episodeGrabs
        .filter { it.isAwaitingRetry }
        .mapNotNull { it.nextAttemptAtEpochMs }
        .minOrNull()
    if (dueAt != null) {
        // Waiting out a backoff is the point — do not run ahead to a later episode.
        return if (nowEpochMs < dueAt) GrabWalkDecision.BLOCK else GrabWalkDecision.GRAB
    }

    // Terminal with no retry pending means the budget is spent: stop holding up the series.
    if (episodeGrabs.any { it.status == GrabStatus.FAILED || it.status == GrabStatus.SKIPPED }) {
        return GrabWalkDecision.SKIP
    }

    return GrabWalkDecision.GRAB
}
