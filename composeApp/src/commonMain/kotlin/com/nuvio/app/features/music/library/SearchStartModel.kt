package com.nuvio.app.features.music.library

/**
 * Docx item 1, "Using the blank space": what the Music search screen shows before a letter is typed, as pure data
 * (no Compose, so it is unit-tested). Three rows: ladder starters, continue-a-ladder cards and genre tiles. Recent
 * searches come straight from MusicSearchHistory and need no model.
 */

/** A genre the library holds, with how many distinct artists carry it and who they are (first seen first). */
internal data class GenreCount(val genre: String, val artists: Int, val artistIds: List<String> = emptyList())

/** Words that may be short inside a genre name ("uk garage", "lo-fi", "nu jazz", "rock and roll"). */
private val shortGenreWords = setOf("uk", "us", "nu", "lo", "fi", "hi", "r", "b", "and", "of", "or", "in", "la", "de", "j", "k", "dj", "3d", "8")

/**
 * False for an unknown tag that is probably noise, not a genre worth a card: a stray fragment such as "aln sh", a word
 * of one or two letters that is not a known short word, or a tag that is mostly digits or symbols. Known genres always pass.
 */
internal fun plausibleGenre(name: String): Boolean {
    if (isKnownGenre(name)) return true
    val tokens = name.lowercase().split(' ', '-', '/', '&').filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return false
    if (name.count { it.isLetter() } < 4) return false
    if (tokens.any { it.length < 3 && it !in shortGenreWords && !it.all(Char::isDigit) }) return false
    return name.all { it.isLetterOrDigit() || it in " -/&'.+" }
}

private val upperGenres = setOf("idm", "edm", "ebm", "uk", "us", "mpb", "dj", "r&b", "k-pop", "j-pop")
private val smallWords = setOf("and", "of", "the", "for", "in", "on")

/** A genre as a title: "acid jazz" is "Acid Jazz", "hip-hop" is "Hip-Hop", "r&b" is "R&B", "drum and bass" is "Drum and Bass". */
internal fun displayGenre(genre: String): String {
    val lower = genre.trim().lowercase()
    if (lower in upperGenres) return lower.uppercase()
    return lower.split(' ').filter { it.isNotEmpty() }.mapIndexed { i, word ->
        when {
            word in upperGenres -> word.uppercase()
            i > 0 && word in smallWords -> word
            else -> word.split('-').joinToString("-") { part ->
                if (part in upperGenres) part.uppercase() else part.replaceFirstChar { it.uppercase() }
            }
        }
    }.joinToString(" ")
}

/** Genres in the library by number of distinct artists (genres and tags both count), biggest first, then by name. */
internal fun genreCounts(artists: List<LibraryArtist>): List<GenreCount> {
    val names = LinkedHashMap<String, String>()
    val carriers = HashMap<String, MutableSet<String>>()
    for (artist in artists) {
        for (raw in artist.genres + artist.tags) {
            val name = canonicalGenre(raw) ?: continue
            if (!plausibleGenre(name)) continue
            val key = genreKey(name)
            names.getOrPut(key) { name }
            carriers.getOrPut(key) { LinkedHashSet() }.add(artist.id)
        }
    }
    return names.map { (key, name) -> GenreCount(name, carriers.getValue(key).size, carriers.getValue(key).toList()) }
        .sortedWith(compareByDescending<GenreCount> { it.artists }.thenBy { it.genre })
}

/** A card that starts a ladder into a genre the person tags but barely owns. */
internal data class LadderStarter(
    val genre: String,
    val artists: Int,
    /** The biggest other genres these artists also carry: where this genre sits next to the person's taste. */
    val neighbours: List<String> = emptyList(),
    val artistIds: List<String> = emptyList(),
) {
    val request: LadderRequest get() = LadderRequest.Genre(genre)
    val label: String get() = displayGenre(genre)
    val title: String get() = "Into ${displayGenre(genre)}"
    val detail: String get() = if (artists == 1) "1 artist in your library" else "$artists artists in your library"
}

/**
 * The thinnest genres first: the best places to grow. A genre held by a single artist is usually one stray tag, so
 * those come after every genre held by two or more, and only fill the row when there is little else.
 */
