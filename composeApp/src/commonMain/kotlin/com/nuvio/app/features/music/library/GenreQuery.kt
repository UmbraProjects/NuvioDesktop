package com.nuvio.app.features.music.library

/*
 * fork:music-genre - telling a typed search line apart from a genre, a mood, a decade or an activity, so the music
 * home can show a genre card ("shoegaze", "90s rock", "chill", "workout music") next to the usual results. Pure, so it
 * is unit-tested. The hand lists answer instantly; a tag the lists do not know is checked against the offline
 * MusicBrainz copy's tag votes by the caller (see [detectGenreQuery]'s `offlineKnown`).
 */

/** What kind of thing a typed line names. */
internal enum class TagKind(val noun: String) {
    Genre("Genre"),
    Mood("Mood"),
    Decade("Decade"),
    Activity("Activity"),
}

/**
 * A search line read as a tag. [label] is how the card names it, [tag] is what the tag searches use (a genre, or the
 * genre a mood or activity leans on), [phrase] is what to search YouTube Music for to find its radio and playlists,
 * and [decade] is the first year of a decade ("90s rock" gives 1990) when the line asked for one.
 */
internal data class GenreQuery(
    val label: String,
    val tag: String,
    val kind: TagKind,
    val phrase: String,
    val decade: Int? = null,
)

/** Words around a genre that add nothing: "jazz music", "the best jazz songs". Dropped before matching. */
private val FILLER = setOf(
    "music", "songs", "song", "tunes", "radio", "playlist", "playlists", "mix", "mixes", "genre", "genres",
    "vibes", "vibe", "sound", "sounds", "best", "top", "some", "good", "the", "of", "for", "a",
)

/** Moods and the genre their tag search leans on. Looked up by whole word. */
private val MOODS: Map<String, String> = mapOf(
    "chill" to "chillout", "chilled" to "chillout", "relaxing" to "chillout", "relaxed" to "chillout",
    "calm" to "ambient", "peaceful" to "ambient", "mellow" to "chillout", "dreamy" to "dream pop",
    "sad" to "melancholic", "melancholy" to "melancholic", "melancholic" to "melancholic", "moody" to "dark",
    "happy" to "pop", "upbeat" to "pop", "cheerful" to "pop", "uplifting" to "pop", "feelgood" to "pop",
    "energetic" to "dance", "hype" to "dance", "angry" to "hardcore", "aggressive" to "metal",
    "dark" to "darkwave", "romantic" to "soul", "sexy" to "r&b", "nostalgic" to "oldies", "epic" to "soundtrack",
    "atmospheric" to "ambient", "groovy" to "funk", "sleepy" to "ambient", "cinematic" to "soundtrack",
)

/** Activities, with the genre that fits them. Several words ("road trip", "rainy day") are matched as a phrase. */
private val ACTIVITIES: Map<String, String> = mapOf(
    "workout" to "dance", "gym" to "hip-hop", "running" to "dance", "cardio" to "dance",
    "study" to "ambient", "studying" to "ambient", "focus" to "ambient", "concentration" to "ambient",
    "sleep" to "ambient", "sleeping" to "ambient", "meditation" to "ambient", "yoga" to "ambient",
    "party" to "dance", "dance floor" to "dance", "driving" to "rock", "road trip" to "rock",
    "cooking" to "jazz", "dinner" to "jazz", "dinner party" to "jazz", "reading" to "ambient",
    "cleaning" to "pop", "morning" to "folk", "rainy day" to "jazz", "summer" to "reggae",
    "barbecue" to "soul", "bbq" to "soul", "work" to "ambient",
)

private val SPELLED_DECADES = mapOf(
    "fifties" to 1950, "sixties" to 1960, "seventies" to 1970, "eighties" to 1980, "nineties" to 1990,
    "noughties" to 2000, "aughts" to 2000, "twenty tens" to 2010,
)

