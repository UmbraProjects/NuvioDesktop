package com.nuvio.app.features.music.library

/**
 * Ladder phase 5: what the search card offers and what the ladder screen shows, as pure data (no Compose, so it is
 * unit-tested). The screen itself is MusicLadderScreen.kt.
 *
 * [ladderOffers] turns the Music tab's query into buttons; [loadLadder] turns a button into a [LadderState] through
 * the same sources the earlier phases use, passed in so the whole path runs without a network.
 */

internal sealed interface LadderRequest {
    /** A ladder into a genre the person typed ("shoegaze"). */
    data class Genre(val genre: String) : LadderRequest

    /** A ladder into ([LadderMode.Into]) or out from ([LadderMode.OutFrom]) an artist. */
    data class Artist(
        val name: String,
        val mode: LadderMode,
        /** Out-from only: the genre to lead into; null lets the artist's own genres decide. */
        val destination: String? = null,
    ) : LadderRequest
}

internal data class LadderOffer(val label: String, val request: LadderRequest)

private fun String.capitalised(): String = replaceFirstChar { it.uppercase() }

/**
 * The buttons for the first result row. A query that is a known genre gives one ("Ladder into Shoegaze"). Otherwise,
 * when the first artist result is known, two ("Climb into X", "Climb out from X"). Nothing for a short or blank query.
 * Album queries get no button until phase 10.
 */
internal fun ladderOffers(query: String, firstArtistName: String?): List<LadderOffer> {
    val text = query.trim()
    if (text.length < 2) return emptyList()
    if (isKnownGenre(text)) {
        val genre = canonicalGenre(text) ?: return emptyList()
        return listOf(LadderOffer("Ladder into ${genre.capitalised()}", LadderRequest.Genre(genre)))
    }
    val artist = firstArtistName?.trim().orEmpty()
    if (artist.isEmpty()) return emptyList()
    return listOf(
        LadderOffer("Climb into $artist", LadderRequest.Artist(artist, LadderMode.Into)),
        LadderOffer("Climb out from $artist", LadderRequest.Artist(artist, LadderMode.OutFrom)),
    )
}

/** Everything the screen draws for a ready ladder. */
internal data class LadderView(
    val title: String,
    val subtitle: String?,
    /** A line about how the ladder was made (borrowed genres, no Library), or null. */
    val note: String?,
    val ladder: Ladder,
    /** Out-from only: other genres to lead out into; the screen offers them as chips. */
    val alternatives: List<String> = emptyList(),
)

internal sealed interface LadderState {
    data object Loading : LadderState
    data class Ready(val view: LadderView) : LadderState
    data class Failed(val message: String, val retry: Boolean) : LadderState
}

internal fun failureMessage(reason: ArtistLadderFailure, name: String): LadderState.Failed = when (reason) {
    ArtistLadderFailure.NoArtist -> LadderState.Failed("MusicBrainz knows no artist called \"$name\".", retry = false)
    ArtistLadderFailure.NoGenre ->
        LadderState.Failed("\"$name\" has no genre tags, and no similar artists could lend any.", retry = false)
    ArtistLadderFailure.NoOutward ->
        LadderState.Failed("Couldn't find a neighbouring genre to lead out from \"$name\".", retry = false)
    ArtistLadderFailure.Unreachable ->
        LadderState.Failed("Couldn't reach the music sources. Check the connection and try again.", retry = true)
}

private fun noteFor(ladder: Ladder, borrowedFrom: List<String>, artist: String?): String? {
    val parts = ArrayList<String>()
    if (borrowedFrom.isNotEmpty() && artist != null) {
        parts += "$artist has no genre tags, so their genres are borrowed from similar artists: ${borrowedFrom.joinToString(", ")}."
    }
    if (ladder.coldStart && !ladder.isEmpty) {
        parts += "Nothing in your Library to compare with yet, so the rungs follow how typical each artist is of the genre."
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
}

private fun emptyOr(view: LadderView, what: String): LadderState =
    if (view.ladder.isEmpty) LadderState.Failed("No artists found for $what.", retry = false) else LadderState.Ready(view)

/**
 * Builds the ladder for [request] from the person's saved [artists]. [candidates] answers a genre's artists (null when
 * unreachable); [resolver] finds artists by name. A genre nobody carries, or a ladder with no rungs, is a message, not
 * an empty screen.
 */
internal suspend fun loadLadder(
    request: LadderRequest,
    artists: List<LibraryArtist>,
    resolver: ArtistTargetResolver,
    sourceCandidates: suspend (genre: String) -> List<LadderCandidate>?,
    progress: LadderProgress = LadderProgress(),
): LadderState {
    // Phase 7: rungs the person has marked stay where they are, skipped artists never come back, and only the
    // remaining rungs are built, at the dial the feedback has moved.
    val excluded = progress.excluded()
    val candidates: suspend (String) -> List<LadderCandidate>? =
        { genre -> sourceCandidates(genre)?.filter { it.id !in excluded } }
    val dial = progress.dial
    val rungs = remainingRungs(progress)
    fun joined(fresh: Ladder): Ladder = mergeClimbed(progress, fresh)
    return when (request) {
    is LadderRequest.Genre -> {
        val pool = candidates(request.genre)
        if (pool == null) {
            failureMessage(ArtistLadderFailure.Unreachable, request.genre)
        } else {
            val ladder = joined(buildLadder(request.genre, artists, pool, dial, rungs))
            emptyOr(
                LadderView("Into ${ladder.target}", null, noteFor(ladder, emptyList(), null), ladder),
                "\"${request.genre}\"",
            )
        }
    }
    is LadderRequest.Artist -> {
        val outcome = when (request.mode) {
            LadderMode.Into -> ladderInto(resolver, candidates, request.name, artists, dial, rungs)
            LadderMode.OutFrom -> ladderOutFrom(resolver, candidates, request.name, artists, request.destination, dial, rungs)
        }
        when (outcome) {
            is ArtistLadderOutcome.Failed -> failureMessage(outcome.reason, request.name)
            is ArtistLadderOutcome.Ready -> {
                val name = outcome.target.artist.name
                val ladder = joined(outcome.ladder)
                val view = when (request.mode) {
                    LadderMode.Into -> LadderView(
                        title = "Into $name",
                        subtitle = "Climbing into ${outcome.target.genre}",
                        note = noteFor(ladder, outcome.target.borrowedFrom, name),
                        ladder = ladder,
                    )
                    LadderMode.OutFrom -> LadderView(
                        title = "Out from $name",
                        subtitle = "Leading out into ${outcome.destination ?: outcome.ladder.target}",
                        note = noteFor(ladder, outcome.target.borrowedFrom, name),
                        ladder = ladder,
                        alternatives = outcome.alternatives,
                    )
                }
                emptyOr(view, "\"$name\"")
            }
        }
    }
}
}
