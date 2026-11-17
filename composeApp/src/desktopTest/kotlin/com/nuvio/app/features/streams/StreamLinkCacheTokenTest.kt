package com.nuvio.app.features.streams

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Upstream 3d70db683: debrid links carry an expiring token and are still saved for reuse. */
class StreamLinkCacheTokenTest {
    private val key = StreamLinkCacheRepository.contentKey(type = "movie", videoId = "tt-token-test")

    @AfterTest
    fun cleanUp() {
        StreamLinkCacheRepository.remove(key)
    }

    @Test
    fun linkWithExpiringTokenIsKept() {
        val tokenUrl = "https://cdn.example.com/dld/file-id?token=abc&expires=1234"

        StreamLinkCacheRepository.save(
            contentKey = key,
            url = tokenUrl,
            streamName = "1080p",
            addonName = "Test",
            addonId = "addon:test",
        )

        assertEquals(tokenUrl, StreamLinkCacheRepository.getValid(key, ONE_HOUR_MS)?.url)
    }

    private companion object {
        const val ONE_HOUR_MS = 60L * 60L * 1000L
    }
}
