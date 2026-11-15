package com.spydermahn.nuvioremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperRulesTest {
    private val phoneWidth = 1080
    private val phoneHeight = 2400

    @Test
    fun posterIsWholeAndCentredBothWays() {
        val box = WallpaperRules.fit(500, 750, phoneWidth, phoneHeight)
        assertEquals(500f / 750f, box.width / box.height, 0.001f) // not stretched
        assertEquals(box.left, phoneWidth - box.right, 0.5f) // same margin left and right
        assertEquals(box.top, phoneHeight - box.bottom, 0.5f) // same margin top and bottom
        assertTrue(box.width <= phoneWidth * WallpaperRules.MAX_WIDTH + 0.5f)
        assertTrue(box.height <= phoneHeight * WallpaperRules.MAX_HEIGHT + 0.5f)
    }

    @Test
    fun albumCoverStaysSquare() {
        val box = WallpaperRules.fit(544, 544, phoneWidth, phoneHeight)
        assertEquals(box.width, box.height, 0.5f)
        assertEquals(phoneWidth * WallpaperRules.MAX_WIDTH, box.width, 0.5f)
    }

    @Test
    fun blurredBackdropCoversTheWholeScreen() {
        val box = WallpaperRules.cover(500, 750, phoneWidth, phoneHeight)
        assertTrue(box.left <= 0f && box.top <= 0f)
        assertTrue(box.right >= phoneWidth && box.bottom >= phoneHeight)
        assertEquals(500f / 750f, box.width / box.height, 0.001f)
    }

    @Test
    fun tmdbPostersAreAskedForLarger() {
        val urls = WallpaperRules.sharperUrls("https://image.tmdb.org/t/p/w342/abc.jpg")
        assertEquals(listOf("https://image.tmdb.org/t/p/w780/abc.jpg", "https://image.tmdb.org/t/p/w342/abc.jpg"), urls)
    }

    @Test
    fun youTubeMusicCoversAreAskedForLarger() {
        val urls = WallpaperRules.sharperUrls("https://lh3.googleusercontent.com/xyz=w544-h544-l90-rj")
        assertEquals("https://lh3.googleusercontent.com/xyz=w1200-h1200-l90-rj", urls.first())
        assertEquals(2, urls.size)
    }

    @Test
    fun otherPicturesAreTriedOnce() {
        assertEquals(listOf("https://picsum.photos/seed/a/300/450"), WallpaperRules.sharperUrls("https://picsum.photos/seed/a/300/450"))
    }

    @Test
    fun chosenPictureIsShrunkButNeverBelowTheScreen() {
        assertEquals(2, WallpaperRules.sampleSize(4000, 6000, phoneWidth, phoneHeight))
        assertEquals(1, WallpaperRules.sampleSize(1000, 2000, phoneWidth, phoneHeight))
    }
}
