package com.nuvio.app.features.music.library

import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

/**
 * Ladder phase 10: an album as the target of a ladder ("Ladder from this record"). Into and out-from, like an artist.
 *
 * A record is not only its artist. The same band made a shoegaze record and a pop one; a ladder from either should
 * not be the band's. So the target vector blends two things:
 *
 *  - the artist's MusicBrainz tag votes (phase 4a), scaled so the top tag is [ALBUM_ARTIST_PEAK]; and
 *  - the record's own genre and style from TheAudioDB ([AlbumDetails]), which outweigh the artist's: its genre
 *    starts at [ALBUM_GENRE_PEAK], its style at [ALBUM_STYLE_PEAK], each falling off down the list.
 *
 * The blend gives an [ArtistTarget] (so the target genre, the out-from destinations and everything downstream are the
 * phase 4 code), and a [LadderFlavour]: the blended sound plus the record's mood and style, and its year. The flavour
 * does not change who is of the genre; it lifts candidates that share the record's sound or began in its era when the
 * rungs are chosen ([buildLadder]).
 *
 * The year comes from the search result (YouTube Music) when it has one, else from TheAudioDB. A record no source
 * describes still gets a ladder: its artist's genres and its year. The artist never stands on the record's ladder.
 */

/** What a source says about one record. Every field is optional. */
internal data class AlbumDetails(
    val genre: String? = null,
    val style: String? = null,
    val mood: String? = null,
    val year: String? = null,
)

/** The artist side of the blend peaks at this many votes (their top tag). */
internal const val ALBUM_ARTIST_PEAK = 50

/** The record's first genre starts at this many votes, the next at half, and so on ([ALBUM_GENRE_PEAK] / position). */
internal const val ALBUM_GENRE_PEAK = 100

/** The record's style tags weigh less than its genre: they are often broad ("Rock/Pop"). */
internal const val ALBUM_STYLE_PEAK = 30

private val descriptorSeparators = Regex("[/,;]")

private fun splitDescriptor(text: String?): List<String> =
    text.orEmpty().split(descriptorSeparators).map { it.trim() }.filter { it.isNotEmpty() }

/** A record is shaped by its artist and by what a source says of it. */
internal data class AlbumTarget(
    val title: String,
    val artistName: String,
    /** The record's year (search result first, else the source's), or null. */
    val year: Int?,
    /** The blend: the artist with the record's genres mixed into their votes. Its [ArtistTarget.genre] is the ladder's target. */
    val target: ArtistTarget,
    /** Null when no source described the record: the ladder then rests on the artist's genres and the year. */
    val details: AlbumDetails?,
    val flavour: LadderFlavour,
) {
    val described: Boolean get() = details != null
}

/** The record's year as a number: [searchYear] when it is a plausible year, else [details]'s. Null otherwise. */
internal fun albumYear(searchYear: String?, details: AlbumDetails?): Int? =
    sequenceOf(searchYear, details?.year)
        .mapNotNull { it?.trim()?.take(4)?.toIntOrNull() }
        .firstOrNull { it in 1900..2100 }

/**
 * The artist's votes and the record's genres as one vote map. The artist's top tag is [ALBUM_ARTIST_PEAK]; the record's
 * genres (TheAudioDB genre, then its style parts) come in above it so a record's own genre beats its artist's usual one.
 * Names are the readable genre names of phase 0, so the same genre from both sides adds up.
 */
internal fun blendAlbumVotes(artistVotes: Map<String, Int>, details: AlbumDetails?): Map<String, Int> {
    val blended = LinkedHashMap<String, Int>()
    fun add(name: String, votes: Int) {
        if (votes > 0) blended.merge(name, votes, Int::plus)
    }
    val artist = normalizeWeightedGenres(artistVotes)
    val top = artist.values.maxOrNull() ?: 0
    if (top > 0) for ((name, count) in artist) add(name, maxOf(1, (count.toDouble() / top * ALBUM_ARTIST_PEAK).roundToInt()))
    normalizeGenres(splitDescriptor(details?.genre)).forEachIndexed { i, g -> add(g, ALBUM_GENRE_PEAK / (1 + i)) }
    normalizeGenres(splitDescriptor(details?.style)).forEachIndexed { i, g -> add(g, maxOf(1, ALBUM_STYLE_PEAK / (1 + i))) }
    return blended
}

