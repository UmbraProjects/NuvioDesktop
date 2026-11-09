package com.spydermahn.nuvioremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryLinesTest {
    @Test
    fun readsTheVideoLibrary() {
        val line = "LIBRARY video 1 Movies,movie,tt0083658,Blade%20Runner%2C%20Final%20Cut,1982,https%3A%2F%2Fimg%2Fa.jpg,-,1;" +
            "Shows,series,tt0903747,Breaking%20Bad,-,-,-,0"
        val parsed = ServerLine.parse(line, 0L) as ServerLine.VideoLibrary
        assertTrue(parsed.ready)
        assertEquals(
            listOf(
                LibraryTitle("Movies", "movie", "tt0083658", "Blade Runner, Final Cut", "1982", "https://img/a.jpg", null, true),
                LibraryTitle("Shows", "series", "tt0903747", "Breaking Bad", null, null, null, false),
            ),
            parsed.titles,
        )
    }

    @Test
    fun readsTheMusicLibraryAndEmptyLists() {
        val parsed = ServerLine.parse("LIBRARY music 0 UC1,Beyonc%C3%A9,-,1 UC1,MPREb_1,Lemonade,Album,2016,-,0", 0L) as ServerLine.MusicLibrary
        assertEquals(false, parsed.ready)
        assertEquals(listOf(LibraryArtistInfo("UC1", "Beyoncé", null, true)), parsed.artists)
        assertEquals(listOf(LibraryAlbum("UC1", "MPREb_1", "Lemonade", "Album", "2016", null, false)), parsed.albums)

        val empty = ServerLine.parse("LIBRARY music 1 - -", 0L) as ServerLine.MusicLibrary
        assertTrue(empty.artists.isEmpty() && empty.albums.isEmpty())
    }

    @Test
    fun ignoresWhatItDoesNotKnow() {
        assertNull(LibraryLines.parse("LIBRARY films 1 -"))
        assertEquals(ServerLine.Other, ServerLine.parse("LIBRARY", 0L))
        // A short record is skipped, not a crash.
        assertTrue((ServerLine.parse("LIBRARY video 1 Movies,movie", 0L) as ServerLine.VideoLibrary).titles.isEmpty())
    }

    @Test
    fun readsTheAudioList() {
        val parsed = ServerLine.parse("AUDIO 0 Track%201%20%28Stereo%29,eng,-,1;Commentary,-,-,0", 0L) as ServerLine.Audio
        assertEquals(
            listOf(SubtitleChoice(1, "Track 1 (Stereo)", "eng", "", true), SubtitleChoice(2, "Commentary", null, "", false)),
            parsed.choices,
        )
    }

    @Test
    fun readsTheSubtitleList() {
        val line = "SUBTITLES 1 Off,-,Off,0;English%2C%20SDH,eng%20%C2%B7%20forced,Built-in,0;" +
            "English%20%28SubSource%29,eng,OpenSubtitles%20v3,1"
        val parsed = ServerLine.parse(line, 0L) as ServerLine.Subtitles
        assertTrue(parsed.loading)
        assertEquals(
            listOf(
                SubtitleChoice(1, "Off", null, "Off", false),
                SubtitleChoice(2, "English, SDH", "eng \u00b7 forced", "Built-in", false),
                SubtitleChoice(3, "English (SubSource)", "eng", "OpenSubtitles v3", true),
            ),
            parsed.choices,
        )
        assertEquals(emptyList<SubtitleChoice>(), (ServerLine.parse("SUBTITLES 0 -", 0L) as ServerLine.Subtitles).choices)
    }
}
