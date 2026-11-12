package com.nuvio.app.features.music.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicHeroInfoTest {
    private fun body(name: String) =
        """{"artists":[{"strArtist":"$name","strArtistLogo":"https://x/logo.png","strBiographyEN":"A band.","strMusicBrainzID":"mb-1"}]}"""

    @Test
    fun findsTheArtistWhenTheNameDiffersOnlyInCaseOrPunctuation() {
        assertEquals("mb-1", parseAudioDbArtist(body("CHVRCHES"), "Chvrches")?.musicBrainzId)
        assertEquals("https://x/logo.png", parseAudioDbArtist(body("The Weeknd"), "the weeknd")?.logo)
    }

    @Test
    fun aDifferentArtistIsNotTaken() {
        assertNull(parseAudioDbArtist(body("Muse"), "Metallica"))
    }

    @Test
    fun noArtistsInTheAnswerIsAMiss() {
        assertNull(parseAudioDbArtist("""{"artists":null}""", "Muse"))
    }

    @Test
    fun onlyMarkedLogosAreNameLogos() {
        assertFalse(MusicHeroLogos.isNameLogo("https://x/cover.jpg"))
        MusicHeroLogos.mark("https://x/name-logo.png")
        assertTrue(MusicHeroLogos.isNameLogo("https://x/name-logo.png"))
        assertFalse(MusicHeroLogos.isNameLogo(null))
    }

    @Test
    fun picksTheDeezerTrackWhoseTitleMatches() {
        val body = """{"data":[{"id":1,"title":"Gun (Live)","explicit_lyrics":false,"artist":{"id":9}},{"id":2,"title":"Gun","explicit_lyrics":true,"artist":{"id":9}}]}"""
        val hit = parseDeezerTrackHit(body, "Gun (Remastered 2023)")!!
        assertEquals(2L, hit.id)
        assertEquals(9L, hit.artistId)
        assertTrue(hit.explicit)
    }

    @Test
    fun readsTheTempoAndDateOfATrack() {
        val d = parseDeezerTrackDetail("""{"id":2,"bpm":127.6,"release_date":"2013-09-20"}""")!!
        assertEquals(128, d.bpm)
        assertEquals("2013-09-20", d.releaseDate)
        assertNull(parseDeezerTrackDetail("""{"error":{"code":800}}"""))
    }

    @Test
    fun factsLinesSayOnlyWhatIsKnown() {
        assertEquals(
            "2013 · 128 BPM · No. 3 of CHVRCHES's most played · Explicit",
            songFactsLine(HeroSongFacts(year = "2013", bpm = 128, rank = 3, explicit = true), "CHVRCHES"),
        )
        assertNull(songFactsLine(HeroSongFacts(null, null, null, false), "X"))
        assertEquals(
            "20 Sep 2013 · Virgin · 12 tracks · 49 min · ★ 8.4",
            albumFactsLine(HeroAlbumFacts(date = "2013-09-20", label = "Virgin", tracks = 12, minutes = 49), "8.4"),
        )
        assertEquals("2013", heroReleaseDate("2013"))
    }

    @Test
    fun aRecordsCoverDiscScoreAndLabelAreRead() {
        val r = parseAudioDbRelease("""{"album":[{"strAlbumThumbHQ":"https://x/hq.jpg","strAlbumCDart":"https://x/cd.png","intScore":"8.43","strLabel":"Virgin"}]}""", "album")!!
        assertEquals("https://x/hq.jpg", r.cover)
        assertEquals("https://x/cd.png", r.cdArt)
        assertEquals("8.4", r.score)
        assertEquals("Virgin", r.label)
    }
}
