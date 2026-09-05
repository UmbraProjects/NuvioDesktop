package com.nuvio.app.features.streams

/**
 * Everything [StreamPrefetchService] needs to know to decide whether a speculative search is worth
 * making, gathered into one value so the decision is a pure function rather than a chain of early
 * returns buried in a coroutine.
 */
internal data class PrefetchGateInputs(
    val trigger: StreamPrefetchService.Trigger,
    val scope: StreamPrefetchScope,
    val playbackActive: Boolean,
    val withinCooldown: Boolean,
    val interactiveFetchRunning: Boolean,
    val hasInstantSourceAlready: Boolean,
)

/**
 * Why a prefetch did or did not run. Named rather than boolean so the log line says which fence
 * stopped it — with six of them, "skipped" on its own is not a diagnosis.
 */
internal enum class PrefetchDecision {
    Proceed,

    /** The user has not opted in. */
    ScopeDisabled,

    /** Opted in, but not for the surface that asked — Continue Watching under a details-only scope. */
    TriggerOutOfScope,

    /** The player owns the screen; its bandwidth is not ours to borrow. */
    PlaybackActive,

    /** This target was searched recently enough that the answer is still held. */
    CoolingDown,

    /** A search the user actually asked for is in flight; it owns the providers' patience. */
    InteractiveFetchRunning,

    /** A local file, a download, or a still-valid last link already makes this play instant. */
    AlreadyInstant,

    /** Too many speculative sweeps in the recent window. Checked separately, since it consumes. */
    BudgetReached,
}

/**
 * Ordered cheapest-reason-first, and by how conclusive the reason is: opting out beats everything,
 * then the transient screen conditions, then the two answers that mean "the work is pointless"
 * rather than "the work is badly timed".
 */
internal fun evaluatePrefetchGate(inputs: PrefetchGateInputs): PrefetchDecision = when {
    !inputs.scope.isEnabled -> PrefetchDecision.ScopeDisabled
    inputs.trigger == StreamPrefetchService.Trigger.ContinueWatching &&
        !inputs.scope.includesContinueWatching -> PrefetchDecision.TriggerOutOfScope
    inputs.playbackActive -> PrefetchDecision.PlaybackActive
    inputs.withinCooldown -> PrefetchDecision.CoolingDown
    inputs.interactiveFetchRunning -> PrefetchDecision.InteractiveFetchRunning
    inputs.hasInstantSourceAlready -> PrefetchDecision.AlreadyInstant
    else -> PrefetchDecision.Proceed
}
