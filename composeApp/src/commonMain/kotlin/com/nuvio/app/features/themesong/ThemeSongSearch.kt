package com.nuvio.app.features.themesong

import com.nuvio.app.features.trailer.YouTubeSearchHit
import kotlin.math.log10

private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
private val YEAR = Regex("\\b(19|20)\\d{2}\\b")

internal fun tokens(text: String): List<String> =
    text.lowercase().split(NON_ALNUM).filter { it.isNotEmpty() }

internal val ARTICLES = setOf("the", "a", "an")

/** Shortest and longest upload that can be a theme: a jingle at one end, a long suite at the other. */
internal const val THEME_MIN_SECONDS = 45

/** Under this a hit is more likely a snippet than the full track, so it ranks lower. */
private const val THEME_FULL_TRACK_SECONDS = 90
internal const val THEME_MAX_SECONDS = 7 * 60

private val TRAILING_QUALIFIER = Regex("""\s*\(([^()]{2,4})\)\s*$""")

/**
 * A title's own name and the qualifier a catalogue adds to tell remakes apart: "Shameless (US)" is
 * "Shameless" and "US", "Ghosts (2021)" is "Ghosts" and "2021". Uploads of the theme seldom carry
 * the qualifier ("Shameless Theme Song"), so it goes into the search and earns a point when an upload
 * has it, but is not required of one: required, it threw out every upload of Shameless's theme.
 */
internal fun splitTitleQualifier(title: String): Pair<String, String?> {
    val name = title.trim()
    val match = TRAILING_QUALIFIER.find(name) ?: return name to null
    val qualifier = match.groupValues[1].trim()
    val isCountry = qualifier.isNotEmpty() && qualifier.all { it.isLetter() && it.isUpperCase() }
    val isYear = qualifier.length == 4 && qualifier.all(Char::isDigit)
    if (!isCountry && !isYear) return name to null
    val base = name.removeRange(match.range).trim()
    return if (base.isEmpty()) name to null else base to qualifier
}

/**
 * What the viewer is looking for, as a YouTube search. Films have a main theme, shows a theme song,
 * and anime an opening: its theme is a named song ("Crossing Field"), so "theme song" mostly finds covers.
 */
internal fun themeSongSearchQuery(title: String, year: String?, isSeries: Boolean, isAnime: Boolean = false): String {
    val (name, qualifier) = splitTitleQualifier(title)
    return if (isAnime) {
        listOfNotNull(name, "opening").joinToString(" ")
    } else if (isSeries) {
        listOfNotNull(name, qualifier, "theme song").joinToString(" ")
    } else {
        // A year qualifier stands in for a missing year; a country one is added as a word.
        val qualifierYear = qualifier?.takeIf { it.all(Char::isDigit) }
        val country = qualifier?.takeIf { qualifierYear == null }
        listOfNotNull(name.takeIf { it.isNotEmpty() }, year?.trim()?.takeIf { it.isNotEmpty() } ?: qualifierYear, country, "main theme")
            .joinToString(" ")
    }
}

/**
 * A show's opening as it aired, as a YouTube search. "<show> theme song" mostly finds the full-length
 * single (Zooey Deschanel's "Hey Girl", Lazlo Bane's "Superman" video) or a cover act; "<show> intro"
 * finds the 20 to 120 second title sequence people know.
 */
internal fun themeIntroSearchQuery(title: String): String {
    val (name, qualifier) = splitTitleQualifier(title)
    return listOfNotNull(name, qualifier, "intro").joinToString(" ")
}

/** Shortest upload that can be a show's opening: Scrubs' is about 12 seconds; a 6 second title card is not. */
internal const val INTRO_MIN_SECONDS = 10

/** Over this, an "intro" upload is the full-length song or a compilation rather than the opening as aired. */
private const val INTRO_FULL_SONG_SECONDS = 150

/** What a full-length upload costs in an intro search. */
private const val INTRO_FULL_SONG_PENALTY = 5

/**
 * Words of the uploads around a show's opening that are not the opening itself: comparisons and
 * compilations ("1999 vs 2017", "side-by-side", "every intro", "through the years"), end credits,
 * fan remakes and lyric videos. Each costs [INTRO_NOISE_PENALTY], once.
 */
