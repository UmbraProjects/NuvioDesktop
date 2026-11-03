package com.nuvio.app.features.youtube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YouTubeCommentsTest {

    @Test
    fun theCommentsTokenComesFromThePanelsContentNotItsSortMenu() {
        val body = """
            {"engagementPanels":[
              {"engagementPanelSectionListRenderer":{"panelIdentifier":"engagement-panel-structured-description",
                "content":{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"DESCRIPTION"}}}}}},
              {"engagementPanelSectionListRenderer":{"panelIdentifier":"engagement-panel-comments-section",
                "header":{"menu":{"continuationCommand":{"token":"SORT"}}},
                "content":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
                  {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"COMMENTS"}}}}]}}]}}}}
            ]}
        """.trimIndent()
        assertEquals("COMMENTS", parseYouTubeCommentsToken(body))
        assertNull(parseYouTubeCommentsToken("""{"engagementPanels":[]}"""))
    }

    @Test
    fun readsCommentsFromTheirEntitiesAndTheNextPageToken() {
        val body = """
            {"onResponseReceivedEndpoints":[{"appendContinuationItemsAction":{"continuationItems":[
              {"commentThreadRenderer":{
                "commentViewModel":{"commentViewModel":{"commentKey":"k1","pinnedText":"Pinned by Creator"}},
                "replies":{"commentRepliesRenderer":{"subThreads":[{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"REPLIES"}}}}]}}}},
              {"commentThreadRenderer":{"commentViewModel":{"commentViewModel":{"commentKey":"k2"}}}},
              {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"NEXT"}}}}
            ]}}],
            "frameworkUpdates":{"entityBatchUpdate":{"mutations":[
              {"payload":{"commentEntityPayload":{"key":"k2","properties":{"commentId":"c2","content":{"content":"Second"},"publishedTime":"1 day ago"},
                "author":{"displayName":"@two"},"toolbar":{"likeCountNotliked":"0","replyCount":""}}}},
              {"payload":{"commentEntityPayload":{"key":"k1","properties":{"commentId":"c1","content":{"content":"First!"},"publishedTime":"2 days ago"},
                "author":{"displayName":"@one","avatarThumbnailUrl":"https://yt3.ggpht.com/one"},"toolbar":{"likeCountNotliked":"1.2K","replyCount":"12"}}}}
            ]}}}
        """.trimIndent()

        val page = parseYouTubeComments(body)

        // In the threads' order, not the entities'.
        assertEquals(listOf("c1", "c2"), page.comments.map { it.id })
        val first = page.comments.first()
        assertEquals("@one", first.author)
        assertEquals("First!", first.text)
        assertEquals("1.2K", first.likes)
        assertEquals("12", first.replies)
        assertTrue(first.pinned)
        assertNull(page.comments[1].likes)
        assertEquals("NEXT", page.continuation)
    }

    @Test
    fun readsTheOlderCommentRendererShape() {
        val body = """
            {"continuationItems":[{"commentThreadRenderer":{"comment":{"commentRenderer":{
              "commentId":"old","authorText":{"simpleText":"@old"},"contentText":{"runs":[{"text":"Hello "},{"text":"there"}]},
              "publishedTimeText":{"runs":[{"text":"5 years ago"}]},"voteCount":{"simpleText":"42"},"replyCount":3}}}}]}
        """.trimIndent()
        val comment = parseYouTubeComments(body).comments.single()
        assertEquals("Hello there", comment.text)
        assertEquals("42", comment.likes)
        assertEquals("3", comment.replies)
    }
}
