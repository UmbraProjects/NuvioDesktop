package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Streams that only carry a YouTube id (upstream 94976222d). */
class StreamYouTubeTest {

    @Test
    fun `parser keeps a stream whose only source is ytId`() {
        val streams = StreamParser.parse(
            payload = """{"streams":[{"name":"Trailer","ytId":" dQw4w9WgXcQ "},{"name":"Nothing"}]}""",
            addonName = "Addon",
            addonId = "addon:a:u",
        )
        assertEquals(1, streams.size)
        assertEquals("dQw4w9WgXcQ", streams.single().ytId)
        assertEquals("dQw4w9WgXcQ", streams.single().youTubeIdToResolve)
        assertTrue(streams.single().isSelectableForPlayback(debridEnabled = false))
    }

    @Test
    fun `a stream with another source does not resolve its ytId`() {
        val withUrl = StreamItem(ytId = "abc", url = "https://host.example/v.mp4", addonName = "A", addonId = "addon:a:u")
        val withHash = StreamItem(ytId = "abc", infoHash = "a".repeat(40), addonName = "A", addonId = "addon:a:u")
        val blank = StreamItem(ytId = "  ", addonName = "A", addonId = "addon:a:u")
        assertNull(withUrl.youTubeIdToResolve)
        assertNull(withHash.youTubeIdToResolve)
        assertNull(blank.youTubeIdToResolve)
    }

    @Test
    fun `watch url is built from the id`() {
        assertEquals("https://www.youtube.com/watch?v=abc", YouTubeStreamResolver.watchUrl("abc"))
    }
}