private val DECADE_WORD = Regex("^(?:'|’)?(\\d{2}|\\d{4})s$")

/** The first year of a decade written as "90s", "1990s", "'80s", "00s" or spelled out; null for anything else. */
internal fun decadeStart(word: String): Int? {
    val w = word.trim().lowercase()
    SPELLED_DECADES[w]?.let { return it }
    val digits = DECADE_WORD.matchEntire(w)?.groupValues?.get(1) ?: return null
    val year = when (digits.length) {
        4 -> digits.toInt()
        else -> digits.toInt().let { if (it >= 50) 1900 + it else 2000 + it }
    }
    return if (year % 10 == 0 && year in 1950..2020) year else null
}

private fun decadeLabel(start: Int): String = if (start >= 2000) "${start}s" else "${start % 100}s"

/** [raw] as lower-case words with filler and punctuation gone. */
private fun contentWords(raw: String): List<String> =
    raw.lowercase().replace('_', ' ').split(Regex("[^\\p{L}\\p{N}&'’\\-]+"))
        .map { it.trim('-', '\'', '’') }
        .filter { it.isNotEmpty() && it !in FILLER }

private val sortedKnown: Map<String, String> by lazy {
    buildMap {
        for (name in knownGenreNames()) {
            val parts = name.lowercase().split(' ', '-').filter { it.isNotEmpty() }
            if (parts.size > 1) put(parts.sorted().joinToString(" "), name)
        }
    }
}

/** A known genre for these words: spelling, one trailing plural "s", and word order ("rock indie") all forgiven. */
private fun knownFromWords(words: List<String>): String? {
    if (words.isEmpty()) return null
    val joined = words.joinToString(" ")
    knownGenreName(joined)?.let { return it }
    // Plurals: "shoegazers" is not a genre, but "metals", "rocks", "blues" (already a name), "punks" are.
    if (joined.endsWith("s") && joined.length > 3) knownGenreName(joined.dropLast(1))?.let { return it }
    if (joined.endsWith("es") && joined.length > 4) knownGenreName(joined.dropLast(2))?.let { return it }
    if (words.size > 1) {
        val unplural = words.map { if (it.endsWith("s") && it.length > 3 && knownGenreName(it) == null) it.dropLast(1) else it }
        val parts = unplural.flatMap { it.split('-') }.sorted().joinToString(" ")
        sortedKnown[parts]?.let { return it }
    }
    return null
}

private fun title(name: String): String =
    name.split(' ').joinToString(" ") { w -> if (w.contains('-')) w.split('-').joinToString("-") { it.cap() } else w.cap() }.replace(" And ", " and ")

private val SHOUTED = setOf("r&b", "idm", "edm", "ebm", "mpb", "uk", "dnb")

private fun String.cap(): String = if (lowercase() in SHOUTED) uppercase() else replaceFirstChar { it.uppercase() }

/**
 * Reads [raw] as a genre, mood, decade or activity, or null when it is just a name. "Shoegaze", "post punk", "rock
 * indie", "hip hop music", "chill", "workout music", "90s", "90s rock" are all read; "Radiohead" is not.
 *
 * [offlineKnown] is asked about the single tag a line is made of when none of the lists know it; the screen answers
 * it from the offline MusicBrainz copy (enough artists carry the tag with real votes). It is only asked for a line of
 * one to three words, so a long sentence is never mistaken for a tag.
 */
