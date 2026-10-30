package com.nuvio.app.features.details

/**
 * fork:trailer-quality
 *
 * Every playable YouTube trailer, best first, with one entry per video. The order is exactly the
 * one [selectHeroTrailer] uses (language, then priority, then newest, ...): it is built by asking
 * that function for the best of what is left, so a change to upstream's ranking carries over here
 * without this file being touched. The runners-up are the alternatives to try when the first
 * upload is low resolution.
 */
internal fun rankHeroTrailers(trailers: List<MetaTrailer>): List<MetaTrailer> {
    val ranked = ArrayList<MetaTrailer>()
    var remaining = trailers
    while (true) {
        val best = selectHeroTrailer(remaining) ?: break
        ranked += best
        remaining = remaining.filter { it.key != best.key }
    }
    return ranked
}
