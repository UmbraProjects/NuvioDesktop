package com.nuvio.app.features.discover

/**
 * The prompt an AI row sends — plan §5.
 *
 * Pure, so the thing hardest to eyeball (what the model is actually asked) is the thing under test.
 */

/** One title from the user's history, as the prompt describes it. */
data class DiscoverAiSeed(
    val title: String,
    val year: Int? = null,
    /** `movie` or `series`. */
    val type: String = "movie",
    /** The user's own rating where there is one, 0-10. */
    val rating: Double? = null,
)

/** A ready-to-send pair. Both backends take a system string and one user message. */
data class DiscoverAiPromptText(val system: String, val user: String)

/**
 * The row types offered. Each is one saved prompt, and [Custom] is the user's own.
 *
 * The instruction text lives here rather than in `strings.xml` deliberately: it is sent to a model,
 * not shown to a person, and translating it would change what is asked of the provider. The row
 * *titles* are user-visible and are localised where they are rendered.
 */
enum class DiscoverAiPreset(val instruction: String) {
    HiddenGems(
        "Suggest lesser-known titles this person is likely to love: things with modest audience " +
            "numbers rather than blockbusters, but genuinely well made. Avoid anything famous.",
    ),
    ComfortWatches(
        "Suggest warm, rewatchable, low-stakes titles that match this person's taste. Think " +
            "easy company rather than demanding cinema.",
    ),
    CriticallyAcclaimed(
        "Suggest widely acclaimed titles that this person appears to have missed, judging by " +
            "what they have already watched.",
    ),
    /**
     * Phase 8. The instruction leans hard on *these titles* rather than on a taste profile, because
     * the slice it receives is the last ten things watched and the question is "what next, while
     * this mood lasts" — the one question a recommendation feed built from an all-time profile
     * cannot answer.
     */
    JustWatched(
        "The list below is what this person watched most recently, newest first. Suggest what to " +
            "watch next while that mood lasts: continuations of the tone, pace and subject of " +
            "these specific titles, rather than of their all-time taste. Weight the newest " +
            "entries most heavily.",
    ),

    /**
     * Phase 8, and the one case §19.3 argued a model is genuinely better at than a query: the
     * shared quality of a set of favourites is usually not a genre or a keyword. Asking for the
     * quality to be named in the reason is what makes the row inspectable — a wrong cluster is
     * obvious once it says out loud what it thought it had found.
     */
    ClusteredFavourites(
        "The list below is what this person has committed the most time to. Read it as a group " +
            "and work out what those titles have in common — the shape of them: their ambition, " +
            "structure, tone or preoccupation, not their genre. Then suggest titles that share " +
            "that quality. In each reason, name the quality you matched.",
    ),

    Custom(""),
}

/** How many seeds travel. The plan's 30-50 band; 40 is the middle of it. */
const val DISCOVER_AI_MAX_SEEDS = 40

/**
 * How many already-watched titles are named as exclusions.
 *
 * Capped separately and lower than it could be: this list is pure prompt weight, it is only a first
 * line of defence (the post-resolution filter is the real one), and a thousand titles would crowd
 * out the seeds that actually describe the taste.
 */
const val DISCOVER_AI_MAX_EXCLUSIONS = 60

/** Default row length asked for. Some suggestions never resolve, so this asks for a few spare. */
const val DISCOVER_AI_REQUESTED_ITEMS = 24

private const val SYSTEM_PROMPT =
    "You recommend films and television to one person, based on their viewing history.\n\n" +
        "Reply with a JSON array and nothing else. No prose, no explanation, no markdown fence. " +
        "Each element must be an object with exactly these keys:\n" +
        "  \"title\"  - the title as it is commonly known in English\n" +
        "  \"year\"   - the release year as a number\n" +
        "  \"type\"   - either \"movie\" or \"series\"\n" +
        "  \"reason\" - one short sentence, under 20 words, saying why this person specifically\n\n" +
        "Suggest only real, released titles. Never repeat a title the user has already seen. " +
        "Never invent a title."

