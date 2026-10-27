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

    /**
     * Phase 10: a ladder into the sound of ([LadderMode.Into]) or out from ([LadderMode.OutFrom]) one record. [year] is
     * the search result's year, when it had one.
     */
    data class Album(
        val title: String,
        val artist: String,
        val year: String? = null,
        val mode: LadderMode = LadderMode.Into,
        val destination: String? = null,
    ) : LadderRequest
}

/** Out-from requests (artist or record) can lead out into more than one genre; the screen offers the others as chips. */
internal val LadderRequest.hasDestinations: Boolean
    get() = when (this) {
        is LadderRequest.Genre -> false
        is LadderRequest.Artist -> true
        is LadderRequest.Album -> true
    }

/** This request, leading out into [genre] instead; the request itself for one with no destinations. */
internal fun LadderRequest.withDestination(genre: String): LadderRequest = when (this) {
    is LadderRequest.Genre -> this
    is LadderRequest.Artist -> copy(destination = genre)
    is LadderRequest.Album -> copy(destination = genre)
}

/** The first album result, as the search card needs it: what it is called, who made it, and its year. */
internal data class LadderAlbumRef(val title: String, val artist: String, val year: String? = null)

internal data class LadderOffer(val label: String, val request: LadderRequest)

private fun String.capitalised(): String = replaceFirstChar { it.uppercase() }

/**
 * The buttons for the first result row. A query that is a known genre gives one ("Ladder into Shoegaze"). Otherwise,
 * when the first artist result is known, two ("Climb into X", "Climb out from X"). Nothing for a short or blank query.
 * Phase 10: on the Albums tab, [firstAlbum] (the first result) gives two for that record ("Climb into the sound of X",
 * "Climb out from X").
 */
internal fun ladderOffers(query: String, firstArtistName: String?, firstAlbum: LadderAlbumRef? = null): List<LadderOffer> {
    val text = query.trim()
    if (text.length < 2) return emptyList()
    if (isKnownGenre(text)) {
        val genre = canonicalGenre(text) ?: return emptyList()
        return listOf(LadderOffer("Ladder into ${genre.capitalised()}", LadderRequest.Genre(genre)))
    }
    val record = firstAlbum?.takeIf { it.title.isNotBlank() && it.artist.isNotBlank() }
    if (record != null) {
        val title = record.title.trim()
        val by = record.artist.trim()
        return listOf(
            LadderOffer("Climb into the sound of $title", LadderRequest.Album(title, by, record.year, LadderMode.Into)),
            LadderOffer("Climb out from $title", LadderRequest.Album(title, by, record.year, LadderMode.OutFrom)),
        )
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
    albumDetails: suspend (artist: String, title: String) -> AlbumDetails? = { _, _ -> null },
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
    is LadderRequest.Album -> {
        val outcome = ladderFromAlbum(
            AlbumTargetResolver(resolver, albumDetails), candidates, request.title, request.artist, request.year,
            request.mode, artists, request.destination, dial, rungs,
        )
        when (outcome) {
            is AlbumLadderOutcome.Failed -> failureMessage(outcome.reason, request.artist)
            is AlbumLadderOutcome.Ready -> {
                val album = outcome.album
                val ladder = joined(outcome.ladder)
                val view = when (request.mode) {
                    LadderMode.Into -> LadderView(
                        title = "Into ${album.title}",
                        subtitle = albumSubtitle(album, "Climbing into ${album.target.genre}"),
                        note = albumNote(album, noteFor(ladder, album.target.borrowedFrom, album.artistName)),
                        ladder = ladder,
                    )
                    LadderMode.OutFrom -> LadderView(
                        title = "Out from ${album.title}",
                        subtitle = albumSubtitle(album, "Leading out into ${outcome.destination ?: outcome.ladder.target}"),
                        note = albumNote(album, noteFor(ladder, album.target.borrowedFrom, album.artistName)),
                        ladder = ladder,
                        alternatives = outcome.alternatives,
                    )
                }
                emptyOr(view, "\"${album.title}\"")
            }
        }
    }
}
}

/** "Climbing into dream pop · Slowdive, 1993": what the ladder does, and the record it comes from. */
private fun albumSubtitle(album: AlbumTarget, action: String): String {
    val by = album.year?.let { "${album.artistName}, $it" } ?: album.artistName
    return "$action · $by"
}

/** The note for a record: how it was described, then whatever the plain ladder note says. */
private fun albumNote(album: AlbumTarget, base: String?): String? {
    val describedBy = when {
        album.described -> null
        album.year != null -> "No details found for this record, so it follows ${album.artistName}'s genres and its year (${album.year})."
        else -> "No details found for this record, so it follows ${album.artistName}'s genres."
    }
    return listOfNotNull(describedBy, base).takeIf { it.isNotEmpty() }?.joinToString(" ")
}
