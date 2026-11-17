package com.nuvio.app.features.locallibrary

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.servers.ServerMediaKind
import com.nuvio.app.features.servers.ServerTitle
import com.nuvio.app.features.tracking.TrackingExternalIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalServerLinksTest {
    private fun title(type: String, imdb: String? = null, tmdb: Long? = null) = ServerTitle(
        preview = MetaPreview(id = "server:c1:42", type = type, name = "Heat", releaseInfo = "1995"),
        externalIds = TrackingExternalIds(imdb = imdb, tmdb = tmdb),
    )

    @Test
    fun titleWithAnIdBecomesAFilelessLocalItemOnTheServersFolder() {
        val item = with(LocalServerLinks) { title("movie", imdb = "tt0113277").toLocalItem("c1", ServerMediaKind.MOVIE) }!!
        assertEquals("server:c1:server:c1:42", item.key)
        assertEquals(localServerFolderId("c1"), item.folderId)
        assertEquals(LocalFolderType.MOVIES, item.type)
        assertEquals(1995, item.year)
        assertEquals("tt0113277", item.contentId)
        assertTrue(item.files.isEmpty()) // Plays from the server's picker group, never as a local file.
        assertEquals("c1", item.folderId.localServerConnectionId())
    }

    @Test
    fun titleWithoutImdbOrTmdbIdIsLeftOut() {
        assertNull(with(LocalServerLinks) { title("movie").toLocalItem("c1", ServerMediaKind.MOVIE) })
    }

    @Test
    fun mixedLibraryTakesTheTitlesOwnType() {
        val item = with(LocalServerLinks) { title("series", tmdb = 1399).toLocalItem("c1", ServerMediaKind.MIXED) }!!
        assertEquals(LocalFolderType.SERIES, item.type)
        assertEquals("tmdb:1399", item.contentId)
    }
}
