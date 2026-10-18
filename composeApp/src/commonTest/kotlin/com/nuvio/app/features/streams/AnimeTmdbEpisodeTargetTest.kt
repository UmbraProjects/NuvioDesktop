package com.nuvio.app.features.streams

import com.nuvio.app.features.metadata.MediaIdResolver
import com.nuvio.app.features.tmdb.TmdbSeasonEpisodeCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One Piece is one kitsu entry (`kitsu:12`, every episode "season 1") but 23 TMDB arc seasons.
 * Counts are TMDB's real ones for tv/37854 (2026-10-18), specials excluded.
 */
class AnimeTmdbEpisodeTargetTest {
    private val onePiece = listOf(61, 16, 14, 39, 13, 52, 33, 35, 73, 45, 26, 14, 101, 58, 62, 50, 56, 55, 74, 14, 197, 67, 25)
        .mapIndexed { index, count -> TmdbSeasonEpisodeCount(season = index + 1, episodeCount = count) }

    @Test
    fun absolute_episode_lands_in_its_arc_season() {
        assertEquals(1 to 61, onePiece.locateAbsoluteEpisode(61))
        assertEquals(2 to 1, onePiece.locateAbsoluteEpisode(62))
        // Skypiea (S6) starts at 144; AIOStreams answers `tmdb:37854:6:7` with episode 150.
        assertEquals(6 to 7, onePiece.locateAbsoluteEpisode(150))
        assertEquals(9 to 1, onePiece.locateAbsoluteEpisode(264))
    }

    @Test
    fun episode_past_the_last_season_or_non_positive_is_not_located() {
        assertNull(onePiece.locateAbsoluteEpisode(onePiece.sumOf { it.episodeCount } + 1))
        assertNull(onePiece.locateAbsoluteEpisode(0))
    }

    @Test
    fun entry_without_placement_counts_from_the_first_episode() {
        assertEquals(150, animeEntryAbsoluteEpisode(onePiece, 150, null, null, null, null))
        assertEquals(150, animeEntryAbsoluteEpisode(onePiece, 150, null, null, 1, 0))
    }

    @Test
    fun entry_placed_in_a_later_season_adds_the_seasons_before_it_and_the_offset() {
        // An entry mapped to TMDB S2 with a 4-episode offset: its episode 3 is S2's 7th.
        val absolute = animeEntryAbsoluteEpisode(onePiece, 3, tmdbSeason = 2, tmdbEpisodeOffset = 4, null, null)
        assertEquals(61 + 4 + 3, absolute)
        assertEquals(2 to 7, onePiece.locateAbsoluteEpisode(absolute!!))
    }

    @Test
    fun untrustworthy_placements_are_refused() {
        assertNull(animeEntryAbsoluteEpisode(onePiece, 3, tmdbSeason = 0, null, null, null))
        assertNull(animeEntryAbsoluteEpisode(onePiece, 3, tmdbSeason = 40, null, null, null))
        assertNull(animeEntryAbsoluteEpisode(onePiece, 3, null, null, tvdbSeason = 3, null))
        assertNull(animeEntryAbsoluteEpisode(onePiece, 3, null, null, tvdbSeason = 1, tvdbEpisodeOffset = 12))
    }

    @Test
    fun plugins_get_tmdb_coordinates_for_a_located_native_episode() {
        val identity = onePiece150()
        assertTrue(AnimeTmdbEpisodeLocator.appliesTo(identity))
        val target = identity.pluginEpisodeTarget(
            TmdbEpisodeLocation(tmdbId = 37854, season = 6, seasonIndex = 7, episodeNumber = 150),
        )
        // TMDB's S6 episode endpoint answers to 150, not 7.
        assertEquals(PluginEpisodeTarget(contentId = "37854", season = 6, episode = 150), target)
    }

    @Test
    fun plugins_never_receive_the_kitsu_id_when_tmdb_is_known() {
        val target = onePiece150().pluginEpisodeTarget(location = null)
        assertEquals("37854", target.contentId)
    }

    @Test
    fun ordinary_tmdb_requests_keep_the_existing_plugin_target() {
        val identity = MediaIdResolver.resolveLocalEpisodeIdentity(
            contentType = "series",
            parentMetaId = "tmdb:1399",
            videoId = "tmdb:1399:1:2",
            title = "Game of Thrones",
            season = 1,
            episode = 2,
        )
        assertFalse(AnimeTmdbEpisodeLocator.appliesTo(identity))
        assertEquals(PluginEpisodeTarget("1399", 1, 2), identity.pluginEpisodeTarget(null))
    }

    @Test
    fun summary_rows_are_not_playable() {
        val summary = StreamItem(name = "[Ninja AIO] Scrape Summary", externalUrl = "https://example.invalid", addonName = "AIO", addonId = "aio")
        val playable = summary.copy(externalUrl = null, url = "https://example.invalid/e150.mkv")
        assertFalse(listOf(summary).hasPlayableStream())
        assertTrue(listOf(summary, playable).hasPlayableStream())
    }

    private fun onePiece150() = MediaIdResolver.resolveLocalEpisodeIdentity(
        contentType = "series",
        parentMetaId = "kitsu:12",
        videoId = "kitsu:12:150",
        title = "One Piece",
        season = 1,
        episode = 150,
    )
}