internal fun detectGenreQuery(raw: String, offlineKnown: (String) -> Boolean = { false }): GenreQuery? {
    val words = contentWords(raw)
    if (words.isEmpty() || words.size > 4) return null

    // A decade, alone or in front of a genre: "90s", "the 80s", "90s rock".
    val decade = decadeStart(words.first())
    if (decade != null) {
        val rest = words.drop(1)
        if (rest.isEmpty()) return GenreQuery(decadeLabel(decade), tag = "", kind = TagKind.Decade, phrase = "${decadeLabel(decade)} hits", decade = decade)
        val genre = knownFromWords(rest) ?: return null
        return GenreQuery("${decadeLabel(decade)} ${title(genre)}", genre, TagKind.Genre, "${decadeLabel(decade)} $genre", decade)
    }

    // A mood or an activity, as the whole line (several words matched as a phrase first).
    val phrase = words.joinToString(" ")
    ACTIVITIES[phrase]?.let { return GenreQuery(title(phrase), it, TagKind.Activity, "$phrase music") }
    MOODS[phrase]?.let { return GenreQuery(title(phrase), it, TagKind.Mood, "$phrase music") }

    knownFromWords(words)?.let { return GenreQuery(title(it), it, TagKind.Genre, "$it music") }

    // A tag the lists do not know: the offline copy's votes decide.
    if (words.size <= 3) {
        val tag = canonicalGenre(phrase)
        if (tag != null && !isNonGenreTag(phrase) && offlineKnown(tag)) return GenreQuery(title(tag), tag, TagKind.Genre, "$tag music")
    }
    return null
}

/**
 * Whether the offline copy's answer for a tag is a real genre rather than a one-off: a handful of artists, and the
 * tag is the top vote for enough of them. Pure on the candidates so it can be tested.
 */
internal fun looksLikeRealTag(tag: String, candidates: List<LadderCandidate>): Boolean {
    val key = genreKey(tag)
    if (key.isEmpty() || candidates.size < MIN_GENRE_ARTISTS) return false
    val strong = candidates.count { c ->
        val own = c.votes.entries.firstOrNull { genreKey(it.key) == key }?.value ?: 0
        own >= MIN_GENRE_VOTES
    }
    return strong >= MIN_GENRE_ARTISTS
}

private const val MIN_GENRE_ARTISTS = 8
private const val MIN_GENRE_VOTES = 2

/** The playlist to play for a genre card: its own radio first, then a playlist named for it, then any radio. Null when none. */
internal fun pickGenreStation(playlists: List<com.nuvio.app.features.music.MusicPlaylistSummary>, genre: GenreQuery): com.nuvio.app.features.music.MusicPlaylistSummary? {
    val words = genreKey(genre.label).let { listOf(it) } + contentWords(genre.phrase).map(::genreKey)
    fun named(p: com.nuvio.app.features.music.MusicPlaylistSummary): Boolean {
        val key = genreKey(p.title)
        return words.any { it.isNotEmpty() && key.contains(it) }
    }
    return playlists.firstOrNull { it.radio && named(it) }
        ?: playlists.firstOrNull { named(it) }
        ?: playlists.firstOrNull { it.radio }
}

/** The library artists whose genres or tags include [tag]; none for a decade (it has no tag). */
internal fun libraryArtistsFor(tag: String, artists: List<LibraryArtist>): List<LibraryArtist> {
    if (tag.isBlank()) return emptyList()
    return artists.filter { a -> (a.manualGenres + a.genres + a.tags).any { sameGenre(it, tag) } }
}

/**
 * The tag-ranked artists for a genre card: those carrying [tag] with the most votes first, then by fans. Artists the
 * person owns (by [ownedNames], folded) come back in [GenreArtist.owned] so the card can mark them.
 */
internal data class GenreArtist(val name: String, val owned: Boolean)

internal fun rankTagArtists(tag: String, candidates: List<LadderCandidate>, ownedNames: Set<String>, limit: Int = 12): List<GenreArtist> {
    val key = genreKey(tag)
    return candidates
        .sortedWith(
            compareByDescending<LadderCandidate> { c -> c.votes.entries.filter { genreKey(it.key) == key }.sumOf { it.value } }
                .thenByDescending { it.fans ?: 0L }.thenBy { it.name },
        )
        .distinctBy { it.name.lowercase() }
        .take(limit)
        .map { GenreArtist(it.name, it.name.lowercase().trim() in ownedNames) }
}