private val INTRO_NOISE = setOf(
    "comparison", "side", "every", "variations", "years", "evolution", "devolution", "supercut",
    "homemade", "reimagined", "imagined", "lyrics", "closing", "end", "lost", "ww2", "war",
    // One episode's gag opening ("The Family Guy Again Opening Sequence | Season 18 Ep. 4").
    "ep", "episode",
)

/**
 * Words that say an upload is the show's music even when the show is named only in the channel. A
 * bare "intro" is not one: on a show's own channel it introduces the characters ("Handy staff intro").
 */
private val STRONG_MUSIC_WORDS = setOf("theme", "opening", "credits", "sequence", "titles", "soundtrack", "ost")
private const val INTRO_NOISE_PENALTY = 4

/** Channels that re-record TV themes ("TV Themes - Topic"): their upload is a cover. */
private val COVER_CHANNELS = setOf("tv themes", "tv themes - topic", "the tv theme players", "tv sounds unlimited")

/** Words that mark an upload as something other than the title's own music. */
internal val UNWANTED = setOf(
    "reaction", "reacts", "reacting", "review", "cover", "remix", "mashup", "piano", "guitar",
    "tutorial", "lesson", "karaoke", "ringtone", "nightcore", "slowed", "reverb", "8d", "boosted",
    "trailer", "teaser", "scene", "clip", "parody", "spoof", "hour", "hours", "loop", "ukulele",
    "violin", "orchestral", "epic", "version", "lofi", "tiktok", "shorts",
    // Dialogue and talk about the film rather than its music: a character's speech with the score
    // underneath is still not the theme.
    "speech", "dialogue", "monologue", "quote", "quotes", "scenes", "clips", "interview",
    "explained", "analysis", "breakdown", "moments", "compilation", "featurette", "behind",
    "movie", "audiobook", "podcast", "commentary", "talks", "says",
    // Fan re-recordings that name themselves without saying "cover": "ENGLISH ver", fandubs, AMVs.
    "english", "ver", "fandub", "amv", "fanmade", "recreation", "recreated",
    // Featurettes about the music.
    "spoilers", "making", "birth",
    // Wrestling entrance themes share words with every show title ("Ghosts In Us").
    "wwe", "tna", "aew", "entrance", "wrestling",
    // Battle and character cues from a film's score ("Gogeta vs Broly"), not its theme.
    "vs", "versus",
    // A fan remake set to another show's music: "Station 19 Intro [Friends Style]" played Friends.
    "style", "styled",
)

/**
 * Words that usually mean someone else is performing the title's music (a talk-show slot, a tribute act, an
 * acoustic version) but can also sit in a real title. They cost [PERFORMANCE_PENALTY] points instead of removing
 * the hit, so a genuine upload that carries one still wins when nothing better is there.
 */
internal val PERFORMANCE_WORDS = setOf(
    "live", "performance", "performs", "sings", "singing", "acoustic", "tribute",
    // A clip of the cast dancing to the theme has the theme under it, but a plain upload is better.
    "dance", "dancing", "dances",
)

/** Enough to drop a verified, much-viewed live clip below a plain upload of the original, once however many such words there are. */
internal const val PERFORMANCE_PENALTY = 4

/** At least one of these must be in the upload's title, or it is probably not the music at all. */
private val MUSIC_WORDS = setOf(
    "theme", "soundtrack", "ost", "score", "opening", "op", "intro", "music", "title", "titles", "credits", "sequence",
)

/** A character's cue ("Evil Morty's Theme") rather than the title's own theme. */
private val POSSESSIVE_THEME = Regex("""['’]s\s+(?:main\s+)?theme\b""", RegexOption.IGNORE_CASE)

/** What a cue for a character costs: enough to fall below any plain upload of the real theme. */
private const val POSSESSIVE_THEME_PENALTY = 5

/**
 * Where [phrase] runs on in [words] as one piece, or -1. Matching the title's words one by one
 * anywhere in an upload let "Drew Galloway TNA Theme ''Ghosts In Us''" stand in for "Ghosts".
 */
private fun indexOfPhrase(words: List<String>, phrase: List<String>): Int {
    if (phrase.isEmpty() || words.size < phrase.size) return -1
    for (start in 0..words.size - phrase.size) {
        if (phrase.indices.all { words[start + it] == phrase[it] }) return start
    }
    return -1
}

