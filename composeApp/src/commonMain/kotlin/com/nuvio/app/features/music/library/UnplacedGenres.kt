package com.nuvio.app.features.music.library

/**
 * Constellation: artists no source has genres for, as pure data (no Compose, so it is unit-tested).
 *
 *  - Backoff: a completed look-up that finds no genres or tags is remembered ([LibraryArtist.tagMisses],
 *    [LibraryArtist.tagsCheckedAtEpochMs]) and the tray stops offering a retry for 3, then 7, then 14 days. A look-up
 *    that never reached MusicBrainz (an outage) is not a miss.
 *  - Choice: the person can give an artist genres by hand ([parseGenreInput], stored in [LibraryArtist.manualGenres]).
 *    [genreChoices] lists the genres already on the map to pick from.
 *  - Suggestion: [suggestGenres] guesses from what the library already knows (the artist's releases, the ladder it was
 *    found through, artists it shares a release title with). It only suggests; nothing is applied without a tap.
 */

private const val DAY_MS = 24L * 60 * 60 * 1000

/** Days to wait after the first, second and every later empty look-up. */
internal val TAG_RETRY_DAYS: List<Long> = listOf(3L, 7L, 14L)

/** How long a retry is withheld after [misses] empty look-ups: 0 for none, then 3, 7 and 14 days (14 from then on). */
internal fun tagRetryDelayMs(misses: Int): Long =
    if (misses <= 0) 0L else TAG_RETRY_DAYS[(misses - 1).coerceAtMost(TAG_RETRY_DAYS.lastIndex)] * DAY_MS

/** True when the look-up itself found genres, tags, style or mood (the person's own choice does not count here). */
internal fun LibraryArtist.hasLookedUpGenres(): Boolean = tagProfile(genres, tags, mood, style).isNotEmpty()

/** Milliseconds until a retry is offered again; 0 when it can be tried now. Never more than one full delay, so a clock set back cannot lock it. */
internal fun LibraryArtist.retryTagsInMs(nowEpochMs: Long): Long {
    if (tagMisses <= 0 || hasLookedUpGenres()) return 0L
    val delay = tagRetryDelayMs(tagMisses)
    return (tagsCheckedAtEpochMs + delay - nowEpochMs).coerceIn(0L, delay)
}

internal fun LibraryArtist.canRetryTags(nowEpochMs: Long): Boolean = retryTagsInMs(nowEpochMs) == 0L

/** A look-up that reached MusicBrainz (or proved a namesake) says something real about the artist; one that did not may only be an outage. */
internal fun LibraryArtist.lookupWasReliable(): Boolean = channelMismatch || "MusicBrainz" in sources

/**
 * The artist after a look-up has finished: a miss is recorded when it found nothing and was [reliable] (the fresh look-up's
 * own [lookupWasReliable], not the stored artist's merged sources), and not again while an earlier miss is still being
 * waited out; finding something clears the record. Anything else leaves it as it was.
 */
internal fun LibraryArtist.withTagCheck(nowEpochMs: Long, reliable: Boolean): LibraryArtist = when {
    hasLookedUpGenres() ->
        if (tagMisses == 0 && tagsCheckedAtEpochMs == 0L) this else copy(tagMisses = 0, tagsCheckedAtEpochMs = 0L)
    !reliable -> this
    tagMisses > 0 && !canRetryTags(nowEpochMs) -> this
    else -> copy(tagMisses = tagMisses + 1, tagsCheckedAtEpochMs = nowEpochMs)
}

/** "3 days", "a day", "5 hours": how long until the retry comes back, for the picker's note. */
internal fun retryWaitText(ms: Long): String {
    val hours = (ms + 3_600_000L - 1) / 3_600_000L
    val days = (ms + DAY_MS - 1) / DAY_MS
    return when {
        hours <= 1L -> "an hour"
        hours < 24L -> "$hours hours"
        days == 1L -> "a day"
        else -> "$days days"
    }
}

private const val MAX_MANUAL_GENRES = 3

/** Genres typed or picked by the person: comma or semicolon separated, cleaned to the library's spelling, at most three. */
internal fun parseGenreInput(text: String): List<String> =
    text.split(',', ';').mapNotNull { canonicalGenre(it.trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null) }
        .distinctBy(::genreKey)
        .take(MAX_MANUAL_GENRES)

/** [current] with [genre] added (the person's pick leads); a genre already there moves to the front. */
internal fun withManualGenre(current: List<String>, genre: String): List<String> {
    val name = canonicalGenre(genre) ?: return current
    return (listOf(name) + current).distinctBy(::genreKey).take(MAX_MANUAL_GENRES)
}

