package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.iptv.IptvPlaylistType
import com.nuvio.app.features.iptv.IptvRepository
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Live TV settings page: add dialog validation, type switch, and saving a playlist. */
@OptIn(ExperimentalTestApi::class)
class IptvSettingsPageTest {

    @Test
    fun `add dialog validates, switches type and saves`() = runComposeUiTest {
        IptvRepository.onProfileChanged()
        IptvRepository.settings.value.playlists.forEach { IptvRepository.removePlaylist(it.id) }
        try {
            setContent {
                NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                    LazyColumn { iptvSettingsContent(isTablet = true) }
                }
            }
            onNodeWithText("Add playlist").performClick()
            waitForIdle()

            // Xtream is the default type; saving empty explains what is missing.
            onNodeWithText("Save").performClick()
            onNodeWithText("Fill in the server, username and password.").assertExists()
            waitForIdle()
            saveScreenshot("iptv-add-dialog-xtream.png")

            onNodeWithText("M3U link").performScrollTo().performClick()
            onNodeWithText("Playlist URL").assertExists()
            onNodeWithText("Save").performClick()
            onNodeWithText("Enter the playlist URL.").assertExists()

            // Fields: name, URL, guide URL.
            val fields = onAllNodes(hasSetTextAction())
            fields[0].performScrollTo().performTextInput("Test list")
            fields[1].performScrollTo().performTextInput("http://127.0.0.1:9/list.m3u")
            saveScreenshot("iptv-add-dialog-m3u.png")
            onNodeWithText("Save").performClick()
            waitForIdle()

            val saved = IptvRepository.settings.value.playlists.single()
            assertEquals(IptvPlaylistType.M3u, saved.type)
            assertEquals("Test list", saved.name)
            assertEquals("http://127.0.0.1:9/list.m3u", saved.url)
            assertEquals(null, saved.server)
            onAllNodesWithText("Test list").fetchSemanticsNodes().let { assertEquals(1, it.size) }
            waitForIdle()
            runCatching {
                val dir = File("build/test-screenshots").apply { mkdirs() }
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "iptv-settings-page.png"))
            }
        } finally {
            IptvRepository.settings.value.playlists.forEach { IptvRepository.removePlaylist(it.id) }
        }
    }

    @Test
    fun `a Stalker portal needs a real MAC, and is saved with it normalised`() = runComposeUiTest {
        IptvRepository.onProfileChanged()
        IptvRepository.settings.value.playlists.forEach { IptvRepository.removePlaylist(it.id) }
        try {
            setContent {
                NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                    LazyColumn { iptvSettingsContent(isTablet = true) }
                }
            }
            onNodeWithText("Add playlist").performClick()
            waitForIdle()
            onNodeWithText("Stalker portal").performScrollTo().performClick()
            onNodeWithText("Portal URL").assertExists()
            onNodeWithText("MAC address").assertExists()
            saveScreenshot("iptv-add-dialog-stalker.png")

            // Fields: name, portal, MAC, serial, device ID, device ID 2, signature, guide URL.
            val fields = onAllNodes(hasSetTextAction())
            assertEquals(8, fields.fetchSemanticsNodes().size)
            fields[0].performScrollTo().performTextInput("Portal")
            fields[1].performScrollTo().performTextInput("http://127.0.0.1:9/c/")
            fields[2].performScrollTo().performTextInput("not a mac")
            onNodeWithText("Save").performClick()
            onNodeWithText("Enter the portal URL and a MAC such as 00:1A:79:12:34:56.").performScrollTo().assertExists()
            assertEquals(0, IptvRepository.settings.value.playlists.size)

            fields[2].performScrollTo().performTextClearance()
            fields[2].performTextInput("00-1a-79-12-34-56")
            fields[3].performScrollTo().performTextInput(" SER1 ")
            saveScreenshot("iptv-add-dialog-stalker-filled.png")
            onNodeWithText("Save").performClick()
            waitForIdle()

            val saved = IptvRepository.settings.value.playlists.single()
            assertEquals(IptvPlaylistType.Stalker, saved.type)
            assertEquals("http://127.0.0.1:9/c/", saved.server)
            assertEquals("00:1A:79:12:34:56", saved.username)
            assertEquals("SER1", saved.serial)
            assertEquals(null, saved.password)
            assertEquals(null, saved.deviceId)
            assertEquals(null, saved.url)
        } finally {
            IptvRepository.settings.value.playlists.forEach { IptvRepository.removePlaylist(it.id) }
            com.nuvio.app.features.iptv.StalkerUrls.portalHosts.clear()
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.saveScreenshot(name: String) {
        val image = runCatching { onNode(isDialog()).captureToImage() }.getOrNull() ?: return
        val dir = File("build/test-screenshots").apply { mkdirs() }
        ImageIO.write(image.toAwtImage(), "png", File(dir, name))
    }
}
