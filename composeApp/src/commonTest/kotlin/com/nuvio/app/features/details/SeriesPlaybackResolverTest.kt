package com.nuvio.app.features.details

import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watching.domain.WatchingContentRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SeriesPlaybackResolverTest {
    @Test
    fun preferredSeriesEpisode_treats_seasonless_anime_as_season_one() {
        val meta = MetaDetails(
            id = "kitsu:486",
            type = "series",
            name = "Pokemon",
            videos = listOf(
                MetaVideo(id = "kitsu:486:1", title = "One", episode = 1),
                MetaVideo(id = "kitsu:486:61", title = "Sixty-one", episode = 61),
            ),
        )
        val action = SeriesPrimaryAction(
            label = "Resume",
            videoId = "kitsu:486:1:61",
            seasonNumber = 1,
            episodeNumber = 61,
            episodeTitle = null,
            episodeThumbnail = null,
            resumePositionMs = null,
        )

        val preferred = meta.preferredSeriesEpisode(action, entries = emptyList())

        assertNotNull(preferred)
        assertEquals("kitsu:486:61", preferred.id)
        assertEquals(1, preferred.playbackSeasonNumber())
    }

    @Test
    fun preferredSeriesEpisode_maps_absolute_anime_action_across_seasons() {
        val meta = MetaDetails(
            id = "anime",
            type = "series",
            name = "Anime",
            videos = listOf(
                MetaVideo(id = "s1e1", title = "One", season = 1, episode = 1),
                MetaVideo(id = "s1e2", title = "Two", season = 1, episode = 2),
                MetaVideo(id = "s2e1", title = "Three", season = 2, episode = 1),
                MetaVideo(id = "s2e2", title = "Four", season = 2, episode = 2),
            ),
        )
        val action = SeriesPrimaryAction(
            label = "Up next",
            videoId = "anime:1:4",
            seasonNumber = 1,
            episodeNumber = 4,
            episodeTitle = null,
            episodeThumbnail = null,
            resumePositionMs = null,
        )

        val preferred = meta.preferredSeriesEpisode(action, entries = emptyList())

        assertNotNull(preferred)
        assertEquals("s2e2", preferred.id)
    }

    @Test
    fun preferredSeriesEpisode_falls_back_to_most_recent_played_episode() {
        val meta = MetaDetails(
            id = "show",
            type = "series",
            name = "Show",
            videos = listOf(
                MetaVideo(id = "ep1", title = "One", season = 1, episode = 1),
                MetaVideo(id = "ep2", title = "Two", season = 1, episode = 2),
            ),
        )
        val older = progressEntry(videoId = "show:1:1", episode = 1, updatedAt = 10L)
        val recent = progressEntry(videoId = "show:1:2", episode = 2, updatedAt = 20L)

        val preferred = meta.preferredSeriesEpisode(action = null, entries = listOf(older, recent))

        assertNotNull(preferred)
        assertEquals("ep2", preferred.id)
    }

    @Test
    fun seriesPrimaryAction_uses_latest_watched_episode_when_manual_mark_exists() {
        val meta = MetaDetails(
            id = "show",
            type = "series",
            name = "Show",
            videos = listOf(
                MetaVideo(id = "ep1", title = "Episode 1", season = 1, episode = 1, released = "2026-03-01"),
                MetaVideo(id = "ep2", title = "Episode 2", season = 1, episode = 2, released = "2026-03-08"),
                MetaVideo(id = "ep3", title = "Episode 3", season = 1, episode = 3, released = "2026-03-15"),
            ),
        )

        val action = meta.seriesPrimaryAction(
            entries = emptyList(),
            watchedItems = listOf(
                WatchedItem(
                    id = "show",
                    type = "series",
                    name = "Episode 2",
                    season = 1,
                    episode = 2,
                    markedAtEpochMs = 123L,
                ),
            ),
            todayIsoDate = "2026-03-30",
        )

        assertNotNull(action)
        assertEquals("Up Next • S1E3", action.label)
        assertEquals("show:1:3", action.videoId)
        assertEquals(1, action.seasonNumber)
        assertEquals(3, action.episodeNumber)
    }

    @Test
    fun seriesPrimaryAction_prefers_next_up_when_manual_watch_is_newer_than_resume() {
        val meta = MetaDetails(
            id = "show",
            type = "series",
            name = "Show",
            videos = listOf(
                MetaVideo(id = "ep1", title = "Episode 1", season = 1, episode = 1, released = "2026-03-01"),
                MetaVideo(id = "ep2", title = "Episode 2", season = 1, episode = 2, released = "2026-03-08"),
                MetaVideo(id = "ep3", title = "Episode 3", season = 1, episode = 3, released = "2026-03-15"),
            ),
        )

        val action = meta.seriesPrimaryAction(
            entries = listOf(
                WatchProgressEntry(
                    contentType = "series",
                    parentMetaId = "show",
                    parentMetaType = "series",
                    videoId = "show:1:2",
                    title = "Show",
                    seasonNumber = 1,
                    episodeNumber = 2,
                    lastPositionMs = 1_000L,
                    durationMs = 10_000L,
                    lastUpdatedEpochMs = 100L,
                    isCompleted = false,
                ),
            ),
            watchedItems = listOf(
                WatchedItem(
                    id = "show",
                    type = "series",
                    name = "Episode 2",
                    season = 1,
                    episode = 2,
                    markedAtEpochMs = 200L,
                ),
            ),
            todayIsoDate = "2026-03-30",
        )

        assertNotNull(action)
        assertEquals("Up Next • S1E3", action.label)
        assertEquals("show:1:3", action.videoId)
    }

    @Test
    fun seriesPrimaryAction_uses_explicit_content_when_meta_id_is_alias() {
        val meta = MetaDetails(
            id = "tt1234567",
            type = "series",
            name = "Show",
            videos = listOf(
                MetaVideo(id = "s4e14", title = "Episode 14", season = 4, episode = 14, released = "2026-03-01"),
                MetaVideo(id = "s4e15", title = "Episode 15", season = 4, episode = 15, released = "2026-03-08"),
            ),
        )

        val action = meta.seriesPrimaryAction(
            content = WatchingContentRef(type = "series", id = "tmdb:98765"),
            entries = listOf(
                WatchProgressEntry(
                    contentType = "series",
                    parentMetaId = "tmdb:98765",
                    parentMetaType = "series",
                    videoId = "tmdb:98765:4:14",
                    title = "Show",
                    seasonNumber = 4,
                    episodeNumber = 14,
                    lastPositionMs = 10_000L,
                    durationMs = 10_000L,
                    lastUpdatedEpochMs = 100L,
                    isCompleted = true,
                ),
            ),
            watchedItems = emptyList(),
            todayIsoDate = "2026-03-30",
        )

        assertNotNull(action)
        assertEquals("Up Next • S4E15", action.label)
        assertEquals("tmdb:98765:4:15", action.videoId)
        assertEquals(4, action.seasonNumber)
        assertEquals(15, action.episodeNumber)
    }

    @Test
    fun nextReleasedEpisodeAfter_global_index_fallback_ignores_specials() {
        val meta = MetaDetails(
            id = "show",
            type = "series",
            name = "Show",
            videos = listOf(
                MetaVideo(id = "sp1", title = "Special 1", season = 0, episode = 1, released = "2026-01-01"),
                MetaVideo(id = "s1e1", title = "Episode 1", season = 1, episode = 1, released = "2026-01-08"),
                MetaVideo(id = "s1e2", title = "Episode 2", season = 1, episode = 2, released = "2026-01-15"),
                MetaVideo(id = "s2e1", title = "Episode 3", season = 2, episode = 1, released = "2026-01-22"),
                MetaVideo(id = "s2e2", title = "Episode 4", season = 2, episode = 2, released = "2026-01-29"),
            ),
        )

        val nextEpisode = meta.nextReleasedEpisodeAfter(
            seasonNumber = 1,
            episodeNumber = 3,
            todayIsoDate = "2026-02-01",
        )

        assertNotNull(nextEpisode)
        assertEquals(2, nextEpisode.season)
        assertEquals(2, nextEpisode.episode)
        assertEquals("s2e2", nextEpisode.id)
    }

    private fun progressEntry(videoId: String, episode: Int, updatedAt: Long) = WatchProgressEntry(
        contentType = "series",
        parentMetaId = "show",
        parentMetaType = "series",
        videoId = videoId,
        title = "Show",
        seasonNumber = 1,
        episodeNumber = episode,
        lastPositionMs = 1_000L,
        durationMs = 10_000L,
        lastUpdatedEpochMs = updatedAt,
    )
}
