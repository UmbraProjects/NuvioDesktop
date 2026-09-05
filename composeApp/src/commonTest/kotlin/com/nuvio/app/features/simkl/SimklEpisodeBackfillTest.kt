package com.nuvio.app.features.simkl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun show(title: String = "Shameless (US)", simkl: Int = 20060, imdb: String = "tt1586680") =
    SimklShowMedia(title = title, year = 2011, ids = SimklMediaIds(simkl = simkl, imdb = imdb))

private fun episodeList(seasons: Map<Int, Int>, specials: Int = 0): List<SimklCatalogEpisode> =
    seasons.flatMap { (season, count) ->
        (1..count).map { SimklCatalogEpisode(season = season, episode = it, type = "episode") }
    } + (1..specials).map { SimklCatalogEpisode(season = null, episode = it, type = "special") }

private fun target(
    watched: Int,
    lastWatchedAt: String? = "2021-04-23T18:02:49Z",
) = SimklEpisodeBackfillTarget(
    simklId = 20060,
    show = show(),
    isAnime = false,
    watchedEpisodesCount = watched,
    lastWatchedAt = lastWatchedAt,
)

class SimklEpisodeBackfillTest {

    @Test
    fun `a completed show with no seasons array is a backfill target`() {
        // Exactly what /sync/all-items sends for a finished show: a count, and nothing to attach it
        // to. The old parser produced zero rows from this, so the show read as entirely unwatched.
        val response = SimklAllItemsResponse(
            shows = listOf(
                SimklAllItemsEntry(
                    show = show(),
                    status = "completed",
                    watchedEpisodesCount = 134,
                    totalEpisodesCount = 134,
                    lastWatchedAt = "2021-04-23T18:02:49Z",
                ),
            ),
        )

        assertTrue(response.toWatchedItems().isEmpty())
        val targets = response.episodeBackfillTargets()
        assertEquals(1, targets.size)
        assertEquals(134, targets.single().watchedEpisodesCount)
    }

    @Test
    fun `an entry that already carries its episodes is never backfilled`() {
        // This is a repair for missing data, not a second source for data already present.
        val response = SimklAllItemsResponse(
            shows = listOf(
                SimklAllItemsEntry(
                    show = show(),
                    watchedEpisodesCount = 2,
                    seasons = listOf(
                        SimklWatchedSeason(1, listOf(SimklWatchedEpisode(1, "2026-01-01T00:00:00Z"))),
                    ),
                ),
            ),
        )

        assertTrue(response.episodeBackfillTargets().isEmpty())
    }

    @Test
    fun `a show with nothing watched is not a target`() {
        val response = SimklAllItemsResponse(
            shows = listOf(
                SimklAllItemsEntry(show = show(), status = "plantowatch", watchedEpisodesCount = 0),
            ),
        )

        assertTrue(response.episodeBackfillTargets().isEmpty())
    }

    @Test
    fun `a finished show marks every numbered episode`() {
        // Shameless (US): 11 seasons, 134 numbered episodes, 36 specials. The count matches the
        // numbered episodes exactly, which is what makes the finished case exact rather than a guess.
        val episodes = episodeList(
            seasons = mapOf(1 to 12, 2 to 12, 3 to 12, 4 to 12, 5 to 12, 6 to 12, 7 to 12, 8 to 12, 9 to 14, 10 to 12, 11 to 12),
            specials = 36,
        )

        val items = buildBackfilledWatchedItems(target(watched = 134), episodes)

        assertEquals(134, items.size)
        assertEquals("tt1586680", items.first().id)
        assertEquals("series", items.first().type)
        assertEquals(1 to 1, items.first().season to items.first().episode)
        assertEquals(11 to 12, items.last().season to items.last().episode)
    }

    @Test
    fun `specials never consume a watched slot`() {
        // SIMKL excludes specials from watched_episodes_count. Counting them here would shift every
        // episode after the first special and mark the wrong ones.
        val episodes = episodeList(seasons = mapOf(1 to 10), specials = 5)

        val items = buildBackfilledWatchedItems(target(watched = 10), episodes)

        assertEquals(10, items.size)
        assertTrue(items.all { it.season == 1 })
        assertEquals((1..10).toList(), items.map { it.episode })
    }

    @Test
    fun `a partly watched show stops at the count, in aired order`() {
        val episodes = episodeList(seasons = mapOf(1 to 10, 2 to 10))

        val items = buildBackfilledWatchedItems(target(watched = 12), episodes)

        assertEquals(12, items.size)
        assertEquals(2 to 2, items.last().season to items.last().episode)
    }

    @Test
    fun `the watched count decides, not SIMKL's last_watched marker`() {
        // Jessica Jones on the account this was measured against: 39 of 39 watched, with
        // `last_watched` stranded at S01E07. Treating that marker as a ceiling restored 7 episodes
        // instead of 39, which is why it plays no part in the selection.
        val episodes = episodeList(seasons = mapOf(1 to 13, 2 to 13, 3 to 13))

        val items = buildBackfilledWatchedItems(
            SimklEpisodeBackfillTarget(
                simklId = 20060,
                show = show(),
                isAnime = false,
                watchedEpisodesCount = 39,
                lastWatchedAt = "2021-04-23T18:02:49Z",
            ),
            episodes,
        )

        assertEquals(39, items.size)
        assertEquals(3 to 13, items.last().season to items.last().episode)
    }

    @Test
    fun `every backfilled row carries the entry timestamp`() {
        // Per-episode times are simply not in this payload; the entry's own is all there is.
        val items = buildBackfilledWatchedItems(target(watched = 3), episodeList(mapOf(1 to 3)))

        assertEquals(1, items.map { it.markedAtEpochMs }.distinct().size)
        assertTrue(items.first().markedAtEpochMs > 0L)
    }

    @Test
    fun `an unresolvable show yields nothing rather than a bad row`() {
        val noIds = SimklEpisodeBackfillTarget(
            simklId = 1,
            show = SimklShowMedia(title = "X", ids = SimklMediaIds()),
            isAnime = false,
            watchedEpisodesCount = 5,
            lastWatchedAt = null,
        )

        assertTrue(buildBackfilledWatchedItems(noIds, episodeList(mapOf(1 to 5))).isEmpty())
    }

    @Test
    fun `an empty episode catalog yields nothing`() {
        assertTrue(buildBackfilledWatchedItems(target(watched = 5), emptyList()).isEmpty())
    }

}
