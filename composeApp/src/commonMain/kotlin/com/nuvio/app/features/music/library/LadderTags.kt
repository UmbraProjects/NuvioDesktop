package com.nuvio.app.features.music.library

/**
 * Ladder phase 0: the tag vocabulary. Pure helpers, no Compose and no network, so they are unit-tested.
 *
 * The ladder compares artists by their genre tags. MusicBrainz tags are a user free-for-all: the same
 * genre arrives as "post-punk", "post punk" and "postpunk", next to junk like "seen live", "90s" or
 * "british" that says nothing about sound. Everything the ladder scores goes through here first, so
 * two artists are never judged different because of spelling, or alike because of a shared junk tag.
 *
 * Identity is [genreKey]; the readable name is [canonicalGenre]. Compare by key, show the name.
 */

/**
 * The comparison key for a tag: lower case, "&" read as "and", apostrophes dropped, and everything that
 * is not a letter or digit removed. "Drum & Bass", "drum-and-bass" and "drumandbass" all give `drumandbass`.
 */
internal fun genreKey(raw: String): String {
    val sb = StringBuilder(raw.length)
    for (ch in raw.lowercase()) {
        when {
            ch == '&' -> sb.append("and")
            ch == '\'' || ch == '’' || ch == '`' -> Unit
            ch.isLetterOrDigit() -> sb.append(ch)
        }
    }
    return sb.toString()
}

/** Tags that name a descriptor, a listener habit or a person's opinion, not a sound. */
private val nonGenreWords = setOf(
    "seen live", "favorite", "favorites", "favourite", "favourites", "favorite songs", "favorite artists",
    "favourite artists", "love", "loved", "love it", "albums i own", "own it", "owned", "i own", "my music",
    "spotify", "lastfm", "last fm", "under 2000 listeners", "check out", "to listen", "listen later", "all",
    "awesome", "best", "good", "great", "amazing", "cool", "male vocalists", "male vocalist",
    "female vocalists", "female vocalist", "vocalists", "singer", "band", "bands", "music", "artist",
    "artists", "duo", "trio", "solo", "live", "cover", "covers", "remix", "remixes", "unknown", "other",
    "misc", "seen", "heard",
    // Decades spelled out.
    "sixties", "seventies", "eighties", "nineties",
    // Places and nationalities: the artist's area is stored on its own, and it is not a sound.
    "british", "english", "american", "usa", "us", "uk", "canadian", "swedish", "german", "french", "japanese",
    "korean", "irish", "scottish", "welsh", "australian", "brazilian", "italian", "spanish", "dutch",
    "norwegian", "danish", "finnish", "icelandic", "russian", "polish", "mexican", "argentinian", "chilean",
    "jamaican", "nigerian", "south african", "indian", "chinese", "belgian", "swiss", "austrian",
)

private val nonGenreKeys: Set<String> = nonGenreWords.mapTo(HashSet()) { genreKey(it) }

/** "90s", "1990s", "00s", "'80s", or a bare year: an era, which the ladder reads from release dates instead. */
private val eraTag = Regex("^(?:\\d{2}|\\d{4})s$|^(?:19|20)\\d{2}$")

private const val MIN_KEY_LENGTH = 2
private const val MAX_RAW_LENGTH = 40

/** True when [raw] is not a genre the ladder can compare: blank, too long, an era, a place or an opinion. */
internal fun isNonGenreTag(raw: String): Boolean {
    val text = raw.trim()
    if (text.isEmpty() || text.length > MAX_RAW_LENGTH) return true
    val key = genreKey(text)
    if (key.length < MIN_KEY_LENGTH) return true
    if (key.all { it.isDigit() }) return true
    if (eraTag.matches(text.lowercase().replace("'", "").replace("’", "").replace(" ", ""))) return true
    return key in nonGenreKeys
}

