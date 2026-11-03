package com.nuvio.app.features.youtube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YouTubeStoryboardTest {

    private val spec = "https://i.ytimg.com/sb/abc/storyboard3_L\$L/\$N.jpg?sqp=xyz" +
        "|48#27#100#10#10#0#default#rs\$AAA" +
        "|80#45#61#10#10#2000#M\$M#rs\$BBB" +
        "|160#90#61#5#5#2000#M\$M#rs\$CCC"

    @Test
    fun takesTheLargestLevelWithItsSignature() {
        val board = parseYouTubeStoryboardSpec(spec, lengthSeconds = 120)

        assertEquals(
            YouTubeStoryboard(
                sheetUrl = "https://i.ytimg.com/sb/abc/storyboard3_L2/M\$M.jpg?sqp=xyz&sigh=rs%24CCC",
                width = 160,
                height = 90,
                columns = 5,
                rows = 5,
                count = 61,
                intervalMs = 2000,
            ),
            board,
        )
    }

    @Test
    fun anIntervalOfZeroSpreadsTheFramesOverTheVideo() {
        val board = parseYouTubeStoryboardSpec("https://i.ytimg.com/sb/abc/L\$L/\$N.jpg|48#27#100#10#10#0#default#rs\$AAA", lengthSeconds = 300)
        assertEquals(3000L, board?.intervalMs)
        assertEquals("https://i.ytimg.com/sb/abc/L0/default.jpg?sigh=rs%24AAA", board?.sheetUrl)
    }

    @Test
    fun readsTheSpecFromAPlayerAnswer() {
        val body = """{"videoDetails":{"lengthSeconds":"120"},"storyboards":{"playerStoryboardSpecRenderer":{"spec":"${spec.replace("\$", "\u0024")}"}}}"""
        assertEquals(160, parseYouTubeStoryboard(body)?.width)
        assertNull(parseYouTubeStoryboard("""{"videoDetails":{}}"""))
    }
}