/**
 * The record as a ladder target, from its [artist] (phase 4a, genres lent or their own) and what a source said of it.
 * The blended target keeps the artist's id and name (so the artist is left off the ladder) and takes the record's mood
 * and style when it has them.
 */
internal fun albumTarget(title: String, searchYear: String?, artist: ArtistTarget, details: AlbumDetails?): AlbumTarget {
    val votes = blendAlbumVotes(artist.artist.votes, details)
    val blendedArtist = artist.artist.copy(
        votes = votes,
        mood = details?.mood?.takeIf { it.isNotBlank() } ?: artist.artist.mood,
        style = details?.style?.takeIf { it.isNotBlank() } ?: artist.artist.style,
    )
    // The artist had a usable genre, and the blend only adds to their votes, so it always has one.
    val blended = artistTarget(blendedArtist, artist.borrowedFrom) ?: artist
    val year = albumYear(searchYear, details)
    val sound = tagProfileFromVotes(votes, blendedArtist.mood, blendedArtist.style)
    return AlbumTarget(
        title = title,
        artistName = artist.artist.name,
        year = year,
        target = blended,
        details = details,
        flavour = LadderFlavour(sound, year),
    )
}

/** The pool without the record's artist, matched by id and by name. */
private fun withoutArtist(album: AlbumTarget, pool: List<LadderCandidate>): List<LadderCandidate> {
    val ownKey = genreKey(album.artistName)
    return pool.filter { it.id != album.target.artist.id && genreKey(it.name) != ownKey }
}

/** The ladder into the record's sound: [buildLadder] on the blended genre, with the record's flavour, without its artist. */
internal fun buildAlbumLadder(
    album: AlbumTarget,
    artists: List<LibraryArtist>,
    pool: List<LadderCandidate>,
    dial: Double = 0.0,
    rungs: Int = LADDER_RUNGS,
): Ladder = buildLadder(album.target.genre, artists, withoutArtist(album, pool), dial, rungs, flavour = album.flavour)

/**
 * The ladder out from the record into [destination]: closeness is measured against the record (the blended artist as
 * the single anchor, with the record's mood and style), and the why lines name the record.
 */
internal fun buildAlbumOutFromLadder(
    album: AlbumTarget,
    destination: String,
    artists: List<LibraryArtist>,
    pool: List<LadderCandidate>,
    dial: Double = 0.0,
    rungs: Int = LADDER_RUNGS,
): Ladder = buildLadder(
    destination,
    artists,
    withoutArtist(album, pool),
    dial,
    rungs,
    anchors = listOf(album.target.asAnchor()),
    from = album.title,
    flavour = album.flavour,
)

internal sealed interface AlbumLookup {
    data class Found(val album: AlbumTarget) : AlbumLookup
    data class Failed(val reason: ArtistLadderFailure) : AlbumLookup
}

/**
 * Finds a record's target: its artist through the phase 4 [artists] resolver (cached, Deezer fallback and all), then the
 * record's details through [details] (null when no source knows the record, or none could be reached: the ladder is
 * still built, from the artist and the year). A details lookup that throws counts as none; cancellation passes through.
 */
internal class AlbumTargetResolver(
    private val artists: ArtistTargetResolver,
    private val details: suspend (artist: String, title: String) -> AlbumDetails?,
) {
    suspend fun resolve(title: String, artistName: String, year: String?): AlbumLookup {
        val artist = when (val lookup = artists.resolve(artistName)) {
            is ArtistLookup.Failed -> return AlbumLookup.Failed(lookup.reason)
            is ArtistLookup.Found -> lookup.target
        }
        val described = try {
            details(artist.artist.name, title)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            null
        }
        return AlbumLookup.Found(albumTarget(title, year, artist, described))
    }
}

internal sealed interface AlbumLadderOutcome {
    data class Ready(
        val album: AlbumTarget,
        val ladder: Ladder,
        val mode: LadderMode,
        /** Out-from only: the genre the ladder leads out into, as named on screen. */
        val destination: String? = null,
        /** Out-from only: other genres the person could lead out into instead. */
        val alternatives: List<String> = emptyList(),
    ) : AlbumLadderOutcome