internal fun ladderStarters(artists: List<LibraryArtist>, limit: Int = 6): List<LadderStarter> {
    val counts = genreCounts(artists)
    val sizeByKey = counts.associate { genreKey(it.genre) to it.artists }
    val genresOf = artists.associate { a ->
        a.id to (a.genres + a.tags).mapNotNull(::canonicalGenre).filter(::plausibleGenre).distinctBy(::genreKey)
    }
    fun neighboursOf(count: GenreCount): List<Pair<String, Int>> =
        count.artistIds.flatMap { genresOf[it].orEmpty() }
            .filter { genreKey(it) != genreKey(count.genre) }
            .distinctBy(::genreKey)
            .map { it to (sizeByKey[genreKey(it)] ?: 0) }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
    val ranked = counts
        // A tag nobody else carries and no list knows is usually noise, not a genre to climb into.
        .filter { isKnownGenre(it.genre) || it.artists >= 2 }
        .map { it to neighboursOf(it) }
        .sortedWith(
            compareBy<Pair<GenreCount, List<Pair<String, Int>>>> { it.first.artists < 2 }
                .thenBy { it.first.artists }
                // Among equally thin genres, the ones that sit next to what the person owns most come first.
                .thenByDescending { (_, n) -> n.sumOf { it.second } }
                .thenBy { it.first.genre },
        )
    // Cards should not repeat each other: a starter must bring an artist no earlier card shows (one big name tagged
    // country and teen pop would otherwise fill two cards), and "near" genres already named are used last.
    val shownArtists = HashSet<String>()
    val shownNear = HashSet<String>()
    val shownPairs = HashSet<List<String>>()
    val picked = ArrayList<LadderStarter>()
    val deferred = ArrayList<Pair<GenreCount, List<Pair<String, Int>>>>()
    fun take(c: GenreCount, n: List<Pair<String, Int>>) {
        // The usual two neighbours, unless an earlier card already says exactly that: then the ones not yet named lead.
        val usual = n.take(2).map { displayGenre(it.first) }
        val near = if (usual in shownPairs) {
            (n.filter { genreKey(it.first) !in shownNear } + n.filter { genreKey(it.first) in shownNear }).take(2).map { displayGenre(it.first) }
        } else usual
        shownPairs += usual
        val ids = c.artistIds.filter { it !in shownArtists } + c.artistIds.filter { it in shownArtists }
        shownArtists += c.artistIds.take(2)
        near.forEach { shownNear += genreKey(it) }
        picked += LadderStarter(c.genre, c.artists, near, ids)
    }
    for ((c, n) in ranked) {
        if (picked.size >= limit) break
        if (c.artistIds.all { it in shownArtists } && picked.isNotEmpty()) deferred += c to n else take(c, n)
    }
    for ((c, n) in deferred) { if (picked.size >= limit) break; take(c, n) }
    return picked
}

/** A card that reopens a ladder with rungs already climbed. */
internal data class ContinueCard(
    val request: LadderRequest,
    val title: String,
    val climbed: Int,
    val total: Int,
    /** The artists on the climbed rungs, in order: their pictures make the card and their names are its detail. */
    val names: List<String> = emptyList(),
) {
    val detail: String get() = "$climbed of $total rungs climbed"
}

internal fun requestTitle(request: LadderRequest): String {
    fun cap(text: String) = text.replaceFirstChar { it.uppercase() }
    return when (request) {
        is LadderRequest.Genre -> "Into ${displayGenre(request.genre)}"
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
        ContinueCard(request, requestTitle(request), progress.climbed.size.coerceAtMost(total), total, progress.climbed.map { it.name })
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


/** What a library artist lends a start card: its name, a picture, and the colour its artwork gave it. */
internal data class StartArt(val name: String, val picture: String?, val tint: Long?)

/** The best picture the library already holds for [artist]: portrait, else a release cover, else a backdrop. */
internal fun artistPicture(artist: LibraryArtist): String? =
    artist.art.thumb
        ?: artist.releases.firstNotNullOfOrNull { it.art.covers.firstOrNull() }
        ?: artist.art.bestBackground

/** [StartArt] by artist id, so cards look pictures and colours up without touching the network. */
internal fun startArt(artists: List<LibraryArtist>): Map<String, StartArt> =
    artists.associate { it.id to StartArt(it.name, artistPicture(it), it.palette?.accent) }
