package com.nuvio.app.features.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Locks the metadata-only escape hatch for native anime movie ids. Real anime-list data: SAO the
 * Movie: Ordinal Scale is kitsu 11423 / mal 31765 / simkl 527702 / TMDB movie 413594, and its
 * franchise siblings (SAO S1 kitsu 6589, SAO II kitsu 8174) are TV entries sharing TMDB tv 45782.
 */
class AnimeMovieTmdbFallbackTest {

    @Test
    fun anime_movie_ids_resolve_to_their_mapped_tmdb_movie_id() {
        assertEquals("tmdb:413594", "kitsu:11423".animeMovieTmdbFallbackId())
        assertEquals("tmdb:413594", "mal:31765".animeMovieTmdbFallbackId())
        assertEquals("tmdb:413594", "anilist:21403".animeMovieTmdbFallbackId())
    }

    @Test
    fun a_simkl_only_playback_id_resolves_too() {
        // The shape SIMKL Continue Watching produces when the payload carries nothing but its own
        // id — the case that left these cards with no artwork on a TMDB-based setup.
        assertEquals("tmdb:413594", "simkl:527702".animeMovieTmdbFallbackId())
    }

    @Test
    fun trailing_segments_are_ignored() {
        assertEquals("tmdb:413594", "kitsu:11423:1".animeMovieTmdbFallbackId())
        assertEquals("tmdb:413594", "kitsu:11423:1:1".animeMovieTmdbFallbackId())
    }

    @Test
    fun series_entries_never_borrow_a_tmdb_id() {
        // Both are TV entries mapped to the franchise's TMDB tv id, which is a different namespace
        // and covers every season — substituting it would return another season's art and episodes.
        assertNull("kitsu:6589".animeMovieTmdbFallbackId())
        assertNull("kitsu:8174".animeMovieTmdbFallbackId())
    }

    @Test
    fun non_anime_and_unmapped_ids_are_rejected() {
        assertNull("tt5544384".animeMovieTmdbFallbackId())
        assertNull("tmdb:413594".animeMovieTmdbFallbackId())
        assertNull("tvdb:259640".animeMovieTmdbFallbackId())
        assertNull("kitsu:999999999".animeMovieTmdbFallbackId())
        assertNull("kitsu:".animeMovieTmdbFallbackId())
    }
}
