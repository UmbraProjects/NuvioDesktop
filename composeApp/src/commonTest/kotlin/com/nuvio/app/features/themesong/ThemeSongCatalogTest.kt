package com.nuvio.app.features.themesong

import com.nuvio.app.features.trailer.YouTubeSearchHit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ThemeSongCatalogTest {
    private fun stub(id: Long, title: String) = SoundtrackStub(id, title)
    private fun track(title: String, rank: Long, seconds: Int = 200, artist: String = "Danny Elfman") =
        SoundtrackTrack(title, artist, seconds, rank)
    private fun hit(id: String, title: String, channel: String = "Someone", verified: Boolean = false, seconds: Int = 200, views: Long = 100_000) =
        YouTubeSearchHit(id, title, channel, verified, seconds, views)

    @Test
    fun parsesAlbumSearch() {
        val body = """{"data":[{"id":123,"title":"The Nightmare Before Christmas","artist":{"name":"Danny Elfman"}},{"id":"x"},{"title":"no id"}],"total":3}"""
        assertEquals(listOf(stub(123, "The Nightmare Before Christmas")), parseAlbumSearch(body))
        assertTrue(parseAlbumSearch("not json").isEmpty())
    }

    @Test
    fun parsesAlbumWithTracks() {
        val body = """{"id":1,"title":"Soundtrack","release_date":"1993-10-12","nb_tracks":2,
            "artist":{"name":"Various Artists"},
            "tracks":{"data":[
              {"title":"This Is Halloween","title_short":"This Is Halloween","duration":199,"rank":812345,"artist":{"name":"Danny Elfman"}},
              {"title":"Jack's Lament","duration":"x","rank":500000}
            ]}}"""
        val album = assertNotNull(parseAlbum(body))
        assertEquals(1993, album.year)
        assertEquals(2, album.trackCount)
        assertEquals(SoundtrackTrack("This Is Halloween", "Danny Elfman", 199, 812345), album.tracks[0])
        assertEquals("Various Artists", album.tracks[1].artist)
    }

    @Test
    fun soundtrackAlbumBeatsLookalikes() {
        val picked = pickSoundtrackAlbums(
            listOf(
                stub(1, "Halloween Party Hits"),
                stub(2, "Halloween Kills (Original Motion Picture Soundtrack)"),
                stub(3, "Halloween (Original Motion Picture Soundtrack)"),
                stub(4, "Halloween Piano Tribute Soundtrack"),
                stub(5, "Halloween (Expanded Edition)"),
            ),
            title = "Halloween",
            year = "1978",
        )
        assertEquals(listOf(3L), picked.map { it.id })
    }

    @Test
    fun albumMustCarryTheWholeTitle() {
        val picked = pickSoundtrackAlbums(
            listOf(stub(1, "Nightmare on Elm Street Soundtrack"), stub(2, "The Nightmare Before Christmas (Special Edition Soundtrack)")),
            title = "The Nightmare Before Christmas",
            year = "1993",
        )
        assertEquals(listOf(2L), picked.map { it.id })
    }

    @Test
    fun mostPopularSongWinsAndSpokenTracksAreSkipped() {
        val album = SoundtrackAlbum(
            "The Nightmare Before Christmas", 1993, 5,
            listOf(
                track("Opening", 100_000, seconds = 120),
                track("This Is Halloween", 800_000),
                track("This Is Halloween (Reprise)", 700_000),
                track("Jack and Sally Dialogue", 900_000),
                track("What's This?", 600_000),
                track("Tiny Clip", 950_000, seconds = 10),
            ),
        )
        val songs = pickPopularSongs(listOf(album))
        assertEquals("This Is Halloween", songs.first().title)
        assertEquals(listOf("This Is Halloween", "What's This?", "Opening"), songs.map { it.title })
    }

    @Test
    fun sameSongOnTwoAlbumsIsOneEntry() {
        val a = SoundtrackAlbum("A", 1993, 1, listOf(track("This Is Halloween", 10)))
        val b = SoundtrackAlbum("B", 2006, 1, listOf(track("This Is Halloween - From \"Nightmare\"", 20)))
        assertEquals(1, pickPopularSongs(listOf(a, b)).size)
    }

    @Test
    fun queryNamesArtistAndSong() {
        assertEquals("Danny Elfman This Is Halloween audio", songSearchQuery(track("This Is Halloween (From \"X\")", 1)))
    }

    @Test
    fun trailerAndCoverUploadsCannotBeTheSong() {
        val song = track("This Is Halloween", 800_000, seconds = 199)
        val ranked = rankSongHits(
            listOf(
                hit("trailer", "The Nightmare Before Christmas Official Trailer", seconds = 150),
                hit("cover", "This Is Halloween (Cover)", seconds = 199),
                hit("long", "This Is Halloween", seconds = 3600),
                hit("short", "This Is Halloween", seconds = 60),
                hit("fan", "This Is Halloween", channel = "Some Fan", seconds = 201),
                hit("topic", "This Is Halloween", channel = "Danny Elfman - Topic", seconds = 199),
            ),
            song,
        )
        assertEquals(listOf("topic", "fan"), ranked.map { it.videoId })
    }

    @Test
    fun songWordsThatLookUnwantedAreAllowedWhenTheyAreInTheSongTitle() {
        val song = track("Epic", 1, seconds = 180, artist = "Faith No More")
        assertEquals(listOf("a"), rankSongHits(listOf(hit("a", "Faith No More - Epic", seconds = 180)), song).map { it.videoId })
    }
}
