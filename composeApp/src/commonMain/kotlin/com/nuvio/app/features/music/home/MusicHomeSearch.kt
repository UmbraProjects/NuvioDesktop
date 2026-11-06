package com.nuvio.app.features.music.home

import com.nuvio.app.features.music.MusicAlbum
import com.nuvio.app.features.music.MusicAlbumSummary
import com.nuvio.app.features.music.MusicArtist
import com.nuvio.app.features.music.MusicArtistSummary
import com.nuvio.app.features.music.MusicSearchEverything
import com.nuvio.app.features.music.MusicTopResult
import com.nuvio.app.features.music.MusicTrack
import com.nuvio.app.features.music.library.essentialRows
import com.nuvio.app.features.music.library.essentialsMix

/*
 * fork:music-home
 *
 * The quick-play first screen's search logic, pure so it is unit-tested: reading a typed sentence into chips
 * ("yelawolf remix" is an artist and a kind), merging a YouTube Music song with the same song's YouTube video,
 * ranking the letters most likely to come next, laying the letter strip out around the focused key, the greyed-out
 * completion, and holding back a top match until it has stayed the same for two typing pauses. The screen and the
 * network calls live elsewhere.
 */

// region Sentence chips

/** What a word in the search line can ask for beyond the name itself. */
enum class QueryKind(val label: String, val plural: String) {
    Remix("remix", "remixes"),
    Live("live", "live"),
    Acoustic("acoustic", "acoustic"),
    Albums("albums only", "albums"),
}

/** The kinds that split results into the artist's own uploads (centre) and everyone else's (right). */
val SPLIT_KINDS: Set<QueryKind> = setOf(QueryKind.Remix, QueryKind.Live, QueryKind.Acoustic)

/**
 * The line rewritten for the Narrow row: the name being searched for, then [kind]'s word (none to clear it), then the
 * year when [keepYear]. "yelawolf live" with Remix gives "yelawolf remix".
 */
fun withKind(query: ParsedQuery, kind: QueryKind?, keepYear: Boolean = true): String =
    listOfNotNull(
        query.terms.trim().ifEmpty { null },
        kind?.let { k -> KIND_WORDS.entries.first { it.value == k }.key },
        query.year?.takeIf { keepYear }?.toString(),
    ).joinToString(" ")

/** A chip drawn in the search line. [word] is the text it came from, so Delete can give it back. */
sealed interface QueryChip {
    val word: String

    data class Artist(val name: String, override val word: String = name) : QueryChip
    data class Kind(val kind: QueryKind, override val word: String) : QueryChip
    data class Year(val year: Int, override val word: String = year.toString()) : QueryChip
}

/**
 * A typed line read as chips. [terms] is what is left once the kind and year words are taken out (the name being
 * searched for). [artist] is set when [terms] is, as a whole, a library artist's name or starts with one.
 */
data class ParsedQuery(
    val raw: String,
    val terms: String,
    val artist: String?,
    val kind: QueryKind?,
    val year: Int?,
) {
    val chips: List<QueryChip>
        get() = buildList {
            artist?.let { add(QueryChip.Artist(it)) }
            kind?.let { k -> add(QueryChip.Kind(k, KIND_WORDS.entries.first { it.value == k }.key)) }
            year?.let { add(QueryChip.Year(it)) }
        }

    /** What to send to YouTube: the line as typed, minus "albums" (which only filters what is shown). */
    val searchText: String
        get() = if (kind == QueryKind.Albums) listOfNotNull(terms.ifBlank { null }, year?.toString()).joinToString(" ") else raw.trim()
}

/** Words that set a kind. Plural and common spellings included; only whole words count. */
private val KIND_WORDS: Map<String, QueryKind> = linkedMapOf(
    "remix" to QueryKind.Remix,
    "remixes" to QueryKind.Remix,
    "rmx" to QueryKind.Remix,
    "live" to QueryKind.Live,
    "acoustic" to QueryKind.Acoustic,
    "unplugged" to QueryKind.Acoustic,
    "albums" to QueryKind.Albums,
    "album" to QueryKind.Albums,
)

private val YEAR_WORD = Regex("""^(19[5-9]\d|20\d\d)$""")

/**
 * Reads [raw] into chips. The first kind word and the first plausible year are taken out of the terms; a kind word
 * that is the whole line ("Live") stays a name. [libraryArtists] are the names the person already owns: when the
 * remaining terms are one of them, or start with one followed by more words, that artist becomes a chip.
 */
