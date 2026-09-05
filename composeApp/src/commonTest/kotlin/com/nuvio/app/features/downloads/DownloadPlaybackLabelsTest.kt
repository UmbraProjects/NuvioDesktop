package com.nuvio.app.features.downloads

import com.nuvio.app.features.locallibrary.LOCAL_LIBRARY_PROVIDER_NAME
import com.nuvio.app.features.locallibrary.LOCAL_LIBRARY_STREAM_NAME
import com.nuvio.app.features.locallibrary.isInsideDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun labels(
    streamTitle: String,
    providerName: String,
    fileName: String = "Show (2024)/Season 01/Show (2024) - S01E02.mkv",
    streamSubtitle: String? = null,
    episodeTitle: String? = "The Episode",
    servedByLocalLibrary: Boolean = false,
) = resolveDownloadPlaybackLabels(
    streamTitle = streamTitle,
    streamSubtitle = streamSubtitle,
    providerName = providerName,
    fileName = fileName,
    episodeTitle = episodeTitle,
    fallbackTitle = "Show",
    downloadedLabel = "Downloaded",
    servedByLocalLibrary = servedByLocalLibrary,
)

class DownloadPlaybackLabelsTest {

    @Test
    fun `a file inside a library folder is named like the scanned stream`() {
        // The reported bug: an auto-advance into a downloaded episode of a show being watched from
        // the Local Library relabelled the source "Torbox / Torbox" mid-binge.
        val result = labels(
            streamTitle = "Torbox",
            providerName = "Torbox",
            streamSubtitle = "1080p",
            servedByLocalLibrary = true,
        )
        assertEquals(LOCAL_LIBRARY_STREAM_NAME, result.streamTitle)
        assertEquals(LOCAL_LIBRARY_PROVIDER_NAME, result.providerName)
        assertNull(result.streamSubtitle)
    }

    @Test
    fun `a download outside every library folder keeps its provenance`() {
        val result = labels(
            streamTitle = "Torrentio 1080p WEB-DL",
            providerName = "Torrentio",
            streamSubtitle = "5.2 GB",
        )
        assertEquals("Torrentio 1080p WEB-DL", result.streamTitle)
        assertEquals("Torrentio", result.providerName)
        assertEquals("5.2 GB", result.streamSubtitle)
    }

    @Test
    fun `a title that only repeats the provider falls back to the file name`() {
        // enqueueFromStream stores StreamItem.name as the title, and the PVR/manual-grab services
        // set name and addonName both to the provider — so both fields read "Torbox".
        val result = labels(streamTitle = "TorBox", providerName = "Torbox")
        assertEquals("Show (2024) - S01E02", result.streamTitle)
        assertEquals("Torbox", result.providerName)
    }

    @Test
    fun `a blank title falls back to the file name`() {
        assertEquals("Show (2024) - S01E02", labels(streamTitle = "  ", providerName = "Torbox").streamTitle)
    }

    @Test
    fun `a bare destination file name keeps everything but its extension`() {
        // Only the trailing extension goes: dots are load-bearing in release names.
        val result = labels(
            streamTitle = "Torbox",
            providerName = "Torbox",
            fileName = "Some.Release.Name.1080p.mkv",
        )
        assertEquals("Some.Release.Name.1080p", result.streamTitle)
    }

    @Test
    fun `a windows separator in the destination path is stripped too`() {
        val result = labels(
            streamTitle = "Torbox",
            providerName = "Torbox",
            fileName = "Show (2024)\\Season 01\\Show (2024) - S01E02.mkv",
        )
        assertEquals("Show (2024) - S01E02", result.streamTitle)
    }

    @Test
    fun `the episode title is used when there is no usable file name`() {
        val result = labels(streamTitle = "Torbox", providerName = "Torbox", fileName = "")
        assertEquals("The Episode", result.streamTitle)
    }

    @Test
    fun `the fallback title is the last resort`() {
        val result = labels(
            streamTitle = "",
            providerName = "Torbox",
            fileName = "",
            episodeTitle = null,
        )
        assertEquals("Show", result.streamTitle)
    }

    @Test
    fun `a blank provider becomes the downloaded label`() {
        val result = labels(streamTitle = "Some Release", providerName = "   ")
        assertEquals("Downloaded", result.providerName)
        assertEquals("Some Release", result.streamTitle)
    }

    @Test
    fun `a title repeating a blank provider's fallback label also falls back`() {
        // providerName resolves to "Downloaded" first, so a stored title of "Downloaded" is just as
        // uninformative as one repeating a real provider name.
        val result = labels(streamTitle = "Downloaded", providerName = "")
        assertEquals("Show (2024) - S01E02", result.streamTitle)
        assertEquals("Downloaded", result.providerName)
    }
}

class LocalLibraryPathContainmentTest {

    @Test
    fun `a file under the root is inside it`() {
        assertTrue("D:/Media/Anime/Show/ep.mkv".isInsideDirectory("D:/Media/Anime"))
    }

    @Test
    fun `separators and casing do not matter`() {
        assertTrue("d:\\media\\anime\\Show\\ep.mkv".isInsideDirectory("D:/Media/Anime"))
        assertTrue("D:/Media/Anime/Show/ep.mkv".isInsideDirectory("d:\\media\\anime\\"))
    }

    @Test
    fun `a sibling folder sharing a name prefix is not inside`() {
        assertFalse("D:/Media/Anime2/Show/ep.mkv".isInsideDirectory("D:/Media/Anime"))
    }

    @Test
    fun `the root itself is not inside itself`() {
        assertFalse("D:/Media/Anime".isInsideDirectory("D:/Media/Anime"))
        assertFalse("D:/Media/Anime/".isInsideDirectory("D:/Media/Anime"))
    }

    @Test
    fun `an unrelated path is not inside`() {
        assertFalse("C:/Users/me/Downloads/ep.mkv".isInsideDirectory("D:/Media/Anime"))
    }

    @Test
    fun `a blank root matches nothing`() {
        assertFalse("D:/Media/Anime/Show/ep.mkv".isInsideDirectory("   "))
    }
}
