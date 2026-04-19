package com.nuvio.app.features.plugins

import kotlin.test.Test
import kotlin.test.assertEquals

class PluginContentIdsTest {

    @Test
    fun `series playback id strips season episode suffix`() {
        assertEquals(
            "tt2575988",
            pluginContentId(
                videoId = "tt2575988:5:8",
                season = 5,
                episode = 8,
            ),
        )
    }

    @Test
    fun `tmdb prefixed series playback id strips prefix and suffix`() {
        assertEquals(
            "12345",
            pluginContentId(
                videoId = "tmdb:12345:2:6",
                season = 2,
                episode = 6,
            ),
        )
    }

    @Test
    fun `movie id stays unchanged`() {
        assertEquals(
            "tt0133093",
            pluginContentId(
                videoId = "tt0133093",
                season = null,
                episode = null,
            ),
        )
    }

    @Test
    fun `slash prefixed tmdb id keeps base content id`() {
        assertEquals(
            "999",
            pluginContentId(
                videoId = "tmdb/999/1/2",
                season = 1,
                episode = 2,
            ),
        )
    }
}
