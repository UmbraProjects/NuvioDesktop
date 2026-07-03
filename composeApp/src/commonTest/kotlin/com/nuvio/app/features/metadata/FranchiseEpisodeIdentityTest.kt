package com.nuvio.app.features.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Kitsu catalogs return one result per franchise season (SAO, SAO II, Alicization… are
 * separate kitsu ids sharing one TVDB show), while franchise-style metas show every season
 * under whichever entry was opened. These tests lock the franchise remap: a season/episode
 * picked under one entry must resolve streams and canonical numbers via the sibling entry
 * that actually covers that season. Backed by the real anime-list-mini.json (SAO family,
 * tvdb 259640: kitsu 6589 = S1, 8174 = S2, 13893 = S3, 42213 = S4 E1-12,
 * 42927 = S4 part II with episode_offset 12).
 */
class FranchiseEpisodeIdentityTest {

    @Test
    fun season_pick_under_another_entry_resolves_the_sibling_entry_stream_id() {
        val resolved = MediaIdResolver.resolveLocalEpisodeIdentity(
            contentType = "series",
            parentMetaId = "kitsu:8174", // opened the SAO II catalog result
            videoId = "kitsu:8174:3:2",  // picked franchise S3E2 in the seasons view
            title = "Sword Art Online",
            season = 3,
            episode = 2,
        )
        assertEquals("kitsu:13893:2", resolved.videoId)
        assertNull(resolved.streamSeason)
        assertEquals(2, resolved.streamEpisode)
        assertEquals(3, resolved.season)
        assertEquals(2, resolved.episode)
    }

    @Test
    fun split_cour_season_uses_episode_offset_to_pick_the_part() {
        val resolved = MediaIdResolver.resolveLocalEpisodeIdentity(
            contentType = "series",
            parentMetaId = "kitsu:8174",
            videoId = "kitsu:8174:4:15", // S4E15 = War of Underworld Part II episode 3
            title = "Sword Art Online",
            season = 4,
            episode = 15,
        )
        assertEquals("kitsu:42927:3", resolved.videoId)
        assertEquals(3, resolved.streamEpisode)
        assertEquals(4, resolved.season)
        assertEquals(15, resolved.episode)
    }

    @Test
    fun entry_local_kitsu_addon_numbering_is_untouched() {
        // Kitsu-addon metas list only the opened entry's episodes, always as season 1 —
        // that regime must keep the entry id and map season 1 to the franchise season.
        val resolved = MediaIdResolver.resolveLocalEpisodeIdentity(
            contentType = "series",
            parentMetaId = "kitsu:8174",
            videoId = "kitsu:8174:5",
            title = "Sword Art Online II",
            season = 1,
            episode = 5,
        )
        assertEquals("kitsu:8174:5", resolved.videoId)
        assertEquals(5, resolved.streamEpisode)
        assertEquals(2, resolved.season)
        assertEquals(5, resolved.episode)
    }

    @Test
    fun re_resolving_an_already_remapped_stream_id_is_idempotent() {
        // StreamsRepository.requestToken re-resolves the effective id produced by load() —
        // the sibling entry's id must survive a second pass under the original parent.
        val resolved = MediaIdResolver.resolveLocalEpisodeIdentity(
            contentType = "series",
            parentMetaId = "kitsu:8174",
            videoId = "kitsu:13893:2",
            title = "Sword Art Online",
            season = null,
            episode = 2,
        )
        assertEquals("kitsu:13893:2", resolved.videoId)
        assertEquals(2, resolved.streamEpisode)
    }

    @Test
    fun season_one_launch_with_remapped_stream_id_keeps_season_one_canonical_numbers() {
        // Reported bug: opening SAO II (kitsu:8174) and playing franchise S1E1 launched with
        // the remapped stream id kitsu:6589:1 — season 1 was then misread as entry-local and
        // renamed to the parent entry's season ("S02E01 - The World of Swords", scrobbled S2).
        // A video id addressing a different entry than the parent proves franchise numbering.
        val resolved = MediaIdResolver.resolveLocalEpisodeIdentity(
            contentType = "series",
            parentMetaId = "kitsu:8174",
            videoId = "kitsu:6589:1",
            title = "Sword Art Online",
            season = 1,
            episode = 1,
        )
        assertEquals("kitsu:6589:1", resolved.videoId)
        assertEquals(1, resolved.streamEpisode)
        assertEquals(1, resolved.season)
        assertEquals(1, resolved.episode)
    }

    @Test
    fun remapped_stream_id_with_franchise_params_keeps_franchise_canonical_numbers() {
        // App.kt resolves the launch with the already-remapped streamVideoId plus the
        // franchise season/episode the user picked — the entry's offset must not be re-added.
        val resolved = MediaIdResolver.resolveLocalEpisodeIdentity(
            contentType = "series",
            parentMetaId = "kitsu:8174",
            videoId = "kitsu:42927:3",
            title = "Sword Art Online",
            season = 4,
            episode = 15,
        )
        assertEquals("kitsu:42927:3", resolved.videoId)
        assertEquals(3, resolved.streamEpisode)
        assertEquals(4, resolved.season)
        assertEquals(15, resolved.episode)
    }
}
