package com.nuvio.app.features.youtube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YouTubeDeArrowTest {

    private fun body(titles: String, thumbnails: String) =
        """{"vid":{"titles":[$titles],"thumbnails":[$thumbnails],"randomTime":0.4,"videoDuration":600},"other":{"titles":[]}}"""

    @Test
    fun theTopTrustedSubmissionReplacesYouTubesOwn() {
        val branding = parseYouTubeBranding(
            body(
                titles = """{"title":">I tested 5 budget keyboards","original":false,"votes":3,"locked":false},
                    {"title":"YOU WON'T BELIEVE THIS KEYBOARD","original":true,"votes":0,"locked":false}""",
                thumbnails = """{"timestamp":123.5,"original":false,"votes":1,"locked":false}""",
            ),
            "vid",
        )
        assertEquals("I tested 5 budget keyboards", branding.title)
        assertEquals("https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=vid&time=123.5", branding.thumbnailUrl)
    }

    @Test
    fun whenTheCommunityKeptTheOriginalNothingIsReplaced() {
        val branding = parseYouTubeBranding(
            body(
                titles = """{"title":"Original","original":true,"votes":5,"locked":false},
                    {"title":"A worse rewrite","original":false,"votes":0,"locked":false}""",
                thumbnails = """{"original":true,"votes":2,"locked":false}""",
            ),
            "vid",
        )
        assertNull(branding.title)
        assertNull(branding.thumbnailUrl)
    }

    @Test
    fun aVotedDownSubmissionIsPassedOver() {
        val branding = parseYouTubeBranding(
            body(titles = """{"title":"Spam","original":false,"votes":-2,"locked":false}""", thumbnails = ""),
            "vid",
        )
        assertNull(branding.title)
        assertNull(parseYouTubeBranding("{}", "vid").title)
    }
}
