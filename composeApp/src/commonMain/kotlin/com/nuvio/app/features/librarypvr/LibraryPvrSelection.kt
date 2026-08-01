package com.nuvio.app.features.librarypvr

/**
 * Pure edits behind the episode picker. Every one of these normalises [MonitoredItem.episodeOverrides]
 * so it only ever holds entries that *disagree* with the season default — otherwise toggling a
 * season on and off would slowly accumulate a per-episode entry for every episode of the show.
 */

/**
 * Turns a whole season on or off. Per the picker's contract, switching a season off also drops any
 * per-episode opt-ins inside it: the season chip is the master switch, so turning it off must not
 * leave stray episodes still monitored.
 */
internal fun MonitoredItem.withSeasonMonitored(season: Int, monitored: Boolean): MonitoredItem {
    val prefix = "$season:"
    return copy(
        monitoredSeasons = if (monitored) monitoredSeasons + season else monitoredSeasons - season,
        episodeOverrides = episodeOverrides.filterKeys { !it.startsWith(prefix) },
    )
}

/** Toggles one episode, keeping the override map free of entries that match the season default. */
internal fun MonitoredItem.withEpisodeMonitored(
    season: Int,
    episode: Int,
    monitored: Boolean,
): MonitoredItem {
    val key = episodeSelectionKey(season, episode)
    val seasonDefault = season in monitoredSeasons
    return copy(
        episodeOverrides = if (monitored == seasonDefault) {
            episodeOverrides - key
        } else {
            episodeOverrides + (key to monitored)
        },
    )
}

/**
 * The "All" / "None" control. [seasons] is every season the show actually has, so that "All" marks
 * seasons the user has not visited yet and "None" leaves nothing selected anywhere.
 */
internal fun MonitoredItem.withAllMonitored(seasons: Collection<Int>, monitored: Boolean): MonitoredItem =
    copy(
        monitoredSeasons = if (monitored) monitoredSeasons + seasons else emptySet(),
        episodeOverrides = emptyMap(),
    )

/**
 * Whether the scheduler should try to grab this episode, combining the picker's selection with the
 * mode's release-date rule. [releasedAtEpochMs] is the episode's air date, or null when unknown.
 */
internal fun MonitoredItem.wantsEpisode(
    season: Int,
    episode: Int,
    releasedAtEpochMs: Long?,
): Boolean = when (mode) {
    MonitorMode.ALL_MISSING -> true
    MonitorMode.SELECTED_ONLY -> selectsEpisode(season, episode)
    // An unknown air date is not treated as "future" — that would make every undated episode of a
    // long-running show a grab target the moment the title is monitored.
    MonitorMode.SELECTED_PLUS_FUTURE ->
        selectsEpisode(season, episode) ||
            (releasedAtEpochMs != null && releasedAtEpochMs >= addedAtEpochMs)
    MonitorMode.MOVIE_WHEN_AVAILABLE -> false
}
