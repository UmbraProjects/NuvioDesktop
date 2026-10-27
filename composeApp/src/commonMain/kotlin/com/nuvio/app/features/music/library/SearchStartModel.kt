package com.nuvio.app.features.music.library

/**
 * Docx item 1, "Using the blank space": what the Music search screen shows before a letter is typed, as pure data
 * (no Compose, so it is unit-tested). Three rows: ladder starters, continue-a-ladder cards and genre tiles. Recent
 * searches come straight from SearchHistoryRepository and need no model.
 */

/** A genre the library holds, with how many distinct artists carry it. */
internal data class GenreCount(val genre: String, val artists: Int)

/** Genres in the library by number of distinct artists (genres and tags both count), biggest first, then by name. */
internal fun genreCounts(artists: List<LibraryArtist>): List<GenreCount> {
    val names = LinkedHashMap<String, String>()
    val carriers = HashMap<String, MutableSet<String>>()
    for (artist in artists) {
        for (raw in artist.genres + artist.tags) {
            val name = canonicalGenre(raw) ?: continue
            val key = genreKey(name)
            names.getOrPut(key) { name }
            carriers.getOrPut(key) { HashSet() }.add(artist.id)
        }
    }
    return names.map { (key, name) -> GenreCount(name, carriers.getValue(key).size) }
        .sortedWith(compareByDescending<GenreCount> { it.artists }.thenBy { it.genre })
}

/** A card that starts a ladder into a genre the person tags but barely owns. */
internal data class LadderStarter(val genre: String, val artists: Int) {
    val request: LadderRequest get() = LadderRequest.Genre(genre)
    val title: String get() = "Into ${genre.replaceFirstChar { it.uppercase() }}"
    val detail: String get() = if (artists == 1) "1 artist in your library" else "$artists artists in your library"
}

/**
 * The thinnest genres first: the best places to grow. A genre held by a single artist is usually one stray tag, so
 * those come after every genre held by two or more, and only fill the row when there is little else.
 */
internal fun ladderStarters(artists: List<LibraryArtist>, limit: Int = 6): List<LadderStarter> =
    genreCounts(artists)
        .sortedWith(compareBy<GenreCount> { it.artists < 2 }.thenBy { it.artists }.thenBy { it.genre })
        .take(limit)
        .map { LadderStarter(it.genre, it.artists) }

/** A card that reopens a ladder with rungs already climbed. */
internal data class ContinueCard(val request: LadderRequest, val title: String, val climbed: Int, val total: Int) {
    val detail: String get() = "$climbed of $total rungs climbed"
}

internal fun requestTitle(request: LadderRequest): String {
    fun cap(text: String) = text.replaceFirstChar { it.uppercase() }
    return when (request) {
        is LadderRequest.Genre -> "Into ${cap(request.genre)}"
        is LadderRequest.Artist -> when (request.mode) {
            LadderMode.Into -> "Into ${request.name}"
            LadderMode.OutFrom -> "Out from ${request.name}"
        }
        is LadderRequest.Album -> when (request.mode) {
            LadderMode.Into -> "Into the sound of ${request.title}"
            LadderMode.OutFrom -> "Out from ${request.title}"
        }
    }
}

/** Saved ladders ([saved], most recent first) that have a request and at least one climbed rung. */
internal fun continueCards(saved: List<LadderProgress>, limit: Int = 6, total: Int = LADDER_RUNGS): List<ContinueCard> =
    saved.mapNotNull { progress ->
        val request = progress.request ?: return@mapNotNull null
        if (progress.climbed.isEmpty()) return@mapNotNull null
        ContinueCard(request, requestTitle(request), progress.climbed.size.coerceAtMost(total), total)
    }.distinctBy { progressKey(it.request) }.take(limit)

/** The genre tiles: the biggest genres in the library, each with its artist count. */
internal fun genreTiles(artists: List<LibraryArtist>, limit: Int = 8): List<GenreCount> =
    genreCounts(artists).take(limit)

/** Recent searches for the chips row: blanks dropped, repeats ignoring case dropped, capped at [limit]. */
internal fun recentChips(history: List<String>, limit: Int = 8): List<String> =
    history.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(limit)

/** True when the start screen would show nothing at all, so the plain hint is shown instead. */
internal fun startIsEmpty(recent: List<String>, starters: List<LadderStarter>, cont: List<ContinueCard>, tiles: List<GenreCount>) =
    recent.isEmpty() && starters.isEmpty() && cont.isEmpty() && tiles.isEmpty()
