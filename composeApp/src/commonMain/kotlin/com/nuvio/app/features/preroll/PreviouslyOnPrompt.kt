package com.nuvio.app.features.preroll

import com.nuvio.app.features.discover.DiscoverAiPromptText

/** Part of the cache key: answers written under an older prompt are not served as the new one's. */
const val PREVIOUSLY_ON_PROMPT_VERSION = 1

private const val CARD_MAX_CHARS = 200

private const val SYSTEM =
    "You write a short \"previously on\" recap for a TV series, shown on screen in a home cinema " +
        "just before the viewer starts the next episode. They watched the episode being recapped " +
        "some time ago and have forgotten it.\n\n" +
        "SPOILER RULE: the viewer is about to watch the NEXT episode. Write only about the " +
        "recapped episode and what came before it. Never hint at, foreshadow or predict anything " +
        "that happens later, and never say what the next episode is about.\n\n" +
        "SOURCES: if an overview of the recapped episode is given, use only what it says (plus the " +
        "series synopsis for who the characters are). Do not add events, names or outcomes that are " +
        "not in the text you were given. If NO overview is given, use only what you are certain you " +
        "know about this exact episode of this exact series; if you are not certain, reply with " +
        "{\"cards\":[],\"quiz\":[]}. A short true recap beats a long invented one.\n\n" +
        "Rules:\n" +
        "- Give up to 5 cards. Kinds: \"last_time\" (1 or 2 cards: what happened), " +
        "\"where_it_ended\" (exactly 1: the cliffhanger or open question the episode ended on), " +
        "\"keep_in_mind\" (up to 2: a character or storyline worth remembering).\n" +
        "- Each card is one or two self-contained sentences of at most $CARD_MAX_CHARS characters, " +
        "readable in a few seconds on a TV across a room. Use names, not pronouns, at the start.\n" +
        "- Give 3 quiz questions about the recapped episode, answerable from your own cards. Each " +
        "has 4 distinct options and exactly one correct one.\n\n" +
        "Reply with a single JSON object and nothing else: no markdown fence, no preamble.\n" +
        "{\n" +
        "  \"cards\": [ { \"kind\": \"last_time\"|\"where_it_ended\"|\"keep_in_mind\", " +
        "\"text\": string, \"confidence\": number from 0 to 1 } ],\n" +
        "  \"quiz\": [ { \"q\": string, \"options\": [string, string, string, string], " +
        "\"answer\": the exact text of the correct option, \"explain\": one short sentence, " +
        "\"confidence\": number from 0 to 1 } ]\n" +
        "}\n" +
        "Be honest in \"confidence\": anything below 0.8 is discarded."

internal fun buildPreviouslyOnPrompt(source: TriviaSource, target: PreviouslyOnTarget, languageTag: String): DiscoverAiPromptText {
    val user = buildString {
        appendLine("Series: ${source.title}${source.year?.let { " ($it)" }.orEmpty()}")
        source.genres.takeIf { it.isNotEmpty() }?.let { appendLine("Genres: ${it.joinToString(", ")}") }
        source.cast.takeIf { it.isNotEmpty() }?.let { appendLine("Starring: ${it.joinToString(", ")}") }
        source.synopsis?.let { appendLine("Series synopsis: $it") }
        appendLine()
        appendLine(
            "Episode to recap: ${target.label}" + target.title?.let { " \"$it\"" }.orEmpty() +
                if (target.previousSeason) " (the last episode the viewer saw, from the previous season)" else "",
        )
        if (target.skippedEpisodes) appendLine("Note: the viewer skipped the episodes after it.")
        target.overview?.let { appendLine("Overview of that episode: $it") }
            ?: appendLine("Overview of that episode: (none given)")
        target.earlierOverview?.let { appendLine("Overview of the episode before it, for context: $it") }
        appendLine()
        append("Write the cards and quiz in the language with tag \"$languageTag\".")
    }
    return DiscoverAiPromptText(system = SYSTEM, user = user)
}

/** A stable stand-in for the inputs, for the cache key. */
internal fun PreviouslyOnTarget.fingerprint(source: TriviaSource): String =
    listOf(source.fingerprint(), title.orEmpty(), overview.orEmpty(), earlierOverview.orEmpty(), previousSeason, skippedEpisodes)
        .joinToString("|").hashCode().toString(16)
