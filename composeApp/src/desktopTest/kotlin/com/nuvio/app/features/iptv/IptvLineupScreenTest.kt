package com.nuvio.app.features.iptv

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/** fork:live-tv-lineup - Your 40 on the Live TV tab: the numbered list, the menu, Never again. */
@OptIn(ExperimentalTestApi::class)
class IptvLineupScreenTest {

    @Test
    fun `the lineup is numbered, can be moved and trimmed, and never again hides a channel`() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/list.m3u" -> MockResponse().setBody(
                    """
                    #EXTM3U
                    #EXTINF:-1 group-title="UK | Entertainment",BBC One
                    http://h/bbc1.ts
                    #EXTINF:-1 group-title="UK | Entertainment",Dave
                    http://h/dave.ts
                    #EXTINF:-1 group-title="US | News",CNN
                    http://h/cnn.ts
                    #EXTINF:-1 group-title="US | Shopping",QVC
                    http://h/qvc.ts
                    """.trimIndent(),
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        IptvRepository.onProfileChanged()
        IptvRepository.settings.value.playlists.forEach { IptvRepository.removePlaylist(it.id) }
        val playlist = IptvRepository.addPlaylist(
            IptvPlaylist(id = "", type = IptvPlaylistType.M3u, name = "Test TV", url = server.url("/list.m3u").toString()),
        )
        try {
            runBlocking { IptvRepository.refresh(playlist.id, force = true) }
            val channels = IptvRepository.contentState(playlist.id).value.content?.channels
            assertNotNull(channels)
            fun key(name: String) = channels.first { it.name == name }.key
            IptvRepository.setLineupOn(true)

            runComposeUiTest {
                setContent {
                    NuvioTheme(darkTheme = true, appTheme = AppTheme.OCEAN) {
                        IptvScreen(onOpenSettings = {})
                    }
                }
                fun shown(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
                fun check(ok: Boolean, what: String) {
                    if (!ok) {
                        saveScreenshot("iptv-lineup-FAILED.png")
                        throw AssertionError("Lineup screen check failed: $what")
                    }
                }
                fun clickMore(row: Int) {
                    val more = onAllNodesWithContentDescription("Your 40: move, take out, never again")
                    check(more.fetchSemanticsNodes().size > row, "a ... button on row $row")
                    more[row].performClick()
                    waitForIdle()
                }
                fun clickText(text: String) {
                    check(onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty(), "nothing to click called \"$text\"")
                    onAllNodesWithText(text)[0].performClick()
                    waitForIdle()
                }
                fun lineupNames() = IptvRepository.settings.value.lineup[playlist.id].orEmpty()
                waitForIdle()

                // The first lineup is built by itself: every channel here, in the playlist's order.
                check(shown("Your 40"), "'Your 40 in the sidebar'")
                check(lineupNames() == listOf(key("BBC One"), key("Dave"), key("CNN"), key("QVC")), "first lineup was ${lineupNames()}")
                check(shown("BBC One") && shown("QVC"), "'the lineup lists its channels'")
                saveScreenshot("iptv-lineup.png")

                // Open a channel's menu.
                clickMore(0)
                check(shown("Number 1 in Your 40"), "'the menu says the channel's number'")
                check(shown("Never again: hide it for good") && shown("Take out of Your 40"), "'the menu offers its actions'")
                check(!shown("Move up"), "'the first channel cannot move up'")
                saveScreenshot("iptv-lineup-menu.png")
                clickText("Never again: hide it for good")
                check(!shown("BBC One"), "'BBC One is gone from the lineup'")
                check(IptvRepository.settings.value.never[playlist.id]?.contains(key("BBC One")) == true, "'BBC One was thrown out'")
                check(lineupNames() == listOf(key("Dave"), key("CNN"), key("QVC")), "lineup after never again was ${lineupNames()}")

                // Move the second channel up.
                clickMore(1)
                clickText("Move up")
                check(lineupNames() == listOf(key("CNN"), key("Dave"), key("QVC")), "lineup after move was ${lineupNames()}")

                // Take the last one out: it is not thrown out, only not in the lineup.
                clickMore(2)
                clickText("Take out of Your 40")
                check(lineupNames() == listOf(key("CNN"), key("Dave")), "lineup after take out was ${lineupNames()}")
                check(IptvRepository.settings.value.never[playlist.id]?.contains(key("QVC")) != true, "'taken out is not thrown out'")

                // Bringing back does not put it in the lineup again.
                IptvRepository.bringBackAll()
                waitForIdle()
                assertEquals(emptyMap(), IptvRepository.settings.value.never)
                check(lineupNames() == listOf(key("CNN"), key("Dave")), "'bringing back leaves the lineup alone'")
            }
        } finally {
            IptvRepository.setLineupOn(false)
            IptvRepository.bringBackAll()
            IptvRepository.removePlaylist(playlist.id)
            server.shutdown()
        }
    }

    private fun ComposeUiTest.saveScreenshot(name: String) {
        runCatching {
            val dir = File("build/test-screenshots").apply { mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, name))
        }
    }
}
