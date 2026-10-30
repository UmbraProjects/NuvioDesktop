package com.nuvio.app.features.preroll

import com.nuvio.app.features.discover.DiscoverAiPromptText

/**
 * Bump on any change to the prompt below; it is part of the cache key, so answers written under an
 * older prompt are not served as if the new one produced them.
 */
const val POST_SHOW_PROMPT_VERSION = 1

private const val CARD_MAX_CHARS = 200

private const val SYSTEM =
    "You write short post-show cards for a film, shown on screen in a home cinema as the end " +
        "credits roll. The viewer has JUST WATCHED the film, so plot, twists and the ending may " +
        "be discussed freely.\n\n" +
        "You will be given the film's metadata. Treat it as ground truth. You may use what you " +
        "reliably know about this specific film, but only facts you are highly confident about, " +
        "and only if you are certain the film is the one described (the year and director are " +
        "given to disambiguate remakes and similarly titled films).\n\n" +
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
        "- Give 3 quiz questions for someone who has just watched the film, about its plot, " +
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

fun buildPostShowPrompt(source: TriviaSource, languageTag: String): DiscoverAiPromptText {
    val user = buildString {
        appendLine("Film: ${source.title}${source.year?.let { " ($it)" }.orEmpty()}")
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
    return DiscoverAiPromptText(system = SYSTEM, user = user)
}
