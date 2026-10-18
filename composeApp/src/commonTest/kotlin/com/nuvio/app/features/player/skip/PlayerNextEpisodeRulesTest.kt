package com.nuvio.app.features.player.skip

import com.nuvio.app.features.details.MetaVideo
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerNextEpisodeRulesTest {

    @Test
    fun resolvesSeasonlessAbsoluteAnimeEpisode() {
        val videos = listOf(
            MetaVideo(id = "anime-3", title = "Episode 3", episode = 3),
            MetaVideo(id = "anime-1", title = "Episode 1", episode = 1),
            MetaVideo(id = "anime-2", title = "Episode 2", episode = 2),
        )

        val next = PlayerNextEpisodeRules.resolveNextEpisode(
            videos = videos,
            currentSeason = null,
            currentEpisode = 1,
        )

        assertEquals("anime-2", next?.id)
    }

    @Test
    fun seasonlessAnimeTreatsPlaybackSeasonOneAsRegularEpisodes() {
        val videos = listOf(
            MetaVideo(id = "kitsu:486:60", title = "Episode 60", episode = 60),
            MetaVideo(id = "kitsu:486:61", title = "Episode 61", episode = 61),
            MetaVideo(id = "kitsu:486:62", title = "Episode 62", episode = 62),
        )

        val next = PlayerNextEpisodeRules.resolveNextEpisode(
            videos = videos,
            currentSeason = 1,
            currentEpisode = 61,
            currentVideoId = "kitsu:486:61",
            parentMetaId = "kitsu:486",
        )

        assertEquals("kitsu:486:62", next?.id)
    }

    @Test
    fun absoluteAnimePositionAdvancesAcrossProviderSeasons() {
        val videos = listOf(
            MetaVideo(id = "s1e1", title = "One", season = 1, episode = 1),
            MetaVideo(id = "s1e2", title = "Two", season = 1, episode = 2),
            MetaVideo(id = "s2e1", title = "Three", season = 2, episode = 1),
            MetaVideo(id = "s2e2", title = "Four", season = 2, episode = 2),
        )

        val next = PlayerNextEpisodeRules.resolveNextEpisode(
            videos = videos,
            currentSeason = 1,
            currentEpisode = 3,
        )

        assertEquals("s2e2", next?.id)
    }

    @Test
    fun regularRunDoesNotAdvanceIntoSpecials() {
        val videos = listOf(
            MetaVideo(id = "s1e1", title = "One", season = 1, episode = 1),
            MetaVideo(id = "special", title = "Special", season = 0, episode = 1),
        )

        val next = PlayerNextEpisodeRules.resolveNextEpisode(
            videos = videos,
            currentSeason = 1,
            currentEpisode = 1,
        )

        assertEquals(null, next)
    }

    @Test
    fun preservesSeasonAndEpisodeOrdering() {
        val videos = listOf(
            MetaVideo(id = "s2e1", title = "S2 E1", season = 2, episode = 1),
            MetaVideo(id = "s1e2", title = "S1 E2", season = 1, episode = 2),
            MetaVideo(id = "s1e1", title = "S1 E1", season = 1, episode = 1),
        )

        val next = PlayerNextEpisodeRules.resolveNextEpisode(
            videos = videos,
            currentSeason = 1,
            currentEpisode = 2,
        )

        assertEquals("s2e1", next?.id)
    }

    private val fillerRun = listOf(
        MetaVideo(id = "kitsu:11:25", title = "Ep 25", released = "2003-04-02", season = 1, episode = 25),
        MetaVideo(id = "kitsu:11:26", title = "Ep 26", released = "2003-04-09", season = 1, episode = 26),
        MetaVideo(id = "kitsu:11:27", title = "Ep 27", released = "2003-04-16", season = 1, episode = 27),
        MetaVideo(id = "kitsu:11:28", title = "Ep 28", released = "2003-04-23", season = 1, episode = 28),
        MetaVideo(id = "kitsu:11:29", title = "Ep 29", released = "2099-01-01", season = 1, episode = 29),
    )

    private fun nextFrom(episode: Int, skipped: Set<String>) = PlayerNextEpisodeRules.resolveNextEpisodeSkipping(
        videos = fillerRun,
        currentSeason = 1,
        currentEpisode = episode,
        currentVideoId = "kitsu:11:$episode",
        parentMetaId = "kitsu:11",
        isSkipped = { it.id in skipped },
    )

    @Test
    fun skipFillerPassesOverAFillerRun() {
        assertEquals("kitsu:11:28", nextFrom(25, setOf("kitsu:11:26", "kitsu:11:27"))?.id)
    }

    @Test
    fun skipFillerKeepsTheNextEpisodeWhenItIsNotSkipped() {
        assertEquals("kitsu:11:26", nextFrom(25, setOf("kitsu:11:27"))?.id)
    }

    @Test
    fun skipFillerFallsBackWhenOnlyFillerFollows() {
        assertEquals("kitsu:11:26", nextFrom(25, setOf("kitsu:11:26", "kitsu:11:27", "kitsu:11:28", "kitsu:11:29"))?.id)
    }

    @Test
    fun skipFillerFallsBackWhenTheNextKeptEpisodeHasNotAired() {
        // A filler arc that is airing now: the canon episode after it is still in the future.
        assertEquals("kitsu:11:27", nextFrom(26, setOf("kitsu:11:27", "kitsu:11:28"))?.id)
    }
}