fun parseQuery(raw: String, libraryArtists: List<String> = emptyList()): ParsedQuery {
    val words = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    var kind: QueryKind? = null
    var year: Int? = null
    val kept = ArrayList<String>()
    for (word in words) {
        val bare = word.lowercase().trim(',', '.', '!', '?')
        val asKind = KIND_WORDS[bare]
        when {
            asKind != null && kind == null && words.size > 1 -> kind = asKind
            YEAR_WORD.matches(bare) && year == null && words.size > 1 -> year = bare.toInt()
            else -> kept += word
        }
    }
    val terms = kept.joinToString(" ")
    val artist = libraryArtistIn(terms, libraryArtists)
    return ParsedQuery(raw = raw, terms = terms, artist = artist, kind = kind, year = year)
}

/** The library artist [terms] names: the longest name that is the whole of it or its leading words. */
internal fun libraryArtistIn(terms: String, libraryArtists: List<String>): String? {
    val key = foldName(terms)
    if (key.isEmpty()) return null
    return libraryArtists
        .filter { name -> foldName(name).let { it.isNotEmpty() && (key == it || key.startsWith("$it ")) } }
        .maxByOrNull { foldName(it).length }
}

/** A name for comparing: lower case, accents and "the " dropped, punctuation and "&" folded to single spaces. */
internal fun foldName(name: String): String {
    val lower = name.lowercase()
        .map { FOLD_ACCENTS[it] ?: it }
        .joinToString("")
        .replace("&", " and ")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    return lower.removePrefix("the ").trim()
}

private val FOLD_ACCENTS: Map<Char, Char> = mapOf(
    'á' to 'a', 'à' to 'a', 'â' to 'a', 'ä' to 'a', 'ã' to 'a', 'å' to 'a',
    'é' to 'e', 'è' to 'e', 'ê' to 'e', 'ë' to 'e',
    'í' to 'i', 'ì' to 'i', 'î' to 'i', 'ï' to 'i',
    'ó' to 'o', 'ò' to 'o', 'ô' to 'o', 'ö' to 'o', 'õ' to 'o', 'ø' to 'o',
    'ú' to 'u', 'ù' to 'u', 'û' to 'u', 'ü' to 'u',
    'ñ' to 'n', 'ç' to 'c', 'ý' to 'y', 'ÿ' to 'y',
)

/** True when [title] reads as the [kind] asked for (a remix, a live take, an acoustic one). Albums match anything. */
fun titleMatchesKind(title: String, kind: QueryKind): Boolean {
    val t = " " + title.lowercase().replace(Regex("[^a-z0-9]"), " ") + " "
    return when (kind) {
        QueryKind.Remix -> listOf(" remix ", " remixes ", " rmx ", " mix ", " edit ", " bootleg ", " flip ", " vip ").any { it in t }
        QueryKind.Live -> listOf(" live ", " session ", " concert ", " tour ", " unplugged ").any { it in t }
        QueryKind.Acoustic -> listOf(" acoustic ", " unplugged ", " stripped ").any { it in t }
        QueryKind.Albums -> true
    }
}

/**
 * Whether a YouTube upload is the artist's own (their channel, their label's, a VEVO or auto-generated "Topic"
 * channel) rather than a fan's or a DJ's. A guess from the channel name; some will be wrong.
 */
fun isOfficialUpload(channel: String?, artistNames: List<String>): Boolean {
    val c = foldName(channel ?: return false)
    if (c.isEmpty()) return false
    // "... Music" is left out on purpose: plenty of fan and re-upload channels are called that.
    if (c.endsWith(" topic") || c.endsWith("vevo") || c.endsWith(" records")) return true
    return artistNames.any { name ->
        val a = foldName(name)
        a.isNotEmpty() && (c == a || c == a.replace(" ", "") || c.startsWith("$a ") || c.startsWith(a.replace(" ", "")))
    }
}

// endregion

// region Merged rows

/** One song as the screen lists it: the YouTube Music track, its YouTube video, or both. */
data class MergedRow(val song: MusicTrack?, val video: MusicTrack?) {
    val hasVideo: Boolean get() = video != null

    /** Where the row came from, for the badge on split panels: both sources, YouTube Music alone, or YouTube alone. */
    val source: RowSource
        get() = when {
            song != null && video != null -> RowSource.Both
            song != null -> RowSource.YouTubeMusic
            else -> RowSource.YouTube
        }

