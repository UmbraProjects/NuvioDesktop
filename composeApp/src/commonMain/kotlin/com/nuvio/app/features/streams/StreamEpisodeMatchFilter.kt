package com.nuvio.app.features.streams

/**
 * Removes only streams which explicitly identify themselves as a different episode. Addons can
 * occasionally return adjacent anime numbering schemes for one request (for example Pokémon
 * S7E41 for an S7E1 request). Unlabelled releases and season packs remain available because their
 * match cannot be determined safely.
 *
 * Evidence is ranked: a numeric S/E coordinate naming the requested episode is decisive, and the
 * episode-title heuristic is consulted only when no explicit coordinate agrees. Titles infer a
 * coordinate from prose, so letting them overrule an agreeing tag lets one ambiguous title reject
 * streams the addon labelled correctly. [seriesTitle], when known, additionally discards episode
 * titles that merely repeat it — those match every stream and identify no episode.
 */
internal fun List<StreamItem>.filterForRequestedEpisode(
    season: Int?,
    episode: Int?,
    episodeTitlesByCoordinate: Map<Pair<Int, Int>, String> = emptyMap(),
    seriesTitle: String? = null,
): List<StreamItem> {
    if (season == null || episode == null) return this
    val normalizedSeriesTitle = seriesTitle?.normalizedEpisodeTitle().orEmpty()
    val knownTitles = episodeTitlesByCoordinate.mapNotNull { (coordinate, title) ->
        title.normalizedEpisodeTitle()
            .takeIf { normalized -> normalized.length >= 8 && ' ' in normalized }
            // An episode title that only repeats the series title identifies nothing: addons put
            // the series title in every description and release name, so it would match the whole
            // result set and reject it as "that one episode". Episode 12 of "A Place Further Than
            // the Universe" is called exactly that, which emptied the picker for every other
            // episode of the show.
            ?.takeIf { normalized ->
                normalizedSeriesTitle.isEmpty() ||
                    !normalizedSeriesTitle.containsWholeNormalizedTitle(normalized)
            }
            ?.let { normalized -> normalized to coordinate }
    }
    return filter { stream ->
        stream.episodeMatchFor(season, episode, knownTitles) != EpisodeMatch.Mismatch
    }
}

private enum class EpisodeMatch {
    Match,
    Mismatch,
    Unknown,
}

private fun StreamItem.episodeMatchFor(
    season: Int,
    episode: Int,
    knownTitles: List<Pair<String, Pair<Int, Int>>>,
): EpisodeMatch {
    var hasExplicitMatch = false
    // File-level fields describe the actual selected torrent file and therefore take precedence
    // over an addon's display description, which may merely echo the requested episode.
    val fileCoordinates = listOfNotNull(
        behaviorHints.filename,
        clientResolve?.filename,
        clientResolve?.torrentName,
        clientResolve?.stream?.raw?.filename,
        clientResolve?.stream?.raw?.torrentName,
    ).flatMap(String::explicitEpisodeCoordinates)
    fileCoordinates.matchAgainst(season, episode)?.let { match ->
        if (match == EpisodeMatch.Mismatch) return match
        hasExplicitMatch = true
    }

    clientResolve?.stream?.raw?.parsed?.let { parsed ->
        if (parsed.seasons.isNotEmpty() && parsed.episodes.isNotEmpty()) {
            if (season !in parsed.seasons || episode !in parsed.episodes) return EpisodeMatch.Mismatch
            hasExplicitMatch = true
        }
    }

    val displayCoordinates = listOfNotNull(title, description, name)
        .flatMap(String::explicitEpisodeCoordinates)
    displayCoordinates.matchAgainst(season, episode)?.let { match ->
        if (match == EpisodeMatch.Mismatch) return match
        hasExplicitMatch = true
    }

    val resolvedSeason = clientResolve?.season
    val resolvedEpisode = clientResolve?.episode
    if (resolvedSeason != null && resolvedEpisode != null) {
        if (resolvedSeason != season || resolvedEpisode != episode) return EpisodeMatch.Mismatch
        hasExplicitMatch = true
    }

    // A numeric S/E tag naming the requested episode settles it. The title heuristic below infers
    // a coordinate from prose, so letting it overrule an explicit agreeing tag means a single
    // ambiguous title can veto a stream the addon labelled correctly — which is how a series whose
    // episode title repeats the series title lost its entire result set.
    if (hasExplicitMatch) return EpisodeMatch.Match

    val searchableText = listOfNotNull(
        behaviorHints.filename,
        clientResolve?.filename,
        clientResolve?.torrentName,
        clientResolve?.stream?.raw?.filename,
        clientResolve?.stream?.raw?.torrentName,
        title,
        description,
        name,
    ).joinToString(" ").normalizedEpisodeTitle()
    val titledCoordinates = knownTitles
        .filter { (knownTitle, _) -> searchableText.containsWholeNormalizedTitle(knownTitle) }
        .map { (_, coordinate) -> coordinate }
        .distinct()
    return titledCoordinates.matchAgainst(season, episode) ?: EpisodeMatch.Unknown
}

