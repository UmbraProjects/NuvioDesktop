package com.nuvio.app.features.youtube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YouTubeWatchPageParserTest {

    @Test
    fun watchPageDetailsAreRead() {
        val body = """
            {"contents":{"twoColumnWatchNextResults":{"results":{"results":{"contents":[
              {"videoPrimaryInfoRenderer":{
                "title":{"runs":[{"text":"A good video"}]},
                "viewCount":{"videoViewCountRenderer":{"viewCount":{"simpleText":"1,234 views"},"shortViewCount":{"simpleText":"1.2K views"}}},
                "dateText":{"simpleText":"Nov 2, 2026"}}},
              {"videoSecondaryInfoRenderer":{
                "owner":{"videoOwnerRenderer":{
                  "thumbnail":{"thumbnails":[{"url":"//yt3.ggpht.com/small"},{"url":"//yt3.ggpht.com/big"}]},
                  "title":{"runs":[{"text":"Chan","navigationEndpoint":{"browseEndpoint":{"browseId":"UCabcdefghijklmnopqrstuv"}}}]},
                  "subscriberCountText":{"simpleText":"12K subscribers"}}},
                "attributedDescription":{"content":"Line one\nLine two"},
                "subscribeButton":{"subscribeButtonRenderer":{"subscribed":true}}}}
            ]}}}}}
        """.trimIndent()

        val details = parseYouTubeVideoDetails(body)!!

        assertEquals("A good video", details.title)
        assertEquals("1,234 views", details.views)
        assertEquals("Nov 2, 2026", details.published)
        assertEquals("Line one\nLine two", details.description)
        assertEquals("Chan", details.channelName)
        assertEquals("UCabcdefghijklmnopqrstuv", details.channelId)
        assertEquals("https://yt3.ggpht.com/big", details.channelAvatarUrl)
        assertEquals("12K subscribers", details.subscribers)
        assertEquals(true, details.subscribed)
    }

    @Test
    fun anAnswerWithoutTheRenderersHasNoDetails() {
        assertNull(parseYouTubeVideoDetails("""{"contents":{}}"""))
    }

    @Test
    fun threadsCarryTheirReplyTokenAndParams() {
        val body = """
            {"onResponseReceivedEndpoints":[{"reloadContinuationItemsCommand":{"continuationItems":[
              {"commentsHeaderRenderer":{"createRenderer":{"commentSimpleboxRenderer":{"submitButton":{"buttonRenderer":{"serviceEndpoint":{"createCommentEndpoint":{"createCommentParams":"NEW"}}}}}}}},
              {"commentThreadRenderer":{
                "commentViewModel":{"commentViewModel":{"commentKey":"K1","toolbarSurfaceKey":"T1"}},
                "replies":{"commentRepliesRenderer":{"contents":[{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"REPLIES"}}}}]}}}}
            ]}}],
             "frameworkUpdates":{"entityBatchUpdate":{"mutations":[
               {"entityKey":"K1","payload":{"commentEntityPayload":{"key":"K1",
                 "properties":{"commentId":"c1","content":{"content":"Nice"},"publishedTime":"1 day ago"},
                 "author":{"displayName":"@someone"},"toolbar":{"replyCount":"3"}}}},
               {"entityKey":"T1","payload":{"engagementToolbarSurfaceEntityPayload":{"replyCommand":{"innertubeCommand":{
                 "createCommentReplyDialogEndpoint":{"dialog":{"commentReplyDialogRenderer":{"replyButton":{"buttonRenderer":{
                   "serviceEndpoint":{"createCommentReplyEndpoint":{"createReplyParams":"REPLY"}}}}}}}}}}}}
             ]}}}
        """.trimIndent()

        val page = parseYouTubeComments(body)

        assertEquals("NEW", page.createParams)
        val comment = page.comments.single()
        assertEquals("REPLIES", comment.repliesToken)
        assertEquals("REPLY", comment.replyParams)
        assertEquals("3", comment.replies)
    }

    @Test
    fun aRepliesPageListsEachReply() {
        val body = """
            {"onResponseReceivedEndpoints":[{"appendContinuationItemsAction":{"continuationItems":[
              {"commentViewModel":{"commentKey":"R1"}},
              {"commentViewModel":{"commentKey":"R2"}},
              {"continuationItemRenderer":{"button":{"buttonRenderer":{"command":{"continuationCommand":{"token":"MORE"}}}}}}
            ]}}],
             "frameworkUpdates":{"entityBatchUpdate":{"mutations":[
               {"entityKey":"R1","payload":{"commentEntityPayload":{"key":"R1","properties":{"commentId":"r1","content":{"content":"First"}},"author":{"displayName":"@a"}}}},
               {"entityKey":"R2","payload":{"commentEntityPayload":{"key":"R2","properties":{"commentId":"r2","content":{"content":"Second"}},"author":{"displayName":"@b"}}}}
             ]}}}
        """.trimIndent()

        val page = parseYouTubeCommentReplies(body)

        assertEquals(listOf("First", "Second"), page.comments.map { it.text })
        assertEquals("MORE", page.continuation)
    }
}
