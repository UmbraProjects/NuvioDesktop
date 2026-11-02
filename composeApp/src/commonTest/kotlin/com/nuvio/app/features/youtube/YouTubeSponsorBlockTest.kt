package com.nuvio.app.features.youtube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YouTubeSponsorBlockTest {

    private val body = """
        [
          {"videoID":"other","segments":[{"category":"sponsor","actionType":"skip","segment":[1.0,30.0]}]},
          {"videoID":"vid","segments":[
            {"category":"outro","actionType":"skip","segment":[580.5,600.0]},
            {"category":"sponsor","actionType":"skip","segment":[61.25,95.0]},
            {"category":"preview","actionType":"skip","segment":[0.0,8.0]},
            {"category":"sponsor","actionType":"mute","segment":[120.0,130.0]},
            {"category":"interaction","actionType":"skip","segment":[200.0,200.4]},
            {"category":"poi_highlight","actionType":"poi","segment":[300.0,300.0]}
          ]}
        ]
    """.trimIndent()

    @Test
    fun keepsThisVideosSkipSegmentsInOrderAsPlayerTypes() {
        val intervals = parseYouTubeSponsorSegments(body, "vid")

        assertEquals(listOf("recap", "sponsor", "outro"), intervals.map { it.type })
        assertEquals(61.25, intervals[1].startTime)
        assertEquals(95.0, intervals[1].endTime)
        assertTrue(intervals.all { it.provider == SPONSORBLOCK_SKIP_PROVIDER })
    }

    @Test
    fun aVideoWithNothingMarkedHasNoSegments() {
        assertEquals(emptyList(), parseYouTubeSponsorSegments(body, "missing"))
        assertEquals(emptyList(), parseYouTubeSponsorSegments("Not Found", "vid"))
    }
}
