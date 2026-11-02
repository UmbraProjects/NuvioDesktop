package com.nuvio.app.features.music.potoken

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Talks to Google and starts a real browser, so it only runs when `NUVIO_POTOKEN_LIVE=1` (CI does not).
 * `-Dnuvio.potoken.browser=<path>` picks the browser; `-Dnuvio.potoken.browserArgs=--no-sandbox` for containers.
 */
class PoTokenLiveTest {
    private val live = System.getenv("NUVIO_POTOKEN_LIVE") == "1"

    @Test
    fun `mints session and video tokens in a real browser`() {
        if (!live) return
        runBlocking {
            val browser = requireNotNull(BrowserLocator.find()) { "no browser found" }
            val minter = BotGuardMinter.create(browser)
            try {
                val session = minter.mint("CgtleGFtcGxlVmlzaXRvcg%3D%3D")
                val video = minter.mint("jNQXAC9IVRw")
                assertNotEquals(session, video)
                assertTrue(session.length in 80..220, "session token length ${session.length}")
                assertTrue(video.length in 80..220, "video token length ${video.length}")
                assertTrue(!minter.isDead && !minter.isExpired)
                // Same identifier, same page: the token is not a constant.
                assertEquals(video.length, minter.mint("jNQXAC9IVRw").length)
            } finally {
                minter.close()
            }
        }
    }

    @Test
    fun `the page cannot reach the network`() {
        if (!live) return
        runBlocking {
            val browser = BotGuardBrowser.launch(requireNotNull(BrowserLocator.find()))
            try {
                val origin = browser.evaluate("location.origin", 5_000)
                assertEquals("https://www.youtube.com", origin)
                val reach = browser.evaluate("fetch('https://example.com/').then(function(){return 'reached'}, function(){return 'blocked'})", 8_000)
                assertEquals("blocked", reach)
            } finally {
                browser.close()
            }
        }
    }
}
