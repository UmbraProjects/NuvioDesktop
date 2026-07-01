package com.nuvio.app.features.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Native anime ids address episodes as `prefix:id:absoluteEpisode` (e.g. `kitsu:8174:1`).
 * Anime stream providers return "500 - Internal Server Error" for the 3-part
 * `prefix:id:season:episode` form, so the resolver must never carry a season segment for
 * these ids. These tests lock the parsing that builds the stream lookup id.
 */
class NativeAnimeStreamIdTest {

    @Test
    fun episode_is_trailing_segment_for_two_part_kitsu_id() {
        // AIOMetadata emits SAO II episode 1 as this id directly.
        assertEquals(1, "kitsu:8174:1".nativeAnimeEpisode())
        assertEquals(24, "kitsu:8174:24".nativeAnimeEpisode())
    }

    @Test
    fun episode_is_trailing_segment_even_when_a_stray_season_is_present() {
        // SIMKL continue-watching builds `showId:season:episode` -> `kitsu:8174:1:1`.
        assertEquals(1, "kitsu:8174:1:1".nativeAnimeEpisode())
        assertEquals(7, "mal:21881:2:7".nativeAnimeEpisode())
    }

    @Test
    fun parent_id_without_an_episode_has_no_episode() {
        assertNull("kitsu:8174".nativeAnimeEpisode())
        assertNull("simkl:46206".nativeAnimeEpisode())
    }

    @Test
    fun base_drops_episode_and_season_segments() {
        assertEquals("kitsu:8174", "kitsu:8174:1".nativeAnimeBase())
        assertEquals("kitsu:8174", "kitsu:8174:1:1".nativeAnimeBase())
        assertEquals("kitsu:8174", "kitsu:8174".nativeAnimeBase())
    }
}
