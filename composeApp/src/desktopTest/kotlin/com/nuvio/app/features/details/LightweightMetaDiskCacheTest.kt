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
            meta.copy(
                cast = emptyList(),
                videos = meta.videos.map { it.copy(thumbnail = null, overview = null) },
            ),
            restored,
        )
        // Random Play counts episodes; the player resolver matches ids and coordinates.
        assertEquals(2, restored.videos.size)
        assertTrue(restored.cast.isEmpty())
    }
}
