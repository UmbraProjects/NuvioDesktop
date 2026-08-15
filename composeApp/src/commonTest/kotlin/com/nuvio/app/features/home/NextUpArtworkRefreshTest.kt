package com.nuvio.app.features.home

import com.nuvio.app.features.watching.domain.WatchingContentRef
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Up Next shelf paints from a cache that persists across restarts, and a seed match used to
 * mean "done" — so a card first resolved before its episode still was published stayed blank for
 * the whole life of the seed, even though the details page showed the new artwork.
 */
class NextUpArtworkRefreshTest {

    private fun candidate(
        contentId: String,
        season: Int = 1,
        episode: Int = 4,
    ) = CompletedSeriesCandidate(
        content = WatchingContentRef(type = "series", id = contentId),
        seasonNumber = season,
        episodeNumber = episode,
        markedAtEpochMs = 1_000L,
    )

    private fun cachedCard(
        contentId: String,
        episodeThumbnail: String?,
        poster: String? = null,
        background: String? = null,
        seedSeason: Int = 1,
        seedEpisode: Int = 4,
    ) = contentId to (
        1_000L to ContinueWatchingItem(
            parentMetaId = contentId,
            parentMetaType = "series",
            videoId = "$contentId:$seedSeason:${seedEpisode + 1}",
            title = contentId,
            subtitle = "",
            imageUrl = episodeThumbnail,
            poster = poster,
            background = background,
            seasonNumber = seedSeason,
            episodeNumber = seedEpisode + 1,
            episodeThumbnail = episodeThumbnail,
            isNextUp = true,
            nextUpSeedSeasonNumber = seedSeason,
            nextUpSeedEpisodeNumber = seedEpisode,
            resumePositionMs = 0L,
            durationMs = 0L,
            progressFraction = 0f,
        )
        )

    @Test
    fun `a cached card with artwork is not re-resolved`() {
        val plan = planNextUpResolution(
            completedSeriesCandidates = listOf(candidate("tt1")),
            cachedNextUpItems = mapOf(cachedCard("tt1", episodeThumbnail = "still.jpg")),
        )

        assertTrue(plan.candidatesToResolve.isEmpty())
        assertTrue(plan.staleArtworkContentIds.isEmpty())
        assertEquals(setOf("tt1"), plan.cachedBySeries.keys)
    }

    @Test
    fun `a cached card missing its thumbnail is queued for re-resolution`() {
        val plan = planNextUpResolution(
            completedSeriesCandidates = listOf(candidate("tt1")),
            cachedNextUpItems = mapOf(cachedCard("tt1", episodeThumbnail = null)),
        )

        assertEquals(listOf("tt1"), plan.candidatesToResolve.map { it.content.id })
        assertEquals(setOf("tt1"), plan.staleArtworkContentIds)
    }

    @Test
    fun `a blank thumbnail counts as missing, not as resolved`() {
        val plan = planNextUpResolution(
            completedSeriesCandidates = listOf(candidate("tt1")),
            cachedNextUpItems = mapOf(cachedCard("tt1", episodeThumbnail = "   ")),
        )

        assertEquals(setOf("tt1"), plan.staleArtworkContentIds)
    }

    @Test
    fun `a show backdrop copied into the thumbnail field is retried`() {
        val backdrop = "https://artworks.thetvdb.com/series/background.jpg"
        val plan = planNextUpResolution(
            completedSeriesCandidates = listOf(candidate("tt1")),
            cachedNextUpItems = mapOf(
                cachedCard(
                    contentId = "tt1",
                    episodeThumbnail = backdrop,
                    background = backdrop,
                ),
            ),
        )

        assertEquals(setOf("tt1"), plan.staleArtworkContentIds)
    }

    @Test
    fun `different TMDB sizes of the same backdrop are still placeholders`() {
        val plan = planNextUpResolution(
            completedSeriesCandidates = listOf(candidate("tt1")),
            cachedNextUpItems = mapOf(
                cachedCard(
                    contentId = "tt1",
                    episodeThumbnail = "https://image.tmdb.org/t/p/w500/same-file.jpg",
                    background = "https://image.tmdb.org/t/p/original/same-file.jpg",
                ),
            ),
        )

        assertEquals(setOf("tt1"), plan.staleArtworkContentIds)
    }

