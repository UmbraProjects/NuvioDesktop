package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MpvMediaTitleTest {
    @Test
    fun `source listing title wins over content fallback`() {
        assertEquals(
            "1080p WEB-DL | Korean Subs",
            preferredMpvMediaTitle(
                streamTitle = "1080p WEB-DL | Korean Subs",
                title = "Friendly Drama",
                episodeText = "S1 E2",
            ),
        )
    }

    @Test
    fun `content and episode form the fallback without a stream label`() {
        assertEquals(
            "Friendly Drama S1 E2",
            preferredMpvMediaTitle("  ", "Friendly Drama", "S1 E2"),
        )
    }

    @Test
    fun `control characters cannot create an mpv option line`() {
        val title = preferredMpvMediaTitle("Release\nforce-media-title=token", null, null)

        assertEquals("Release force-media-title=token", title)
        assertFalse('\n' in title)
        assertFalse('\r' in title)
    }
}
