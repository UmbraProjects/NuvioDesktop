package com.nuvio.app.features.librarypvr

import com.nuvio.app.features.locallibrary.LocalFolderType
import com.nuvio.app.features.locallibrary.LocalMediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The identity preference decides which id a title is addressed by, and it can change after a
 * monitor is written. These assert that a monitor still recognises its own local files and its own
 * downloads across that change, in either direction — which no migration can guarantee, because
 * franchise → native is ambiguous.
 */
class MonitoredIdMatchingTest {

    private val monitoredUnderFranchiseId = MonitoredItem(
        id = "monitor-1",
        contentId = "tt13293588",
        contentType = "series",
        tmdbId = 94664,
        imdbId = "tt13293588",
        kitsuId = 49002,
        malId = 59193,
        isAnime = true,
        title = "Mushoku Tensei: Jobless Reincarnation",
        targetFolderId = "folder",
        mode = MonitorMode.ALL_MISSING,
        addedAtEpochMs = 1L,
    )

    private val localItem = LocalMediaItem(
        key = "folder:mushoku-tensei",
        folderId = "folder",
        type = LocalFolderType.SERIES,
        isAnime = true,
        title = "Mushoku Tensei: Jobless Reincarnation",
        imdbId = "tt13293588",
        tmdbId = 94664,
        kitsuId = 49002,
        malId = 59193,
    )

    @Test
    fun `a monitor written under a franchise id still matches its local item under a native one`() {
        assertTrue(localItem.matchesMonitored(monitoredUnderFranchiseId))
    }

    @Test
    fun `a monitor written under a native id still matches its local item`() {
        val monitoredUnderKitsu = monitoredUnderFranchiseId.copy(contentId = "kitsu:49002")

        assertTrue(localItem.matchesMonitored(monitoredUnderKitsu))
    }

    @Test
    fun `downloads are recognised whichever id they were created under`() {
        assertTrue(monitoredUnderFranchiseId.matchesContentId("tt13293588"))
        assertTrue(monitoredUnderFranchiseId.matchesContentId("kitsu:49002"))
        assertTrue(monitoredUnderFranchiseId.matchesContentId("mal:59193"))
        assertTrue(monitoredUnderFranchiseId.matchesContentId("tmdb:94664"))
    }

    @Test
    fun `an unrelated title never matches`() {
        val other = localItem.copy(
            key = "folder:sao",
            imdbId = "tt2250192",
            tmdbId = 45782,
            kitsuId = 8174,
            malId = 21881,
        )

        assertFalse(other.matchesMonitored(monitoredUnderFranchiseId))
        assertFalse(monitoredUnderFranchiseId.matchesContentId("kitsu:8174"))
        assertFalse(monitoredUnderFranchiseId.matchesContentId(""))
    }

    @Test
    fun `lookup by any known id finds the monitor`() {
        val state = LibraryPvrUiState(monitoredItems = listOf(monitoredUnderFranchiseId))

        assertTrue(state.isMonitored("tt13293588"))
        assertTrue(state.isMonitored("kitsu:49002"))
        assertFalse(state.isMonitored("kitsu:8174"))
    }

    // --- Franchise collisions ---
    //
    // Season 2 is its own Kitsu/MAL entry but shares the franchise's IMDb and TMDB ids, so both
    // seasons answer to them. The loose match is meant to return both; anything that must act on one
    // title has to say which, and anything destructive has to refuse when it cannot.

    private val secondSeasonLocalItem = localItem.copy(
        key = "folder:mushoku-tensei-s2",
        kitsuId = 44039,
        malId = 48316,
    )

    @Test
    fun `sibling seasons sharing a franchise id both match loosely`() {
        assertTrue(localItem.matchesMonitored(monitoredUnderFranchiseId))
        assertTrue(secondSeasonLocalItem.matchesMonitored(monitoredUnderFranchiseId))
    }

    @Test
    fun `a native id outranks the franchise id and resolves to the right season`() {
        val monitoringSecondSeason = monitoredUnderFranchiseId.copy(
            id = "monitor-2",
            contentId = "kitsu:44039",
            kitsuId = 44039,
            malId = 48316,
        )
        val items = listOf(localItem, secondSeasonLocalItem)

        assertEquals(MonitorIdMatch.NATIVE, secondSeasonLocalItem.matchStrengthFor(monitoringSecondSeason))
        assertEquals(MonitorIdMatch.FRANCHISE, localItem.matchStrengthFor(monitoringSecondSeason))
        assertEquals(secondSeasonLocalItem, items.resolveMonitoredExclusively(monitoringSecondSeason))
        assertEquals(listOf(secondSeasonLocalItem), items.matchingMonitored(monitoringSecondSeason))
    }

    @Test
    fun `an ambiguous franchise match resolves to nothing rather than to a sibling`() {
        // The monitor carries no native id, so both seasons tie at FRANCHISE. Deleting either would
        // be a guess, and the guess deletes a file the user still has.
        val franchiseOnly = monitoredUnderFranchiseId.copy(kitsuId = null, malId = null)
        val items = listOf(localItem, secondSeasonLocalItem)

        assertNull(items.resolveMonitoredExclusively(franchiseOnly))
        // Availability maths keeps both: over-counting only skips a re-download.
        assertEquals(items, items.matchingMonitored(franchiseOnly))
    }

    @Test
    fun `a unique franchise match is still resolvable`() {
        val franchiseOnly = monitoredUnderFranchiseId.copy(kitsuId = null, malId = null)

        assertEquals(localItem, listOf(localItem).resolveMonitoredExclusively(franchiseOnly))
    }

    @Test
    fun `monitors are ranked so the exact id beats a franchise sibling`() {
        val franchiseSibling = monitoredUnderFranchiseId.copy(
            id = "monitor-2",
            contentId = "kitsu:44039",
            kitsuId = 44039,
            malId = 48316,
        )
        val ranked = listOf(franchiseSibling, monitoredUnderFranchiseId)
            .rankedForContentId("tt13293588")

        assertEquals(listOf("monitor-1", "monitor-2"), ranked.map { it.id })
    }

    @Test
    fun `nothing matches an unrelated id`() {
        assertNull(localItem.matchStrengthFor(monitoredUnderFranchiseId.copy(
            imdbId = "tt2250192",
            tmdbId = 45782,
            kitsuId = 8174,
            malId = 21881,
            contentId = "tt2250192",
        )))
        assertTrue(emptyList<LocalMediaItem>().matchingMonitored(monitoredUnderFranchiseId).isEmpty())
        assertNull(emptyList<LocalMediaItem>().resolveMonitoredExclusively(monitoredUnderFranchiseId))
    }
}
