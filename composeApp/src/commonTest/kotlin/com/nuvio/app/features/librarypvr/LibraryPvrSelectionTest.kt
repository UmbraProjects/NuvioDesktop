package com.nuvio.app.features.librarypvr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryPvrSelectionTest {
    private val addedAt = 1_700_000_000_000L

    private fun item(
        mode: MonitorMode = MonitorMode.SELECTED_ONLY,
        seasons: Set<Int> = emptySet(),
        overrides: Map<String, Boolean> = emptyMap(),
    ) = MonitoredItem(
        id = "item-1",
        contentId = "tt0108778",
        contentType = "series",
        title = "Friends",
        targetFolderId = "folder-1",
        mode = mode,
        monitoredSeasons = seasons,
        episodeOverrides = overrides,
        addedAtEpochMs = addedAt,
    )

    @Test
    fun aSeasonToggleSelectsEveryEpisodeInIt() {
        val monitored = item().withSeasonMonitored(3, monitored = true)
        assertTrue(monitored.selectsEpisode(3, 1))
        assertTrue(monitored.selectsEpisode(3, 24))
        assertFalse(monitored.selectsEpisode(4, 1))
    }

    @Test
    fun turningASeasonOffAlsoDropsPerEpisodeOptInsInsideIt() {
        val monitored = item()
            .withEpisodeMonitored(3, 5, monitored = true)
            .withEpisodeMonitored(4, 5, monitored = true)
            .withSeasonMonitored(3, monitored = false)

        assertFalse(monitored.selectsEpisode(3, 5))
        // Another season's opt-in is untouched.
        assertTrue(monitored.selectsEpisode(4, 5))
    }

    @Test
    fun anEpisodeCanBeOptedOutOfAMonitoredSeason() {
        val monitored = item()
            .withSeasonMonitored(3, monitored = true)
            .withEpisodeMonitored(3, 13, monitored = false)

        assertTrue(monitored.selectsEpisode(3, 12))
        assertFalse(monitored.selectsEpisode(3, 13))
        assertTrue(monitored.selectsPartOfSeason(3, (1..24).toList()))
        assertFalse(monitored.selectsWholeSeason(3, (1..24).toList()))
    }

    @Test
    fun anEpisodeCanBeOptedIntoAnUnmonitoredSeason() {
        val monitored = item().withEpisodeMonitored(6, 8, monitored = true)
        assertTrue(monitored.selectsEpisode(6, 8))
        assertFalse(monitored.selectsEpisode(6, 7))
    }

    @Test
    fun overridesThatAgreeWithTheSeasonDefaultAreNotStored() {
        // Toggling an episode off and on again inside a monitored season must leave no residue,
        // otherwise the map grows an entry per episode every time a season is cycled.
        val monitored = item()
            .withSeasonMonitored(3, monitored = true)
            .withEpisodeMonitored(3, 13, monitored = false)
            .withEpisodeMonitored(3, 13, monitored = true)

        assertEquals(emptyMap(), monitored.episodeOverrides)
        assertTrue(monitored.selectsEpisode(3, 13))
    }

    @Test
    fun allAndNoneCoverSeasonsTheUserNeverOpened() {
        val all = item().withAllMonitored(listOf(1, 2, 3), monitored = true)
        assertTrue(all.selectsEpisode(1, 1))
        assertTrue(all.selectsEpisode(3, 9))

        val none = all.withAllMonitored(listOf(1, 2, 3), monitored = false)
        assertFalse(none.selectsEpisode(1, 1))
        assertEquals(emptySet(), none.monitoredSeasons)
        assertEquals(emptyMap(), none.episodeOverrides)
    }

    @Test
    fun selectedOnlyIgnoresNewlyAiredEpisodes() {
        val monitored = item(mode = MonitorMode.SELECTED_ONLY, seasons = setOf(1))
        assertTrue(monitored.wantsEpisode(1, 1, addedAt - 1_000L))
        assertFalse(monitored.wantsEpisode(2, 1, addedAt + 1_000L))
    }

    @Test
    fun selectedPlusFutureAddsAnythingAiringAfterTheTitleWasMonitored() {
        val monitored = item(mode = MonitorMode.SELECTED_PLUS_FUTURE, seasons = setOf(1))
        assertTrue(monitored.wantsEpisode(1, 1, addedAt - 1_000L))
        assertTrue(monitored.wantsEpisode(9, 1, addedAt + 1_000L))
        assertFalse(monitored.wantsEpisode(9, 2, addedAt - 1_000L))
    }

    @Test
    fun anUndatedEpisodeIsNotTreatedAsFuture() {
        val monitored = item(mode = MonitorMode.SELECTED_PLUS_FUTURE)
        assertFalse(monitored.wantsEpisode(4, 2, releasedAtEpochMs = null))
    }

    @Test
    fun allMissingIgnoresTheSelectionEntirely() {
        val monitored = item(mode = MonitorMode.ALL_MISSING)
        assertTrue(monitored.wantsEpisode(7, 3, addedAt - 1_000L))
    }
}
