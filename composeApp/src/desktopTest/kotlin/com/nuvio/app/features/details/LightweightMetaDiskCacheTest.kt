package com.nuvio.app.features.details

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LightweightMetaDiskCacheTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Test
    fun everyFieldALightweightConsumerReadsSurvivesTheRoundTrip() {
        val meta = MetaDetails(
            id = "tt0944947",
            type = "series",
            name = "Game of Thrones",
            tmdbId = 1399,
            imdbId = "tt0944947",
            malId = null,
            tvdbId = "121361",
            poster = "https://image.tmdb.org/t/p/w500/p.jpg",
            background = "https://image.tmdb.org/t/p/original/b.jpg",
            logo = "https://image.tmdb.org/t/p/w500/l.png",
            description = "Seven noble families fight for control.",
            releaseInfo = "2011",
            lastAirDate = "2019-05-19",
            status = "Ended",
            imdbRating = "9.2",
            ageRating = "TV-MA",
            runtime = "60m",
            genres = listOf("Drama", "Fantasy"),
            country = "US",
            language = "en",
            imdbTmdbIdentityTrusted = false,
            cast = listOf(MetaPerson(name = "Not kept")),
            videos = listOf(
                MetaVideo(id = "tt0944947:1:1", title = "Winter Is Coming", season = 1, episode = 1, thumbnail = "x", overview = "y"),
                MetaVideo(id = "tt0944947:1:2", title = "The Kingsroad", season = 1, episode = 2),
            ),
        )

        val restored = json.decodeFromString<StoredLightweightMeta>(
            json.encodeToString(meta.toStored(context = "ctx", savedAtMs = 1L)),
        ).toMetaDetails()

        assertEquals(
            meta.copy(cast = emptyList(), videos = emptyList(), lightweightEpisodeCount = 2),
            restored,
        )
        // The episodes themselves are not kept (2026-11-23), only how many there were: Random Play's hint.
        assertTrue(restored.videos.isEmpty())
        assertEquals(2, restored.lightweightEpisodeCount)
        assertTrue(restored.cast.isEmpty())
    }

    @Test
    fun anOlderEntryWithEpisodesIsCountedAndSlimmed() {
        val old = """{"context":"ctx","savedAtMs":1,"id":"tt1","type":"series","name":"Show",
            "videos":[{"id":"tt1:1:1","title":"One"},{"id":"tt1:1:2","title":"Two"},{"id":"tt1:1:3"}]}"""
        val stored = json.decodeFromString<StoredLightweightMeta>(old)
        val slim = stored.slimmed()
        assertTrue(slim.videos.isEmpty())
        assertEquals(3, slim.episodeCount)
        assertEquals(3, slim.toMetaDetails().lightweightEpisodeCount)
        // Nothing to slim: the same instance, so the loader can tell which entries changed.
        assertTrue(slim.slimmed() === slim)
        assertTrue(!json.encodeToString(slim).contains("videos"))
    }

    @Test
    fun aLightweightResultDropsItsEpisodesButKeepsTheCount() {
        val meta = MetaDetails(
            id = "tt1", type = "series", name = "Show",
            videos = listOf(MetaVideo(id = "tt1:1:1", title = "One"), MetaVideo(id = "tt1:1:2", title = "Two")),
        )
        val slim = meta.withoutEpisodeList()
        assertTrue(slim.videos.isEmpty())
        assertEquals(2, slim.lightweightEpisodeCount)
        assertTrue(slim.withoutEpisodeList() === slim)
    }
}
