package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.MusicAlbum
import com.nuvio.app.features.music.MusicArtistRef
import com.nuvio.app.features.music.MusicTopSong
import com.nuvio.app.features.music.MusicTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArtistEssentialsTest {
    private val pac = MusicArtistRef("2Pac", "UCpac")

    private fun track(id: String, title: String, seconds: Int, available: Boolean = true) =
        MusicTrack(id, title, listOf(pac), seconds, 1, available)

    private val eyez = MusicAlbum(
        browseId = "MPREb_eyez", title = "All Eyez On Me", artists = listOf(pac), year = "1996", kind = "Album",
        thumbnailUrl = "https://img/eyez", audioPlaylistId = null,
        tracks = listOf(track("amb", "Ambitionz Az A Ridah", 279), track("cal", "California Love (Remix)", 386)),
    )

    private val top = listOf(
        MusicTopSong("cal-video", "California Love (Remix)", "MPREb_eyez"),
        MusicTopSong("chg", "Changes", "MPREb_hits"),
        MusicTopSong("cal-again", "California Love", "MPREb_other"),
        MusicTopSong("amb", "Ambitionz Az A Ridah", "MPREb_eyez"),
    )

    @Test
    fun albumsAreOpenedOnceInSongOrder() {
        assertEquals(listOf("MPREb_eyez", "MPREb_hits", "MPREb_other"), essentialAlbumIds(top))
        assertEquals(listOf("MPREb_eyez"), essentialAlbumIds(top, limit = 1))
    }

    @Test
    fun rowsAreFilledFromTheirAlbumsAndEachSongComesOnce() {
        val rows = essentialRows(top, mapOf("MPREb_eyez" to eyez))
        assertEquals(listOf("California Love (Remix)", "Changes", "Ambitionz Az A Ridah"), rows.map { it.title })
        assertEquals(listOf(1, 2, 3), rows.map { it.rank })
        // Matched by title on its album, so it plays the album's audio track, with its length and cover.
        assertEquals("cal", rows[0].videoId)
        assertEquals(386, rows[0].durationSeconds)
        assertEquals("All Eyez On Me", rows[0].albumTitle)
        assertEquals("https://img/eyez", rows[0].coverUrl)
        // Its album was not opened: shown as YouTube Music lists it, with no length.
        assertEquals("chg", rows[1].videoId)
        assertEquals(0, rows[1].durationSeconds)
        assertNull(rows[1].albumTitle)
        assertEquals("MPREb_hits", rows[1].albumBrowseId)
    }

    @Test
    fun theMixKeepsOrderCoversAndLeavesOutGreyedSongs() {
        val greyed = eyez.copy(tracks = listOf(track("amb", "Ambitionz Az A Ridah", 279, available = false), track("cal", "California Love (Remix)", 386)))
        val rows = essentialRows(top, mapOf("MPREb_eyez" to greyed))
        val mix = essentialsMix("UCpac", "2Pac", "https://img/pac", rows)!!
        assertEquals("essentials:UCpac", mix.browseId)
        assertEquals(listOf("cal", "chg"), mix.tracks.map { it.videoId })
        assertEquals(listOf(1, 2), mix.tracks.map { it.trackNumber })
        assertEquals("All Eyez On Me", mix.tracks[0].albumTitle)
        assertEquals("2Pac", mix.tracks[1].artists.single().name)
        assertEquals("https://img/pac", mix.thumbnailUrl)
    }

    @Test
    fun nothingToPlayGivesNoMix() {
        assertNull(essentialsMix("UCpac", "2Pac", null, emptyList()))
    }

    private fun rel(key: String, kind: ReleaseKind, year: String?, cover: String? = null) =
        LibraryRelease(key = key, title = key, kind = kind, year = year, art = ReleaseArtwork(covers = listOfNotNull(cover)))

    @Test
    fun factsWithNoValueGiveWayToOnesThatHaveOne() {
        val artist = LibraryArtist(
            id = "UChc", name = "High Contrast", type = "Person", area = "Penarth", beginYear = "1979", fans = 50_000,
            releases = listOf(rel("a", ReleaseKind.Album, "2002"), rel("b", ReleaseKind.Single, "1999")),
        )
        val facts = artistStageFacts(artist, total = 127, playable = 76)
        assertEquals(listOf("Fans", "Releases", "Playable here", "From"), facts.map { it.first })
        assertEquals("Penarth", facts[3].second)
        val bare = artist.copy(fans = null, area = null, country = null)
        assertEquals(listOf("Releases", "Playable here", "Born", "First release"), artistStageFacts(bare, 127, 76).map { it.first })
        assertEquals("1999", artistStageFacts(bare, 127, 76)[3].second)
    }

    @Test
    fun theHeaderFallsBackToTheNewestCoveredAlbum() {
        val artist = LibraryArtist(
            id = "UChc", name = "High Contrast",
            releases = listOf(
                rel("old", ReleaseKind.Album, "2002", "https://c/old"),
                rel("new", ReleaseKind.Album, "2024", "https://c/new"),
                rel("bare", ReleaseKind.Album, "2025"),
                rel("single", ReleaseKind.Single, "2026", "https://c/single"),
            ),
        )
        assertEquals("new", stageHeaderRelease(artist)?.key)
        assertEquals("old", stageHeaderRelease(artist.copy(topAlbumKeys = listOf("old")))?.key)
    }

    @Test
    fun onlyAMusiciansWikipediaPageCounts() {
        assertEquals(true, looksLikeMusicianText("Lincoln Barrett, known as High Contrast, is a Welsh drum and bass DJ and record producer."))
        assertEquals(false, looksLikeMusicianText("High contrast is a property of an image in which light and dark areas differ greatly."))
    }
}
