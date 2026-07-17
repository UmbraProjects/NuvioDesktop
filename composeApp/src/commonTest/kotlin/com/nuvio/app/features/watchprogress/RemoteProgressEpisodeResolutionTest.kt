package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.details.MetaVideo
import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteProgressEpisodeResolutionTest {
    @Test
    fun `SIMKL episode title corrects ambiguous Pokemon entry coordinate`() {
        val videos = listOf(
            MetaVideo(id = "pokemon:6:41", title = "Some Other Episode", season = 6, episode = 41),
            MetaVideo(id = "pokemon:7:1", title = "What You Seed Is What You Get", season = 7, episode = 1),
        )
        val entry = WatchProgressEntry(
            contentType = "series",
            parentMetaId = "kitsu:1404",
            parentMetaType = "series",
            videoId = "kitsu:1404:6:41",
            title = "Pokemon",
            seasonNumber = 6,
            episodeNumber = 41,
            episodeTitle = "What You Seed Is What You Get!",
            lastPositionMs = 1_000,
            durationMs = 10_000,
            lastUpdatedEpochMs = 1,
        )

        val resolved = resolveRemoteProgressEpisode(videos, entry)

        assertEquals(7, resolved?.season)
        assertEquals(1, resolved?.episode)
    }
}
