package com.nuvio.app.features.watched

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchedRepositoryTest {
    @Test
    fun unmarkDeleteTargets_include_items_with_no_local_row() {
        // The SIMKL watching-list marker renders an episode as watched without a local row. The
        // unmark must still reach the provider, or the tick can never be cleared.
        val requested = WatchedItem(
            id = "tt27497393",
            type = "series",
            name = "",
            season = 1,
            episode = 1,
            markedAtEpochMs = 0L,
        )

        assertEquals(
            listOf(requested),
            watchedUnmarkDeleteTargets(listOf(requested), removedByKey = emptyMap()),
        )
    }

    @Test
    fun unmarkDeleteTargets_prefer_the_stored_row_and_deduplicate() {
        val requested = WatchedItem(
            id = "tt1",
            type = "series",
            name = "",
            season = 1,
            episode = 1,
            markedAtEpochMs = 0L,
        )
        val stored = requested.copy(name = "Pilot", releaseInfo = "2026")
        val key = watchedItemKey(type = "series", id = "tt1", season = 1, episode = 1)

        assertEquals(
            listOf(stored),
            watchedUnmarkDeleteTargets(
                requestedItems = listOf(requested, requested),
                removedByKey = mapOf(key to stored),
            ),
        )
    }

    @Test
    fun watchedItemKey_isTypeAware() {
        assertEquals("movie:tt1:-1:-1", watchedItemKey(type = "movie", id = "tt1"))
    }

    @Test
    fun watchedItemKey_trimsValues() {
        assertEquals("series:abc:-1:-1", watchedItemKey(type = " series ", id = " abc "))
    }

    @Test
    fun watchedItemKey_includes_episode_coordinates() {
        assertEquals(
            "series:show:2:5",
            watchedItemKey(type = "series", id = "show", season = 2, episode = 5),
        )
    }

    @Test
    fun episodeProgressIds_cover_canonical_and_addon_video_ids() {
        val meta = MetaDetails(
            id = "show",
            type = "series",
            name = "Show",
        )
        val episode = MetaVideo(
            id = "addon-episode-2",
            title = "Episode 2",
            season = 1,
            episode = 2,
        )

        assertEquals(
            listOf("show:1:2", "addon-episode-2"),
            meta.episodePlaybackIds(episode),
        )
    }

    @Test
    fun inferred_episode_coordinates_use_the_same_watched_key_as_playback() {
        val meta = MetaDetails(
            id = "show",
            type = "series",
            name = "Show",
        )
        val episode = MetaVideo(
            id = "show-s02e02",
            title = "Episode 2",
        )

        val watchedItem = meta.toEpisodeWatchedItem(episode)

        assertEquals(2, watchedItem.season)
        assertEquals(2, watchedItem.episode)
        assertEquals(
            "series:show:2:2",
            watchedItemKey(
                type = watchedItem.type,
                id = watchedItem.id,
                season = watchedItem.season,
                episode = watchedItem.episode,
            ),
        )
    }

    @Test
    fun fullyWatchedSeries_ignores_specials() {
        val meta = MetaDetails(
            id = "show",
            type = "series",
            name = "Show",
            videos = listOf(
                MetaVideo(id = "special", title = "Special", season = 0, episode = 1, released = "2026-03-01"),
                MetaVideo(id = "ep1", title = "Episode 1", season = 1, episode = 1, released = "2026-03-08"),
                MetaVideo(id = "ep2", title = "Episode 2", season = 1, episode = 2, released = "2026-03-15"),
            ),
        )

        val result = meta.hasWatchedAllMainSeasonEpisodes(todayIsoDate = "2026-03-30") { episode ->
            episode.season == 1
        }

        assertTrue(result)
    }

    @Test
    fun showLevelWatchedAction_excludesUnreleasedEpisodes() {
        val released = MetaVideo(
            id = "released",
            title = "Released",
            season = 1,
            episode = 1,
            released = "2026-03-01",
        )
        val unreleased = MetaVideo(
            id = "unreleased",
            title = "Unreleased",
            season = 1,
            episode = 2,
            released = "2026-04-01",
        )
        val meta = MetaDetails(
            id = "show",
            type = "series",
            name = "Show",
            videos = listOf(released, unreleased),
        )

        assertEquals(
            listOf(released),
            meta.releasedMainSeasonEpisodes(todayIsoDate = "2026-03-15"),
        )
    }

    @Test
    fun mergeWatchedItemsPreservingUnsynced_keeps_local_items_marked_after_last_push() {
        val serverItem = WatchedItem(
            id = "show",
            type = "series",
            name = "Episode 1",
            season = 1,
            episode = 1,
            markedAtEpochMs = 1_000L,
        )
        val unsyncedLocalItem = WatchedItem(
            id = "show",
            type = "series",
            name = "Episode 2",
            season = 1,
            episode = 2,
            markedAtEpochMs = 3_000L,
        )

        val merged = mergeWatchedItemsPreservingUnsynced(
            serverItems = listOf(serverItem),
            localItems = listOf(serverItem, unsyncedLocalItem),
            lastSuccessfulPushEpochMs = 2_000L,
            pullStartedEpochMs = 4_000L,
        )

        assertEquals(
            setOf("series:show:1:1", "series:show:1:2"),
            merged.keys,
        )
    }

    @Test
    fun mergeWatchedItemsPreservingUnsynced_drops_old_local_items_missing_from_server() {
        val oldLocalItem = WatchedItem(
            id = "show",
            type = "series",
            name = "Episode 1",
            season = 1,
            episode = 1,
            markedAtEpochMs = 1_000L,
        )

        val merged = mergeWatchedItemsPreservingUnsynced(
            serverItems = emptyList(),
            localItems = listOf(oldLocalItem),
            lastSuccessfulPushEpochMs = 2_000L,
            pullStartedEpochMs = 4_000L,
        )

        assertTrue(merged.isEmpty())
    }

    @Test
    fun onlyManualWatchedMarks_writeSelectedLibraryHistory() {
        assertFalse(shouldWriteSelectedLibraryHistory(WatchedRemoteSync.Skip))
        assertTrue(shouldWriteSelectedLibraryHistory(WatchedRemoteSync.Manual))
    }

    @Test
    fun additiveProviderMerge_neverDeletesItemsMissingFromProvider() {
        val local = WatchedItem("show", "series", "Show", season = 1, episode = 1, markedAtEpochMs = 1L)
        val remote = local.copy(episode = 2, markedAtEpochMs = 2L)

        val merged = mergeWatchedItemsAdditively(listOf(local), listOf(remote))

        assertEquals(setOf("series:show:1:1", "series:show:1:2"), merged.keys)
    }

    @Test
    fun additiveProviderMerge_keepsNewestTimestampForDuplicateEpisode() {
        val local = WatchedItem("show", "series", "Local", season = 1, episode = 1, markedAtEpochMs = 5L)
        val olderRemote = local.copy(name = "Remote", markedAtEpochMs = 2L)

        val merged = mergeWatchedItemsAdditively(listOf(local), listOf(olderRemote))

        assertEquals("Local", merged.getValue("series:show:1:1").name)
    }

    @Test
    fun remoteEpisodeBatch_dropsDerivedSeriesMarker() {
        val series = WatchedItem(
            id = "show",
            type = "series",
            name = "Show",
            markedAtEpochMs = 1L,
        )
        val releasedEpisode = series.copy(name = "Episode 1", season = 1, episode = 1)

        assertEquals(
            listOf(releasedEpisode),
            listOf(series, releasedEpisode).withoutDerivedSeriesMarkers(),
        )
    }

    @Test
    fun remoteMarkerFilter_preservesStandaloneTitles() {
        val movie = WatchedItem(
            id = "movie",
            type = "movie",
            name = "Movie",
            markedAtEpochMs = 1L,
        )
        val series = WatchedItem(
            id = "show",
            type = "series",
            name = "Show",
            markedAtEpochMs = 1L,
        )

        assertEquals(listOf(movie, series), listOf(movie, series).withoutDerivedSeriesMarkers())
    }
}
