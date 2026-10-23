package com.nuvio.app.features.iptv

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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

/** The Live TV tab against a real M3U + XMLTV pair: categories, now/next, favourites, filter, play. */
@OptIn(ExperimentalTestApi::class)
class IptvScreenTest {

    private fun xmltvTime(ms: Long) =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").format(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC))

    @Test
    fun `tab lists channels with guide, favourites them and plays on click`() {
        val now = System.currentTimeMillis()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/list.m3u" -> MockResponse().setBody(
                    """
                    #EXTM3U x-tvg-url="${server.url("/guide.xml")}"
                    #EXTINF:-1 tvg-id="news.uk" group-title="News",World News HD
                    http://h/news.ts
                    #EXTINF:-1 tvg-id="sport.uk" group-title="Sport",Sports One
                    http://h/sport.ts
                    #EXTINF:-1 group-title="Sport",Sports Two
                    http://h/sport2.ts
                    """.trimIndent(),
                )
                "/guide.xml" -> MockResponse().setBody(
                    """
                    <tv>
                      <programme start="${xmltvTime(now - 1_800_000)}" stop="${xmltvTime(now + 1_800_000)}" channel="news.uk"><title>Evening Bulletin</title></programme>
                      <programme start="${xmltvTime(now - 1_500_000)}" stop="${xmltvTime(now + 1_500_000)}" channel="news.uk"><title>Duplicate Bulletin</title></programme>
                      <programme start="${xmltvTime(now + 1_800_000)}" stop="${xmltvTime(now + 3_600_000)}" channel="news.uk"><title>Weather Hour</title></programme>
                      <programme start="${xmltvTime(now - 600_000)}" stop="${xmltvTime(now + 600_000)}" channel="sport.uk"><title>Cup Final</title></programme>
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
        try {
            runBlocking {
                IptvRepository.refresh(playlist.id, force = true)
                IptvEpgRepository.refresh(assertNotNull(IptvRepository.contentState(playlist.id).value.content), force = true)
            }

            val scope = CoroutineScope(Dispatchers.Default)
            var played: PlayerLaunch? = null
            scope.launch { played = IptvPlaybackRequests.requests.first() }

            runComposeUiTest {
                setContent {
                    NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                        IptvScreen(onOpenSettings = {})
                    }
                }
                waitForIdle()
                onNodeWithText("World News HD").assertExists()
                onNodeWithText("Evening Bulletin", substring = true).assertExists()
                onNodeWithText("Weather Hour", substring = true).assertExists()
                onNodeWithText("Cup Final", substring = true).assertExists()
                onNodeWithText("News").assertExists() // category in the sidebar
                saveScreenshot("iptv-tab.png")

                // The guide view shows the same channels on a time axis.
                onAllNodesWithContentDescription("Show the programme guide")[0].performClick()
                waitForIdle()
                onNodeWithText("Evening Bulletin").assertExists()
                onNodeWithText("Weather Hour").assertExists()
                onNodeWithText("No guide information").assertExists() // Sports Two has none
                onNodeWithText("Duplicate Bulletin").assertDoesNotExist() // overlaps Evening Bulletin
                saveScreenshot("iptv-guide.png")
                onAllNodesWithContentDescription("Show the channel list")[0].performClick()
                waitForIdle()

                // The keyboard (and so the pad) drives the same: the first press shows the
                // highlight, Up reaches the header, where the guide button clicked above is
                // still the highlighted one; Left/Right walk it, Enter acts.
                fun press(keys: List<Key>) {
                    keys.forEach { key -> onRoot().performKeyInput { pressKey(key) } }
                    waitForIdle()
                }
                press(listOf(Key.DirectionUp, Key.DirectionUp, Key.DirectionRight, Key.DirectionLeft, Key.Enter))
                onNodeWithText("No guide information").assertExists()
                press(listOf(Key.Enter))
                onNodeWithText("No guide information").assertDoesNotExist()
                // Down into the channels, Left to the categories, Down to "News", Enter picks it.
                press(listOf(Key.DirectionDown, Key.DirectionLeft, Key.DirectionDown, Key.Enter))
                onNodeWithText("World News HD").assertExists()
                onNodeWithText("Sports One").assertDoesNotExist()

                // Favourite the first channel, then show only favourites.
                onAllNodesWithContentDescription("Add to favourites")[0].performClick()
                waitForIdle()
                assertTrue(IptvRepository.settings.value.favourites.any { it.startsWith(playlist.id) })
                onNodeWithText("Favourites").performClick()
                waitForIdle()
                onNodeWithText("World News HD").assertExists()
                onNodeWithText("Sports One").assertDoesNotExist()

                // Filter within all channels: the magnifier opens the box.
                onNodeWithText("All channels").performClick()
                onAllNodes(hasSetTextAction()).assertCountEquals(0)
                onAllNodesWithContentDescription("Filter channels")[0].performClick()
                waitForIdle()
                onAllNodes(hasSetTextAction())[0].performTextInput("two")
                waitForIdle()
                onNodeWithText("Sports Two").assertExists()
                onNodeWithText("World News HD").assertDoesNotExist()

                onNodeWithText("Sports Two").performClick()
                waitForIdle()
            }
            runBlocking { kotlinx.coroutines.withTimeout(5_000) { while (played == null) kotlinx.coroutines.delay(20) } }
            assertEquals("Sports Two", played!!.title)
            assertEquals("http://h/sport2.ts", played!!.sourceUrl)
            scope.cancel()
        } finally {
            IptvPlaybackSession.clear()
            IptvRepository.removePlaylist(playlist.id)
            server.shutdown()
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.saveScreenshot(name: String) {
        runCatching {
            val dir = File("build/test-screenshots").apply { mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, name))
        }
    }
}