/** [current] with the genres typed in [text] added in front (see [parseGenreInput]); unchanged when the text names none. */
internal fun withManualGenres(current: List<String>, text: String): List<String> =
    parseGenreInput(text).asReversed().fold(current) { acc, genre -> withManualGenre(acc, genre) }

/** The genres already on the map, biggest first: what the picker offers. Spelt as the Constellation's chips spell them. */
internal fun genreChoices(artists: List<LibraryArtist>, limit: Int = 12): List<String> =
    genreCounts(artists.map { it.copy(genres = it.manualGenres + it.genres) }).take(limit).map { it.genre }

/** One guess at an artist's genre and the plain reason for it. */
internal data class GenreSuggestion(val genre: String, val reason: String, val score: Double)

private const val RELEASE_WEIGHT = 1.0
private const val LADDER_GENRE_WEIGHT = 4.0
private const val LADDER_SOURCE_WEIGHT = 2.5
private const val COLLAB_WEIGHT = 1.5
private const val NEIGHBOUR_GENRES = 2
private const val MIN_NAME_LENGTH = 3

/** The first couple of genres a library artist is known for, in the library's spelling. */
private fun LibraryArtist.topGenres(): List<String> =
    normalizeGenres(manualGenres + genres + tags).filter(::plausibleGenre).take(NEIGHBOUR_GENRES)

/** True when [title] names [name] as a whole word or phrase ("Song (feat. Sting)"), not as part of a longer word. */
internal fun titleMentions(title: String, name: String): Boolean {
    val needle = name.trim().lowercase()
    if (needle.length < MIN_NAME_LENGTH) return false
    val hay = title.lowercase()
    var from = 0
    while (true) {
        val at = hay.indexOf(needle, from)
        if (at < 0) return false
        val before = hay.getOrNull(at - 1)
        val after = hay.getOrNull(at + needle.length)
        if ((before == null || !before.isLetterOrDigit()) && (after == null || !after.isLetterOrDigit())) return true
        from = at + 1
    }
}

private class Guess(val genre: String) {
    var score = 0.0
    var reason = ""
    var best = 0.0
    fun add(weight: Double, why: String) {
        score += weight
        if (weight > best) {
            best = weight
            reason = why
        }
    }
}

/**
 * Up to [limit] genres worth offering for [artist], best first, from what [library] already knows:
 *  - the genres Deezer lists on the artist's own releases (a point per release);
 *  - the ladder it was found through: "Into shoegaze" names the genre, "Out from Sting" lends Sting's genres;
 *  - artists whose name appears in one of its release titles, or whose releases name it (a feature or collaboration).
 * An artist that already has a look-up profile gets nothing: it is on the map and needs no guess.
 */
internal fun suggestGenres(artist: LibraryArtist, library: List<LibraryArtist>, limit: Int = 3): List<GenreSuggestion> {
    if (artist.hasLookedUpGenres()) return emptyList()
    val guesses = LinkedHashMap<String, Guess>()
    fun add(raw: String, weight: Double, why: String) {
        val name = canonicalGenre(raw) ?: return
        if (!plausibleGenre(name)) return
        guesses.getOrPut(genreKey(name)) { Guess(name) }.add(weight, why)
    }

    val releaseVotes = LinkedHashMap<String, Int>()
    for (release in artist.releases) {
        for (g in normalizeGenres(release.genres)) releaseVotes.merge(g, 1, Int::plus)
    }
    for ((genre, votes) in releaseVotes) {
        add(genre, RELEASE_WEIGHT * votes, if (votes == 1) "Listed on one of its releases" else "Listed on $votes of its releases")
    }

    val via = foundViaText(artist.foundVia)
    if (via != null && via.startsWith("Into ")) {
        val genre = via.removePrefix("Into ").trim()
        add(genre, LADDER_GENRE_WEIGHT, "You found it through the Into $genre ladder")
    }
    foundViaSource(artist.foundVia)?.let { sourceName ->
        library.firstOrNull { it.id != artist.id && sameArtistName(it.name, sourceName) }?.let { source ->
            for (g in source.topGenres()) add(g, LADDER_SOURCE_WEIGHT, "Found from ${source.name}")
        }
    }

    for (other in library) {
        if (other.id == artist.id) continue
        val together = artist.releases.any { titleMentions(it.title, other.name) } ||
            other.releases.any { titleMentions(it.title, artist.name) }
        if (!together) continue
        for (g in other.topGenres()) add(g, COLLAB_WEIGHT, "Appears with ${other.name}")
    }

    return guesses.values
        .sortedWith(compareByDescending<Guess> { it.score }.thenBy { it.genre })
        .take(limit)
        .map { GenreSuggestion(it.genre, it.reason, it.score) }
}
