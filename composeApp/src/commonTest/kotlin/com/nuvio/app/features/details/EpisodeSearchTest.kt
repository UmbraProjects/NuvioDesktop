package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertEquals

class EpisodeSearchTest {
    private val episodes = listOf(
        MetaVideo(
            id = "s1e1",
            title = "Cartman Gets an Anal Probe",
            overview = "The boys meet visitors from another planet.",
            season = 1,
            episode = 1,
        ),
        MetaVideo(
            id = "s2e5",
            title = "Conjoined Fetus Lady",
            overview = "The school nurse becomes the centre of attention.",
            season = 2,
            episode = 5,
        ),
        MetaVideo(
            id = "absolute-42",
            title = "A Great Adventure",
            overview = "The friends travel to a distant city.",
            episode = 42,
        ),
    )

    @Test
    fun `matches remembered words across title and synopsis`() {
        assertEquals(
            listOf("s1e1"),
            episodes.matchingEpisodeSearch("cartman planet").map(MetaVideo::id),
        )
    }

    @Test
    fun `matches common season and episode codes`() {
        assertEquals(listOf("s2e5"), episodes.matchingEpisodeSearch("S02E05").map(MetaVideo::id))
        assertEquals(listOf("s2e5"), episodes.matchingEpisodeSearch("2x5").map(MetaVideo::id))
        assertEquals(listOf("s2e5"), episodes.matchingEpisodeSearch("season 2 episode 5").map(MetaVideo::id))
    }

    @Test
    fun `matches absolute episode numbers used by anime metadata`() {
        assertEquals(listOf("absolute-42"), episodes.matchingEpisodeSearch("episode 42").map(MetaVideo::id))
    }

    @Test
    fun `blank query preserves the supplied episode order`() {
        assertEquals(episodes, episodes.matchingEpisodeSearch("   "))
    }
}
