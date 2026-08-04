package com.nuvio.app.features.details

/**
 * Filters a series' episodes using the text viewers are most likely to remember: the title,
 * synopsis, episode number, or a season/episode code such as S02E05 or 2x05.
 *
 * Every whitespace-separated query term must match, so a search such as "cartman future" can
 * match words from the same title/synopsis without requiring an exact phrase.
 */
internal fun List<MetaVideo>.matchingEpisodeSearch(query: String): List<MetaVideo> {
    val terms = query
        .trim()
        .lowercase()
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
    if (terms.isEmpty()) return this

    return filter { episode ->
        val season = episode.effectiveSeasonNumber()
        val number = episode.effectiveEpisodeNumber()
        val searchableText = buildString {
            append(episode.title.lowercase())
            append(' ')
            append(episode.overview.orEmpty().lowercase())
            if (number != null) {
                append(" episode ")
                append(number)
                append(" ep ")
                append(number)
                append(" e")
                append(number)
            }
            if (season != null) {
                append(" season ")
                append(season)
                if (number != null) {
                    append(" s")
                    append(season)
                    append('e')
                    append(number)
                    append(" s")
                    append(season.toString().padStart(2, '0'))
                    append('e')
                    append(number.toString().padStart(2, '0'))
                    append(' ')
                    append(season)
                    append('x')
                    append(number)
                }
            }
        }
        terms.all(searchableText::contains)
    }.sortedWith(metaVideoSeasonEpisodeComparator)
}
