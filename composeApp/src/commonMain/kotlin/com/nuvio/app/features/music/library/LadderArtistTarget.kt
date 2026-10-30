package com.nuvio.app.features.music.library

import com.nuvio.app.features.library.LibraryClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * Ladder phase 4a: an artist as the target of a ladder ("Climb into My Bloody Valentine"). Into mode only.
 *
 * The artist is turned into a target genre: their own tag votes, cleaned up by phase 0, give a primary genre (the
 * most voted). The ladder is then the same [buildLadder] run on that genre, over the genre's candidates (phase 3's
 * one tag search), with the artist themself left out of the pool so they never stand on their own ladder.
 *
 * Phase 4b adds two things on top (see LadderRelated.kt and LadderOutFrom.kt): an artist with no usable genre tag
 * borrows genres from Deezer's related artists ([ArtistTarget.borrowedFrom]), and out-from mode, for an artist the
 * person already loves.
 *
 * One paced MusicBrainz call finds the artist and their tags ([MusicBrainzClient.searchArtistWithTags]); the answer is
 * kept for the session, like the genre candidates.
 */

/** An artist resolved for a ladder: who they are, the genre to climb into, and their top genres for the screen. */
@Serializable
internal data class ArtistTarget(
    val artist: LadderCandidate,
    /** Readable name of the primary genre, for example "shoegaze". Always a usable genre. */
    val genre: String,
    /** Up to [ARTIST_TARGET_GENRES] genres by votes, the primary first. */
    val genres: List<String>,
    /** Related artists the genres were borrowed from when the artist's own tags gave none; empty when they are the artist's own. */
    val borrowedFrom: List<String> = emptyList(),
)

internal const val ARTIST_TARGET_GENRES = 3

/** An artist's answer stays good for a day, like a genre's candidates; the cache holds this many artists. */
internal const val ARTIST_TARGET_TTL_MS = 24L * 60 * 60 * 1000
internal const val MAX_CACHED_ARTISTS = 48

/**
 * The hit that is the artist the person typed: the one whose name matches ([genreKey] ignores case, spacing and
 * punctuation), else the search's best hit. Null when there are no hits.
 */
internal fun pickArtistHit(query: String, hits: List<LadderCandidate>): LadderCandidate? {
    val key = genreKey(query)
    return hits.firstOrNull { genreKey(it.name) == key } ?: hits.firstOrNull()
}

/** The ladder target for [artist], or null when none of their tags is a usable genre. */
internal fun artistTarget(artist: LadderCandidate, borrowedFrom: List<String> = emptyList()): ArtistTarget? {
    val genres = normalizeWeightedGenres(artist.votes).keys.toList()
    val primary = genres.firstOrNull() ?: return null
    return ArtistTarget(artist, primary, genres.take(ARTIST_TARGET_GENRES), borrowedFrom)
}

internal enum class ArtistLadderFailure {
    /** MusicBrainz knows no artist by that name. */
    NoArtist,

    /** The artist has no usable genre tag, and Deezer's related artists did not give one either. */
    NoGenre,

    /** Out-from mode found no neighbouring genre to lead out into. */
    NoOutward,

    /** A source could not be reached; nothing was cached, so asking again tries again. */
    Unreachable,
}

internal sealed interface ArtistLookup {
    data class Found(val target: ArtistTarget) : ArtistLookup
    data class Failed(val reason: ArtistLadderFailure) : ArtistLookup
}

internal sealed interface ArtistLadderOutcome {
    @Serializable
    data class Ready(
        val target: ArtistTarget,
        val ladder: Ladder,
        val mode: LadderMode = LadderMode.Into,
        /** Out-from only: the genre the ladder leads out into, as named on screen. */
        val destination: String? = null,
        /** Out-from only: other genres the person could lead out into instead. */
        val alternatives: List<String> = emptyList(),
    ) : ArtistLadderOutcome
    data class Failed(val reason: ArtistLadderFailure) : ArtistLadderOutcome
}

/**
 * The ladder into [target]: [buildLadder] on the primary genre over [pool], without the artist themself (matched by
 * id and by name). An empty ladder is a real answer (a pool with none of the genre).
 */
internal fun buildArtistLadder(
    target: ArtistTarget,
    artists: List<LibraryArtist>,
    pool: List<LadderCandidate>,
    dial: Double = 0.0,
    rungs: Int = LADDER_RUNGS,
): Ladder {
    val ownKey = genreKey(target.artist.name)
    val rest = pool.filter { it.id != target.artist.id && genreKey(it.name) != ownKey }
    return buildLadder(target.genre, artists, rest, dial, rungs)
}

/**
 * Finds artists by name and caches the answer by [genreKey] of the typed name. [fetch] answers null when the source
 * could not be reached: that is never cached. "No such artist" and "no usable genre" are cached for the day.
 *
 * An artist with no usable tag asks [relatedTags] (when given) for the tagged artists related to them and borrows
 * their genres ([borrowGenres]); it answers null when its sources could not be reached, which is never cached.
 */
