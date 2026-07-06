package com.nuvio.app.features.details

internal const val SPECIALS_SEASON_NUMBER = 0

private val seasonEpisodePattern = Regex(
    pattern = """(?i)(?:\bS(\d{1,3})\s*E(\d{1,3})\b|\b(\d{1,3})x(\d{1,3})\b)""",
)

internal val metaVideoSeasonEpisodeComparator: Comparator<MetaVideo> =
    compareBy<MetaVideo>(
        { seasonSortKey(it.effectiveSeasonNumber()) },
        { it.effectiveEpisodeNumber() ?: Int.MAX_VALUE },
        { it.released ?: "" },
        { it.title },
    )

internal fun MetaVideo.effectiveSeasonNumber(): Int? =
    season ?: inferredSeasonEpisode()?.first

internal fun MetaVideo.effectiveEpisodeNumber(): Int? =
    episode ?: inferredSeasonEpisode()?.second

private fun MetaVideo.inferredSeasonEpisode(): Pair<Int, Int>? {
    val source = "$title $id"
    val match = seasonEpisodePattern.find(source) ?: return null
    val season = match.groups[1]?.value ?: match.groups[3]?.value
    val episode = match.groups[2]?.value ?: match.groups[4]?.value
    val seasonNumber = season?.toIntOrNull()
    val episodeNumber = episode?.toIntOrNull()
    return if (seasonNumber != null && episodeNumber != null) {
        seasonNumber to episodeNumber
    } else {
        null
    }
}

internal fun normalizeSeasonNumber(seasonNumber: Int?): Int =
    if (seasonNumber == null || seasonNumber <= SPECIALS_SEASON_NUMBER) {
        SPECIALS_SEASON_NUMBER
    } else {
        seasonNumber
    }

internal fun seasonSortKey(seasonNumber: Int?): Int =
    if (seasonNumber == null || seasonNumber <= SPECIALS_SEASON_NUMBER) {
        Int.MAX_VALUE
    } else {
        seasonNumber
    }
