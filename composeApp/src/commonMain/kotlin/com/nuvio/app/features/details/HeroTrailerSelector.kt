package com.nuvio.app.features.details

/**
 * The trailer the hero plays by itself. Language first: the list can hold every language TMDB has
 * (48 and 56 entries for two films, 2026-10-16), and ranking by "official, then newest" alone put a
 * newer foreign-language trailer ahead of the English one for an English title. A trailer in
 * [preferredLanguage] wins, one whose language is unknown comes next, any other language last.
 */
internal fun selectHeroTrailer(
    trailers: List<MetaTrailer>,
    preferredLanguage: String? = com.nuvio.app.features.tmdb.TmdbSettingsRepository.snapshot().language,
): MetaTrailer? {
    val wanted = preferredLanguage?.substringBefore('-')?.lowercase()?.takeIf(String::isNotBlank)
    return trailers
        .asSequence()
        .filter { it.isPlayableYouTubeTrailerCandidate() }
        .maxWithOrNull(
            compareBy<MetaTrailer>(
                { it.languageRank(wanted) },
                { it.heroTrailerPriority() },
                { it.publishedAt.orEmpty() },
                { it.size ?: 0 },
                { it.name },
            ),
        )
}

private fun MetaTrailer.languageRank(wanted: String?): Int {
    val own = language?.substringBefore('-')?.lowercase()?.takeIf(String::isNotBlank) ?: return 1
    return if (wanted == null || own == wanted) 2 else 0
}

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