    /** What plays: the YouTube Music track when there is one, else the video. */
    val playable: MusicTrack? get() = song ?: video
}

enum class RowSource(val label: String) { Both("BOTH"), YouTubeMusic("YT MUSIC"), YouTube("YOUTUBE") }

/** The merge's answer: the rows for the YouTube Music panel and the videos no song claimed, for the YouTube panel. */
data class MergedResults(val rows: List<MergedRow>, val otherVideos: List<MusicTrack>)

/** Lengths further apart than this are different recordings (a radio edit, an extended mix). */
private const val SAME_LENGTH_SLACK_SECONDS = 4

/**
 * Pairs each YouTube Music song with the YouTube video of the same recording: same title once brackets and video
 * words are dropped, an artist in common (or the video's channel naming one), and lengths within 4 s (or unknown).
 * Each video pairs at most once; the songs keep their order and unpaired videos keep theirs.
 */
fun mergeSourceRows(songs: List<MusicTrack>, videos: List<MusicTrack>): MergedResults {
    val unclaimed = videos.toMutableList()
    val rows = songs.map { song ->
        val match = unclaimed.firstOrNull { sameRecording(song, it) }
        if (match != null) unclaimed.remove(match)
        MergedRow(song = song, video = match)
    }
    return MergedResults(rows, unclaimed)
}

internal fun sameRecording(song: MusicTrack, video: MusicTrack): Boolean {
    if (songKey(song.title) != songKey(video.title) || songKey(song.title).isEmpty()) return false
    val songArtists = song.artists.map { foldName(it.name) }.filter { it.isNotEmpty() }
    val videoArtists = video.artists.map { foldName(it.name) }.filter { it.isNotEmpty() }
    val artistAgrees = songArtists.isEmpty() || videoArtists.isEmpty() ||
        songArtists.any { s -> videoArtists.any { v -> v == s || v.startsWith("$s ") || v.replace(" ", "") .startsWith(s.replace(" ", "")) } }
    if (!artistAgrees) return false
    if (song.durationSeconds <= 0 || video.durationSeconds <= 0) return true
    return kotlin.math.abs(song.durationSeconds - video.durationSeconds) <= SAME_LENGTH_SLACK_SECONDS
}

/** Words a video title adds that are not part of the song's name. */
private val VIDEO_NOISE = listOf(
    "official music video", "official video", "official audio", "official lyric video", "lyric video", "music video",
    "official visualizer", "visualizer", "lyrics", "audio", "hd", "4k", "explicit", "clean",
)

/**
 * A title reduced to the song's name: lower case, any bracketed part removed, a "feat." tail removed, video words and
 * an "Artist - " lead dropped, punctuation folded. "Yelawolf - Till It's Gone (Official Music Video)" and
 * "Till It's Gone" give the same key.
 */
internal fun songKey(title: String): String {
    var t = title.lowercase()
    t = t.replace(Regex("""[\(\[\{][^\)\]\}]*[\)\]\}]"""), " ")
    t = t.replace(Regex("""\s(feat\.?|ft\.?|featuring)\s.*$"""), " ")
    val dash = Regex("""\s[-–—|]\s""").find(t)
    if (dash != null) t = t.substring(dash.range.last + 1)
    t = foldName(t)
    for (noise in VIDEO_NOISE) t = t.replace(Regex("(^| )" + Regex.escape(noise) + "( |$)"), " ")
    return t.replace(Regex("\\s+"), " ").trim()
}

// endregion

// region Letter strip

/** The letters on the strip, in their resting order. */
val STRIP_LETTERS: List<Char> = ('A'..'Z').toList()

/** How many likely letters move next to the focused key. */
const val STRIP_PULLED = 3

/** Where a name the strip learns from came from. Library names count most: they are what this person plays. */
enum class LetterSource(val weight: Double) { Library(3.0), Suggestion(2.0), History(1.0) }

/**
 * The letters most likely to come next after [typed], best first: every candidate whose folded form starts with the
 * folded [typed] votes for its next letter, weighted by where it came from, earlier candidates of a source a little
 * more. A space after a whole word is ' '; digits are kept. Letters only, A to Z, upper case, plus ' ' and digits.
 */
