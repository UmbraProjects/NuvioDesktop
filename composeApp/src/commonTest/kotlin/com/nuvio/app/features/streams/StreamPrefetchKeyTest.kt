package com.nuvio.app.features.streams

import com.nuvio.app.features.metadata.MediaIdResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A prefetched response is only ever useful if the background search and the real play agree on
 * what to call the thing they fetched. Both sides run the same resolver, so these lock the property
 * that matters: the key follows the *resolved* episode identity, not the ids the caller happened to
 * be holding.
 *
 * The anime cases use the Sword Art Online franchise from the real anime-list-mini.json, the same
 * fixture `FranchiseEpisodeIdentityTest` is built on (tvdb 259640: kitsu 6589 = S1, 8174 = S2,
 * 13893 = S3).
 */
class StreamPrefetchKeyTest {

    @Test
    fun `raw and already-resolved ids for one episode share a key`() {
        // The prefetch runs from the details screen holding franchise coordinates; the play runs
        // from a route that already carries the remapped sibling-entry id. Same episode, so the
        // background result has to be findable from either.
        val fromFranchiseCoordinates = StreamPrefetchCache.resolvedContentKey(
            type = "series",
            parentMetaId = "kitsu:8174",
            videoId = "kitsu:8174:3:2",
            title = "Sword Art Online",
            season = 3,
            episode = 2,
        )
        val fromRemappedStreamId = StreamPrefetchCache.resolvedContentKey(
            type = "series",
            parentMetaId = "kitsu:8174",
            videoId = "kitsu:13893:2",
            title = "Sword Art Online",
            season = null,
            episode = 2,
        )

        assertEquals(fromFranchiseCoordinates, fromRemappedStreamId)
    }

    @Test
    fun `franchise seasons that resolve to different entries do not share a key`() {
        val seasonThree = StreamPrefetchCache.resolvedContentKey(
            type = "series",
            parentMetaId = "kitsu:8174",
            videoId = "kitsu:8174:3:2",
            title = "Sword Art Online",
            season = 3,
            episode = 2,
        )
        val seasonOne = StreamPrefetchCache.resolvedContentKey(
            type = "series",
            parentMetaId = "kitsu:8174",
            videoId = "kitsu:8174:1:2",
            title = "Sword Art Online",
            season = 1,
            episode = 2,
        )

        assertNotEquals(seasonThree, seasonOne)
    }

    @Test
    fun `the prefetch key is the request token without its manual-selection flag`() {
        // Deliberate coupling. What a provider returns does not depend on whether the user will be
        // shown a picker, so the cache drops that flag — but if the token's shape ever changes, the
        // key has to be revisited alongside it rather than silently missing forever.
        val key = StreamPrefetchCache.resolvedContentKey(
            type = "series",
            parentMetaId = "tt999",
            videoId = "tt999:1:2",
            title = "Example",
            season = 1,
            episode = 2,
        )
        val token = StreamsRepository.requestToken(
            type = "series",
            videoId = "tt999:1:2",
            parentMetaId = "tt999",
            title = "Example",
            season = 1,
            episode = 2,
            manualSelection = false,
        )

        assertEquals(token.substringBeforeLast("::"), key)
    }

    @Test
    fun `manual selection does not split a key`() {
        val manual = StreamsRepository.requestToken(
            type = "movie",
            videoId = "tt123",
            parentMetaId = "tt123",
            manualSelection = true,
        )
        val automatic = StreamsRepository.requestToken(
            type = "movie",
            videoId = "tt123",
            parentMetaId = "tt123",
            manualSelection = false,
        )
        assertNotEquals(manual, automatic)

        assertEquals(
            manual.substringBeforeLast("::"),
            StreamPrefetchCache.resolvedContentKey(
                type = "movie",
                parentMetaId = "tt123",
                videoId = "tt123",
            ),
        )
        assertEquals(automatic.substringBeforeLast("::"), manual.substringBeforeLast("::"))
    }