private fun List<Pair<Int, Int>>.matchAgainst(season: Int, episode: Int): EpisodeMatch? = when {
    isEmpty() -> null
    any { (declaredSeason, declaredEpisode) -> declaredSeason == season && declaredEpisode == episode } ->
        EpisodeMatch.Match
    else -> EpisodeMatch.Mismatch
}

private fun String.explicitEpisodeCoordinates(): List<Pair<Int, Int>> = buildList {
    seasonEpisodeRangeRegex.findAll(this@explicitEpisodeCoordinates).forEach { match ->
        val season = match.groupValues[1].toIntOrNull() ?: return@forEach
        val firstEpisode = match.groupValues[2].toIntOrNull() ?: return@forEach
        val lastEpisode = match.groupValues[3].toIntOrNull() ?: return@forEach
        if (lastEpisode >= firstEpisode && lastEpisode - firstEpisode <= 100) {
            (firstEpisode..lastEpisode).forEach { episode -> add(season to episode) }
        }
    }
    seasonEpisodeRegex.findAll(this@explicitEpisodeCoordinates).forEach { match ->
        val season = match.groupValues[1].toIntOrNull() ?: return@forEach
        val episode = match.groupValues[2].toIntOrNull() ?: return@forEach
        add(season to episode)
    }
    numericSeasonEpisodeRegex.findAll(this@explicitEpisodeCoordinates).forEach { match ->
        val season = match.groupValues[1].toIntOrNull() ?: return@forEach
        val episode = match.groupValues[2].toIntOrNull() ?: return@forEach
        add(season to episode)
    }
}.distinct()

private val seasonEpisodeRangeRegex = Regex(
    pattern = """(?i)(?:^|[^a-z0-9])s(?:eason)?\s*0*(\d{1,3})[\s._-]*e(?:p(?:isode)?)?\s*0*(\d{1,4})\s*(?:-|to)\s*e?(?:p(?:isode)?)?\s*0*(\d{1,4})(?=$|[^0-9])""",
)

private val seasonEpisodeRegex = Regex(
    pattern = """(?i)(?:^|[^a-z0-9])s(?:eason)?\s*0*(\d{1,3})[\s._-]*e(?:p(?:isode)?)?\s*0*(\d{1,4})(?=$|[^0-9])""",
)

private val numericSeasonEpisodeRegex = Regex(
    pattern = """(?i)(?:^|[^a-z0-9])0*(\d{1,3})\s*x\s*0*(\d{1,4})(?=$|[^0-9])""",
)

private fun String.normalizedEpisodeTitle(): String =
    lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

private fun String.containsWholeNormalizedTitle(title: String): Boolean =
    this == title || startsWith("$title ") || endsWith(" $title") || contains(" $title ")
