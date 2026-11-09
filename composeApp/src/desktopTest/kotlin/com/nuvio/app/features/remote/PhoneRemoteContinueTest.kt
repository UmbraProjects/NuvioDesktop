package com.nuvio.app.features.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhoneRemoteContinueTest {
    @Test
    fun continueWatchingTravelsAsOneLine() {
        val line = PhoneRemoteLibraryLines.continueLine(
            listOf(
                RemoteResume(
                    type = "series", parentId = "tt0903747", videoId = "tt0903747:2:5", title = "Breaking, Bad; Show",
                    episode = "S2 E5 · Breakage", poster = "https://img/p.jpg", still = null, positionMs = 1_200_000L,
                    durationMs = 2_820_000L, lastSource = "Torrentio 1080p", progressPermille = 425,
                ),
                RemoteResume(
                    type = "movie", parentId = "tt1856101", videoId = "tt1856101", title = "Blade Runner 2049",
                    episode = null, poster = null, still = null, positionMs = 0L, durationMs = 0L, lastSource = null,
                    progressPermille = 300,
                ),
            ),
        )
        assertEquals(
            "CONTINUE series,tt0903747,tt0903747%3A2%3A5,Breaking%2C%20Bad%3B%20Show,S2%20E5%20%C2%B7%20Breakage," +
                "https%3A%2F%2Fimg%2Fp.jpg,-,1200000,2820000,Torrentio%201080p,425;" +
                "movie,tt1856101,tt1856101,Blade%20Runner%202049,-,-,-,0,0,-,300",
            line,
        )
        assertEquals("CONTINUE -", PhoneRemoteLibraryLines.continueLine(emptyList()))
    }

    @Test
    fun theRequestsParse() {
        assertEquals(RemoteMessage.Continue, PhoneRemoteProtocol.parse("CONTINUE"))
        assertEquals(RemoteMessage.Resume("tt0903747", "tt0903747:2:5"), PhoneRemoteProtocol.parse("RESUME tt0903747 tt0903747%3A2%3A5"))
        assertNull(PhoneRemoteProtocol.parse("RESUME tt0903747"))
    }
}