/** Genres that get a fixed readable name, whatever spelling a source used. */
private val knownGenres = listOf(
    "rock", "pop", "hip-hop", "r&b", "soul", "neo-soul", "funk", "jazz", "blues", "country", "folk", "classical",
    "electronic", "house", "techno", "trance", "ambient", "drum and bass", "jungle", "breakbeat", "dubstep",
    "garage", "uk garage", "grime", "trip-hop", "downtempo", "idm", "synth-pop", "synthwave", "chillwave",
    "vaporwave", "new wave", "post-punk", "punk", "hardcore", "post-hardcore", "emo", "indie", "indie rock",
    "indie pop", "alternative rock", "dream pop", "shoegaze", "noise rock", "post-rock", "math rock",
    "krautrock", "psychedelic rock", "progressive rock", "art rock", "glam rock", "hard rock", "metal",
    "heavy metal", "thrash metal", "death metal", "black metal", "doom metal", "metalcore", "grunge",
    "britpop", "reggae", "dub", "ska", "afrobeat", "highlife", "bossa nova", "latin", "salsa", "k-pop",
    "j-pop", "city pop", "disco", "lo-fi", "singer-songwriter", "folk rock", "americana", "bluegrass",
    "gospel", "opera", "minimalism", "industrial", "ebm", "darkwave", "goth rock", "trap", "drill", "dance",
    "edm", "experimental", "avant-garde", "free jazz", "bebop", "rock and roll", "electropop", "electro",
    "alternative", "world", "easy listening", "new age", "noise", "drone", "stoner rock", "garage rock",
    "surf rock", "power pop", "pop punk", "ska punk", "folk punk", "baroque pop", "chamber pop", "slowcore",
    "sadcore", "post-metal", "sludge metal", "grindcore", "synthpunk", "no wave", "acid jazz", "smooth jazz",
    "nu jazz", "jazz fusion", "afro-pop", "dancehall", "soca", "cumbia", "reggaeton", "flamenco", "fado",
    "tropicalia", "samba", "mpb",
)

/** Spellings and shorthand that mean a known genre but do not match its key. Key on the left. */
private val aliases = mapOf(
    "rnb" to "r&b",
    "rhythmandblues" to "r&b",
    "rap" to "hip-hop",
    "dnb" to "drum and bass",
    "drumnbass" to "drum and bass",
    "drumbass" to "drum and bass",
    "altrock" to "alternative rock",
    "altcountry" to "americana",
    "electronica" to "electronic",
    "psychedelic" to "psychedelic rock",
    "psych" to "psychedelic rock",
    "psychrock" to "psychedelic rock",
    "prog" to "progressive rock",
    "progrock" to "progressive rock",
    "progressive" to "progressive rock",
    "goth" to "goth rock",
    "gothic" to "goth rock",
    "gothicrock" to "goth rock",
    "rocknroll" to "rock and roll",
    "lofi" to "lo-fi",
    "lofihiphop" to "lo-fi",
    "singersongwriter" to "singer-songwriter",
    "triphop" to "trip-hop",
    "synthpop" to "synth-pop",
    "postpunk" to "post-punk",
    "postrock" to "post-rock",
    "posthardcore" to "post-hardcore",
    "postmetal" to "post-metal",
    "hiphoprap" to "hip-hop",
)

private val knownByKey: Map<String, String> = buildMap {
    for (name in knownGenres) put(genreKey(name), name)
    for ((key, name) in aliases) put(key, name)
}

/**
 * The readable name the ladder shows and stores for [raw], or null when the tag is not a genre.
 * A known genre gets its fixed name ("Postpunk" gives "post-punk"). An unknown one is kept, lower case
 * with hyphens and underscores read as spaces, so a niche tag still takes part in the scoring.
 */
internal fun canonicalGenre(raw: String): String? {
    if (isNonGenreTag(raw)) return null
    val key = genreKey(raw)
    knownByKey[key]?.let { return it }
    return raw.trim().lowercase()
        .replace(Regex("[-_]+"), " ")
        .replace(Regex("\\s+"), " ")
}

/** True when [raw] is a genre or alias this file knows by name (not merely a tag that passes as one). The search card uses it to tell "shoegaze" from "My Bloody Valentine". */
internal fun isKnownGenre(raw: String): Boolean = genreKey(raw) in knownByKey

/** True when two tags are the same genre once spelling is ignored. Two non-genre tags are never the same. */
internal fun sameGenre(a: String, b: String): Boolean {
    val ka = canonicalGenre(a)?.let(::genreKey) ?: return false
    val kb = canonicalGenre(b)?.let(::genreKey) ?: return false
    return ka == kb
}

/** [raw] as canonical genre names: junk dropped, spelling variants merged, first-seen order kept. */
internal fun normalizeGenres(raw: List<String>): List<String> {
    val seen = HashSet<String>()
    val out = ArrayList<String>()
    for (tag in raw) {
        val name = canonicalGenre(tag) ?: continue
        if (seen.add(genreKey(name))) out += name
    }
    return out
}

/**
 * Tag votes as canonical genre votes. Variants of one genre add their counts together, junk is dropped,
 * and the result is ordered by votes, highest first, then by name so the order is stable.
 */
internal fun normalizeWeightedGenres(votes: Map<String, Int>): Map<String, Int> {
    val byKey = LinkedHashMap<String, Pair<String, Int>>()
    for ((tag, count) in votes) {
        val name = canonicalGenre(tag) ?: continue
        val key = genreKey(name)
        val weight = count.coerceAtLeast(0)
        val previous = byKey[key]
        byKey[key] = name to ((previous?.second ?: 0) + weight)
    }
    val sorted = byKey.values.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
    return LinkedHashMap<String, Int>().apply { for ((name, weight) in sorted) put(name, weight) }
}
