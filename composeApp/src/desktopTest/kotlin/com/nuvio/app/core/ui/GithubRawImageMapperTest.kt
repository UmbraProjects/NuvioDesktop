package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GithubRawImageMapperTest {
    @Test
    fun blobLinkWithRawFlagPointsAtRawHost() {
        assertEquals(
            "https://raw.githubusercontent.com/bramst0ne/prism-wallpapers/main/collections/networks/213-netflix/backdrops/t2_flat_1080p.jpg",
            githubRawImageUrl("https://github.com/bramst0ne/prism-wallpapers/blob/main/collections/networks/213-netflix/backdrops/t2_flat_1080p.jpg?raw=true"),
        )
    }

    @Test
    fun rawLinkPointsAtRawHost() {
        assertEquals(
            "https://raw.githubusercontent.com/o/r/main/a/b.png",
            githubRawImageUrl("https://github.com/o/r/raw/main/a/b.png"),
        )
    }

    @Test
    fun otherUrlsAreLeftAlone() {
        assertNull(githubRawImageUrl("https://github.com/o/r/blob/main/a/b.png"))
        assertNull(githubRawImageUrl("https://raw.githubusercontent.com/o/r/main/a/b.png"))
        assertNull(githubRawImageUrl("https://image.tmdb.org/t/p/original/x.jpg"))
    }
}
