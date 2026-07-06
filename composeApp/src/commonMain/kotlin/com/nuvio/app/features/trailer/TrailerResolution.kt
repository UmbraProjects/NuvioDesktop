package com.nuvio.app.features.trailer

/** Outcome of resolving a YouTube trailer to a direct, playable stream. */
sealed class TrailerResolution {
    data class Available(val source: TrailerPlaybackSource) : TrailerResolution()
    data class Unavailable(val reason: TrailerUnavailableReason) : TrailerResolution()
}

/**
 * Why a trailer could not be resolved to a playable stream. Distinguishes YouTube's own
 * "this video is not available" verdicts — which a retry can never fix, since they are
 * enforced server-side against the request's IP/account — from an unknown or transient
 * failure (network hiccup, YouTube response layout change) where a retry might succeed.
 */
enum class TrailerUnavailableReason {
    /** YouTube reports the video is blocked in the viewer's country. */
    REGION_BLOCKED,

    /** Age-gated and requires a signed-in YouTube session. */
    AGE_RESTRICTED,

    /** Removed, private, or otherwise no longer available. */
    REMOVED_OR_PRIVATE,

    /** Extraction failed for an unrecognized reason; retrying may succeed. */
    UNKNOWN,
}

fun TrailerResolution.sourceOrNull(): TrailerPlaybackSource? =
    (this as? TrailerResolution.Available)?.source
