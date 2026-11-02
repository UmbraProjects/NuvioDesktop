package com.nuvio.app.features.youtube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YouTubeChannelsTest {

    private fun item(name: String, pageId: String?, hasChannel: Boolean, handle: String? = null): String {
        val tokens = buildList {
            pageId?.let { add("""{"pageIdToken":{"pageId":"$it"}}""") }
            add("""{"accountStateToken":{"hasChannel":$hasChannel,"isMerged":false}}""")
        }.joinToString(",")
        val handleJson = handle?.let { ""","channelHandle":{"simpleText":"$it"}""" }.orEmpty()
        return """{"accountItem":{"accountName":{"simpleText":"$name"}$handleJson,
            "accountPhoto":{"thumbnails":[{"url":"https://yt3/$name.jpg"}]},
            "serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[$tokens]}}}}"""
    }

    private fun list(vararg items: String) =
        """{"actions":[{"getMultiPageMenuAction":{"menu":{"multiPageMenuRenderer":{"sections":[
            {"accountSectionListRenderer":{"contents":[{"accountItemSectionRenderer":{"contents":[${items.joinToString(",")}]}}]}}
        ]}}}}]}"""

    @Test
    fun readsProfileAndBrandChannels() {
        val channels = parseYouTubeChannels(
            list(
                item("Profile", pageId = null, hasChannel = false),
                item("Umbra", pageId = "111222333444555666777", hasChannel = true, handle = "@umbra"),
            ),
        )
        assertEquals(2, channels.size)
        assertNull(channels[0].pageId)
        assertEquals(false, channels[0].hasChannel)
        assertEquals("111222333444555666777", channels[1].pageId)
        assertEquals("@umbra", channels[1].handle)
        assertEquals("https://yt3/Umbra.jpg", channels[1].photoUrl)
    }

    @Test
    fun picksTheOnlyBrandChannelWhenTheProfileHasNone() {
        val channels = parseYouTubeChannels(
            list(item("Profile", null, hasChannel = false), item("Umbra", "1", hasChannel = true)),
        )
        assertEquals("1", autoChannelPageId(channels))
    }

    @Test
    fun keepsTheProfileWhenItHasAChannel() {
        val channels = parseYouTubeChannels(
            list(item("Profile", null, hasChannel = true), item("Umbra", "1", hasChannel = true)),
        )
        assertNull(autoChannelPageId(channels))
    }

    @Test
    fun readsTheChannelSwitcherShape() {
        val body = """{"actions":[{"updateChannelSwitcherPageAction":{"page":{"channelSwitcherPageRenderer":{"contents":[
            ${item("Umbra", "999", true).replace("\"accountItem\"", "\"accountItemRenderer\"")}
        ]}}}}]}"""
        val channels = parseYouTubeChannels(body)
        assertEquals(listOf("999"), channels.map { it.pageId })
        // No profile entry in the switcher: the one brand channel is still picked.
        assertEquals("999", autoChannelPageId(channels))
    }

    @Test
    fun severalBrandChannelsWithoutAProfileEntryAsk() {
        val body = """{"contents":[${item("A", "1", true).replace("\"accountItem\"", "\"accountItemRenderer\"")},
            ${item("B", "2", true).replace("\"accountItem\"", "\"accountItemRenderer\"")}]}"""
        val channels = parseYouTubeChannels(body)
        assertNull(autoChannelPageId(channels))
        assertEquals(true, channelChoiceNeeded(channels))
    }

    @Test
    fun noGuessBetweenSeveralBrandChannels() {
        val channels = parseYouTubeChannels(
            list(item("Profile", null, false), item("A", "1", true), item("B", "2", true)),
        )
        assertNull(autoChannelPageId(channels))
    }
}
