package com.nuvio.app.features.music

import com.nuvio.app.features.music.library.LibraryRelease
import com.nuvio.app.features.music.library.ReleaseKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlbumObjectModelTest {
    private fun track(n: Int, seconds: Int = 200) = MusicTrack(
        videoId = "v$n", title = "Song $n", artists = emptyList(), durationSeconds = seconds, trackNumber = n, isAvailable = true,
    )

    private fun album(tracks: Int) = MusicAlbum(
        browseId = "MPREb_x", title = "Album", artists = listOf(MusicArtistRef("Band", "UC1")), year = "2014",
        kind = "Album", thumbnailUrl = null, tracks = (1..tracks).map { track(it) }, audioPlaylistId = null,
    )

    private fun release(
        label: String? = null, date: String? = null, genres: List<String> = emptyList(),
        style: String? = null, mood: String? = null, description: String? = null, upc: String? = null, explicit: Boolean? = null,
    ) = LibraryRelease(
        key = "k", title = "Album", kind = ReleaseKind.Album, label = label, releaseDate = date, genres = genres,
        style = style, mood = mood, description = description, upc = upc, explicit = explicit,
    )

    @Test
    fun releaseDatesReadAsPeopleWriteThem() {
        assertEquals("20 May 2014", formatReleaseDate("2014-05-20"))
        assertEquals("May 2014", formatReleaseDate("2014-05"))
        assertEquals("2014", formatReleaseDate("2014"))
        assertEquals("2014", formatReleaseDate("2014-13-40"))
        assertEquals("sometime", formatReleaseDate(" sometime "))
        assertNull(formatReleaseDate(null))
        assertNull(formatReleaseDate("  "))
    }

    @Test
    fun backCoverListsTracksAndCapsLongOnes() {
        val back = buildBackCover(album(23), release(label = "Island", date = "2014-05-20"))
        assertEquals(BACK_COVER_MAX_LINES, back.lines.size)
        assertEquals(23 - BACK_COVER_MAX_LINES, back.hiddenCount)
        assertEquals("3:20", back.lines.first().duration)
        assertEquals("Island · 20 May 2014", back.credit)
        assertEquals("Band", back.artist)
    }

    @Test
    fun backCoverWithoutLibraryDetailsFallsBackToTheAlbumYear() {
        val back = buildBackCover(album(3), null)
        assertEquals(0, back.hiddenCount)
        assertEquals("2014", back.credit)
    }

    @Test
    fun barcodeIsStableAndFramedByThinBars() {
        val a = barcodeBars("602537", seed = "x")
        assertEquals(a, barcodeBars("602537", seed = "y"))
        assertEquals(barcodeBars(null, seed = "x"), barcodeBars("", seed = "x"))
        assertTrue(a.all { it in 1..3 })
        assertEquals(listOf(1, 1, 1), a.take(3))
        assertEquals(listOf(1, 1, 1), a.takeLast(3))
        assertFalse(barcodeBars(null, seed = "x") == barcodeBars(null, seed = "z"))
    }

    @Test
    fun linerNotesKeepMoodApartFromGenresAndDropDuplicates() {
        val notes = buildLinerNotes(
            release(label = "Island", date = "2014-05-20", genres = listOf("Rock", "rock", "Soul"), style = "Blues", mood = "soul", explicit = true),
            fallbackYear = "2014",
        )
        assertEquals(listOf("Rock", "Blues"), notes.genres)
        assertEquals("soul", notes.mood)
        assertEquals("20 May 2014", notes.date)
        assertTrue(notes.explicit)
        assertFalse(notes.isEmpty)
    }

    @Test
    fun linerNotesForAnUnknownReleaseAreJustTheYear() {
        val notes = buildLinerNotes(null, fallbackYear = "2014")
        assertEquals("2014", notes.date)
        assertNull(notes.label)
        assertFalse(notes.isEmpty)
        assertTrue(buildLinerNotes(null, null).isEmpty)
    }
}
