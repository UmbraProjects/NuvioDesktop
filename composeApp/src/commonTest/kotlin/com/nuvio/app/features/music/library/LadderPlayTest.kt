package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.MusicAlbum
import com.nuvio.app.features.music.MusicAlbumSummary
import com.nuvio.app.features.music.MusicArtist
import com.nuvio.app.features.music.MusicArtistSummary
import com.nuvio.app.features.music.MusicCatalog
import com.nuvio.app.features.music.MusicTrack
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LadderPlayTest {
    private fun summary(id: String, title: String, kind: String?) =
        MusicAlbumSummary(browseId = id, title = title, artists = emptyList(), year = null, kind = kind, thumbnailUrl = null)

    private fun track(available: Boolean = true) =
        MusicTrack(videoId = "v", title = "t", artists = emptyList(), durationSeconds = 1, trackNumber = 1, isAvailable = available)

    private fun album(id: String, title: String, tracks: List<MusicTrack> = listOf(track())) =
        MusicAlbum(browseId = id, title = title, artists = emptyList(), year = null, kind = "Album", thumbnailUrl = null, tracks = tracks, audioPlaylistId = null)

    private class Fake(
        val artists: List<MusicArtistSummary> = emptyList(),
        val releases: List<MusicAlbumSummary> = emptyList(),
        val albums: Map<String, MusicAlbum> = emptyMap(),
    ) : MusicCatalog {
        val opened = ArrayList<String>()
        override suspend fun searchAlbums(query: String) = emptyList<MusicAlbumSummary>()
        override suspend fun searchArtists(query: String) = artists
        override suspend fun album(browseId: String): MusicAlbum? { opened += browseId; return albums[browseId] }
        override suspend fun artist(browseId: String): MusicArtist? = null
        override suspend fun artistReleases(browseId: String) = releases
    }

    private val slowdive = MusicArtistSummary("UC1", "Slowdive", null)

    @Test
    fun exactNameWinsAndANamesakeIsNeverPicked() {
        val hits = listOf(MusicArtistSummary("x", "Slowdive Tribute", null), slowdive)
        assertEquals("UC1", pickRungArtist("slowdive", hits)?.browseId)
        assertNull(pickRungArtist("Ride", hits))
    }

    @Test
    fun firstFullAlbumBeatsEarlierSinglesAndEps() {
        val list = listOf(summary("a", "Single", "Single"), summary("b", "Souvlaki", "Album"), summary("c", "Other", "Album"))
        assertEquals("b", pickRungAlbum(list)?.browseId)
        assertEquals("a", pickRungAlbum(listOf(summary("a", "S", "Single")))?.browseId)
        assertNull(pickRungAlbum(emptyList()))
    }

    @Test
    fun anUnsavedRungPlaysTheFirstFullAlbum() = runBlocking<Unit> {
        val cat = Fake(listOf(slowdive), listOf(summary("a1", "Ep", "EP"), summary("a2", "Souvlaki", "Album")), mapOf("a2" to album("a2", "Souvlaki")))
        var played: MusicAlbum? = null
        val out = playRung(cat, "Slowdive", emptyList(), play = { played = it })
        assertEquals(RungPlay.Playing("Souvlaki", "UC1"), out)
        assertEquals("a2", played?.browseId)
    }

    @Test
    fun aSavedRungUsesItsTopAlbum() = runBlocking<Unit> {
        val cat = Fake(listOf(slowdive), listOf(summary("a2", "Souvlaki", "Album")), mapOf("top" to album("top", "Just for a Day"), "a2" to album("a2", "Souvlaki")))
        val saved = LibraryArtist(id = "UC1", name = "Slowdive")
        val out = assertIs<RungPlay.Playing>(playRung(cat, "Slowdive", listOf(saved), topAlbumId = { "top" }, play = {}))
        assertEquals("Just for a Day", out.albumTitle)
        assertEquals(listOf("top"), cat.opened)
    }

    @Test
    fun aSavedRungWithNoTopAlbumFallsBackToThePage() = runBlocking<Unit> {
        val cat = Fake(listOf(slowdive), listOf(summary("a2", "Souvlaki", "Album")), mapOf("a2" to album("a2", "Souvlaki")))
        val saved = LibraryArtist(id = "UC1", name = "Slowdive")
        assertEquals(RungPlay.Playing("Souvlaki", "UC1"), playRung(cat, "Slowdive", listOf(saved), topAlbumId = { null }, play = {}))
    }

    @Test
    fun noArtistNoReleaseOrNoAvailableTrackIsNotPlayable() = runBlocking<Unit> {
        var played = false
        assertEquals(RungPlay.NotPlayable, playRung(Fake(), "Slowdive", emptyList(), play = { played = true }))
        assertEquals(RungPlay.NotPlayable, playRung(Fake(listOf(slowdive)), "Slowdive", emptyList(), play = { played = true }))
        val greyed = Fake(listOf(slowdive), listOf(summary("a", "A", "Album")), mapOf("a" to album("a", "A", listOf(track(false)))))
        assertEquals(RungPlay.NotPlayable, playRung(greyed, "Slowdive", emptyList(), play = { played = true }))
        val empty = Fake(listOf(slowdive), listOf(summary("a", "A", "Album")), mapOf("a" to album("a", "A", emptyList())))
        assertEquals(RungPlay.NotPlayable, playRung(empty, "Slowdive", emptyList(), play = { played = true }))
        assertTrue(!played)
    }

    @Test
    fun theFoundViaLabelNamesTheLadder() {
        assertEquals("ladder: Into shoegaze", foundViaLabel("Into shoegaze"))
    }
}