fun nextLetterRanks(typed: String, candidates: List<Pair<String, LetterSource>>): List<Char> {
    val prefix = foldForTyping(typed)
    val scores = HashMap<Char, Double>()
    val positionInSource = HashMap<LetterSource, Int>()
    for ((name, source) in candidates) {
        val position = positionInSource.getOrElse(source) { 0 }
        positionInSource[source] = position + 1
        val folded = foldForTyping(name)
        if (folded.length <= prefix.length || !folded.startsWith(prefix)) continue
        val next = folded[prefix.length].uppercaseChar()
        if (next != ' ' && next !in 'A'..'Z' && next !in '0'..'9') continue
        scores[next] = scores.getOrElse(next) { 0.0 } + source.weight / (1.0 + position * 0.1)
    }
    return scores.entries.sortedWith(compareByDescending<Map.Entry<Char, Double>> { it.value }.thenBy { it.key }).map { it.key }
}

/** What has been typed, as the strip compares it: lower case, accents folded, punctuation dropped, spaces kept single. */
internal fun foldForTyping(text: String): String =
    text.lowercase()
        .map { FOLD_ACCENTS[it] ?: it }
        .filter { it.isLetterOrDigit() || it == ' ' }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .trimStart()

/**
 * The strip's order with the [likely] letters (best first, at most [STRIP_PULLED]) moved next to [focused]: the best
 * one just right of it, the second just left, the third one further right. Everything else keeps A to Z order.
 * With no [focused] key (nothing typed yet, or the line cleared) or nothing likely, the strip is A to Z.
 */
fun arrangeStrip(focused: Char?, likely: List<Char>, letters: List<Char> = STRIP_LETTERS): List<Char> {
    val focus = focused?.uppercaseChar()?.takeIf { it in letters } ?: return letters
    val pulled = likely.map { it.uppercaseChar() }.filter { it in letters && it != focus }.distinct().take(STRIP_PULLED)
    if (pulled.isEmpty()) return letters
    val rest = letters.filter { it !in pulled }.toMutableList()
    val at = rest.indexOf(focus)
    // Right of the focus first, then left, then further right: the order a remote's Left/Right reaches them.
    pulled.getOrNull(0)?.let { rest.add(at + 1, it) }
    pulled.getOrNull(1)?.let { rest.add(at, it) }
    pulled.getOrNull(2)?.let { third -> rest.add(rest.indexOf(focus) + 2, third) }
    return rest
}

/**
 * The greyed-out rest of the word after [typed]: the first candidate (in the order given) that starts with what is
 * typed and is longer, returned as the part still to come, in the candidate's own spelling. Empty when none fits.
 */
fun ghostCompletion(typed: String, candidates: List<String>): String {
    if (typed.isBlank()) return ""
    val lowerTyped = typed.lowercase()
    val hit = candidates.firstOrNull { it.length > typed.length && it.lowercase().startsWith(lowerTyped) } ?: return ""
    return hit.substring(typed.length)
}

// endregion

// region Settling the top match

/**
 * Holds back a change of top match until the same one has come back from [needed] searches in a row, so typing "yel"
 * then "yela" does not flash two artists' fanart. [offer] takes each search's top match id (null for none) and
 * returns the settled one, which only changes once the new id has been seen [needed] times running.
 */
class MatchSettler(private val needed: Int = 2) {
    private var candidate: String? = null
    private var seen = 0

    var settled: String? = null
        private set

    fun offer(id: String?): String? {
        if (id == null) {
            candidate = null
            seen = 0
            return settled
        }
        if (id == candidate) seen++ else {
            candidate = id
            seen = 1
        }
        if (seen >= needed) settled = id
        return settled
    }

    /** Forgets everything: the line was cleared. */
    fun reset() {
        candidate = null
        seen = 0
        settled = null
    }
}

// endregion

// region The panels' contents

/**
 * What the three panels show for one search. Left: [top] and [artists]. Centre: [rows] (YouTube Music songs, each
 * with its YouTube video when one matched) and [albums]. Right: [videos], the YouTube and YouTube Music videos no song
 * claimed. A kind chip narrows every list to titles of that kind; "albums only" empties the song and video lists; a
 * year chip keeps albums from that year.
 */
