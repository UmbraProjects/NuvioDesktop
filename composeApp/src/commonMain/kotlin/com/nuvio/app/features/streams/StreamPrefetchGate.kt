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
    // The next-episode preload has its own setting (checked by the caller) and runs during playback
    // by design, so neither the scope nor the playback fence applies to it.
    inputs.trigger == StreamPrefetchService.Trigger.NextEpisode -> when {
        inputs.withinCooldown -> PrefetchDecision.CoolingDown
        inputs.interactiveFetchRunning -> PrefetchDecision.InteractiveFetchRunning
        inputs.hasInstantSourceAlready -> PrefetchDecision.AlreadyInstant
        else -> PrefetchDecision.Proceed
    }
    !inputs.scope.isEnabled -> PrefetchDecision.ScopeDisabled
    inputs.trigger == StreamPrefetchService.Trigger.ContinueWatching &&
        !inputs.scope.includesContinueWatching -> PrefetchDecision.TriggerOutOfScope
    inputs.playbackActive -> PrefetchDecision.PlaybackActive
    inputs.withinCooldown -> PrefetchDecision.CoolingDown
    inputs.interactiveFetchRunning -> PrefetchDecision.InteractiveFetchRunning
    inputs.hasInstantSourceAlready -> PrefetchDecision.AlreadyInstant
    else -> PrefetchDecision.Proceed
}

/** The kinds of source that can make a search ahead pointless, in the order they are checked. */
internal enum class InstantSource {
    /** Streams supplied with the episode's own meta listing. */
    Embedded,

    /** Local streams the details meta carries. */
    LocalStream,

    /** A file found by the local folder scan. */
    LocalLibrary,

    /** A finished download. */
    Download,

    /** A still-valid last link (Reuse last link). */
    LastLink,
}

/**
 * Whether [source] means the target will start without a search, for the surface that asked.
 *
 * Home and Details open through the streams screen, which plays any of these straight away. The
 * next-episode preload only runs while an online stream is playing, and next-episode autoplay then
 * drops local streams from its pick and never reads the last link: it plays a download first (when
 * the title keeps its local copy for local playback) and otherwise searches, embedded streams
 * included. Counting a local file there skipped the preload for every show also kept on disk, and
 * the pick at the end of the episode ran its full search cold (Nov 21, South Park S1E3).
 */
internal fun InstantSource.skipsPrefetchFor(
    trigger: StreamPrefetchService.Trigger,
    localCopyIsBackup: Boolean,
): Boolean = when (trigger) {
    StreamPrefetchService.Trigger.NextEpisode -> when (this) {
        InstantSource.Embedded -> true
        InstantSource.Download -> !localCopyIsBackup
        InstantSource.LocalStream, InstantSource.LocalLibrary, InstantSource.LastLink -> false
    }
    else -> true
}