/**
 * Builds the prompt for one AI row.
 *
 * [exclusions] are fed in as "do not suggest these" because it is far cheaper than discarding half
 * the response — but it is never trusted: a model will confidently return an excluded title anyway,
 * which is why the same watched set is applied again after TMDB resolution (§5.1).
 */
fun buildDiscoverAiPrompt(
    preset: DiscoverAiPreset,
    customInstruction: String = "",
    seeds: List<DiscoverAiSeed> = emptyList(),
    exclusions: List<String> = emptyList(),
    requestedItems: Int = DISCOVER_AI_REQUESTED_ITEMS,
): DiscoverAiPromptText {
    val instruction = when (preset) {
        DiscoverAiPreset.Custom -> customInstruction.trim()
            .ifBlank { "Suggest titles this person is likely to enjoy." }

        else -> preset.instruction
    }

    val user = buildString {
        append(instruction)
        append("\n\nReturn exactly ")
        append(requestedItems.coerceIn(1, 50))
        append(" suggestions.")

        val describedSeeds = seeds.take(DISCOVER_AI_MAX_SEEDS).filter { it.title.isNotBlank() }
        if (describedSeeds.isNotEmpty()) {
            append("\n\nRecently watched, most relevant first:\n")
            describedSeeds.forEach { seed ->
                append("- ")
                append(seed.title.trim())
                seed.year?.let { append(" (").append(it).append(")") }
                append(" [").append(normalizedSeedType(seed.type)).append("]")
                seed.rating?.let { append(" rated ").append(formatRating(it)).append("/10") }
                append('\n')
            }
        }

        val excluded = exclusions.asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() }
            .distinct()
            .take(DISCOVER_AI_MAX_EXCLUSIONS)
            .toList()
        if (excluded.isNotEmpty()) {
            append("\nDo not suggest any of these, they have already been watched:\n")
            append(excluded.joinToString("; "))
            append('\n')
        }
    }

    return DiscoverAiPromptText(system = SYSTEM_PROMPT, user = user)
}

private fun normalizedSeedType(type: String): String =
    if (type.equals("series", ignoreCase = true) || type.equals("tv", ignoreCase = true)) {
        "series"
    } else {
        "movie"
    }

/**
 * One decimal, no locale involvement. `toString()` on a Double would emit `7.0` as `7.0` on some
 * targets and `7` on others, and the difference travels into the prompt.
 */
private fun formatRating(rating: Double): String {
    val clamped = rating.coerceIn(0.0, 10.0)
    val tenths = (clamped * 10).toInt()
    return "${tenths / 10}.${tenths % 10}"
}


/**
 * The seeds and exclusions for an AI row, from watch history alone.
 *
 * Deliberately built from [collapseWatchedToSeedCandidates] rather than [DiscoverSeedService]: that
 * one resolves every candidate against TMDB to get ids and genres, at a request each, and the model
 * needs none of that — a title, a type and a rough sense of recency is the whole input. An AI row
 * therefore costs exactly one provider call and no TMDB calls until its answer comes back.
 *
 * `year` and `rating` are left null because the watch history does not carry either in a form worth
 * trusting: `releaseInfo` is free text that is a bare year for some addons and a range for others,
 * and a wrong year in the prompt is worse than no year.
 */
fun aiSeedsFromHistory(
    history: List<com.nuvio.app.features.watched.WatchedItem>,
    now: Long,
): List<DiscoverAiSeed> =
    collapseWatchedToSeedCandidates(history, now = now)
        .take(DISCOVER_AI_MAX_SEEDS)
        .map { candidate ->
            DiscoverAiSeed(
                title = candidate.title,
                type = if (candidate.type.equals("movie", ignoreCase = true)) "movie" else "series",
            )
        }

/**
 * Titles to tell the model not to suggest.
 *
 * Drawn from the whole history rather than the seed window: something watched two years ago is
 * still watched, and the exclusion list is the cheap half of the defence. The expensive half —
 * dropping what it suggests anyway — happens after resolution.
 */
fun aiExclusionsFromHistory(
    history: List<com.nuvio.app.features.watched.WatchedItem>,
): List<String> =
    history.asSequence()
        .map { it.name.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(DISCOVER_AI_MAX_EXCLUSIONS)
        .toList()