data class HomeResults(
    val query: ParsedQuery,
    val top: MusicTopResult?,
    val artists: List<MusicArtistSummary>,
    val rows: List<MergedRow>,
    val albums: List<MusicAlbumSummary>,
    val videos: List<MusicTrack>,
) {
    val isEmpty: Boolean get() = top == null && artists.isEmpty() && rows.isEmpty() && albums.isEmpty() && videos.isEmpty()

    /** True when a remix, live or acoustic chip split the results into the artist's own and everyone else's. */
    val split: Boolean get() = query.kind in SPLIT_KINDS

    /** The centre panel's title. */
    val centreLabel: String
        get() = when (query.kind) {
            QueryKind.Remix -> "OFFICIAL REMIXES · BOTH SOURCES"
            QueryKind.Live -> "OFFICIAL LIVE · BOTH SOURCES"
            QueryKind.Acoustic -> "OFFICIAL ACOUSTIC · BOTH SOURCES"
            QueryKind.Albums -> "YOUTUBE MUSIC · ALBUMS"
            null -> "YOUTUBE MUSIC · SONGS AND ALBUMS"
        }

    /** The right panel's title. */
    val rightLabel: String
        get() = when (query.kind) {
            QueryKind.Remix -> "FAN AND DJ REMIXES · YOUTUBE"
            QueryKind.Live -> "FAN-FILMED AND SESSIONS · YOUTUBE"
            QueryKind.Acoustic -> "COVERS AND SESSIONS · YOUTUBE"
            QueryKind.Albums, null -> "YOUTUBE · VIDEOS"
        }

    /** Everything the centre panel can play, in its order: the queue "Play all" starts. */
    val centreTracks: List<MusicTrack> get() = rows.mapNotNull { it.playable }

    /** What identifies the top match for [MatchSettler]: its album, else its artist, else its video, else its title. */
    val topKey: String?
        get() = top?.let(::topKeyOf)
}

/** What identifies a top match for [MatchSettler]: its album, else its artist, else its video, else its title. */
/** What a recent search is saved as: a half-typed line ("sleep t") that led to a match it begins ("Sleep Token") is kept as the match's name. */
fun recentSearchLabel(typed: String, topTitle: String?): String {
    val line = typed.trim()
    val title = topTitle?.trim().orEmpty()
    return if (title.isNotEmpty() && title.length > line.length && title.startsWith(line, ignoreCase = true)) title else line
}

fun topKeyOf(top: MusicTopResult): String = top.albumBrowseId ?: top.artists.firstOrNull()?.browseId ?: top.videoId ?: top.title

fun buildHomeResults(query: ParsedQuery, everything: MusicSearchEverything, youtube: List<MusicTrack>): HomeResults {
    val kind = query.kind
    val albumsOnly = kind == QueryKind.Albums
    fun keep(title: String) = kind == null || albumsOnly || titleMatchesKind(title, kind)
    val songs = if (albumsOnly) emptyList() else everything.songs.filter { keep(it.title) }
    val videos = if (albumsOnly) emptyList() else (everything.videos + youtube).distinctBy { it.videoId }.filter { keep(it.title) }
    val merged = mergeSourceRows(songs, videos)
    // A remix, live or acoustic chip: the artist's own uploads join the songs in the centre, everyone else's go right.
    val ownNames = listOfNotNull(query.artist) + everything.top?.artists.orEmpty().map { it.name } +
        query.terms.takeIf { it.isNotBlank() }.let { listOfNotNull(it) }
    val (ownVideos, otherVideos) = if (kind in SPLIT_KINDS) {
        merged.otherVideos.partition { isOfficialUpload(it.artists.firstOrNull()?.name, ownNames) }
    } else {
        emptyList<MusicTrack>() to merged.otherVideos
    }
    val year = query.year?.toString()
    val albums = everything.albums.filter { keep(it.title) && (year == null || it.year == year) }
    // The top artist is drawn as the top match, so it is not listed again under it.
    val topArtistId = everything.top?.takeIf { it.albumBrowseId == null && it.videoId == null }?.artists?.firstOrNull()?.browseId
    return HomeResults(
        query = query,
        top = everything.top,
        artists = everything.artists.filter { it.browseId != topArtistId },
        rows = merged.rows + ownVideos.map { MergedRow(song = null, video = it) },
        albums = albums,
        videos = otherVideos,
    )
}

/**
 * The small line over the question: the weekday and the part of the day, "THURSDAY EVENING". [isoWeekday] is 1 for
 * Monday to 7 for Sunday, [hour] 0 to 23. Night runs from 22:00 to 04:59.
 */
