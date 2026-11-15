package com.nuvio.app.features.iptv

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/** fork:live-tv-triage - the three questions, and what the Live TV tab shows after them. */
@OptIn(ExperimentalTestApi::class)
class IptvTriageScreenTest {

    @Test
    fun `answering the questions narrows the tab, and search still finds the rest`() {
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
                    #EXTINF:-1 group-title="UK | Sports",Sky Sports Main Event
                    http://h/ssme.ts
                    #EXTINF:-1 group-title="FR | Info",BFM TV
                    http://h/bfm.ts
                    #EXTINF:-1 group-title="FR | Cinema",Canal Cinema
                    http://h/canal.ts
                    #EXTINF:-1 group-title="US | News",CNN
                    http://h/cnn.ts
                    #EXTINF:-1 group-title="US | Shopping",QVC
                    http://h/qvc.ts
                    #EXTINF:-1 group-title="24/7 Movies",Loop One
                    http://h/loop.ts
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
            assertNotNull(IptvRepository.contentState(playlist.id).value.content)

            runComposeUiTest {
                setContent {
                    NuvioTheme(darkTheme = true, appTheme = AppTheme.OCEAN) {
                        IptvScreen(onOpenSettings = {})
                    }
                }
                fun shown(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
                fun check(ok: Boolean, what: String) {
                    if (!ok) {
                        saveScreenshot("iptv-triage-FAILED.png")
                        throw AssertionError("Triage screen check failed: $what")
                    }
                }
                fun click(text: String) {
                    check(onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty(), "nothing to click called \"$text\"")
                    onAllNodesWithText(text)[0].performClick()
                    waitForIdle()
                }
                fun openTriage() {
                    onAllNodesWithContentDescription("Triage: choose what Live TV shows")[0].performClick()
                    waitForIdle()
                }
                waitForIdle()
                check(shown("FR | Info"), "'before the triage every category is in the sidebar'")

                openTriage()
                check(shown("Which countries do you watch?"), "shown('Which countries do you watch?')")
                check(shown("No country marked"), "shown('No country marked')")
                click("United Kingdom")
                check(shown("3"), "'three UK channels left'")
                saveScreenshot("iptv-triage-countries.png")
                click("Next: kinds")

                check(shown("Which of these would you ever watch?"), "shown('Which of these would you ever watch?')")
                check(shown("Entertainment and general"), "shown('Entertainment and general')")
                check(!shown("Shopping"), "'a kind the chosen countries do not have is not asked about'")
                click("Sport")
                saveScreenshot("iptv-triage-kinds.png")
                click("Next: your first ten")

                check(shown("Your first ten"), "shown('Your first ten')")
                check(shown("Dave"), "shown('Dave')")
                check(!shown("Sky Sports Main Event"), "!shown('Sky Sports Main Event')")
                click("BBC One")
                saveScreenshot("iptv-triage-first-ten.png")
                click("Done")

                // Back on the tab: only what was kept, with BBC One starred.
                assertEquals(IptvTriage(setOf("GB"), setOf("Entertainment")), IptvRepository.settings.value.triage[playlist.id])
                check(IptvRepository.settings.value.favourites.any { it.startsWith(playlist.id) }, "BBC One was starred")
                check(shown("UK | Entertainment"), "shown('UK | Entertainment')")
                check(!shown("UK | Sports"), "!shown('UK | Sports')")
                check(!shown("FR | Info"), "!shown('FR | Info')")
                check(shown("Dave"), "shown('Dave')")
                check(!shown("CNN"), "!shown('CNN')")
                saveScreenshot("iptv-triage-after.png")

                // The search box still reaches a channel the triage left out.
                onAllNodesWithContentDescription("Filter channels")[0].performClick()
                waitForIdle()
                onAllNodes(hasSetTextAction())[0].performTextInput("cnn")
                waitForIdle()
                check(shown("CNN"), "shown('CNN')")
                onRoot().performKeyInput { pressKey(Key.Escape) }
                onRoot().performKeyInput { pressKey(Key.Escape) }
                waitForIdle()

                // The keyboard drives the questions, and Back on the first one leaves without saving.
                openTriage()
                check(shown("Show everything"), "'offered once a triage exists'")
                listOf(Key.DirectionDown, Key.DirectionRight, Key.Enter).forEach { key -> onRoot().performKeyInput { pressKey(key) } }
                waitForIdle()
                saveScreenshot("iptv-triage-keyboard.png")
                onRoot().performKeyInput { pressKey(Key.Escape) }
                waitForIdle()
                check(shown("UK | Entertainment"), "'back on the tab'")
                assertEquals(setOf("GB"), IptvRepository.settings.value.triage[playlist.id]?.regions, "unsaved")

                // Show everything clears it.
                openTriage()
                click("Show everything")
                assertNull(IptvRepository.settings.value.triage[playlist.id])
                check(shown("FR | Info"), "shown('FR | Info')")
            }
        } finally {
            IptvRepository.setTriage(playlist.id, null)
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
