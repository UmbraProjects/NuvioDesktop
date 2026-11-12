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

class ArtistFactRowsTest {
    @Test
    fun shortPlaceKeepsTheLastPartWhenTooLong() {
        assertEquals("USA", shortPlace("Boston, Massachusetts, USA"))
        assertEquals("London", shortPlace("London"))
    }

    @Test
    fun factSheetSkipsWhatIsUnknown() {
        val artist = LibraryArtist(id = "UC1", name = "Gang Starr", beginYear = "1987", genres = listOf("Hip hop", "Jazz rap"), style = "hip hop")
        val sheet = artistFactSheet(artist)
        assertEquals("1987–present", sheet.years)
        assertEquals(null, sheet.releases)
        // The style is left out when it is already one of the genres.
        assertEquals(listOf("Hip hop", "Jazz rap"), sheet.tags)
        assertEquals(null, sheet.mood)
    }
}

/** fork:artist-fill - Deezer fills the Essentials list when YouTube Music's is short. */
class DeezerFillTest {
    private val pac = MusicArtistRef("2Pac", "UCpac")

    private fun song(title: String, albumTitle: String, albumId: Long, rank: Long) = DeezerTopSong(title, albumTitle, albumId, rank)

    private fun release(key: String, title: String, deezerId: Long, yt: String?) =
        LibraryRelease(key = key, title = title, kind = ReleaseKind.Album, ytBrowseId = yt, deezerId = deezerId)

    private fun track(id: String, title: String, available: Boolean = true) = MusicTrack(id, title, listOf(pac), 200, 1, available)

    private fun album(browseId: String, vararg tracks: MusicTrack) = MusicAlbum(
        browseId = browseId, title = browseId, artists = listOf(pac), year = "1996", kind = "Album",
        thumbnailUrl = null, audioPlaylistId = null, tracks = tracks.toList(),
    )

    private val releases = listOf(
        release("hits", "Greatest Hits", 1L, "MPREb_hits"),
        release("eyez", "All Eyez On Me", 2L, "MPREb_eyez"),
        release("ghost", "Not On YouTube Music", 3L, null),
    )

    private val deezer = listOf(
        song("Changes", "Greatest Hits", 1L, 900),
        song("California Love", "All Eyez On Me", 2L, 800),
        song("California Love (Remix)", "All Eyez On Me", 2L, 700),
        song("Hit Em Up", "Not On YouTube Music", 3L, 600),
        song("Dear Mama", "Greatest Hits", 1L, 500),
    )

    @Test
    fun onlyAShortListAsksDeezer() {
        val five = (1..5).map { MusicTopSong("v$it", "Song $it") }
        val ten = (1..10).map { MusicTopSong("v$it", "Song $it") }
        assertEquals(true, needsDeezerFill(five))
        assertEquals(false, needsDeezerFill(ten))
        // The same song under two ids counts once.
        assertEquals(true, needsDeezerFill(ten.map { it.copy(title = "Song") }))
    }

    @Test
    fun fillsAreTheMostPlayedSongsTheListLacksThatCanBePlayed() {
        val have = listOf(MusicTopSong("chg", "Changes", "MPREb_hits"))
        val fills = deezerFills(have, deezer, releases, limit = 10)
        // Changes is already there, the Remix is the same song as California Love, Hit Em Up has no playable release.
        assertEquals(listOf("California Love", "Dear Mama"), fills.map { it.song.title })
        assertEquals(listOf("MPREb_eyez", "MPREb_hits"), fills.map { it.browseId })
    }

    @Test
    fun fillsStopWhenTheListIsFull() {
        val have = listOf(MusicTopSong("a", "A"), MusicTopSong("b", "B"), MusicTopSong("c", "Changes"))
        assertEquals(1, deezerFills(have, deezer, releases, limit = 4).size)
        assertEquals(emptyList(), deezerFills(have, deezer, releases, limit = 3))
    }

    @Test
    fun aFillBecomesATopSongOnceItsAlbumIsOpenAndHoldsIt() {
        val fills = deezerFills(emptyList(), deezer, releases, limit = 10)
        val eyez = album("MPREb_eyez", track("cal", "California Love (Remix)"), track("ambitionz", "Ambitionz Az A Ridah"))
        val filled = filledTopSongs(fills, mapOf("MPREb_eyez" to eyez))
        // California Love is matched to the album's own track by title; Changes and Dear Mama sit on an album not open yet.
        assertEquals(listOf(MusicTopSong("cal", "California Love (Remix)", "MPREb_eyez")), filled)

        val greyed = eyez.copy(tracks = listOf(track("cal", "California Love (Remix)", available = false)))
        assertEquals(emptyList(), filledTopSongs(fills, mapOf("MPREb_eyez" to greyed)))
    }

    @Test
    fun filledSongsFollowYouTubeMusicsOwnInEssentials() {
        val have = listOf(MusicTopSong("chg", "Changes", "MPREb_hits"))
        val fills = deezerFills(have, deezer, releases, limit = 10)
        val eyez = album("MPREb_eyez", track("cal", "California Love (Remix)"))
        val rows = essentialRows(have + filledTopSongs(fills, mapOf("MPREb_eyez" to eyez)), mapOf("MPREb_eyez" to eyez))
        assertEquals(listOf("Changes", "California Love (Remix)"), rows.map { it.title })
        assertEquals(listOf(1, 2), rows.map { it.rank })
        assertEquals("cal", rows[1].videoId)
    }
}
