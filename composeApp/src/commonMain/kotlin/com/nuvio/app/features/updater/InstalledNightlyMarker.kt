package com.nuvio.app.features.updater

/**
 * What a launch should do with the stored nightly marker.
 *
 * @param keepMarker false when the marker no longer describes the running build and must be dropped.
 * @param recordBuildId the build id to persist as "last seen", or null to leave the stored one alone.
 * @param clearPending whether the pending-swap flag has been accounted for and should be cleared.
 */
data class NightlyMarkerDecision(
    val keepMarker: Boolean,
    val recordBuildId: String?,
    val clearPending: Boolean,
)

/**
 * Decides whether the stored nightly marker still describes the build that is running.
 *
 * The marker is written by the updater just before it exits, so it is a statement about the build
 * that is *about to* be swapped in — nothing verifies afterwards that the swap happened, or that
 * the binary sitting there now is still that nightly. A locally built distributable copied over
 * the install is the common case: nothing about it is a nightly, yet the sidebar kept announcing
 * the nightly it replaced, which reads as "the new build never installed".
 *
 * Comparing the packaged build stamp against the one seen on the previous launch settles it:
 *
 * - the binary changed and the updater was mid-swap -> the nightly landed, keep the marker;
 * - the binary changed with no swap pending -> someone installed a build by hand, drop it;
 * - the binary did not change while a swap was pending -> the swap never happened, drop it;
 * - no build id was ever recorded -> treated as changed, so only a pending swap keeps the marker;
 * - neither changed -> the same build is still running, keep it;
 * - no stamp (an unpackaged `gradlew run`) -> no evidence either way, change nothing.
 */
fun decideNightlyMarker(
    hasMarker: Boolean,
    swapPending: Boolean,
    runningBuildId: String?,
    lastSeenBuildId: String?,
): NightlyMarkerDecision {
    if (runningBuildId == null) {
        return NightlyMarkerDecision(keepMarker = hasMarker, recordBuildId = null, clearPending = false)
    }
    if (!hasMarker) {
        return NightlyMarkerDecision(keepMarker = false, recordBuildId = runningBuildId, clearPending = swapPending)
    }
    // A swap that landed changes the build; a launch with nothing pending should not. The marker
    // survives when those two agree, and is dropped when they contradict each other.
    //
    // No recorded build id counts as changed rather than as "assume the marker is fine": on the
    // first launch that can read a stamp there is no evidence the binary is still the nightly the
    // marker names, and taking the marker at its word there is what kept a hand-installed build
    // announcing the nightly it replaced. The updater's own swap is still recognised, because it
    // leaves the pending flag set — which is the only thing that separates the two on that launch.
    val buildChanged = lastSeenBuildId == null || runningBuildId != lastSeenBuildId
    return NightlyMarkerDecision(
        keepMarker = buildChanged == swapPending,
        recordBuildId = runningBuildId,
        clearPending = swapPending,
    )
}
