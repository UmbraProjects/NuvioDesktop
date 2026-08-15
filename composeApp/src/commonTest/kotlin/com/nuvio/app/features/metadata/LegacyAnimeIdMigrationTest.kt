package com.nuvio.app.features.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Real anime-list data:
 * - Mushoku Tensei 3rd Season — kitsu 49002 / imdb tt13293588 / tvdb season 3, no offset
 * - Sword Art Online II — kitsu 8174 / imdb tt2250192 / tvdb season 2
 * - War of Underworld Part II — kitsu 42927 / tvdb season 4, episode offset 12
 * - SAO the Movie: Ordinal Scale — kitsu 11423 / imdb tt5544384 / TMDB movie 413594
 */
class LegacyAnimeIdMigrationTest {

    @Test
    fun `a native preference suppresses the migration entirely`() {
        // Under KITSU the stored native id is not stale — it is what the live derivation produces,
        // so rewriting it to a franchise id would break the matches the migration exists to keep.
        assertNull(migrateLegacyAnimeContentId("kitsu:49002", "series", AnimeIdPreference.KITSU))
        assertNull(migrateLegacyAnimeVideoId("kitsu:49002:1:1", "series", AnimeIdPreference.MAL))
    }

    @Test
    fun `a kitsu content id becomes the franchise id`() {
        assertEquals("tt13293588", migrateLegacyAnimeContentId("kitsu:49002", "series", AnimeIdPreference.IMDB))
        assertEquals("tt5544384", migrateLegacyAnimeContentId("kitsu:11423", "movie", AnimeIdPreference.IMDB))
    }

    @Test
    fun `ids already franchise, or with no franchise mapping, are left alone`() {
        assertNull(migrateLegacyAnimeContentId("tt13293588", "series", AnimeIdPreference.IMDB))
        assertNull(migrateLegacyAnimeContentId("tmdb:94664", "series", AnimeIdPreference.IMDB))
        assertNull(migrateLegacyAnimeContentId("kitsu:99999999", "series", AnimeIdPreference.IMDB))
    }

    @Test
    fun `an entry-local video id gains both the franchise base and its season`() {
        // The case that made this necessary: S1E1 of the third season's own entry is franchise
        // S3E1, not the first season's opener.
        assertEquals("tt13293588:3:1", migrateLegacyAnimeVideoId("kitsu:49002:1:1", "series", AnimeIdPreference.IMDB))
        assertEquals("tt2250192:2:5", migrateLegacyAnimeVideoId("kitsu:8174:1:5", "series", AnimeIdPreference.IMDB))
    }

    @Test
    fun `a split-cour entry has its episode offset applied`() {
        // War of Underworld Part II covers franchise season 4 from episode 13 on.
        assertEquals("tt2250192:4:15", migrateLegacyAnimeVideoId("kitsu:42927:1:3", "series", AnimeIdPreference.IMDB))
    }

    @Test
    fun `coordinates that are already franchise-numbered are not shifted again`() {
        // Season 2 cannot be entry-local (an entry's own season is 1 by definition), so these
        // coordinates came from a franchise meta and must survive untouched.
        assertEquals("tt2250192:2:5", migrateLegacyAnimeVideoId("kitsu:8174:2:5", "series", AnimeIdPreference.IMDB))
    }

    @Test
    fun `a movie video id carries no coordinates`() {
        assertEquals("tt5544384", migrateLegacyAnimeVideoId("kitsu:11423", "movie", AnimeIdPreference.IMDB))
    }

    @Test
    fun `unmigratable video ids return null so the caller keeps the original`() {
        assertNull(migrateLegacyAnimeVideoId("tt2250192:2:5", "series", AnimeIdPreference.IMDB))
        assertNull(migrateLegacyAnimeVideoId("kitsu:99999999:1:1", "series", AnimeIdPreference.IMDB))
    }
}