fun greetingLabel(isoWeekday: Int, hour: Int): String {
    val day = listOf("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")[(isoWeekday - 1).mod(7)]
    val part = when (hour) {
        in 5..11 -> "MORNING"
        in 12..16 -> "AFTERNOON"
        in 17..21 -> "EVENING"
        else -> "NIGHT"
    }
    return "$day $part"
}

// endregion

// region The action row

/** What the top match is, which decides its action row. */
enum class TopMatchKind { Artist, Album, Track }

fun topMatchKind(top: MusicTopResult): TopMatchKind = when {
    top.albumBrowseId != null -> TopMatchKind.Album
    top.videoId != null -> TopMatchKind.Track
    else -> TopMatchKind.Artist
}

/** One button of the action row, which takes the letter strip's place once Down is pressed with a top match. */
enum class HomeAction(val label: String) {
    PlayEssentials("Play Essentials"),
    ShuffleEssentials("Shuffle"),
    PlayTopAlbums("Play Top 5"),
    OpenArtist("Open artist page"),
    PlayAlbum("Play album"),
    ShuffleAlbum("Shuffle"),
    QueueAlbum("Add to queue"),
    OpenAlbum("Open album"),
    PlayTrack("Play"),
    QueueTrack("Add to queue"),
    PlayAll("Play all"),
    ShuffleAll("Shuffle all"),
    More("More"),
}

/** The button's words: "Play all remixes", "Shuffle live"; the rest as named. */
fun HomeAction.labelFor(kind: QueryKind?): String = when {
    this == HomeAction.PlayAll && kind != null -> "Play all ${kind.plural}"
    this == HomeAction.ShuffleAll && kind != null -> "Shuffle ${kind.plural}"
    else -> label
}

/**
 * The action row for [top], the main action first (the one focus lands on): an artist plays its Essentials, an album
 * plays, a song or video plays. "Open artist page" only when the match links an artist; More always ends the row.
 */
fun homeActions(top: MusicTopResult, kind: QueryKind? = null): List<HomeAction> {
    // With a remix, live or acoustic chip the row leads with playing what the centre panel found.
    if (kind in SPLIT_KINDS) {
        return listOfNotNull(
            HomeAction.PlayAll,
            HomeAction.ShuffleAll,
            HomeAction.OpenArtist.takeIf { top.artists.firstOrNull()?.browseId != null },
            HomeAction.More,
        )
    }
    return baseActions(top)
}

private fun baseActions(top: MusicTopResult): List<HomeAction> = when (topMatchKind(top)) {
    TopMatchKind.Artist -> listOfNotNull(
        HomeAction.PlayEssentials,
        HomeAction.ShuffleEssentials,
        HomeAction.PlayTopAlbums,
        HomeAction.OpenArtist.takeIf { top.artists.firstOrNull()?.browseId != null },
        HomeAction.More,
    )
    TopMatchKind.Album -> listOf(HomeAction.PlayAlbum, HomeAction.ShuffleAlbum, HomeAction.QueueAlbum, HomeAction.OpenAlbum, HomeAction.More)
    TopMatchKind.Track -> listOfNotNull(
        HomeAction.PlayTrack,
        HomeAction.QueueTrack,
        HomeAction.OpenArtist.takeIf { top.artists.firstOrNull()?.browseId != null },
        HomeAction.More,
    )
}

/** How many of an artist's albums Play Top 5 opens and plays one after the other. */
const val HOME_TOP_ALBUMS = 5

/**
 * The Essentials queue for an artist found by search (not necessarily in the library): YouTube Music's most played list
 * from the artist's page, in its order, as the artist page's Play Essentials builds it. The songs' albums are not opened
 * first, so it starts at once; lengths and covers fill in as each song plays. Null when the page lists no songs.
 */
fun searchEssentials(artist: MusicArtist): MusicAlbum? =
    essentialsMix(artist.browseId, artist.name, artist.thumbnailUrl, essentialRows(artist.topSongs, emptyMap()))

/** The albums Play Top 5 plays: the artist page's albums first, then its singles and EPs, at most [HOME_TOP_ALBUMS]. */
fun topAlbumIds(artist: MusicArtist): List<String> {
    val (albums, rest) = artist.albums.partition { it.kind == null || it.kind.equals("Album", ignoreCase = true) }
    return (albums + rest).map { it.browseId }.distinct().take(HOME_TOP_ALBUMS)
}

// endregion