    @Test
    fun `first home pass performs a real artwork refresh`() {
        assertTrue(
            shouldForceNextUpArtworkMetaRefresh(
                requestVersion = 0,
                appliedVersion = -1,
            ),
        )
        assertTrue(
            !shouldForceNextUpArtworkMetaRefresh(
                requestVersion = 0,
                appliedVersion = 0,
            ),
        )
    }

    @Test
    fun `the stale card stays in the base map so a failed retry cannot delete it`() {
        val plan = planNextUpResolution(
            completedSeriesCandidates = listOf(candidate("tt1")),
            cachedNextUpItems = mapOf(cachedCard("tt1", episodeThumbnail = null)),
        )

        // cachedBySeries is what the results merge onto; dropping the stale entry here is what
        // would make a card vanish when the network is down.
        assertTrue("tt1" in plan.cachedBySeries)
    }

    @Test
    fun `never-resolved candidates are queued ahead of artwork retries`() {
        val plan = planNextUpResolution(
            completedSeriesCandidates = listOf(
                candidate("cached-blank"),
                candidate("uncached"),
                candidate("cached-ok"),
            ),
            cachedNextUpItems = mapOf(
                cachedCard("cached-blank", episodeThumbnail = null),
                cachedCard("cached-ok", episodeThumbnail = "still.jpg"),
            ),
        )

        // The resolution budget is capped, so ordering decides who gets served when it runs out:
        // a card with nothing on screen must outrank one that is merely missing its thumbnail.
        assertEquals(
            listOf("uncached", "cached-blank"),
            plan.candidatesToResolve.map { it.content.id },
        )
    }

    @Test
    fun `a cached card whose seed moved on is re-resolved and dropped from the base map`() {
        val plan = planNextUpResolution(
            completedSeriesCandidates = listOf(candidate("tt1", season = 1, episode = 5)),
            cachedNextUpItems = mapOf(
                cachedCard("tt1", episodeThumbnail = "still.jpg", seedSeason = 1, seedEpisode = 4),
            ),
        )

        assertTrue(plan.cachedBySeries.isEmpty())
        assertEquals(listOf("tt1"), plan.candidatesToResolve.map { it.content.id })
        // A moved seed is a different card entirely, not an artwork problem — it must not be
        // allowed to bypass the meta LRU on resync.
        assertTrue(plan.staleArtworkContentIds.isEmpty())
    }

    @Test
    fun `a tvdb series background is generic art even when it matches nothing on the card`() {
        val plan = planNextUpResolution(
            completedSeriesCandidates = listOf(candidate("tt1")),
            cachedNextUpItems = mapOf(
                cachedCard(
                    contentId = "tt1",
                    // AIOMetadata serves TVDB art in the still field while Nuvio's own backdrop
                    // came from TMDB, so URL comparison alone clears it as a real still.
                    episodeThumbnail = "https://artworks.thetvdb.com/banners/series/78901/backgrounds/62.jpg",
                    background = "https://image.tmdb.org/t/p/original/unrelated-backdrop.jpg",
                ),
            ),
        )

        assertEquals(setOf("tt1"), plan.staleArtworkContentIds)
    }

    @Test
    fun `a fanart tv show background is generic art`() {
        val plan = planNextUpResolution(
            completedSeriesCandidates = listOf(candidate("tt1")),
            cachedNextUpItems = mapOf(
                cachedCard(
                    contentId = "tt1",
                    episodeThumbnail = "https://assets.fanart.tv/fanart/tv/78901/showbackground/show-5f2.jpg",
                    background = "https://image.tmdb.org/t/p/original/unrelated-backdrop.jpg",
                ),
            ),
        )

        assertEquals(setOf("tt1"), plan.staleArtworkContentIds)
    }

    @Test
    fun `a real episode still is left alone`() {
        val plan = planNextUpResolution(
            completedSeriesCandidates = listOf(candidate("tt1"), candidate("tt2")),
            cachedNextUpItems = mapOf(
                cachedCard(
                    contentId = "tt1",
                    episodeThumbnail = "https://image.tmdb.org/t/p/w300/episode-still.jpg",
                    background = "https://image.tmdb.org/t/p/original/backdrop.jpg",
                ),
                cachedCard(
                    contentId = "tt2",
                    // TVDB episode stills sit under /episodes/, which must not trip the show-art rule.
                    episodeThumbnail = "https://artworks.thetvdb.com/banners/episodes/78901/8123456.jpg",
                    background = "https://image.tmdb.org/t/p/original/backdrop.jpg",
                ),
            ),
        )

        assertTrue(plan.staleArtworkContentIds.isEmpty())
        assertTrue(plan.candidatesToResolve.isEmpty())
    }

