package com.nuvio.app.features.music.torbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TorboxAlbumMatchingTest {
    private fun hit(title: String, cached: Boolean = true, seeders: Int = 10) =
        TorboxSearchHit(hash = title.hashCode().toString(16), title = title, magnet = null, seeders = seeders, sizeBytes = 0, cached = cached)

    @Test
    fun parsesTheTorrentsListAndABareArray() {
        val wrapped = """{"success":true,"data":{"torrents":[{"hash":"ABC","raw_title":"Artist - Album [FLAC]","last_known_seeders":12,"size":123,"cached":true,"magnet":"magnet:?xt=urn:btih:abc"}]}}"""
        val bare = """{"data":[{"hash":"def","title":"Other","cached":false}]}"""
        val a = parseTorboxSearchHits(wrapped).single()
        assertEquals("abc", a.hash)
        assertEquals(12, a.seeders)
        assertTrue(a.cached)
        assertEquals("Other", parseTorboxSearchHits(bare).single().title)
        assertTrue(parseTorboxSearchHits("not json").isEmpty())
        assertTrue(parseTorboxSearchHits("""{"data":{"torrents":[{"title":"no hash"}]}}""").isEmpty())
    }

    @Test
    fun queryDropsEditionNoise() {
        assertEquals("pink floyd dark side of moon", torboxAlbumQuery("The Dark Side of the Moon (2011 Remastered Version)", "Pink Floyd"))
        assertEquals("loveless", torboxAlbumQuery("Loveless (Deluxe Edition)", "Various Artists"))
    }

    @Test
    fun rankingNeedsArtistAndAlbumAndACachedTorrent() {
        val ranked = rankTorboxAlbumHits(
            listOf(
                hit("Radiohead - OK Computer (1997) [FLAC]"),
                hit("Radiohead - OK Computer (1997) [MP3 320]"),
                hit("Radiohead - OK Computer [FLAC]", cached = false),
                hit("Radiohead Discography [FLAC]"),
                hit("Radiohead - Kid A [FLAC]"),
                hit("Someone Else - OK Computer [FLAC]"),
            ),
            albumTitle = "OK Computer",
            artist = "Radiohead",
            year = "1997",
        )
        assertEquals(listOf("Radiohead - OK Computer (1997) [FLAC]", "Radiohead - OK Computer (1997) [MP3 320]"), ranked.map { it.title })
    }

    @Test
    fun anAlbumWhoseTitleHasABulkWordStillMatches() {
        val ranked = rankTorboxAlbumHits(listOf(hit("Artist - The Complete Collection [FLAC]")), "The Complete Collection", "Artist", null)
        assertEquals(1, ranked.size)
    }

    @Test
    fun qualityLabels() {
        assertEquals("FLAC", torboxQualityLabel("A - B [FLAC 24bit]"))
        assertEquals("MP3 320", torboxQualityLabel("A - B (320 kbps)"))
        assertEquals("", torboxQualityLabel("A - B"))
    }

    private val tracks = listOf(
        TorboxTrackKey(0, 1, "Airbag"),
        TorboxTrackKey(1, 2, "Paranoid Android"),
        TorboxTrackKey(2, 3, "Subterranean Homesick Alien (feat. Someone)"),
    )

    @Test
    fun filesMatchByTitleThenNumber() {
        val files = listOf(
            TorboxFile(10, "OK Computer/02 - Paranoid Android.flac"),
            TorboxFile(11, "OK Computer/01 - Airbag.flac"),
            TorboxFile(12, "OK Computer/03 - Subterranean Homesick Alien.flac"),
            TorboxFile(13, "OK Computer/cover.jpg"),
        )
        val mapped = assertNotNull(mapTorboxFilesToTracks(files, tracks))
        assertEquals(11, mapped[0]?.id)
        assertEquals(10, mapped[1]?.id)
        assertEquals(12, mapped[2]?.id)
    }

    @Test
    fun aDifferentlyNamedTrackFallsBackToItsUniqueNumber() {
        val files = listOf(
            TorboxFile(1, "01 Airbag.mp3"),
            TorboxFile(2, "02 Paranoid Android.mp3"),
            TorboxFile(3, "03 Totally Renamed.mp3"),
        )
        val mapped = assertNotNull(mapTorboxFilesToTracks(files, tracks))
        assertEquals(3, mapped[2]?.id)
    }

    @Test
    fun aFileIsNeverUsedTwiceAndAWrongTorrentIsRefused() {
        val files = listOf(TorboxFile(1, "01 Something Else.flac"), TorboxFile(2, "02 Another.flac"))
        assertNull(mapTorboxFilesToTracks(files, tracks.map { it.copy(title = "Unrelated ${it.index}", number = 0) }))
        assertNull(mapTorboxFilesToTracks(listOf(TorboxFile(1, "readme.txt")), tracks))
    }

    @Test
    fun discAndTrackPrefixIsReadAsTheTrackNumber() {
        val files = listOf(TorboxFile(1, "CD1/1-01 Airbag.flac"), TorboxFile(2, "CD1/1-02 Paranoid Android.flac"), TorboxFile(3, "CD1/1-03 Subterranean Homesick Alien.flac"))
        assertEquals(3, assertNotNull(mapTorboxFilesToTracks(files, tracks)).size)
    }
}
