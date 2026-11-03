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

    private fun sb(start: Double, end: Double, type: String) =
        com.nuvio.app.features.player.skip.SkipInterval(start, end, type, SPONSORBLOCK_SKIP_PROVIDER)

    @Test
    fun sponsorStretchesBecomeChaptersAndResumeTheInterruptedOne() {
        val chapters = listOf(
            com.nuvio.app.features.player.PlayerChapter(0.0, "Intro"),
            com.nuvio.app.features.player.PlayerChapter(100.0, "Main"),
            com.nuvio.app.features.player.PlayerChapter(130.0, "Inside"),
        )
        val result = withSponsorChapters(
            chapters,
            listOf(sb(50.0, 70.0, "sponsor"), sb(120.0, 140.0, "selfpromo"), sb(5.0, 8.0, "recap")),
        )
        assertEquals(
            listOf(0.0 to "Intro", 50.0 to "Sponsor", 70.0 to "Intro", 100.0 to "Main", 120.0 to "Self-promotion", 140.0 to "Inside"),
            result.map { it.startTime to it.title },
        )
        assertEquals(listOf("sponsor", "selfpromo"), sponsorSeekSegments(listOf(sb(50.0, 70.0, "sponsor"), sb(120.0, 140.0, "selfpromo"), sb(5.0, 8.0, "recap"))).map { it.kind })
    }

    @Test
    fun aVideoWithoutChaptersGetsOneAroundTheSponsor() {
        val result = withSponsorChapters(emptyList(), listOf(sb(30.0, 60.0, "sponsor")))
        assertEquals(listOf(0.0 to "Video", 30.0 to "Sponsor", 60.0 to "Video"), result.map { it.startTime to it.title })
    }
}
