package com.nuvio.app.features.librarypvr

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamPackGrabServiceTest {

    @Test
    fun `counts only the requested native anime entry`() {
        val meta = animeMeta(
            id = "kitsu:5605",
            episodes = 84,
        )

        assertEquals(84, StreamPackGrabService.episodeCountForTarget("kitsu:5605", meta))
    }

    @Test
    fun `rejects a sibling anime entry instead of accepting its 146 episodes`() {
        val sunAndMoon = animeMeta(
            id = "kitsu:12533",
            episodes = 146,
        )

        assertNull(StreamPackGrabService.episodeCountForTarget("kitsu:5605", sunAndMoon))
    }

    @Test
    fun `rejects foreign namespaced videos inside an otherwise matching entry`() {
        val polluted = animeMeta(
            id = "kitsu:5605",
            episodes = 84,
        ).copy(
            videos = (1..146).map { episode ->
                MetaVideo(
                    id = "kitsu:12533:$episode",
                    title = "Episode $episode",
                    season = 1,
                    episode = episode,
                )
            },
        )

        assertNull(StreamPackGrabService.episodeCountForTarget("kitsu:5605", polluted))
    }

    @Test
    fun `ignores specials and duplicate episode records`() {
        val regular = (1..84).map { episode ->
            MetaVideo(
                id = "kitsu:5605:$episode",
                title = "Episode $episode",
                season = 1,
                episode = episode,
            )
        }
        val meta = MetaDetails(
            id = "kitsu:5605",
            type = "series",
            name = "Pokemon Best Wishes",
            videos = regular + regular.first() + MetaVideo(
                id = "kitsu:5605:0:1",
                title = "Special",
                season = 0,
                episode = 1,
            ),
        )

        assertEquals(84, StreamPackGrabService.episodeCountForTarget("kitsu:5605", meta))
    }

    private fun animeMeta(id: String, episodes: Int): MetaDetails =
        MetaDetails(
            id = id,
            type = "series",
            name = id,
            videos = (1..episodes).map { episode ->
                MetaVideo(
                    id = "$id:$episode",
                    title = "Episode $episode",
                    season = 1,
                    episode = episode,
                )
            },
        )
}
