package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EpisodeRangeGroupsTest {
    private fun episodes(season: Int, count: Int, idPrefix: String = "kitsu:12") =
        (1..count).map { episode -> MetaVideo(id = "$idPrefix:$episode", title = "Episode $episode", season = season, episode = episode) }

    private fun grouped(vararg seasons: Pair<Int, List<MetaVideo>>): Map<Int, List<MetaVideo>> =
        linkedMapOf(*seasons)

    @Test
    fun ordinary_seasons_are_returned_untouched() {
        val map = grouped(1 to episodes(1, 24), 2 to episodes(2, 100))
        assertSame(map, map.withEpisodeRanges())
    }

    @Test
    fun a_long_season_becomes_ranges_of_one_hundred() {
        // One Piece as AIOMetadata lists it: 1410 videos, all season 1.
        val ranges = grouped(1 to episodes(1, 1410)).withEpisodeRanges()
        assertEquals(15, ranges.size)
        val keys = ranges.keys.sortedBy(::episodeGroupSortKey)
        assertTrue(keys.all { it.isEpisodeRangeKey() && it.episodeGroupSeason() == 1 })
        assertEquals("1–100", ranges.episodeRangeLabel(keys.first()))
        assertEquals("101–200", ranges.episodeRangeLabel(keys[1]))
        assertEquals("1401–1410", ranges.episodeRangeLabel(keys.last()))
        assertEquals(1410, ranges.values.sumOf { it.size })
    }

    @Test
    fun ranges_sort_in_place_of_their_season_and_before_specials() {
        val map = grouped(
            0 to episodes(0, 5, "sp"),
            1 to episodes(1, 24, "s1"),
            2 to episodes(2, 250, "s2"),
            3 to episodes(3, 12, "s3"),
        ).withEpisodeRanges()
        val ordered = map.keys.sortedBy(::episodeGroupSortKey).map { key ->
            map.episodeRangeLabel(key)?.let { "2:$it" } ?: key.toString()
        }
        assertEquals(listOf("1", "2:1–100", "2:101–200", "2:201–250", "3", "0"), ordered)
    }

    @Test
    fun the_group_for_an_episode_is_the_range_holding_it() {
        val map = grouped(1 to episodes(1, 1410)).withEpisodeRanges()
        assertEquals("101–200", map.episodeRangeLabel(map.episodeGroupKeyFor(1, 150)!!))
        // Unknown episode: the season's first range.
        assertEquals("1–100", map.episodeRangeLabel(map.episodeGroupKeyFor(1, null)!!))
        // Seasonless up-next (absolute anime): found by episode alone.
        assertEquals("901–1000", map.episodeRangeLabel(map.episodeGroupKeyFor(null, 950)!!))
        assertNull(map.episodeGroupKeyFor(null, null))
        assertNull(map.episodeGroupKeyFor(4, 1))
    }

    @Test
    fun unsplit_seasons_resolve_to_themselves() {
        val map = grouped(1 to episodes(1, 24)).withEpisodeRanges()
        assertEquals(1, map.episodeGroupKeyFor(1, 3))
        assertFalse(1.isEpisodeRangeKey())
        assertEquals(1, 1.episodeGroupSeason())
        assertNull(map.episodeRangeLabel(1))
    }

    @Test
    fun specials_are_never_split() {
        val map = grouped(0 to episodes(0, 300, "sp")).withEpisodeRanges()
        assertEquals(setOf(0), map.keys)
    }
}
