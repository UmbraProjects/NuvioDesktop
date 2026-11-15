package com.nuvio.app.features.iptv

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.player.PlayerLaunch
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/** fork:live-tv-on-now - the Live TV tab with On Now on, against a real M3U + XMLTV pair. */
@OptIn(ExperimentalTestApi::class)
class IptvOnNowScreenTest {

    private fun xmltvTime(ms: Long) =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").format(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC))

    @Test
    fun `wall shows sport and films, a film opens its page, and copies are one row`() {
        val now = System.currentTimeMillis()
        val minute = 60_000L
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/list.m3u" -> MockResponse().setBody(
                    """
                    #EXTM3U x-tvg-url="${server.url("/guide.xml")}"
                    #EXTINF:-1 tvg-id="bbc1.uk" group-title="UK | Entertainment",BBC One SD
                    http://h/bbc1sd.ts
                    #EXTINF:-1 tvg-id="bbc1.uk" group-title="UK | Entertainment",BBC One FHD
                    http://h/bbc1fhd.ts
                    #EXTINF:-1 tvg-id="dave.uk" group-title="UK | Entertainment",Dave HD
                    http://h/dave.ts
                    #EXTINF:-1 tvg-id="film4.uk" group-title="UK | Movies",Film4 HD
                    http://h/film4.ts
                    #EXTINF:-1 tvg-id="greats.uk" group-title="UK | Movies",Sky Cinema Greats
                    http://h/greats.ts
                    #EXTINF:-1 tvg-id="tnt1.uk" group-title="UK | Sports",TNT Sports 1
                    http://h/tnt1.ts
                    #EXTINF:-1 tvg-id="ssn.uk" group-title="UK | Sports",Sky Sports News
                    http://h/ssn.ts
                    """.trimIndent(),
                )
                "/guide.xml" -> MockResponse().setBody(
                    """
                    <tv>
                      <programme start="${xmltvTime(now - 10 * minute)}" stop="${xmltvTime(now + 20 * minute)}" channel="bbc1.uk"><title>EastEnders</title><category>Soap</category></programme>
                      <programme start="${xmltvTime(now - 15 * minute)}" stop="${xmltvTime(now + 15 * minute)}" channel="dave.uk"><title>Would I Lie to You?</title></programme>
                      <programme start="${xmltvTime(now - 20 * minute)}" stop="${xmltvTime(now + 150 * minute)}" channel="film4.uk"><title>Heat</title><category>Movie</category></programme>
                      <programme start="${xmltvTime(now + 19 * minute)}" stop="${xmltvTime(now + 139 * minute)}" channel="greats.uk"><title>Mad Max: Fury Road</title></programme>
                      <programme start="${xmltvTime(now - 75 * minute)}" stop="${xmltvTime(now + 75 * minute)}" channel="tnt1.uk"><title>Live: Man Utd v Real Sociedad</title><category>Football</category></programme>
                      <programme start="${xmltvTime(now - 40 * minute)}" stop="${xmltvTime(now + 20 * minute)}" channel="ssn.uk"><title>Sky Sports News</title></programme>
                    </tv>
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
        IptvRepository.setOnNow(true)
        try {
            runBlocking {
                IptvRepository.refresh(playlist.id, force = true)
                IptvEpgRepository.refresh(assertNotNull(IptvRepository.contentState(playlist.id).value.content), force = true)
            }
            // The guide's categories were kept as kinds.
            val content = assertNotNull(IptvRepository.contentState(playlist.id).value.content)
            val film4 = content.channels.first { it.name == "Film4 HD" }
            assertEquals(IptvProgrammeKind.FILM, IptvEpgRepository.guideEntries(playlist, film4).first().kind)

            val scope = CoroutineScope(Dispatchers.Default)
            var played: PlayerLaunch? = null
            scope.launch { played = IptvPlaybackRequests.requests.first() }

            runComposeUiTest {
                setContent {
                    NuvioTheme(darkTheme = true, appTheme = AppTheme.OCEAN) {
                        IptvScreen(onOpenSettings = {})
                    }
                }
                fun shown(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
                fun click(text: String) {
                    onAllNodesWithText(text)[0].performClick()
                    waitForIdle()
                }
                // The wall is worked out off the UI thread.
                waitUntil(timeoutMillis = 10_000) { shown("Heat") }
                assertTrue(shown("Live sport"))
                assertTrue(shown("Live: Man Utd v Real Sociedad"))
                assertTrue(shown("Mad Max: Fury Road"), "a film on a film channel, about to start")
                assertTrue(shown("On now"))
                saveScreenshot("iptv-on-now.png")

                // The keyboard: the first press shows the highlight, then Down and Right walk the tiles.
                listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionRight).forEach { key -> onRoot().performKeyInput { pressKey(key) } }
                waitForIdle()
                saveScreenshot("iptv-on-now-keyboard.png")

                // A film opens its page; this playlist has no catch-up, so it can only be joined live.
                click("Heat")
                assertTrue(shown("Watch live"))
                assertTrue(!shown("Start from the beginning"))
                assertTrue(shown("can only be joined live"))
                saveScreenshot("iptv-on-now-film.png")
                click("Watch live")

                onAllNodesWithContentDescription("Back")[0].performClick()
                waitForIdle()
                assertTrue(shown("Live sport"))

                click("Sport")
                assertTrue(shown("Sky Sports News"))
                saveScreenshot("iptv-on-now-sport.png")

                // Copies of one channel are one row, the best copy first.
                click("All channels")
                assertTrue(shown("BBC One FHD"))
                assertTrue(!shown("BBC One SD"))
                assertTrue(shown("FHD · 2 sources"))
                saveScreenshot("iptv-on-now-channels.png")
            }
            runBlocking { kotlinx.coroutines.withTimeout(5_000) { while (played == null) kotlinx.coroutines.delay(20) } }
            assertEquals("Film4 HD", played!!.title)
            assertEquals("http://h/film4.ts", played!!.sourceUrl)
            scope.cancel()
        } finally {
            IptvPlaybackSession.clear()
            IptvRepository.setOnNow(false)
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
