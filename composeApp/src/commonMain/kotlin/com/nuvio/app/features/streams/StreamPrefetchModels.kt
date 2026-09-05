package com.nuvio.app.features.streams

/**
 * How far the background stream search is allowed to reach.
 *
 * Speculative scraping costs provider requests the user did not ask for, which is the one thing that
 * turns this feature from a latency win into rate-limit trouble, so the scope is a single explicit
 * choice rather than a switch plus hidden defaults — and it starts at [OFF].
 */
enum class StreamPrefetchScope {
    /** No background searching. Every scrape is one the user triggered. */
    OFF,

    /**
     * Search when a details page is opened, for the one episode its play button would start:
     * the resume-or-next-up primary action for a series, or the film itself.
     */
    DETAILS,

    /**
     * [DETAILS], plus the Continue Watching / Up Next card the user is dwelling on. Costs more
     * requests — a browsing session touches many cards — but covers the "open the app and press
     * play" path, which is the one that otherwise always pays full scrape latency.
     */
    DETAILS_AND_CONTINUE_WATCHING,
    ;

    val isEnabled: Boolean get() = this != OFF

    val includesContinueWatching: Boolean get() = this == DETAILS_AND_CONTINUE_WATCHING
}

/** Selectable retention windows, in minutes, for prefetched provider responses. */
val STREAM_PREFETCH_CACHE_MINUTE_VALUES: List<Int> = listOf(5, 10, 15, 30)

/**
 * Default retention for a prefetched response.
 *
 * Deliberately inside `DirectDebridResolver`'s 15-minute resolve TTL: a prefetch that also warmed a
 * debrid link is only worth replaying while that link is still likely to be warm.
 */
const val STREAM_PREFETCH_DEFAULT_CACHE_MINUTES: Int = 10
