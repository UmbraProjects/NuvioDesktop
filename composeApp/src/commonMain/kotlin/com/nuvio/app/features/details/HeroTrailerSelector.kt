package com.nuvio.app.features.details

internal fun selectHeroTrailer(trailers: List<MetaTrailer>): MetaTrailer? =
    rankHeroTrailers(trailers).firstOrNull()

/**
 * Every playable YouTube trailer, best first by the same ordering [selectHeroTrailer] uses, with
 * one entry per video. The runners-up are the alternatives to try when the first upload is low
 * resolution.
 */
internal fun rankHeroTrailers(trailers: List<MetaTrailer>): List<MetaTrailer> =
    trailers
        .filter { it.isPlayableYouTubeTrailerCandidate() }
        .sortedWith(
            compareByDescending<MetaTrailer> { it.heroTrailerPriority() }
                .thenByDescending { it.publishedAt.orEmpty() }
                .thenByDescending { it.size ?: 0 }
                .thenByDescending { it.name },
        )
        .distinctBy { it.key }

internal fun MetaTrailer.youtubePlaybackUrl(): String =
    key.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        ?: "https://www.youtube.com/watch?v=$key"

private fun MetaTrailer.isPlayableYouTubeTrailerCandidate(): Boolean =
    key.isNotBlank() && site.equals("YouTube", ignoreCase = true)

private fun MetaTrailer.heroTrailerPriority(): Int {
    val isSeriesTrailer = seasonNumber != null
    val isTrailerType = type.equals("Trailer", ignoreCase = true)
    val basePriority = when {
        !isSeriesTrailer && isTrailerType && official -> 70
        !isSeriesTrailer && isTrailerType -> 60
        !isSeriesTrailer && official -> 50
        !isSeriesTrailer -> 40
        isTrailerType && official -> 30
        isTrailerType -> 20
        official -> 10
        else -> 0
    }
    // Accessibility-specific uploads are useful in the trailer list, but should not replace
    // the standard theatrical trailer in an automatically playing hero.
    return basePriority - if (isAccessibilityVariant()) 100 else 0
}

private fun MetaTrailer.isAccessibilityVariant(): Boolean {
    val label = listOf(name, displayName.orEmpty(), type).joinToString(" ").lowercase()
    return Regex("\\b(asl|american sign language|audio described|audio description|descriptive audio)\\b")
        .containsMatchIn(label)
}
