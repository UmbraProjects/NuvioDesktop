package com.nuvio.app.features.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class LyricsTest {
    @Test
    fun parsesStampsIntoSortedLines() {
        val lines = parseLrc("[00:12.50] second\n[00:05.00] first\n[01:02.5] third")
        assertEquals(listOf("first", "second", "third"), lines.map { it.text })
        assertEquals(listOf(5_000L, 12_500L, 62_500L), lines.map { it.timeMs })
    }

    @Test
    fun aRepeatedLineCarriesSeveralStamps() {
        val lines = parseLrc("[00:10.00][00:40.00] chorus")
        assertEquals(listOf(10_000L, 40_000L), lines.map { it.timeMs })
        assertEquals(listOf("chorus", "chorus"), lines.map { it.text })
    }

    @Test
    fun metadataTagsAndWordStampsAreDropped() {
        val lines = parseLrc("[ar:Ren]\n[ti:Seven Sins]\n[00:01.000] <00:01.000>hello <00:01.500>world")
        assertEquals(1, lines.size)
        assertEquals("hello world", lines[0].text)
    }

    @Test
    fun aStampWithNoWordsIsAnInstrumentalGap() {
        val lines = parseLrc("[00:30.00]")
        assertEquals(listOf(LyricLine(30_000L, "")), lines)
    }

    @Test
    fun activeLineIsTheLastOneStarted() {
        val lines = parseLrc("[00:05.00] a\n[00:10.00] b\n[00:20.00] c")
        assertEquals(-1, activeLyricIndex(lines, 4_999L))
        assertEquals(0, activeLyricIndex(lines, 5_000L))
        assertEquals(1, activeLyricIndex(lines, 19_999L))
        assertEquals(2, activeLyricIndex(lines, 90_000L))
        assertEquals(-1, activeLyricIndex(emptyList(), 1_000L))
    }

    private fun record(
        artist: String = "Ren",
        duration: Double = 200.0,
        synced: String? = "[00:01.00] hi",
        plain: String? = "hi",
        instrumental: Boolean = false,
    ) = LrcLibRecord("Seven Sins", artist, duration, instrumental, plain, synced)

    @Test
    fun syncedBeatsPlainWhenBothAreThere() {
        assertIs<Lyrics.Synced>(lyricsOf(record()))
        assertIs<Lyrics.Plain>(lyricsOf(record(synced = null)))
        assertEquals(Lyrics.Instrumental, lyricsOf(record(synced = null, plain = null, instrumental = true)))
        assertNull(lyricsOf(record(synced = null, plain = null)))
    }

    @Test
    fun pickingWantsTheSameArtistAndLength() {
        val right = record(duration = 201.0)
        val otherCut = record(duration = 260.0)
        val otherArtist = record(artist = "Someone Else")
        assertEquals(right, pickLrcLibRecord(listOf(otherCut, otherArtist, right), "Ren", 200))
        assertNull(pickLrcLibRecord(listOf(otherCut, otherArtist), "Ren", 200))
    }

    @Test
    fun pickingPrefersSyncedThenTheClosestLength() {
        val plainExact = record(duration = 200.0, synced = null)
        val syncedNear = record(duration = 202.0)
        assertEquals(syncedNear, pickLrcLibRecord(listOf(plainExact, syncedNear), "Ren", 200))
    }

    @Test
    fun fanartComesFromTheArtistWithTheSameName() {
        val body = """{"artists":[
            {"strArtist":"Ren Other","strArtistFanart":"https://x/other.jpg"},
            {"strArtist":"Ren","strArtistFanart":"https://x/1.jpg","strArtistFanart2":"","strArtistFanart3":"https://x/3.jpg","strArtistWideThumb":null}
        ]}"""
        assertEquals(listOf("https://x/1.jpg", "https://x/3.jpg"), fanartFromAudioDb(body, "ren"))
        assertEquals(emptyList(), fanartFromAudioDb("""{"artists":null}""", "Ren"))
        assertEquals(emptyList(), fanartFromAudioDb("not json", "Ren"))
    }
}