    data class Failed(val reason: ArtistLadderFailure) : AlbumLadderOutcome
}

/**
 * The whole path for a record, with its sources passed in so it runs without a network. Into mode climbs into the
 * blended genre; out-from mode leads out into [destination] (or the first genre the record's blend offers, found as
 * in phase 4b: the record's other genres, then the genres that sit beside its primary one among that genre's candidates).
 */
internal suspend fun ladderFromAlbum(
    resolver: AlbumTargetResolver,
    candidates: suspend (genre: String) -> List<LadderCandidate>?,
    title: String,
    artistName: String,
    year: String?,
    mode: LadderMode,
    artists: List<LibraryArtist>,
    destination: String? = null,
    dial: Double = 0.0,
    rungs: Int = LADDER_RUNGS,
): AlbumLadderOutcome {
    val album = when (val lookup = resolver.resolve(title, artistName, year)) {
        is AlbumLookup.Failed -> return AlbumLadderOutcome.Failed(lookup.reason)
        is AlbumLookup.Found -> lookup.album
    }
    if (mode == LadderMode.Into) {
        val pool = candidates(album.target.genre) ?: return AlbumLadderOutcome.Failed(ArtistLadderFailure.Unreachable)
        return AlbumLadderOutcome.Ready(album, buildAlbumLadder(album, artists, pool, dial, rungs), LadderMode.Into)
    }
    var options = outwardOptions(album.target)
    var chosen = pickDestination(album.target, options, destination)
    if (chosen == null) {
        val primaryPool = candidates(album.target.genre) ?: return AlbumLadderOutcome.Failed(ArtistLadderFailure.Unreachable)
        options = outwardOptions(album.target, primaryPool)
        chosen = pickDestination(album.target, options, null)
            ?: return AlbumLadderOutcome.Failed(ArtistLadderFailure.NoOutward)
    }
    val pool = candidates(chosen) ?: return AlbumLadderOutcome.Failed(ArtistLadderFailure.Unreachable)
    val ladder = buildAlbumOutFromLadder(album, chosen, artists, pool, dial, rungs)
    val chosenKey = genreKey(chosen)
    return AlbumLadderOutcome.Ready(
        album = album,
        ladder = ladder,
        mode = LadderMode.OutFrom,
        destination = ladder.target,
        alternatives = options.filter { genreKey(it) != chosenKey },
    )
}

/** "Loveless (Deluxe Edition)" and "Loveless [Remastered]" are both "loveless": the title without a trailing bracket. */
internal fun baseAlbumTitle(title: String): String = title.substringBefore('(').substringBefore('[').trim().ifEmpty { title.trim() }

/**
 * The TheAudioDB album that is [title]: an exact match (spelling, case and punctuation ignored), else one that matches
 * once a "(Deluxe Edition)" or "[Remastered]" tail is dropped from either side. Null when none does: a near title is never guessed.
 */
internal fun pickAudioDbAlbum(title: String, albums: List<AudioDbAlbum>): AudioDbAlbum? {
    val exact = genreKey(title)
    albums.firstOrNull { genreKey(it.title) == exact }?.let { return it }
    val base = genreKey(baseAlbumTitle(title))
    if (base.isEmpty()) return null
    return albums.firstOrNull { genreKey(baseAlbumTitle(it.title)) == base }
}

internal fun AudioDbAlbum.toDetails(): AlbumDetails? =
    AlbumDetails(genre = genre, style = style, mood = mood, year = year)
        .takeIf { it.genre != null || it.style != null || it.mood != null || it.year != null }

/** The live source of record details: TheAudioDB, by artist then album title. Answers are kept for the session. */
internal object LadderAlbumSources {
    private val found = HashMap<String, AlbumDetails>()

    suspend fun details(artist: String, title: String): AlbumDetails? {
        val key = genreKey(artist) + "|" + genreKey(title)
        found[key]?.let { return it }
        val client = AudioDbClient(MusicLibraryRepository.state.value.settings.audioDbKey)
        val dbArtist = client.artistByName(artist) ?: return null
        val album = pickAudioDbAlbum(title, client.albums(dbArtist.id)) ?: return null
        val details = album.toDetails() ?: return null
        found[key] = details
        return details
    }
}