    /**
     * The key a prefetch stores under, from the raw ids a details screen or Continue Watching card
     * is holding.
     */
    private fun keyAsPrefetched(
        type: String,
        parentMetaId: String,
        videoId: String,
        title: String?,
        season: Int?,
        episode: Int?,
    ): String = StreamPrefetchCache.resolvedContentKey(
        type = type,
        parentMetaId = parentMetaId,
        videoId = videoId,
        title = title,
        season = season,
        episode = episode,
    )

    /**
     * The key the interactive load ends up computing for the same play — which is *not* simply the
     * same call.
     *
     * `App.kt` resolves the launch once and hands `StreamsScreen` the resolved stream video id
     * together with the **canonical** season/episode; `StreamsRepository.load` then resolves that a
     * second time. So the read side keys on a double resolution of a partly-rewritten identity, and
     * the whole feature is worthless unless that lands where the single resolution did.
     */
    private fun keyAsLoaded(
        type: String,
        parentMetaId: String,
        videoId: String,
        title: String?,
        season: Int?,
        episode: Int?,
    ): String {
        val launchResolved = MediaIdResolver.resolveLocalEpisodeIdentity(
            contentType = type,
            parentMetaId = parentMetaId,
            videoId = videoId,
            title = title,
            season = season,
            episode = episode,
            isAnimeHint = type.equals("anime", ignoreCase = true),
        )
        return StreamPrefetchCache.resolvedContentKey(
            type = type,
            parentMetaId = parentMetaId,
            videoId = launchResolved.videoId,
            title = title,
            season = launchResolved.season ?: season,
            episode = launchResolved.episode ?: episode,
        )
    }

    @Test
    fun `a movie survives the launch round trip`() {
        assertEquals(
            keyAsPrefetched("movie", "tt123", "tt123", "Example", null, null),
            keyAsLoaded("movie", "tt123", "tt123", "Example", null, null),
        )
    }

    @Test
    fun `a plain series episode survives the launch round trip`() {
        assertEquals(
            keyAsPrefetched("series", "tt999", "tt999:1:2", "Example", 1, 2),
            keyAsLoaded("series", "tt999", "tt999:1:2", "Example", 1, 2),
        )
    }

    @Test
    fun `a franchise-numbered anime episode survives the launch round trip`() {
        assertEquals(
            keyAsPrefetched("series", "kitsu:8174", "kitsu:8174:3:2", "Sword Art Online", 3, 2),
            keyAsLoaded("series", "kitsu:8174", "kitsu:8174:3:2", "Sword Art Online", 3, 2),
        )
    }

    @Test
    fun `a split-cour anime episode survives the launch round trip`() {
        assertEquals(
            keyAsPrefetched("series", "kitsu:8174", "kitsu:8174:4:15", "Sword Art Online", 4, 15),
            keyAsLoaded("series", "kitsu:8174", "kitsu:8174:4:15", "Sword Art Online", 4, 15),
        )
    }

    @Test
    fun `an entry-local kitsu episode survives the launch round trip`() {
        assertEquals(
            keyAsPrefetched("series", "kitsu:8174", "kitsu:8174:5", "Sword Art Online II", 1, 5),
            keyAsLoaded("series", "kitsu:8174", "kitsu:8174:5", "Sword Art Online II", 1, 5),
        )
    }

    @Test
    fun `plugin scrapers are keyed per scraper rather than per display group`() {
        // Grouping plugin rows by repository is a display setting that can change between the
        // background search and the play; the cache must not be keyed on it.
        assertNotEquals(
            StreamPrefetchCache.scraperProviderId("scraper-a"),
            StreamPrefetchCache.scraperProviderId("scraper-b"),
        )
        assertEquals("plugin-scraper:scraper-a", StreamPrefetchCache.scraperProviderId("scraper-a"))
    }
}
