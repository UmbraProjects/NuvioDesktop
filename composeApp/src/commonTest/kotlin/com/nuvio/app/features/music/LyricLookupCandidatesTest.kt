package com.nuvio.app.features.music

import kotlin.test.Test
import kotlin.test.assertEquals

class LyricLookupCandidatesTest {
    @Test
    fun anAlbumTrackIsAskedAsItIs() {
        assertEquals(listOf("The Mother We Share" to "CHVRCHES"), lyricLookupCandidates("The Mother We Share", "CHVRCHES"))
    }

    @Test
    fun aGroupUploadAlsoTriesItsFirstArtist() {
        val c = lyricLookupCandidates("STAY", "Alex Goot, Kurt Hugo Schneider & First to Eleven")
        assertEquals("STAY" to "Alex Goot", c[1])
        assertEquals("Forever Young" to "Dave Moffatt", lyricLookupCandidates("Forever Young", "Dave Moffatt & Jada Facer")[1])
    }

    @Test
    fun aCoverTitleIsReadForTheOriginalSong() {
        val c = lyricLookupCandidates("Hopelessly Devoted To You - Olivia Newton-John (Cover by Emily Linge)", "Emily Linge")
        assertEquals(true, "Hopelessly Devoted To You" to "Olivia Newton-John" in c)
    }
}