internal class ArtistTargetResolver(
    private val now: () -> Long = { LibraryClock.nowEpochMs() },
    private val relatedTags: (suspend (artist: LadderCandidate) -> List<LadderCandidate>?)? = null,
    private val fetch: suspend (name: String) -> List<LadderCandidate>?,
) {
    private class Entry(val lookup: ArtistLookup, val expiresAt: Long)

    private val gate = Mutex()
    private val entries = LinkedHashMap<String, Entry>()

    suspend fun resolve(query: String): ArtistLookup {
        val name = query.trim()
        val key = genreKey(name)
        if (key.isEmpty()) return ArtistLookup.Failed(ArtistLadderFailure.NoArtist)
        return gate.withLock {
            val cached = entries[key]
            if (cached != null && cached.expiresAt > now()) return@withLock cached.lookup
            entries.remove(key)
            val hits = fetch(name) ?: return@withLock ArtistLookup.Failed(ArtistLadderFailure.Unreachable)
            val lookup = lookupFor(pickArtistHit(name, hits))
            // Nothing is remembered when a source could not be reached, so asking again tries again.
            if (lookup == ArtistLookup.Failed(ArtistLadderFailure.Unreachable)) return@withLock lookup
            entries[key] = Entry(lookup, now() + ARTIST_TARGET_TTL_MS)
            while (entries.size > MAX_CACHED_ARTISTS) entries.remove(entries.keys.first())
            lookup
        }
    }

    private suspend fun lookupFor(hit: LadderCandidate?): ArtistLookup {
        if (hit == null) return ArtistLookup.Failed(ArtistLadderFailure.NoArtist)
        artistTarget(hit)?.let { return ArtistLookup.Found(it) }
        val borrow = relatedTags ?: return ArtistLookup.Failed(ArtistLadderFailure.NoGenre)
        val related = borrow(hit) ?: return ArtistLookup.Failed(ArtistLadderFailure.Unreachable)
        val borrowed = borrowGenres(related) ?: return ArtistLookup.Failed(ArtistLadderFailure.NoGenre)
        return artistTarget(hit.copy(votes = borrowed.votes), borrowed.from)
            ?.let { ArtistLookup.Found(it) }
            ?: ArtistLookup.Failed(ArtistLadderFailure.NoGenre)
    }

    fun clear() {
        entries.clear()
    }
}

/** The session's artist cache over the live MusicBrainz search, and the one call a screen makes. */
internal object LadderArtistTargets {
    private val resolver = ArtistTargetResolver(
        relatedTags = { artist ->
            relatedArtistTags(
                artist,
                related = { name -> DeezerClient.relatedByName(name)?.map { it.name } },
                tagged = { name -> MusicTagSource.searchArtistWithTags(name) },
            )
        },
        fetch = { name -> MusicTagSource.searchArtistWithTags(name) },
    )

    /** Resolves [query] to an artist, fetches the genre's candidates (cached) and builds the ladder into the artist. */
    suspend fun ladderInto(query: String, artists: List<LibraryArtist>, dial: Double = 0.0): ArtistLadderOutcome =
        ladderInto(resolver, { genre -> LadderCandidates.forGenre(genre) }, query, artists, dial)

    /** The one call the ladder screen makes: any [request], through the session's caches. */
    suspend fun load(request: LadderRequest, artists: List<LibraryArtist>, progress: LadderProgress = LadderProgress()): LadderState =
        loadLadder(
            request, artists, resolver, { genre -> LadderCandidates.forGenre(genre) }, progress,
            albumDetails = { artist, title -> LadderAlbumSources.details(artist, title) },
        )

    /**
     * Resolves [query] to an artist the person already loves and builds the ladder leading out from them into
     * [destination] (a neighbouring genre; null picks the artist's second genre).
     */
    suspend fun ladderOutFrom(
        query: String,
        artists: List<LibraryArtist>,
        destination: String? = null,
        dial: Double = 0.0,
    ): ArtistLadderOutcome =
        ladderOutFrom(resolver, { genre -> LadderCandidates.forGenre(genre) }, query, artists, destination, dial)
}

/** [LadderArtistTargets.ladderInto] with its two sources passed in, so the whole path is tested without a network. */
internal suspend fun ladderInto(
    resolver: ArtistTargetResolver,
    candidates: suspend (genre: String) -> List<LadderCandidate>?,
    query: String,
    artists: List<LibraryArtist>,
    dial: Double = 0.0,
    rungs: Int = LADDER_RUNGS,
): ArtistLadderOutcome {
    val target = when (val lookup = resolver.resolve(query)) {
        is ArtistLookup.Failed -> return ArtistLadderOutcome.Failed(lookup.reason)
        is ArtistLookup.Found -> lookup.target
    }
    val pool = candidates(target.genre) ?: return ArtistLadderOutcome.Failed(ArtistLadderFailure.Unreachable)
    return ArtistLadderOutcome.Ready(target, buildArtistLadder(target, artists, pool, dial, rungs))
}
