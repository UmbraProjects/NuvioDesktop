package com.nuvio.app.features.tmdb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TmdbImageSizeTest {

    @Test
    fun aSizedTmdbBackdropIsAskedForAtFullSize() {
        // What catalog.nuvio.tv supplies, verbatim.
        assertEquals(
            "https://image.tmdb.org/t/p/original/onq6lsJLX8ay5iwxEbKX3qJKeO9.jpg",
            fullSizeTmdbBackdrop("https://image.tmdb.org/t/p/w1280/onq6lsJLX8ay5iwxEbKX3qJKeO9.jpg"),
        )
        assertEquals(
            "https://image.tmdb.org/t/p/original/a.jpg",
            fullSizeTmdbBackdrop("https://image.tmdb.org/t/p/w780/a.jpg"),
        )
    }

    @Test
    fun everythingElsePassesThrough() {
        val untouched = listOf(
            "https://image.tmdb.org/t/p/original/a.jpg",
            "https://images.metahub.space/background/large/tt0083907/img",
            "https://artworks.thetvdb.com/banners/fanart/original/1.jpg",
            "https://example.com/t/p/w1280/a.jpg",
        )
        untouched.forEach { assertEquals(it, fullSizeTmdbBackdrop(it)) }
        assertNull(fullSizeTmdbBackdrop(null))
    }
}
