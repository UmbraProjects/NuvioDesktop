package com.nuvio.app.features.youtube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YouTubeDontRecommendTest {

    private fun lockup(id: String, menu: String) = """
        {"richItemRenderer":{"content":{"lockupViewModel":{
          "contentId":"$id","contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
          "metadata":{"lockupMetadataViewModel":{
            "title":{"content":"A video"},
            "metadata":{"contentMetadataViewModel":{"metadataRows":[{"metadataParts":[{"text":{"content":"Chan"}}]},{"metadataParts":[{"text":{"content":"1K views"}}]}]}},
            "menuButton":{"buttonViewModel":{"onTap":{"innertubeCommand":{"showSheetCommand":{"panelLoadingStrategy":{"inlineContent":{"sheetViewModel":{"content":{"listViewModel":{"listItems":[$menu]}}}}}}}}}}
          }}
        }}}}
    """.trimIndent()

    private fun sheetRow(label: String, token: String) =
        """{"listItemViewModel":{"title":{"content":"$label"},"rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"feedbackEndpoint":{"feedbackToken":"$token"}}}}}}}"""

    private fun feed(vararg items: String) =
        """{"contents":{"richGridRenderer":{"contents":[${items.joinToString(",")}]}}}"""

    @Test
    fun theChannelRowsTokenIsTakenNotTheVideos() {
        val page = parseYouTubeFeed(
            feed(lockup("abcdefghijk", listOf(sheetRow("Not interested", "VIDEO"), sheetRow("Don’t recommend channel", "CHANNEL")).joinToString(","))),
        )
        assertEquals("CHANNEL", page.videos.single().dontRecommendToken)
    }

    @Test
    fun theOlderMenuLayoutIsReadToo() {
        val renderer = """
            {"videoRenderer":{"videoId":"abcdefghijk","title":{"runs":[{"text":"A video"}]},
              "menu":{"menuRenderer":{"items":[
                {"menuServiceItemRenderer":{"text":{"runs":[{"text":"Don't recommend channel"}]},"serviceEndpoint":{"feedbackEndpoint":{"feedbackToken":"OLD"}}}}
              ]}}}}
        """.trimIndent()
        assertEquals("OLD", parseYouTubeFeed(feed(renderer)).videos.single().dontRecommendToken)
    }

    @Test
    fun aMenuWithoutTheRowHasNoToken() {
        val page = parseYouTubeFeed(feed(lockup("abcdefghijk", sheetRow("Save to playlist", "X"))))
        assertNull(page.videos.single().dontRecommendToken)
    }
}