    private fun freshCard(
        contentId: String = "tt1",
        season: Int = 1,
        episode: Int = 5,
        poster: String? = null,
        background: String? = null,
        episodeThumbnail: String? = null,
    ) = ContinueWatchingItem(
        parentMetaId = contentId,
        parentMetaType = "series",
        videoId = "$contentId:$season:$episode",
        title = contentId,
        subtitle = "",
        imageUrl = episodeThumbnail ?: background ?: poster,
        poster = poster,
        background = background,
        seasonNumber = season,
        episodeNumber = episode,
        episodeThumbnail = episodeThumbnail,
        isNextUp = true,
        nextUpSeedSeasonNumber = season,
        nextUpSeedEpisodeNumber = episode - 1,
        resumePositionMs = 0L,
        durationMs = 0L,
        progressFraction = 0f,
    )

    private fun cachedArtwork(
        season: Int = 1,
        episode: Int = 5,
        poster: String? = "poster.jpg",
        background: String? = "backdrop.jpg",
        episodeThumbnail: String? = "still.jpg",
    ) = CachedNextUpArtwork(
        poster = poster,
        background = background,
        logo = "logo.png",
        seasonNumber = season,
        episodeNumber = episode,
        episodeThumbnail = episodeThumbnail,
    )

    @Test
    fun `a metadata read that returned no artwork cannot blank the card`() {
        // The failure the user sees: the fetch succeeds, the payload has no images, and the card
        // that replaces the good one has nothing to draw.
        val merged = freshCard().withCachedArtworkFallback(
            cached = cachedArtwork(),
            reason = "test",
        )

        assertEquals("poster.jpg", merged.poster)
        assertEquals("backdrop.jpg", merged.background)
        assertEquals("still.jpg", merged.episodeThumbnail)
        assertEquals("still.jpg", merged.imageUrl)
    }

    @Test
    fun `a cached still is not carried onto a different episode`() {
        val merged = freshCard(season = 1, episode = 6).withCachedArtworkFallback(
            cached = cachedArtwork(season = 1, episode = 5),
            reason = "test",
        )

        // Showing the previous episode's still is worse than showing the show backdrop.
        assertEquals(null, merged.episodeThumbnail)
        assertEquals("backdrop.jpg", merged.imageUrl)
        // Series-level art is still valid across the seed change.
        assertEquals("poster.jpg", merged.poster)
        assertEquals("backdrop.jpg", merged.background)
    }

    @Test
    fun `fresh artwork always wins over the cached copy`() {
        val merged = freshCard(
            poster = "new-poster.jpg",
            background = "new-backdrop.jpg",
            episodeThumbnail = "new-still.jpg",
        ).withCachedArtworkFallback(cached = cachedArtwork(), reason = "test")

        assertEquals("new-poster.jpg", merged.poster)
        assertEquals("new-backdrop.jpg", merged.background)
        assertEquals("new-still.jpg", merged.episodeThumbnail)
        assertEquals("new-still.jpg", merged.imageUrl)
    }

    @Test
    fun `a series with no cached artwork is passed through untouched`() {
        val fresh = freshCard(episodeThumbnail = "new-still.jpg")

        assertEquals(fresh, fresh.withCachedArtworkFallback(cached = null, reason = "test"))
    }

    @Test
    fun `the persisted map is the merged one, not the raw fresh result`() {
        val merged = mergeNextUpResultsWithCachedArtwork(
            results = mapOf("tt1" to (1_000L to freshCard())),
            cachedArtwork = mapOf("tt1" to cachedArtwork()),
            reason = "test",
        )

        // saveContinueWatchingSnapshots() writes exactly this map, so anything lost here is lost
        // on disk and comes back blank after a restart.
        assertEquals("backdrop.jpg", merged.getValue("tt1").second.background)
        assertEquals(1_000L, merged.getValue("tt1").first)
    }
}
