package com.nuvio.app.features.preroll

import com.nuvio.app.features.discover.DiscoverAiPromptText

/**
 * The prompt the pre-show sends.
 *
 * Pure, like the recap and Discover builders, because what the model is asked is the hardest thing
 * to eyeball and the easiest to get subtly wrong. The instruction text is deliberately not
 * localised: it goes to a provider, not a person. The language of the *answer* is stated inside it.
 *
 * Bump [TRIVIA_PROMPT_VERSION] on any change here; it is part of the cache key, so old answers
 * written under an older prompt are not served as if the new one had produced them.
 */
const val TRIVIA_PROMPT_VERSION = 2

private const val SLIDES_MIN = 8
private const val SLIDES_MAX = 10
private const val QUESTIONS = 3
private const val SLIDE_MAX_CHARS = 140

/** Must list every [TriviaAtmosphere]; a test enforces it, so adding one cannot be forgotten here. */
private const val THEME_ATMOSPHERES =
    "\"embers\"|\"snow\"|\"rain\"|\"dust\"|\"stars\"|\"fog\"|\"neon\"|\"sparks\"|\"bokeh\"|\"none\""

private const val COMMON_RULES =
    "Rules:\n" +
        "- NEVER reveal plot: no twists, endings, deaths, betrayals, who survives, or identity " +
        "reveals. The viewer is about to watch this film for the first time. The premise in the " +
        "supplied synopsis is fine to refer to; nothing beyond it is.\n" +
        "- Prefer facts about the making of the film, its cast and crew, its release and " +
        "reception, and its legacy. Avoid exact box-office or budget figures unless they are " +
        "supplied.\n" +
        "- If you are not confident a fact is true, leave it out. Fewer, correct facts beat more. " +
        "Never invent an anecdote, a quote, or a statistic.\n" +
        "- Each fact is one self-contained sentence of at most $SLIDE_MAX_CHARS characters, " +
        "readable in a few seconds on a TV across a room.\n" +
        "- Quiz questions must be answerable by someone who has NOT seen the film, from general " +
        "film knowledge: about cast, crew, release, awards, or making-of. Never about the plot. " +
        "Each has 4 distinct options and exactly one correct one.\n\n" +
        "Reply with a single JSON object and nothing else: no markdown fence, no preamble.\n" +
        "{\n" +
        "  \"slides\": [ { \"fact\": string, \"category\": \"production\"|\"cast\"|\"legacy\"|" +
        "\"awards\"|\"trivia\", \"spoiler_risk\": \"none\"|\"low\"|\"high\", " +
        "\"confidence\": number from 0 to 1 } ],\n" +
        "  \"quiz\": [ { \"q\": string, \"options\": [string, string, string, string], " +
        "\"answer\": the exact text of the correct option, \"explain\": one short sentence, " +
        "\"spoiler_risk\": \"none\"|\"low\"|\"high\", \"confidence\": number from 0 to 1 } ],\n" +
        "  \"theme\": { \"primary\": \"#RRGGBB\", \"secondary\": \"#RRGGBB\", " +
        "\"atmosphere\": $THEME_ATMOSPHERES, \"motif\": a few words }\n" +
        "}\n" +
        "\"theme\" is the art direction for a title card drawn over the film's own artwork: " +
        "\"primary\" is the colour the film is most remembered for (Rocky: deep boxing-glove red; " +
        "Alien: sickly green), \"secondary\" a contrasting accent (Rocky: gold), both fairly " +
        "saturated and neither near-white or near-black. \"atmosphere\" is the drifting particle " +
        "effect that best fits its mood. \"motif\" is a few words for its look.\n" +
        "Give $SLIDES_MIN to $SLIDES_MAX slides and $QUESTIONS quiz questions. Be honest in " +
        "\"confidence\": it is used to discard weak facts."

private const val SYSTEM_METADATA_ONLY =
    "You write short pre-show trivia for a film, shown on screen in a home cinema just before " +
        "it starts.\n\n" +
        "You will be given the film's metadata. It is your ONLY source: use nothing you " +
        "remember about the film beyond it. Build facts and questions from the supplied cast, " +
        "crew, studios, genres, release year, awards and keywords, and say nothing the metadata " +
        "does not support.\n\n" + COMMON_RULES

private const val SYSTEM_MODEL_KNOWLEDGE =
    "You write short pre-show trivia for a film, shown on screen in a home cinema just before " +
        "it starts.\n\n" +
        "You will be given the film's metadata. Treat it as ground truth. You may also use what " +
        "you reliably know about this specific film, but only facts you are highly confident " +
        "about, and only if you are certain the film is the one described (the year and " +
        "director are given to disambiguate remakes and similarly titled films).\n\n" +
        COMMON_RULES

fun buildTriviaPrompt(
    source: TriviaSource,
    languageTag: String,
    mode: TriviaMode,
): DiscoverAiPromptText {
    val user = buildString {
        appendLine("Film: ${source.title}${source.year?.let { " ($it)" }.orEmpty()}")
        source.directors.takeIf { it.isNotEmpty() }?.let { appendLine("Directed by: ${it.joinToString(", ")}") }
        source.cast.takeIf { it.isNotEmpty() }?.let { appendLine("Starring: ${it.joinToString(", ")}") }
        source.studios.takeIf { it.isNotEmpty() }?.let { appendLine("Studios: ${it.joinToString(", ")}") }
        source.genres.takeIf { it.isNotEmpty() }?.let { appendLine("Genres: ${it.joinToString(", ")}") }
        source.collection?.let { appendLine("Part of: $it") }
        source.runtime?.let { appendLine("Runtime: $it") }
        source.country?.let { appendLine("Country: $it") }
        source.language?.let { appendLine("Original language: $it") }
        source.awards?.let { appendLine("Awards: $it") }
        source.keywords.takeIf { it.isNotEmpty() }?.let { appendLine("Keywords: ${it.joinToString(", ")}") }
        source.synopsis?.let { appendLine("Synopsis (premise only): $it") }
        appendLine()
        append("Write the slides and quiz in the language with tag \"$languageTag\".")
    }
    return DiscoverAiPromptText(
        system = when (mode) {
            TriviaMode.MetadataOnly -> SYSTEM_METADATA_ONLY
            TriviaMode.ModelKnowledge -> SYSTEM_MODEL_KNOWLEDGE
        },
        user = user,
    )
}
