package com.nuvio.app.features.music.library

import com.nuvio.app.features.library.LibraryClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Ladder phase 3: where a genre's candidate artists come from.
 *
 * One MusicBrainz tag search gives about 100 artists with their tag votes ([MusicBrainzClient.searchByTag], one
 * paced call). The answer is kept for the session so opening the same ladder again, or nudging the dial, costs no
 * call. The cache is a plain class with an injected clock and fetch, so the rules are unit-tested without a network.
 */

/** A genre's candidates stay good for a day: tags move slowly and the rate limit is tight. */
internal const val CANDIDATE_TTL_MS = 24L * 60 * 60 * 1000

/** Fewer than this many candidates is a thin answer: it is kept only briefly, then asked again. */
internal const val THIN_CANDIDATE_COUNT = 5
internal const val THIN_CANDIDATE_TTL_MS = 60L * 60 * 1000

/** Genres kept at once; the oldest answer is dropped past this. */
internal const val MAX_CACHED_GENRES = 24

/**
 * Candidates for a genre, cached by [genreKey] so "post punk" and "Post-Punk" share one entry. [fetch] is given the
 * readable genre name and answers null when the source could not be reached: that is never cached, so the next
 * try asks again. An empty or thin answer is cached for [THIN_CANDIDATE_TTL_MS] only.
 */
internal class LadderCandidateCache(
    private val now: () -> Long = { LibraryClock.nowEpochMs() },
    private val fetch: suspend (genre: String) -> List<LadderCandidate>?,
) {
    private class Entry(val candidates: List<LadderCandidate>, val expiresAt: Long)

    private val gate = Mutex()
    private val entries = LinkedHashMap<String, Entry>()

    /**
     * The candidates tagged with [rawGenre] (what the person typed: "DnB" and "drum and bass" are one genre), or an
     * empty list when it is not a genre or nobody carries it. Null when the source could not be reached.
     */
    suspend fun candidates(rawGenre: String): List<LadderCandidate>? {
        val genre = canonicalGenre(rawGenre) ?: return emptyList()
        val key = genreKey(genre)
        // The lock is held across the fetch on purpose: two screens asking for one genre make one call, and the
        // client paces calls anyway.
        return gate.withLock {
            val cached = entries[key]
            if (cached != null && cached.expiresAt > now()) return@withLock cached.candidates
            entries.remove(key)
            val fresh = fetch(genre) ?: return@withLock null
            val ttl = if (fresh.size < THIN_CANDIDATE_COUNT) THIN_CANDIDATE_TTL_MS else CANDIDATE_TTL_MS
            entries[key] = Entry(fresh, now() + ttl)
            while (entries.size > MAX_CACHED_GENRES) entries.remove(entries.keys.first())
            fresh
        }
    }

    fun clear() {
        entries.clear()
    }
}

/** The session's cache, backed by the live tag search (Last.fm when set up, else MusicBrainz; see [MusicTagSource]). */
internal object LadderCandidates {
    private val cache = LadderCandidateCache { genre -> MusicTagSource.searchByTag(genre) }

    /** See [LadderCandidateCache.candidates]. */
    suspend fun forGenre(rawGenre: String): List<LadderCandidate>? = cache.candidates(rawGenre)
}
