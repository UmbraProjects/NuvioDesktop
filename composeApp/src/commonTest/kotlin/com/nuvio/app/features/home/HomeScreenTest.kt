package com.nuvio.app.features.home

import com.nuvio.app.features.cloud.CloudLibraryFile
import com.nuvio.app.features.cloud.CloudLibraryItem
import com.nuvio.app.features.cloud.CloudLibraryItemType
import com.nuvio.app.features.cloud.CloudLibraryProviderState
import com.nuvio.app.features.cloud.CloudLibraryUiState
import com.nuvio.app.features.cloud.playbackVideoId
import com.nuvio.app.features.debrid.DebridProviders
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.ContinueWatchingSortMode
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watching.domain.WatchingCompletedEpisode
import com.nuvio.app.features.tracking.ContinueWatchingSource
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.trakt.TRAKT_CONTINUE_WATCHING_DAYS_CAP_ALL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeScreenTest {

    @Test
    fun `search library card uses enriched backdrop without replacing row provenance`() {
        val raw = MetaPreview(
            id = "tt0193676",
            type = "series",
            name = "Freaks and Geeks",
            poster = "poster-provider",
            banner = "catalog-art",
            logo = "catalog-logo",
            preferLocalStreams = true,
        )
        val enriched = raw.copy(
            banner = "tmdb-backdrop",
            logo = "tmdb-logo",
            preferLocalStreams = false,
        )

        val result = mergeSearchLibraryCardEnrichment(raw, enriched)

        assertEquals("tmdb-backdrop", result.banner)
        assertEquals("tmdb-logo", result.logo)
        assertEquals("poster-provider", result.poster)
        assertEquals(true, result.preferLocalStreams)
    }

    @Test
    fun `pending landscape card suppresses original artwork until enrichment arrives`() {
        val raw = MetaPreview(
            id = "tt0193676",
            type = "series",
            name = "Freaks and Geeks",
            poster = "original-poster",
            posterFallback = "poster-fallback",
            banner = "original-catalog-art",
            logo = "original-logo",
            preferLocalStreams = true,
        )

        val result = mergeSearchLibraryCardEnrichment(
            raw = raw,
            enriched = null,
            suppressPendingArtwork = true,
        )

        assertEquals(null, result.poster)
        assertEquals(null, result.posterFallback)
        assertEquals(null, result.banner)
        assertEquals(null, result.logo)
        assertEquals(true, result.preferLocalStreams)
    }

    @Test
    fun `filename resolved TMDB backdrop bypasses pending landscape placeholder`() {
        val resolved = MetaPreview(
            id = "cloud-file-id",
            type = "movie",
            name = "Arrival",
            poster = "https://image.tmdb.org/t/p/w500/poster.jpg",
            banner = "https://image.tmdb.org/t/p/w1280/backdrop.jpg",
        )

        val result = mergeSearchLibraryCardEnrichment(
            raw = resolved,
            enriched = null,
            suppressPendingArtwork = true,
        )

        assertEquals(resolved.poster, result.poster)
        assertEquals(resolved.banner, result.banner)
    }

    @Test
    fun `filename resolved poster bypasses pending landscape placeholder without a backdrop`() {
        val resolved = MetaPreview(
            id = "cloud-file-id",
            type = "movie",
            name = "Arrival",
            poster = "https://image.tmdb.org/t/p/w500/poster.jpg",
            banner = null,
        )

        val result = mergeSearchLibraryCardEnrichment(
            raw = resolved,
            enriched = null,
            suppressPendingArtwork = true,
        )

        assertEquals(resolved.poster, result.poster)
    }

    @Test
    fun `home trakt continue watching candidate limits match TV`() {
        assertEquals(300, HomeContinueWatchingMaxRecentProgressItems)
        assertEquals(32, HomeNextUpInitialResolutionLimit)
    }

    @Test
    fun `continue watching rows remain combined when separation is disabled`() {
        val resume = continueWatchingItem("show:1:4", "S1E4").copy(isNextUp = false)
        val next = continueWatchingItem("show:1:5", "Up Next - S1E5")
        val items = listOf(next, resume)

        val (continueWatching, nextUp) = splitContinueWatchingRows(
            items = items,
            separateNextUpRow = false,
        )

        assertEquals(items, continueWatching)
        assertTrue(nextUp.isEmpty())
    }

    @Test
    fun `continue watching rows split next up items while preserving row order`() {
        val resumeOne = continueWatchingItem("first:1:4", "S1E4").copy(isNextUp = false)
        val nextOne = continueWatchingItem("second:1:5", "Up Next - S1E5")
        val resumeTwo = continueWatchingItem("third:1:2", "S1E2").copy(isNextUp = false)
        val nextTwo = continueWatchingItem("fourth:2:1", "Up Next - S2E1")

        val (continueWatching, nextUp) = splitContinueWatchingRows(
            items = listOf(resumeOne, nextOne, resumeTwo, nextTwo),
            separateNextUpRow = true,
        )

        assertEquals(listOf(resumeOne, resumeTwo), continueWatching)
        assertEquals(listOf(nextOne, nextTwo), nextUp)
    }

    @Test
    fun `upcoming row takes only unaired next up episodes, soonest first, independent of next up split`() {
        val now = requireNotNull(com.nuvio.app.features.watchprogress.parseReleaseDateToEpochMs("2026-09-27T12:30:00Z"))
        val resume = continueWatchingItem("resume:1:4", "S1E4").copy(isNextUp = false, released = "2099-01-01T00:00:00Z")
        val aired = continueWatchingItem("aired:1:5", "Up Next - S1E5").copy(released = "2026-09-27T11:00:00Z")
        val unknown = continueWatchingItem("unknown:1:5", "Up Next - S1E5")
        val later = continueWatchingItem("later:1:5", "Up Next - S1E5").copy(released = "2026-10-05T00:00:00Z")
        val sooner = continueWatchingItem("sooner:1:5", "Up Next - S1E5").copy(released = "2026-09-28T00:00:00Z")
        val items = listOf(later, resume, aired, unknown, sooner)

        val upcomingOnly = splitContinueWatchingRows(items, separateNextUpRow = false, separateUpcomingRow = true, nowEpochMs = now)
        assertEquals(listOf(resume, aired, unknown), upcomingOnly.continueWatching)
        assertTrue(upcomingOnly.nextUp.isEmpty())
        assertEquals(listOf(sooner, later), upcomingOnly.upcoming)

        val both = splitContinueWatchingRows(items, separateNextUpRow = true, separateUpcomingRow = true, nowEpochMs = now)
        assertEquals(listOf(resume), both.continueWatching)
        assertEquals(listOf(aired, unknown), both.nextUp)
        assertEquals(listOf(sooner, later), both.upcoming)

        val nextUpOnly = splitContinueWatchingRows(items, separateNextUpRow = true, separateUpcomingRow = false, nowEpochMs = now)
        assertEquals(listOf(later, aired, unknown, sooner), nextUpOnly.nextUp)
        assertTrue(nextUpOnly.upcoming.isEmpty())
    }

    @Test
    fun `build home continue watching items removes duplicate video ids`() {
        val inProgress = progressEntry(
            videoId = "tt0944947:1:4",
            title = "Game of Thrones",
            episodeTitle = "Cripples, Bastards, and Broken Things",
            lastUpdatedEpochMs = 250L,
        )
        val nextUp = continueWatchingItem(
            videoId = "tt0944947:1:4",
            subtitle = "Up Next • S1E4 • Cripples, Bastards, and Broken Things",
        )
        val movie = progressEntry(
            videoId = "movie-1",
            title = "Movie",
            lastUpdatedEpochMs = 100L,
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
        )

        val result = buildHomeContinueWatchingItems(
            visibleEntries = listOf(inProgress, movie),
            nextUpItemsBySeries = mapOf("tt0944947" to (200L to nextUp)),
        )

        assertEquals(listOf("tt0944947:1:4", "movie-1"), result.map(ContinueWatchingItem::videoId))
        assertEquals("S1E4 • Cripples, Bastards, and Broken Things", result.first().subtitle)
    }

    @Test
    fun `build home continue watching items prefers progress entry on timestamp tie`() {
        val inProgress = progressEntry(
            videoId = "show:1:5",
            title = "Show",
            episodeNumber = 5,
            episodeTitle = "The Wolf and the Lion",
            lastUpdatedEpochMs = 500L,
        )
        val nextUp = continueWatchingItem(
            videoId = "show:1:5",
            subtitle = "Up Next • S1E5 • The Wolf and the Lion",
        )

        val result = buildHomeContinueWatchingItems(
            visibleEntries = listOf(inProgress),
            nextUpItemsBySeries = mapOf("show" to (500L to nextUp)),
        )

        assertEquals(1, result.size)
        assertEquals("S1E5 • The Wolf and the Lion", result.single().subtitle)
    }

    @Test
    fun `build home continue watching items suppresses next up when series has in progress resume`() {
        val inProgress = progressEntry(
            videoId = "show:1:4",
            title = "Show",
            episodeNumber = 4,
            episodeTitle = "Current",
            lastUpdatedEpochMs = 200L,
        )
        val nextUp = continueWatchingItem(
            videoId = "show:1:5",
            subtitle = "Up Next • S1E5 • Next",
        )

        val result = buildHomeContinueWatchingItems(
            visibleEntries = listOf(inProgress),
            nextUpItemsBySeries = mapOf("show" to (500L to nextUp)),
        )

        assertEquals(listOf("show:1:4"), result.map(ContinueWatchingItem::videoId))
        assertEquals("S1E4 • Current", result.single().subtitle)
    }

    @Test
    fun `build home continue watching items enriches cloud title from library file`() {
        val file = CloudLibraryFile(id = "8", name = "GOAT.2026.2160p.UHD.mkv")
        val cloudItem = CloudLibraryItem(
            providerId = DebridProviders.TORBOX_ID,
            providerName = DebridProviders.Torbox.displayName,
            id = "29773238",
            type = CloudLibraryItemType.Torrent,
            name = "GOAT torrent",
            files = listOf(file),
        )
        val progress = WatchProgressEntry(
            contentType = "cloud",
            parentMetaId = cloudItem.stableKey,
            parentMetaType = "cloud",
            videoId = cloudItem.playbackVideoId(file),
            title = cloudItem.stableKey,
            lastPositionMs = 120_000L,
            durationMs = 1_000_000L,
            lastUpdatedEpochMs = 500L,
        )

        val result = buildHomeContinueWatchingItems(
            visibleEntries = listOf(progress),
            nextUpItemsBySeries = emptyMap(),
            cloudLibraryUiState = CloudLibraryUiState(
                isLoaded = true,
                providers = listOf(
                    CloudLibraryProviderState(
                        provider = DebridProviders.Torbox,
                        items = listOf(cloudItem),
                    ),
                ),
            ),
        )

        assertEquals("GOAT.2026.2160p.UHD.mkv", result.single().title)
    }

    @Test
    fun `Trakt continue watching window filters old progress only when Trakt source is active`() {
        val oldEntry = progressEntry(
            videoId = "old",
            title = "Old",
            lastUpdatedEpochMs = 1_000L,
            seasonNumber = null,
            episodeNumber = null,
        )
        val recentEntry = progressEntry(
            videoId = "recent",
            title = "Recent",
            lastUpdatedEpochMs = 30L * MILLIS_PER_DAY,
            seasonNumber = null,
            episodeNumber = null,
        )
        val entries = listOf(oldEntry, recentEntry)

        val filtered = filterEntriesForTraktContinueWatchingWindow(
            entries = entries,
            isTraktProgressActive = true,
            daysCap = 60,
            nowEpochMs = 90L * MILLIS_PER_DAY,
        )
        val nuvioSource = filterEntriesForTraktContinueWatchingWindow(
            entries = entries,
            isTraktProgressActive = false,
            daysCap = 60,
            nowEpochMs = 90L * MILLIS_PER_DAY,
        )

        assertEquals(listOf("recent"), filtered.map(WatchProgressEntry::videoId))
        assertEquals(listOf("old", "recent"), nuvioSource.map(WatchProgressEntry::videoId))
    }

    @Test
    fun `Trakt all history window keeps old progress`() {
        val oldEntry = progressEntry(
            videoId = "old",
            title = "Old",
            lastUpdatedEpochMs = 1_000L,
            seasonNumber = null,
            episodeNumber = null,
        )
        val recentEntry = progressEntry(
            videoId = "recent",
            title = "Recent",
            lastUpdatedEpochMs = 30L * MILLIS_PER_DAY,
            seasonNumber = null,
            episodeNumber = null,
        )

        val result = filterEntriesForTraktContinueWatchingWindow(
            entries = listOf(oldEntry, recentEntry),
            isTraktProgressActive = true,
            daysCap = TRAKT_CONTINUE_WATCHING_DAYS_CAP_ALL,
            nowEpochMs = 90L * MILLIS_PER_DAY,
        )

        assertEquals(listOf("old", "recent"), result.map(WatchProgressEntry::videoId))
    }

    @Test
    fun `home next up seed uses completed progress when watched item lags on Nuvio Sync`() {
        val completedProgress = progressEntry(
            videoId = "show:4:14",
            title = "Show",
            seasonNumber = 4,
            episodeNumber = 14,
            lastUpdatedEpochMs = 2_000L,
            isCompleted = true,
        )
        val olderWatchedItem = watchedItem(
            id = "show",
            season = 4,
            episode = 10,
            markedAtEpochMs = 1_000L,
        )

        val result = buildHomeNextUpSeedCandidates(
            progressEntries = listOf(completedProgress),
            watchedItems = listOf(olderWatchedItem),
            isTraktProgressActive = false,
            preferFurthestEpisode = true,
            nowEpochMs = 3_000L,
        )

        assertEquals(1, result.size)
        assertEquals("show", result.single().content.id)
        assertEquals(4, result.single().seasonNumber)
        assertEquals(14, result.single().episodeNumber)
    }

    @Test
    fun `home next up seed follows an active rewatch instead of the watched finale`() {
        val finale = watchedItem(id = "show", season = 4, episode = 14, markedAtEpochMs = 1_000L)
        val otherShow = watchedItem(id = "other", season = 1, episode = 3, markedAtEpochMs = 1_500L)
        val rewatch = WatchingCompletedEpisode(seasonNumber = 1, episodeNumber = 2, markedAtEpochMs = 2_000L)

        val duringRewatch = buildHomeNextUpSeedCandidates(
            progressEntries = emptyList(),
            watchedItems = listOf(finale, otherShow),
            isTraktProgressActive = false,
            preferFurthestEpisode = true,
            nowEpochMs = 3_000L,
            rewatchSeedFor = { contentId -> rewatch.takeIf { contentId == "show" } },
        )
        // Session completed or deleted: the lookup answers nothing and the history seeds again.
        val afterRewatch = buildHomeNextUpSeedCandidates(
            progressEntries = emptyList(),
            watchedItems = listOf(finale, otherShow),
            isTraktProgressActive = false,
            preferFurthestEpisode = true,
            nowEpochMs = 3_000L,
        )

        val show = duringRewatch.single { it.content.id == "show" }
        assertEquals(1 to 2, show.seasonNumber to show.episodeNumber)
        assertEquals(2_000L, show.markedAtEpochMs)
        assertEquals(1 to 3, duringRewatch.single { it.content.id == "other" }.let { it.seasonNumber to it.episodeNumber })
        assertEquals(4 to 14, afterRewatch.single { it.content.id == "show" }.let { it.seasonNumber to it.episodeNumber })
    }

    @Test
    fun `home next up seed uses furthest watched item when progress is older`() {
        val olderCompletedProgress = progressEntry(
            videoId = "show:4:10",
            title = "Show",
            seasonNumber = 4,
            episodeNumber = 10,
            lastUpdatedEpochMs = 2_000L,
            isCompleted = true,
        )
        val newerWatchedItem = watchedItem(
            id = "show",
            season = 4,
            episode = 14,
            markedAtEpochMs = 1_000L,
        )

        val result = buildHomeNextUpSeedCandidates(
            progressEntries = listOf(olderCompletedProgress),
            watchedItems = listOf(newerWatchedItem),
            isTraktProgressActive = false,
            preferFurthestEpisode = true,
            nowEpochMs = 3_000L,
        )

        assertEquals(4, result.single().seasonNumber)
        assertEquals(14, result.single().episodeNumber)
    }

    @Test
    fun `stale live next up item is dropped when current seed advances`() {
        val staleNextUp = continueWatchingItem(
            videoId = "show:4:11",
            subtitle = "Up Next • S4E11",
            seedSeasonNumber = 4,
            seedEpisodeNumber = 10,
        )

        val result = filterNextUpItemsByCurrentSeeds(
            nextUpItemsBySeries = mapOf("show" to (1_000L to staleNextUp)),
            activeSeedContentIds = setOf("show"),
            currentSeedByContentId = mapOf("show" to (4 to 14)),
            shouldDropItemsWithoutActiveSeed = true,
        )

        assertTrue(result.isEmpty())
    }

    private fun rotationFixture(): List<ContinueWatchingItem> {
        val recentPartial = progressEntry(videoId = "a:1:4", title = "A", lastUpdatedEpochMs = 300L)
        val olderPartial = progressEntry(videoId = "b:1:4", title = "B", lastUpdatedEpochMs = 100L)
        val partialMovie = progressEntry(
            videoId = "movie",
            title = "Movie",
            lastUpdatedEpochMs = 150L,
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
        )
        val newEpisode = continueWatchingItem(videoId = "c:2:1", subtitle = "Up Next • S2E1 • New")
            .copy(isReleaseAlert = true, isNewSeasonRelease = true, released = "2026-10-20")
        val staleNextUp = continueWatchingItem(videoId = "d:1:5", subtitle = "Up Next • S1E5 • Stale")
            .copy(released = "2026-01-01")
        val justFinishedNextUp = continueWatchingItem(videoId = "e:1:5", subtitle = "Up Next • S1E5 • Fresh")
            .copy(released = "2026-02-01")
        val unairedLater = continueWatchingItem(videoId = "f:1:5", subtitle = "Up Next • S1E5 • Later")
            .copy(released = "2099-01-01")
        val unairedSooner = continueWatchingItem(videoId = "g:1:5", subtitle = "Up Next • S1E5 • Sooner")
            .copy(released = "2098-01-01")

        return buildHomeContinueWatchingItems(
            visibleEntries = listOf(recentPartial, olderPartial, partialMovie),
            nextUpItemsBySeries = mapOf(
                "c" to (200L to newEpisode),
                "d" to (50L to staleNextUp),
                "e" to (400L to justFinishedNextUp),
                "f" to (10L to unairedLater),
                "g" to (20L to unairedSooner),
            ),
            sortMode = ContinueWatchingSortMode.ROTATION,
            todayIsoDate = ROTATION_TODAY_ISO_DATE,
        )
    }

    @Test
    fun `rotation keeps partly watched and new episodes in front then least recently watched then unaired`() {
        assertEquals(
            listOf("a:1:4", "c:2:1", "movie", "b:1:4", "d:1:5", "e:1:5", "g:1:5", "f:1:5"),
            rotationFixture().map(ContinueWatchingItem::videoId),
        )
    }

    @Test
    fun `rotation order carries into separated rows`() {
        val rows = splitContinueWatchingRows(
            items = rotationFixture(),
            separateNextUpRow = true,
            separateUpcomingRow = true,
            nowEpochMs = ROTATION_NOW_EPOCH_MS,
        )

        assertEquals(listOf("a:1:4", "movie", "b:1:4"), rows.continueWatching.map(ContinueWatchingItem::videoId))
        assertEquals(listOf("c:2:1", "d:1:5", "e:1:5"), rows.nextUp.map(ContinueWatchingItem::videoId))
        assertEquals(listOf("g:1:5", "f:1:5"), rows.upcoming.map(ContinueWatchingItem::videoId))
    }

    @Test
    fun `rotation is stored as last watched for builds that do not know it`() {
        assertEquals(ContinueWatchingSortMode.DEFAULT, ContinueWatchingSortMode.ROTATION.legacyValue)
        assertEquals(ContinueWatchingSortMode.STREAMING_STYLE, ContinueWatchingSortMode.STREAMING_STYLE.legacyValue)
    }

    private fun progressEntry(
        videoId: String,
        title: String,
        lastUpdatedEpochMs: Long,
        seasonNumber: Int? = 1,
        episodeNumber: Int? = 4,
        episodeTitle: String? = "Episode",
        isCompleted: Boolean = false,
    ): WatchProgressEntry =
        WatchProgressEntry(
            contentType = if (seasonNumber != null && episodeNumber != null) "series" else "movie",
            parentMetaId = videoId.substringBefore(':'),
            parentMetaType = if (seasonNumber != null && episodeNumber != null) "series" else "movie",
            videoId = videoId,
            title = title,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
            lastPositionMs = if (seasonNumber != null && episodeNumber != null) 120_000L else 60_000L,
            durationMs = 1_000_000L,
            lastUpdatedEpochMs = lastUpdatedEpochMs,
            isCompleted = isCompleted,
        )

    private fun continueWatchingItem(
        videoId: String,
        subtitle: String,
        seasonNumber: Int? = 1,
        episodeNumber: Int? = 4,
        seedSeasonNumber: Int? = seasonNumber,
        seedEpisodeNumber: Int? = episodeNumber,
    ): ContinueWatchingItem =
        ContinueWatchingItem(
            parentMetaId = videoId.substringBefore(':'),
            parentMetaType = "series",
            videoId = videoId,
            title = "Show",
            subtitle = subtitle,
            imageUrl = null,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = subtitle.substringAfterLast(" • ", "Episode"),
            isNextUp = true,
            nextUpSeedSeasonNumber = seedSeasonNumber,
            nextUpSeedEpisodeNumber = seedEpisodeNumber,
            resumePositionMs = 0L,
            durationMs = 0L,
            progressFraction = 0f,
        )

    @Test
    fun `a disconnected continue watching provider is not an active remote source`() {
        // The repository resolves a disconnected selection back to Nuvio Sync and shows local rows.
        // Treating it as remote here dropped the watched seeds those rows need for Up Next.
        assertFalse(
            isContinueWatchingRemoteSourceActive(
                source = ContinueWatchingSource.SIMKL,
                connectedProviderIds = emptySet(),
            ),
        )
        assertTrue(
            isContinueWatchingRemoteSourceActive(
                source = ContinueWatchingSource.SIMKL,
                connectedProviderIds = setOf(TrackingProviderId.SIMKL),
            ),
        )
        assertFalse(
            isContinueWatchingRemoteSourceActive(
                source = ContinueWatchingSource.LOCAL,
                connectedProviderIds = setOf(TrackingProviderId.SIMKL),
            ),
        )
    }

    private fun watchedItem(
        id: String,
        season: Int,
        episode: Int,
        markedAtEpochMs: Long,
    ): WatchedItem =
        WatchedItem(
            id = id,
            type = "series",
            name = "Show",
            season = season,
            episode = episode,
            markedAtEpochMs = markedAtEpochMs,
        )

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

        // The rotation fixture's "today": the sort reads the date, the row split reads the
        // instant. They must name the same day, or aired episodes land in the Upcoming row.
        const val ROTATION_TODAY_ISO_DATE = "2026-10-28"
        const val ROTATION_NOW_EPOCH_MS = 1_793_188_800_000L // 2026-10-28T12:00:00Z
    }
}
