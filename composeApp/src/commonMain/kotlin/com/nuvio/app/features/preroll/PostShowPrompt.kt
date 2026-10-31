package com.nuvio.app.features.preroll

import com.nuvio.app.features.discover.DiscoverAiPromptText

/**
 * Bump on any change to the prompt below; it is part of the cache key, so answers written under an
 * older prompt are not served as if the new one produced them.
 */
const val POST_SHOW_PROMPT_VERSION = 2

private const val CARD_MAX_CHARS = 200

private const val RULES =
        "Rules:\n" +
        "- Give 5 or 6 cards, each of a different kind: \"ending\" (what the ending meant), " +
        "\"easter_egg\" (a hidden detail or reference), \"watch_again\" (something to look for on a " +
        "second viewing), \"motivation\" (why a main character acted as they did), " +
        "\"connection\" (a link to another film, book or event), \"behind_the_scenes\" (a fact " +
        "about the making).\n" +
        "- Never invent an Easter egg, a quote, an anecdote or a statistic. If you are not sure a " +
        "detail is real, leave it out. Fewer, correct cards beat more.\n" +
        "- Do not give timestamps or scene numbers. Describe the moment in words.\n" +
        "- Each card is one or two self-contained sentences of at most $CARD_MAX_CHARS characters, " +
        "readable in a few seconds on a TV across a room.\n" +
        "- Interpretations are fine, but say so (\"is often read as\") instead of stating a " +
        "contested reading as fact.\n" +
        "- Give 3 quiz questions for someone who has just watched this, about its plot, " +
        "characters or making. Each has 4 distinct options and exactly one correct one.\n\n" +
        "Reply with a single JSON object and nothing else: no markdown fence, no preamble.\n" +
        "{\n" +
        "  \"cards\": [ { \"kind\": \"ending\"|\"easter_egg\"|\"watch_again\"|\"motivation\"|" +
        "\"connection\"|\"behind_the_scenes\", \"text\": string, " +
        "\"confidence\": number from 0 to 1 } ],\n" +
        "  \"quiz\": [ { \"q\": string, \"options\": [string, string, string, string], " +
        "\"answer\": the exact text of the correct option, \"explain\": one short sentence, " +
        "\"confidence\": number from 0 to 1 } ]\n" +
        "}\n" +
        "Be honest in \"confidence\": anything below 0.8 is discarded."

private const val SYSTEM_FILM =
    "You write short post-show cards for a film, shown on screen in a home cinema as the end " +
        "credits roll. The viewer has JUST WATCHED the film, so plot, twists and the ending may " +
        "be discussed freely.\n\n" +
        "You will be given the film's metadata. Treat it as ground truth. You may use what you " +
        "reliably know about this specific film, but only facts you are highly confident about, " +
        "and only if you are certain the film is the one described (the year and director are " +
        "given to disambiguate remakes and similarly titled films).\n\n" +
        RULES

private fun systemForSeries(scope: PostShowScope): String {
    val (kind, gate) = when (scope) {
        is PostShowScope.SeasonFinale -> "the finale of season ${scope.season}" to
            "- SPOILER GATE: the viewer has seen seasons 1 to ${scope.season} and nothing after. " +
            "Never mention, hint at or foreshadow anything that happens after season ${scope.season}: " +
            "later seasons, later plot points, character fates, cast changes, renewals or " +
            "cancellation. This holds for behind-the-scenes cards and quiz questions too. If a " +
            "point cannot be made without it, leave it out. \"ending\" means what this finale " +
            "meant and what it sets up, not how the show ends.\n"
        else -> "the series finale" to
            "- This is the series finale: the whole series may be discussed. \"ending\" means " +
            "what the ending of the whole show meant.\n"
    }
    return "You write short post-show cards for a TV series, shown on screen in a home cinema as " +
        "the end credits roll on $kind. The viewer has JUST WATCHED this episode, so what " +
        "happened in it and before it may be discussed freely.\n\n" +
        "You will be given the series' metadata. Treat it as ground truth. You may use what you " +
        "reliably know about this specific series, but only facts you are highly confident about, " +
        "and only if you are certain the series is the one described (the year is given to " +
        "disambiguate remakes and similarly titled shows).\n\n" +
        gate + RULES
}

fun buildPostShowPrompt(
    source: TriviaSource,
    languageTag: String,
    scope: PostShowScope = PostShowScope.Film,
): DiscoverAiPromptText {
    val user = buildString {
        val label = if (scope is PostShowScope.Film) "Film" else "Series"
        appendLine("$label: ${source.title}${source.year?.let { " ($it)" }.orEmpty()}")
        when (scope) {
            PostShowScope.Film -> Unit
            is PostShowScope.SeasonFinale ->
                appendLine("Episode just watched: ${episodeLabel(scope.season, scope.episode, scope.episodeTitle)} (season finale)")
            is PostShowScope.SeriesFinale ->
                appendLine("Episode just watched: ${episodeLabel(scope.season, scope.episode, scope.episodeTitle)} (series finale)")
        }
        source.directors.takeIf { it.isNotEmpty() }?.let { appendLine("Directed by: ${it.joinToString(", ")}") }
        source.cast.takeIf { it.isNotEmpty() }?.let { appendLine("Starring: ${it.joinToString(", ")}") }
        source.genres.takeIf { it.isNotEmpty() }?.let { appendLine("Genres: ${it.joinToString(", ")}") }
        source.collection?.let { appendLine("Part of: $it") }
        source.runtime?.let { appendLine("Runtime: $it") }
        source.awards?.let { appendLine("Awards: $it") }
        source.synopsis?.let { appendLine("Synopsis: $it") }
        appendLine()
        append("Write the cards and quiz in the language with tag \"$languageTag\".")
    }
    val system = when (scope) {
        PostShowScope.Film -> SYSTEM_FILM
        is PostShowScope.SeasonFinale, is PostShowScope.SeriesFinale -> systemForSeries(scope)
    }
    return DiscoverAiPromptText(system = system, user = user)
}

private fun episodeLabel(season: Int, episode: Int, title: String?): String =
    "S${season}E$episode" + title?.let { " \"$it\"" }.orEmpty()
