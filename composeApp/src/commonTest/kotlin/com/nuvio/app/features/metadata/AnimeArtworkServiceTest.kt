package com.nuvio.app.features.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the id resolution feeding the per-season anime artwork lookup (AniList banner →
 * Kitsu cover). Uses real anime-list data: SAO II is kitsu 8174 / anilist 20594 / mal 21881.
 */
class AnimeArtworkServiceTest {

    @Test
    fun kitsu_id_gains_its_anilist_sibling_from_the_anime_list() {
        val ids = AnimeArtworkService.animeArtworkLookupIds("kitsu:8174")
        assertEquals(8174, ids?.kitsu)
        assertEquals(20594, ids?.anilist)
    }

    @Test
    fun episode_segments_are_ignored() {
        val ids = AnimeArtworkService.animeArtworkLookupIds("kitsu:8174:3:2")
        assertEquals(8174, ids?.kitsu)
    }

    @Test
    fun mal_id_resolves_both_artwork_namespaces_via_the_anime_list() {
        val ids = AnimeArtworkService.animeArtworkLookupIds("mal:21881")
        assertEquals(8174, ids?.kitsu)
        assertEquals(20594, ids?.anilist)
    }

    @Test
    fun non_anime_ids_are_rejected() {
        assertNull(AnimeArtworkService.animeArtworkLookupIds("tt2250192"))
        assertNull(AnimeArtworkService.animeArtworkLookupIds("tmdb:45782"))
        assertNull(AnimeArtworkService.animeArtworkLookupIds("simkl:527702"))
    }

    @Test
    fun franchise_anchor_and_movies_keep_tmdb_art() {
        // SAO S1 (kitsu 6589, mapped tvdb season 1, no offset): TMDB franchise art matches.
        assertTrue(AnimeArtworkService.usesFranchiseArt("kitsu:6589"))
        // Ordinal Scale (kitsu 11423): has its own TMDB movie id — TMDB art is entry-exact.
        assertTrue(AnimeArtworkService.usesFranchiseArt("kitsu:11423"))
    }

    @Test
    fun sequels_specials_and_later_cours_use_per_season_art() {
        // SAO II = franchise season 2.
        assertTrue(!AnimeArtworkService.usesFranchiseArt("kitsu:8174"))
        // War of Underworld Part II = season 4 with episode offset 12.
        assertTrue(!AnimeArtworkService.usesFranchiseArt("kitsu:42927"))
        // Extra Edition = specials (season 0, offset 9) with no TMDB movie id of its own.
        assertTrue(!AnimeArtworkService.usesFranchiseArt("kitsu:7914"))
        // Non-native ids never take the anime art path at all.
        assertTrue(!AnimeArtworkService.usesFranchiseArt("tt2250192"))
    }

    @Test
    fun season_art_url_detection_matches_provider_cdns_only() {
        assertTrue("https://s4.anilist.co/file/anilistcdn/media/anime/banner/20594-BZOLwqidcS1G.jpg".isAnimeSeasonArtUrl())
        assertTrue("https://media.kitsu.app/anime/cover_images/8174/original.jpg".isAnimeSeasonArtUrl())
        assertTrue(!"https://image.tmdb.org/t/p/original/abc.jpg".isAnimeSeasonArtUrl())
        assertTrue(!(null as String?).isAnimeSeasonArtUrl())
    }
}
