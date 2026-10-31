package com.nuvio.app.features.music

import com.nuvio.app.features.trailer.YouTubeSearchHit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YouTubeMusicSearchTest {
    @Test
    fun splitsArtistAndSongOnTheDashAndDropsUploadLabels() {
        assertEquals(
            "Sabrina Carpenter" to "Espresso",
            splitYouTubeMusicTitle("Sabrina Carpenter - Espresso (Official Video)", "SabrinaCarpenterVEVO"),
        )
        assertEquals(
            "Fred again.." to "leavemealone",
            splitYouTubeMusicTitle("Fred again.. – leavemealone [Official Audio] [HD]", "Fred again.."),
        )
    }

    @Test
    fun keepsBracketsThatArePartOfTheSong() {
        assertEquals(
            "Artist" to "Song (feat. Someone) (Live at Glastonbury)",
            splitYouTubeMusicTitle("Artist - Song (feat. Someone) (Live at Glastonbury) (Official Video)", "Artist"),
        )
    }

    @Test
    fun withNoDashTheChannelIsTheArtist() {
        assertEquals("Mitski" to "My Love Mine All Mine", splitYouTubeMusicTitle("My Love Mine All Mine", "Mitski - Topic"))
        assertEquals("Adele" to "Hello", splitYouTubeMusicTitle("Hello | Official Music Video", "AdeleVEVO"))
    }

    @Test
    fun hitBecomesAPlayableTrackWithAThumbnail() {
        val track = YouTubeSearchHit("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up (Official Music Video)", "Rick Astley", true, 213, 1L)
            .toMusicTrack()
        assertEquals("Never Gonna Give You Up", track.title)
        assertEquals("Rick Astley", track.artists.single().name)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", track.coverUrl)
        assertEquals(listOf(track), youTubeSingleAlbum(track).tracks)
    }

    @Test
    fun lengthLabel() {
        assertEquals("3:33", youTubeLengthLabel(213))
        assertEquals("1:02:05", youTubeLengthLabel(3725))
        assertNull(youTubeLengthLabel(0))
    }
}
