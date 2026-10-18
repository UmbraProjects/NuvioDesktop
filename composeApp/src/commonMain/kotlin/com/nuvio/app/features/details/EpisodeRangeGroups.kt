package com.nuvio.app.features.details

/**
 * Display-only "pseudo seasons" for very long seasons.
 *
 * Long-running anime kept as one entry (One Piece: 1400+ videos, all "season 1") arrive as a single
 * season, so the episode row was one endless strip with no way to jump to episode 900. Any season
 * with more than [EPISODE_RANGE_SIZE] episodes is split into ranges of that size ("1–100",
 * "101–200", …), and the ranges take the season's place in the selector.
 *
 * Only the grouping changes. Every episode keeps its own id, season and number, so playback,
 * watched state and stream lookup never see a range. A range is addressed by a synthetic group key
 * well above any real season number; [episodeGroupSeason] turns it back into the real season.
 */
internal const val EPISODE_RANGE_SIZE = 100

private const val EPISODE_RANGE_KEY_BASE = 1_000_000_000
private const val EPISODE_RANGE_KEY_SEASON_STRIDE = 1_000
private const val EPISODE_RANGE_MAX_SEASON = 999

internal fun Int.isEpisodeRangeKey(): Boolean =
    this >= EPISODE_RANGE_KEY_BASE && this < EPISODE_RANGE_KEY_BASE + (EPISODE_RANGE_MAX_SEASON + 1) * EPISODE_RANGE_KEY_SEASON_STRIDE

/** The real season a group key belongs to; real season keys are returned as they are. */
internal fun Int.episodeGroupSeason(): Int =
    if (isEpisodeRangeKey()) (this - EPISODE_RANGE_KEY_BASE) / EPISODE_RANGE_KEY_SEASON_STRIDE else this

private fun episodeRangeKey(season: Int, rangeIndex: Int): Int =
    EPISODE_RANGE_KEY_BASE + season * EPISODE_RANGE_KEY_SEASON_STRIDE + rangeIndex

/**
 * Splits every season of an already season-grouped, episode-sorted map that is longer than
 * [rangeSize] into ranges. Specials, and seasons numbered beyond what a key can carry, are left whole.
 */
internal fun Map<Int, List<MetaVideo>>.withEpisodeRanges(
    rangeSize: Int = EPISODE_RANGE_SIZE,
): Map<Int, List<MetaVideo>> {
    if (values.none { it.size > rangeSize }) return this
    val result = LinkedHashMap<Int, List<MetaVideo>>()
    forEach { (season, episodes) ->
        val splittable = episodes.size > rangeSize &&
            season > SPECIALS_SEASON_NUMBER &&
            season <= EPISODE_RANGE_MAX_SEASON
        if (!splittable) {
            result[season] = episodes
        } else {
            episodes.chunked(rangeSize).forEachIndexed { index, range ->
                result[episodeRangeKey(season, index)] = range
            }
        }
    }
    return result
}

/** Orders group keys like seasons, with a season's ranges in place of it. */
internal fun episodeGroupSortKey(key: Int): Long {
    val season = key.episodeGroupSeason()
    val seasonKey = seasonSortKey(season).toLong()
    if (seasonKey == Int.MAX_VALUE.toLong()) return Long.MAX_VALUE
    val rangeIndex = if (key.isEpisodeRangeKey()) (key - EPISODE_RANGE_KEY_BASE) % EPISODE_RANGE_KEY_SEASON_STRIDE else 0
    return seasonKey * EPISODE_RANGE_KEY_SEASON_STRIDE + rangeIndex
}

/**
 * The group a season/episode lives in: the season itself when it was not split, otherwise the range
 * holding [episodeNumber] (the season's first range when the episode is unknown or not listed).
 * Null when nothing matches, so callers keep their own default.
 */
internal fun Map<Int, List<MetaVideo>>.episodeGroupKeyFor(seasonNumber: Int?, episodeNumber: Int?): Int? {
    if (seasonNumber == null) {
        // Absolute-numbered anime can carry no season at all; only a range can be inferred then.
        episodeNumber ?: return null
        return keys.filter { it.isEpisodeRangeKey() }
            .sortedBy(::episodeGroupSortKey)
            .firstOrNull { key -> getValue(key).any { it.effectiveEpisodeNumber() == episodeNumber } }
    }
    val season = normalizeSeasonNumber(seasonNumber)
    if (season in this) return season
    val ranges = keys.filter { it.isEpisodeRangeKey() && it.episodeGroupSeason() == season }
        .sortedBy(::episodeGroupSortKey)
    if (ranges.isEmpty()) return null
    if (episodeNumber != null) {
        ranges.firstOrNull { key ->
            getValue(key).any { it.effectiveEpisodeNumber() == episodeNumber }
        }?.let { return it }
    }
    return ranges.first()
}

/** "101–200": the episode numbers a range actually holds, or null for a real season key. */
internal fun Map<Int, List<MetaVideo>>.episodeRangeLabel(key: Int): String? {
    if (!key.isEpisodeRangeKey()) return null
    val numbers = get(key).orEmpty().mapNotNull { it.effectiveEpisodeNumber() }
    val first = numbers.minOrNull() ?: return null
    val last = numbers.maxOrNull() ?: return null
    return if (first == last) "$first" else "$first–$last"
}
