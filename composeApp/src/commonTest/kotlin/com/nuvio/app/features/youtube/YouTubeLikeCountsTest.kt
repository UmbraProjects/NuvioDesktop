package com.nuvio.app.features.youtube

import kotlin.test.Test
import kotlin.test.assertEquals

class YouTubeLikeCountsTest {

    @Test
    fun countsAreWordedAsYouTubeDoes() {
        assertEquals("999", formatYouTubeCount(999))
        assertEquals("1K", formatYouTubeCount(1_000))
        assertEquals("1.3K", formatYouTubeCount(1_349))
        assertEquals("1.9K", formatYouTubeCount(1_999))
        assertEquals("13K", formatYouTubeCount(13_900))
        assertEquals("130K", formatYouTubeCount(130_500))
        assertEquals("1.3M", formatYouTubeCount(1_300_000))
        assertEquals("13M", formatYouTubeCount(13_400_000))
        assertEquals("2B", formatYouTubeCount(2_000_000_000))
    }

    @Test
    fun likesGoBetweenViewsAndAge() {
        assertEquals("1.2M views • 13K likes • 3 years ago", youTubeDetailsWithLikes("1.2M views • 3 years ago", 13_000))
        assertEquals("13K likes • 3 years ago", youTubeDetailsWithLikes("3 years ago", 13_000))
        assertEquals("999 likes", youTubeDetailsWithLikes(null, 999))
        assertEquals("1.2M views • 3 years ago", youTubeDetailsWithLikes("1.2M views • 3 years ago", null))
    }

    @Test
    fun likeCountIsReadFromTheAnswer() {
        assertEquals(1234L, parseYouTubeLikeCount("""{"id":"abc","likes":1234,"dislikes":5,"viewCount":9}"""))
        assertEquals(null, parseYouTubeLikeCount("""{"id":"abc"}"""))
        assertEquals(null, parseYouTubeLikeCount("not json"))
    }
}