/**
 * "Fallout 76: The Pitt Theme": the title is the subtitle of something else, a game or a franchise.
 * Allowed when the part before the colon is the title's own words or says it is music ("OST: ...").
 */
private fun isSubtitleOfSomethingElse(rawTitle: String, titleTokens: List<String>): Boolean {
    val colon = rawTitle.indexOf(':')
    if (colon <= 0) return false
    val before = tokens(rawTitle.substring(0, colon)).filterNot { it in ARTICLES }
    val after = tokens(rawTitle.substring(colon + 1)).filterNot { it in ARTICLES }
    if (before.isEmpty() || indexOfPhrase(after, titleTokens) != 0) return false
    if (before.any { it in MUSIC_WORDS }) return false
    return before.any { it !in titleTokens }
}

/** An episode code ("1x04", "S01E04"): one episode's song or scene, not the show's theme. */
private val EPISODE_CODE = Regex("""(?i)(?<![\p{L}\p{N}])(?:\d{1,2}x\d{1,3}|s\d{1,2}\s?e\d{1,3})(?![\p{L}\p{N}])""")

/** Where an upload's title splits into parts: brackets and the separators uploaders put between names. */
private val TITLE_PARTS = Regex("""[()\[\]{}|]|\s[-–—:/]\s|:\s""")

/** Words that may stand before "theme" without naming some other title ("Main Title Theme", "Season 1 Theme"). */
private val THEME_LEAD_WORDS = MUSIC_WORDS + ARTICLES + setOf(
    "main", "official", "original", "tv", "show", "series", "full", "song", "new", "hd", "season", "seasons",
    "end", "ending", "closing", "s", "and", "of", "with",
)

/**
 * "station 19 intro (friends theme song)", "Station 19 - fireman sam theme song": the title is named,
 * but the theme in the upload belongs to something else, named in its own part of the upload's title.
 */
private fun isAnotherTitlesTheme(rawTitle: String, titleTokens: List<String>, qualifierToken: String?): Boolean {
    val parts = rawTitle.split(TITLE_PARTS).map { tokens(it) }.filter { it.isNotEmpty() }
    if (parts.size < 2) return false
    return parts.any { part ->
        val at = part.indexOf("theme")
        if (at <= 0 || part.getOrNull(at + 1) == "from") return@any false
        if (indexOfPhrase(part.filterNot { it in ARTICLES }, titleTokens) >= 0) return@any false
        part.subList(0, at).any { word ->
            word !in THEME_LEAD_WORDS && word !in titleTokens && word != qualifierToken && !word.all(Char::isDigit)
        }
    }
}

/** Weighted: how much a word says "this is the real theme" ([intro]: "the opening as aired"). */
private fun keywordScore(words: Set<String>, intro: Boolean): Int {
    var score = 0
    if ("theme" in words) score += 3
    if ("main" in words && "title" in words) score += 2
    if ("opening" in words || "intro" in words) score += if (intro) 3 else 1
    // "Opening credits", "title sequence", "main title": the names a show's own opening goes by.
    if (intro && ("credits" in words || "sequence" in words || "titles" in words || ("main" in words && "title" in words))) score += 2
    if (!intro && ("soundtrack" in words || "ost" in words || "score" in words)) score += 2
    if ("official" in words) score += 1
    return score
}

/**
 * Ranks the search results for [title] and returns up to [limit] worth trying, best first.
 *
 * An upload has to carry every significant word of the title and say it is music, last between
 * [THEME_MIN_SECONDS] and [THEME_MAX_SECONDS], and not be a cover, remix or reaction. Among those, an
 * official "Theme" upload from a verified channel or a YouTube Music "- Topic" channel wins, with
 * views as the tie-break. A different year in the upload's title costs points: it is another film.
 * So does a word that points to a live or re-performed version ([PERFORMANCE_WORDS]).
 *
 * [intro] ranks the results of [themeIntroSearchQuery]: a show's opening as aired is short (from
 * [INTRO_MIN_SECONDS]), so a full-length song loses points instead of earning them, and comparisons,
 * compilations and end credits ([INTRO_NOISE]) lose points too.
 */
