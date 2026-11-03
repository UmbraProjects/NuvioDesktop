package com.nuvio.app.features.youtube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YouTubeFeedParserTest {

    private fun lockup(
        id: String,
        title: String,
        rows: List<List<String>>,
        badge: String = "10:54",
        badgeStyle: String = "THUMBNAIL_OVERLAY_BADGE_STYLE_DEFAULT",
        contentType: String = "LOCKUP_CONTENT_TYPE_VIDEO",
    ): String {
        val rowsJson = rows.joinToString(",") { parts ->
            """{"metadataParts":[${parts.joinToString(",") { """{"text":{"content":"$it"}}""" }}]}"""
        }
        return """
            {"richItemRenderer":{"content":{"lockupViewModel":{
              "contentId":"$id","contentType":"$contentType",
              "contentImage":{"thumbnailViewModel":{
                "image":{"sources":[{"url":"https://i.ytimg.com/vi/$id/small.jpg"},{"url":"https://i.ytimg.com/vi/$id/big.jpg"}]},
                "overlays":[{"thumbnailBottomOverlayViewModel":{"badges":[{"thumbnailBadgeViewModel":{"text":"$badge","badgeStyle":"$badgeStyle"}}]}}]
              }},
              "metadata":{"lockupMetadataViewModel":{
                "title":{"content":"$title"},
                "metadata":{"contentMetadataViewModel":{"metadataRows":[$rowsJson]}}
              }}
            }}}}
        """.trimIndent()
    }

    private fun feed(vararg items: String, continuation: String? = null): String {
        val tail = continuation?.let {
            """,{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$it"}}}}"""
        }.orEmpty()
        return """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"richGridRenderer":{"contents":[${items.joinToString(",")}$tail]}}}}]}}}"""
    }

    @Test
    fun marksPastLiveStreams() {
        val page = parseYouTubeFeed(
            feed(
                lockup("s1", "Late stream", listOf(listOf("Chan"), listOf("1K views", "Streamed 13 hours ago")), badge = "3:12:40"),
                lockup("v1", "Upload", listOf(listOf("Chan"), listOf("1K views", "13 hours ago"))),
            ),
        )

        assertEquals(listOf(true, false), page.videos.map { it.wasLive })
    }

    @Test
    fun readsPlaylistRowsAndSkipsUnplayableOnes() {
        val body = """
            {"contents":[{"playlistVideoListRenderer":{"contents":[
              {"playlistVideoRenderer":{"videoId":"p1","title":{"runs":[{"text":"Saved one"}]},
                "shortBylineText":{"runs":[{"text":"Chan","navigationEndpoint":{"browseEndpoint":{"browseId":"UCchan"}}}]},
                "videoInfo":{"runs":[{"text":"1.2M views"},{"text":" • "},{"text":"3 years ago"}]},
                "lengthText":{"simpleText":"4:05"},"isPlayable":true}},
              {"playlistVideoRenderer":{"videoId":"p2","title":{"runs":[{"text":"[Private video]"}]},"isPlayable":false}}
            ]}}]}
        """.trimIndent()

        val page = parseYouTubeFeed(body)

        assertEquals(listOf("p1"), page.videos.map { it.videoId })
        assertEquals("1.2M views • 3 years ago", page.videos.single().details)
        assertEquals("UCchan", page.videos.single().channelId)
    }

    @Test
    fun readsThePlaylistLibraryAndTheSaveChoices() {
        val library = """
            {"contents":[{"lockupViewModel":{"contentId":"PLabc","contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST",
              "contentImage":{"collectionThumbnailViewModel":{"primaryThumbnail":{"thumbnailViewModel":{
                "image":{"sources":[{"url":"https://i.ytimg.com/a.jpg"}]},
                "overlays":[{"thumbnailOverlayBadgeViewModel":{"thumbnailBadges":[{"thumbnailBadgeViewModel":{"text":"12 videos"}}]}}]}}}},
              "metadata":{"lockupMetadataViewModel":{"title":{"content":"Mine"}}}}},
             {"lockupViewModel":{"contentId":"vid","contentType":"LOCKUP_CONTENT_TYPE_VIDEO"}}]}
        """.trimIndent()
        val playlists = parseYouTubePlaylists(library)
        assertEquals(listOf("PLabc"), playlists.map { it.playlistId })
        assertEquals("Mine", playlists.single().title)
        assertEquals("https://i.ytimg.com/a.jpg", playlists.single().thumbnailUrl)
        assertEquals("12 videos", playlists.single().details)

        val choices = """
            {"contents":[{"addToPlaylistRenderer":{"playlists":[
              {"playlistAddToOptionRenderer":{"playlistId":"WL","title":{"simpleText":"Watch later"},"containsSelectedVideos":"ALL"}},
              {"playlistAddToOptionRenderer":{"playlistId":"PLabc","title":{"simpleText":"Mine"},"containsSelectedVideos":"NONE"}}
            ]}}]}
        """.trimIndent()
        val options = parseYouTubePlaylistOptions(choices)
        assertEquals(listOf(true, false), options.map { it.containsVideo })
    }

    @Test
    fun aVideoRendererKeepsItsChannelId() {
        val body = """
            {"contents":[{"videoRenderer":{"videoId":"vid1","title":{"simpleText":"T"},
              "ownerText":{"runs":[{"text":"Some Channel","navigationEndpoint":{"browseEndpoint":{"browseId":"UCabc","canonicalBaseUrl":"/@some"}}}]}}}]}
        """.trimIndent()
        assertEquals("UCabc", parseYouTubeFeed(body).videos.single().channelId)
    }

    @Test
    fun readsTheSubscribedChannelsList() {
        val body = """
            {"contents":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[{"shelfRenderer":{"content":{"expandedShelfContentsRenderer":{"items":[
              {"channelRenderer":{"channelId":"UC1","title":{"simpleText":"First"},
                "thumbnail":{"thumbnails":[{"url":"//yt3.ggpht.com/a=s88"},{"url":"//yt3.ggpht.com/a=s176"}]},
                "videoCountText":{"simpleText":"@first"},"subscriberCountText":{"simpleText":"1.2M subscribers"}}},
              {"channelRenderer":{"channelId":"UC2","title":{"simpleText":"Second"}}}
            ]}}}}]}},
            {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"MORE"}}}}]}}}
        """.trimIndent()

        val page = parseYouTubeChannelList(body)

        assertEquals(listOf("UC1", "UC2"), page.channels.map { it.channelId })
        assertEquals("https://yt3.ggpht.com/a=s176", page.channels[0].avatarUrl)
        assertEquals("@first • 1.2M subscribers", page.channels[0].details)
        assertEquals("MORE", page.continuation)
    }

    @Test
    fun readsWhetherTheAccountLikesTheVideo() {
        val liked = """{"frameworkUpdates":{"entityBatchUpdate":{"mutations":[{"payload":{"likeStatusEntity":{"key":"k","likeStatus":"LIKE"}}}]}}}"""
        assertEquals(true, parseYouTubeLikeStatus(liked))
        val neutral = """{"contents":{"likeButtonRenderer":{"likeStatus":"INDIFFERENT"}}}"""
        assertEquals(false, parseYouTubeLikeStatus(neutral))
        assertNull(parseYouTubeLikeStatus("""{"contents":{}}"""))
    }

    @Test
    fun readsWhetherTheAccountIsSubscribed() {
        val entity = """{"frameworkUpdates":{"entityBatchUpdate":{"mutations":[{"payload":{"subscriptionStateEntity":{"key":"k","subscribed":true}}}]}}}"""
        assertEquals(true, parseYouTubeSubscribed(entity))
        val button = """{"header":{"c4TabbedHeaderRenderer":{"subscribeButton":{"subscribeButtonRenderer":{"subscribed":false}}}}}"""
        assertEquals(false, parseYouTubeSubscribed(button))
        assertNull(parseYouTubeSubscribed("""{"header":{}}"""))
    }

    @Test
    fun readsSearchResultsAndSkipsChannelsAndShorts() {
        // The `search` answer: an item section of video renderers, with a channel result and a
        // Shorts shelf mixed in, and the next page's token after the section.
        val body = """
            {"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[
              {"itemSectionRenderer":{"contents":[
                {"channelRenderer":{"channelId":"UC1","title":{"simpleText":"Some Channel"}}},
                {"videoRenderer":{"videoId":"vid1","title":{"runs":[{"text":"First result"}]},
                  "ownerText":{"runs":[{"text":"Some Channel"}]},
                  "viewCountText":{"simpleText":"1,234 views"},"publishedTimeText":{"simpleText":"2 days ago"},
                  "lengthText":{"simpleText":"12:34"},
                  "thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/vid1/hq.jpg"}]}}},
                {"reelShelfRenderer":{"items":[{"videoRenderer":{"videoId":"short1","title":{"simpleText":"A short"}}}]}}
              ]}},
              {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"NEXT"}}}}
            ]}}}}}
        """.trimIndent()

        val page = parseYouTubeFeed(body)

        assertEquals(listOf("vid1"), page.videos.map { it.videoId })
        assertEquals("Some Channel", page.videos.single().channel)
        assertEquals("1,234 views • 2 days ago", page.videos.single().details)
        assertEquals("12:34", page.videos.single().duration)
        assertEquals("NEXT", page.continuation)
    }

    @Test
    fun readsFeedLockupsWithChannelRow() {
        val page = parseYouTubeFeed(
            feed(
                lockup("aaaaaaaaaaa", "First", listOf(listOf("Some Channel"), listOf("1.2M views", "3 days ago"))),
                continuation = "NEXT",
            ),
        )
        val video = page.videos.single()
        assertEquals("aaaaaaaaaaa", video.videoId)
        assertEquals("First", video.title)
        assertEquals("Some Channel", video.channel)
        assertEquals("1.2M views • 3 days ago", video.details)
        assertEquals("10:54", video.duration)
        assertEquals("https://i.ytimg.com/vi/aaaaaaaaaaa/big.jpg", video.thumbnailUrl)
        assertEquals("NEXT", page.continuation)
    }

    @Test
    fun readsTheWatchedBar() {
        val watched = lockup("wwwwwwwwwww", "Half", listOf(listOf("Ch"), listOf("1 view"))).replace(
            "\"overlays\":[",
            "\"overlays\":[{\"thumbnailOverlayProgressBarViewModel\":{\"startPercent\":42}},",
        )
        val page = parseYouTubeFeed(feed(watched, lockup("nnnnnnnnnnn", "Fresh", listOf(listOf("Ch"), listOf("1 view")))))
        assertEquals(listOf(42, null), page.videos.map { it.watchedPercent })
    }

    @Test
    fun channelPageLockupHasNoChannel() {
        val video = parseYouTubeFeed(feed(lockup("bbbbbbbbbbb", "Own", listOf(listOf("10M views", "4 days ago"))))).videos.single()
        assertNull(video.channel)
        assertEquals("10M views • 4 days ago", video.details)
    }

    @Test
    fun skipsLiveUpcomingPlaylistsAndShelves() {
        val shortsShelf = """{"richSectionRenderer":{"content":{"richShelfRenderer":{"contents":[${
            lockup("sssssssssss", "In a shelf", listOf(listOf("Ch"), listOf("1 view")))
        }]}}}}"""
        val page = parseYouTubeFeed(
            feed(
                lockup("lllllllllll", "Live", listOf(listOf("Ch"), listOf("500 watching")), badge = "LIVE", badgeStyle = "THUMBNAIL_OVERLAY_BADGE_STYLE_LIVE"),
                lockup("uuuuuuuuuuu", "Soon", listOf(listOf("Ch"), listOf("Scheduled for 11/3/26, 8:00 PM"))),
                lockup("ppppppppppp", "A playlist", listOf(listOf("Ch")), contentType = "LOCKUP_CONTENT_TYPE_PLAYLIST"),
                shortsShelf,
                lockup("kkkkkkkkkkk", "Keep", listOf(listOf("Ch"), listOf("2 views"))),
            ),
        )
        assertEquals(listOf("kkkkkkkkkkk"), page.videos.map { it.videoId })
        assertNull(page.continuation)
    }

    @Test
    fun readsOlderVideoRenderer() {
        val item = """
            {"richItemRenderer":{"content":{"videoRenderer":{
              "videoId":"rrrrrrrrrrr",
              "title":{"runs":[{"text":"Old "},{"text":"shape"}]},
              "ownerText":{"runs":[{"text":"Channel R"}]},
              "shortViewCountText":{"simpleText":"5K views"},
              "publishedTimeText":{"simpleText":"1 hour ago"},
              "lengthText":{"simpleText":"1:02:03"},
              "thumbnail":{"thumbnails":[{"url":"https://example/1.jpg"},{"url":"https://example/2.jpg"}]},
              "thumbnailOverlays":[{"thumbnailOverlayResumePlaybackRenderer":{"percentDurationWatched":100}}]
            }}}}
        """.trimIndent()
        val video = parseYouTubeFeed(feed(item)).videos.single()
        assertEquals("Old shape", video.title)
        assertEquals("Channel R", video.channel)
        assertEquals("5K views • 1 hour ago", video.details)
        assertEquals("1:02:03", video.duration)
        assertEquals("https://example/2.jpg", video.thumbnailUrl)
        assertEquals(100, video.watchedPercent)
    }

    @Test
    fun readsContinuationPageAndDedupes() {
        val body = """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[
            ${lockup("ccccccccccc", "Next page", listOf(listOf("Ch"), listOf("1 view")))},
            ${lockup("ccccccccccc", "Next page", listOf(listOf("Ch"), listOf("1 view")))},
            {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"MORE"}}}}
        ]}}]}"""
        val page = parseYouTubeFeed(body)
        assertEquals(1, page.videos.size)
        assertEquals("MORE", page.continuation)
    }

    @Test
    fun garbageIsAnEmptyPage() {
        val page = parseYouTubeFeed("<html>not json")
        assertTrue(page.videos.isEmpty())
        assertNull(page.continuation)
    }
}
