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

    private fun androidx.compose.ui.test.ComposeUiTest.saveScreenshot(name: String) {
        val image = runCatching { onNode(isDialog()).captureToImage() }.getOrNull() ?: return
        val dir = File("build/test-screenshots").apply { mkdirs() }
        ImageIO.write(image.toAwtImage(), "png", File(dir, name))
    }
}
