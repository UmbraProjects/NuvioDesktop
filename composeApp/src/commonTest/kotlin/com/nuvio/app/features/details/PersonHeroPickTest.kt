package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PersonHeroPickTest {
    private fun candidate(
        id: Int,
        votes: Int,
        mediaType: String = "movie",
        billing: Int? = 0,
        episodes: Int? = null,
        genres: List<Int> = emptyList(),
        backdrop: String? = "/b$id.jpg",
        ownWork: Boolean = false,
    ) = PersonHeroCandidate(
        tmdbId = id,
        mediaType = mediaType,
        title = "T$id",
        backdropPath = backdrop,
        voteCount = votes,
        billing = billing,
        episodeCount = episodes,
        genreIds = genres,
        ownWork = ownWork,
    )

    @Test
    fun picksMostVotedLeadingRole() {
        val pick = pickPersonHeroCandidate(
            listOf(
                candidate(1, votes = 900, billing = 1),
                candidate(2, votes = 30_000, billing = 14), // small part in a big film
                candidate(3, votes = 5_000, billing = 0),
            ),
        )
        assertEquals(3, pick?.tmdbId)
    }

    @Test
    fun talkShowGuestSpotsNeverWin() {
        val pick = pickPersonHeroCandidate(
            listOf(
                candidate(1, votes = 50_000, mediaType = "tv", episodes = 12, genres = listOf(10767)),
                candidate(2, votes = 800, billing = 2),
            ),
        )
        assertEquals(2, pick?.tmdbId)
    }

    @Test
    fun seriesNeedsSeveralEpisodes() {
        val pick = pickPersonHeroCandidate(
            listOf(
                candidate(1, votes = 20_000, mediaType = "tv", billing = null, episodes = 1),
                candidate(2, votes = 3_000, mediaType = "tv", billing = null, episodes = 8),
            ),
        )
        assertEquals(2, pick?.tmdbId)
    }

    @Test
    fun skipsCreditsWithoutBackdrop() {
        val pick = pickPersonHeroCandidate(
            listOf(
                candidate(1, votes = 20_000, backdrop = null),
                candidate(2, votes = 100),
            ),
        )
        assertEquals(2, pick?.tmdbId)
    }

    @Test
    fun fallsBackToAnyCreditWhenNoLeadingRole() {
        val pick = pickPersonHeroCandidate(
            listOf(
                candidate(1, votes = 100, billing = 20),
                candidate(2, votes = 400, billing = 9),
            ),
        )
        assertEquals(2, pick?.tmdbId)
    }

    @Test
    fun directorsOwnWorkCounts() {
        val pick = pickPersonHeroCandidate(
            listOf(
                candidate(1, votes = 40_000, billing = null, ownWork = true),
                candidate(2, votes = 1_000, billing = null, ownWork = true),
            ),
        )
        assertEquals(1, pick?.tmdbId)
    }

    @Test
    fun nothingWithBackdropGivesNull() {
        assertNull(pickPersonHeroCandidate(listOf(candidate(1, votes = 10, backdrop = null))))
        assertNull(pickPersonHeroCandidate(emptyList()))
    }
}
