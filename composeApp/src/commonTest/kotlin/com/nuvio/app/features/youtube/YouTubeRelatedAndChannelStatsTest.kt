package com.nuvio.app.features.youtube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YouTubeRelatedAndChannelStatsTest {

    private fun lockup(id: String) = """
        {"lockupViewModel":{
          "contentId":"$id","contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
          "contentImage":{"thumbnailViewModel":{"image":{"sources":[{"url":"https://i.ytimg.com/vi/$id/a.jpg"}]},
            "overlays":[{"thumbnailBottomOverlayViewModel":{"badges":[{"thumbnailBadgeViewModel":{"text":"4:20","badgeStyle":"THUMBNAIL_OVERLAY_BADGE_STYLE_DEFAULT"}}]}}]}},
          "metadata":{"lockupMetadataViewModel":{"title":{"content":"Video $id"},
            "metadata":{"contentMetadataViewModel":{"metadataRows":[{"metadataParts":[{"text":{"content":"Channel"}}]},{"metadataParts":[{"text":{"content":"1K views"}},{"text":{"content":"1 day ago"}}]}]}}}}
        }}
    """.trimIndent()

    private fun continuationItem(token: String) =
        """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$token"}}}}"""

    @Test
    fun relatedTokenIsTheSideColumnsNotTheComments() {
        // The comments' continuation sits in the primary results; it must not be taken for the column's.
        val body = """
            {"contents":{"twoColumnWatchNextResults":{
              "results":{"results":{"contents":[{"itemSectionRenderer":{"contents":[${continuationItem("COMMENTS")}]}}]}},
              "secondaryResults":{"secondaryResults":{"results":[${lockup("aaaaaaaaaaa")},${lockup("bbbbbbbbbbb")},${continuationItem("RELATED")}]}}
            }}}
        """.trimIndent()
        val page = parseYouTubeRelated(body)
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), page.videos.map { it.videoId })
        assertEquals("RELATED", page.continuation)
    }

    @Test
    fun relatedContinuationPageIsReadWhole() {
        val body = """
            {"onResponseReceivedEndpoints":[{"appendContinuationItemsAction":{"continuationItems":[${lockup("ccccccccccc")},${continuationItem("NEXT")}]}}]}
        """.trimIndent()
        val page = parseYouTubeRelated(body)
        assertEquals(listOf("ccccccccccc"), page.videos.map { it.videoId })
        assertEquals("NEXT", page.continuation)
    }

    @Test
    fun channelStatsFromPageHeader() {
        val body = """
            {"header":{"pageHeaderRenderer":{"content":{"pageHeaderViewModel":{
              "metadata":{"contentMetadataViewModel":{"metadataRows":[
                {"metadataParts":[{"text":{"content":"@somechannel"}}]},
                {"metadataParts":[{"text":{"content":"1.2M subscribers"}},{"text":{"content":"345 videos"}}]}
              ]}}
            }}}}}
        """.trimIndent()
        assertEquals(YouTubeChannelStats("1.2M subscribers", "345 videos"), parseYouTubeChannelStats(body))
    }

    @Test
    fun channelStatsFromOlderHeader() {
        val body = """
            {"header":{"c4TabbedHeaderRenderer":{"subscriberCountText":{"simpleText":"12K subscribers"},"videosCountText":{"runs":[{"text":"1 video"}]}}}}
        """.trimIndent()
        assertEquals(YouTubeChannelStats("12K subscribers", "1 video"), parseYouTubeChannelStats(body))
    }

    @Test
    fun noStatsWhenThePageHasNoHeader() {
        assertNull(parseYouTubeChannelStats("""{"contents":{}}"""))
    }
}