internal fun rankThemeSongHits(
    hits: List<YouTubeSearchHit>,
    title: String,
    year: String?,
    limit: Int = 3,
    intro: Boolean = false,
): List<YouTubeSearchHit> {
    val (name, qualifier) = splitTitleQualifier(title)
    val titleTokens = tokens(name).filterNot { it in ARTICLES }
    if (titleTokens.isEmpty()) return emptyList()
    val qualifierToken = qualifier?.lowercase()
    // A country qualifier counts only as written ("US", "(US)"): as a word, "us" is in half of all titles.
    val qualifierPattern = qualifier?.takeIf { q -> q.all(Char::isLetter) }
        ?.let { q -> Regex("""(?<![\p{L}\p{N}])${Regex.escape(q)}(?![\p{L}\p{N}])""") }
    val ownPossessive = POSSESSIVE_THEME.containsMatchIn("$name theme")
    return hits.withIndex()
        .mapNotNull { (index, hit) ->
            val minSeconds = if (intro) INTRO_MIN_SECONDS else THEME_MIN_SECONDS
            if (hit.durationSeconds !in minSeconds..THEME_MAX_SECONDS) return@mapNotNull null
            if (hit.channel.trim().lowercase() in COVER_CHANNELS) return@mapNotNull null
            val wordList = tokens(hit.title)
            val words = wordList.toSet()
            val channelWords = tokens(hit.channel).toSet()
            // The title as one phrase in the upload's title, or every word of it in the channel's
            // name (a show's own channel, or a "- Topic" channel named after the soundtrack).
            val inTitle = indexOfPhrase(wordList.filterNot { it in ARTICLES }, titleTokens) >= 0
            if (!inTitle && titleTokens.any { it !in channelWords }) return@mapNotNull null
            if (!inTitle && words.none { it in STRONG_MUSIC_WORDS }) return@mapNotNull null
            if (isSubtitleOfSomethingElse(hit.title, titleTokens)) return@mapNotNull null
            if (isAnotherTitlesTheme(hit.title, titleTokens, qualifierToken)) return@mapNotNull null
            if (EPISODE_CODE.containsMatchIn(hit.title) && !EPISODE_CODE.containsMatchIn(name)) return@mapNotNull null
            // A word that is part of the film's own name ("The King's Speech", "The Hours") is not a red flag.
            if (words.any { it in UNWANTED && it !in titleTokens }) return@mapNotNull null
            if (words.none { it in MUSIC_WORDS }) return@mapNotNull null
            var score = keywordScore(words, intro)
            // The remake the catalogue means ("US"), when the upload says so.
            val hasQualifier = when {
                qualifierToken == null -> false
                qualifierPattern != null -> qualifierPattern.containsMatchIn(hit.title) || qualifierPattern.containsMatchIn(hit.channel)
                else -> qualifierToken in words || qualifierToken in channelWords
            }
            if (hasQualifier) score += 2
            if (!ownPossessive && POSSESSIVE_THEME.containsMatchIn(hit.title)) score -= POSSESSIVE_THEME_PENALTY
            if (intro) {
                // The opening as aired, not the single it was cut from or a compilation of every season's.
                if (hit.durationSeconds > INTRO_FULL_SONG_SECONDS) score -= INTRO_FULL_SONG_PENALTY
                if (words.any { it in INTRO_NOISE && it !in titleTokens }) score -= INTRO_NOISE_PENALTY
            } else if (hit.durationSeconds < THEME_FULL_TRACK_SECONDS) {
                // A full-length track beats a short excerpt of it.
                score -= 2
            }
            if (hit.verified) score += 2
            if ("topic" in channelWords) score += 2
            if (hit.views > 0) score += (log10(hit.views.toDouble()) / 2.5).toInt().coerceIn(0, 3)
            val otherYear = YEAR.findAll(hit.title).map { it.value }.firstOrNull { it != year }
            if (otherYear != null) score -= 3
            // A word that is part of the title itself ("Live and Let Die") is not a sign of someone else's performance.
            if (words.any { it in PERFORMANCE_WORDS && it !in titleTokens }) score -= PERFORMANCE_PENALTY
            Triple(score, index, hit)
        }
        .sortedWith(compareByDescending<Triple<Int, Int, YouTubeSearchHit>> { it.first }.thenBy { it.second })
        .take(limit)
        .map { it.third }
}
