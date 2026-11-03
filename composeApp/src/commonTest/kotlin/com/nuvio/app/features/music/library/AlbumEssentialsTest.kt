package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlbumEssentialsTest {
    private fun song(title: String, rank: Long, albumId: Long = 1L, album: String = "Album") = DeezerTopSong(title, album, albumId, rank)

    private fun release(title: String, kind: ReleaseKind = ReleaseKind.Album, deezerId: Long? = null, yt: String? = null) =
        LibraryRelease(key = title + kind, title = title, kind = kind, deezerId = deezerId, ytBrowseId = yt)

    @Test
    fun editionsAndFeaturesOfASongShareAKey() {
        assertEquals(songKey("Black or White"), songKey("Black or White (Single Version)"))
        assertEquals(songKey("Billie Jean"), songKey("Billie Jean - 2015 Remaster"))
        assertEquals(songKey("Jam"), songKey("Jam [Remastered]"))
        assertEquals("blackorwhite", songKey("Black or White!"))
        assertEquals("remix", songKey("(Remix)"))
    }

    @Test
    fun bestSongsAreRankedAndOnePerSong() {
        val top = listOf(
            song("Beat It", 90), song("Billie Jean", 100), song("Billie Jean - Remastered", 70), song("Thriller", 95),
        )
        assertEquals(listOf("Billie Jean", "Thriller", "Beat It"), bestSongs(top).map { it.title })
        assertEquals(2, bestSongs(top, limit = 2).size)
    }

    @Test
    fun listOrderStandsInWhenNoSongHasARank() {
        val top = listOf(song("A", 0), song("B", 0), song("C", 0))
        assertEquals(listOf("A", "B", "C"), bestSongs(top).map { it.title })
        val bars = trackPopularity(listOf("A", "C", "Z"), top)
        assertEquals(1f, bars[0])
        assertTrue(bars[1]!! < bars[0]!! && bars[1]!! > 0f)
        assertNull(bars[2])
    }

    @Test
    fun popularityIsRelativeToTheArtistsBestSongAndEmptyWhenUnknown() {
        val top = listOf(song("Billie Jean", 1000), song("Black or White", 500), song("Jam", 250))
        val bars = trackPopularity(listOf("Black or White", "Jam", "Keep the Faith", "Black or White (Remix)"), top)
        assertEquals(0.5f, bars[0])
        assertEquals(0.25f, bars[1])
        assertNull(bars[2])
        assertEquals(0.5f, bars[3])
        assertEquals(listOf(null, null), trackPopularity(listOf("x", "y"), emptyList()))
    }

    @Test
    fun aSongOnlyJustInTheListStillGetsAVisibleBar() {
        val top = listOf(song("Big", 1_000_000), song("Tiny", 10))
        assertEquals(0.08f, trackPopularity(listOf("Tiny"), top)[0])
    }

    @Test
    fun singlesAreFoundByTheirTitles() {
        val keys = singleKeys(
            listOf(
                release("Jam", ReleaseKind.Single), release("Remember the Time (Remix)", ReleaseKind.Single),
                release("Dangerous", ReleaseKind.Album), release("Heal the World - Single", ReleaseKind.Single),
            ),
        )
        assertTrue(songKey("Jam") in keys)
        assertTrue(songKey("Remember the Time") in keys)
        assertTrue(songKey("Dangerous") !in keys) // an album is not a single
        assertTrue(songKey("Heal the World") in keys)
    }

    @Test
    fun aBestSongOpensThePlayableReleaseItIsOn() {
        val releases = listOf(
            release("Dangerous", deezerId = 99L, yt = null),
            release("Dangerous (Deluxe)", deezerId = 100L, yt = "MPREb_dang"),
        )
        // Deezer's album has no YouTube Music id: the playable one with the same title stands in.
        val found = releaseForSong(song("Black or White", 5, albumId = 99L, album = "Dangerous"), releases)
        assertEquals("MPREb_dang", found?.ytBrowseId)
        // Nothing playable and nothing by that title: the Deezer album itself is returned (for its cover), unplayable.
        val onlyUnplayable = releaseForSong(song("X", 1, albumId = 99L, album = "Other"), listOf(release("Dangerous", deezerId = 99L)))
        assertNotNull(onlyUnplayable)
        assertNull(onlyUnplayable.ytBrowseId)
        assertNull(releaseForSong(song("X", 1, albumId = 5L, album = "Nope"), releases))
    }

    @Test
    fun theStartHerePromptNamesOnlyTheGivenAlbums() {
        val prompt = startHerePrompt("Michael Jackson", listOf("Thriller (1982)", "Bad (1987)"), listOf("Billie Jean"))
        assertTrue("1. Thriller (1982)" in prompt && "2. Bad (1987)" in prompt && "Billie Jean" in prompt)
        assertTrue("Use only the albums listed" in prompt)
    }

    @Test
    fun theStartHereReplyIsCleanedAndCut() {
        assertEquals("Start with Thriller, then play Bad.", cleanStartHere("**Start with Thriller**, then play \"Bad\"."))
        assertNull(cleanStartHere("ok"))
        val long = "Start with Thriller for the hits. ".repeat(20)
        val cut = cleanStartHere(long)!!
        assertTrue(cut.length <= 321 && cut.endsWith("."))
    }

    @Test
    fun theTopSongsReplyIsReadWithTitlesAndAlbums() {
        val body = """{"data":[{"title":"Black or White (Single Version)","title_short":"Black or White","rank":812345,"album":{"id":119606,"title":"Dangerous"}},
            {"title":"Jam","rank":700000,"album":{"id":119606,"title":"Dangerous"}}],"total":2}"""
        val songs = parseDeezerTopSongs(body)
        assertEquals(listOf("Black or White", "Jam"), songs.map { it.title })
        assertEquals(119606L, songs[0].albumId)
        assertEquals("Dangerous", songs[1].albumTitle)
        assertEquals(emptyList(), parseDeezerTopSongs("""{"error":{"type":"x"}}"""))
    }
}
