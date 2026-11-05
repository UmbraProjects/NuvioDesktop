package com.nuvio.app.features.music.home

import com.nuvio.app.features.music.MusicArtistRef
import com.nuvio.app.features.music.MusicTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicHomeSearchTest {
    private val library = listOf("Yelawolf", "2Pac", "Dr. Dre", "The Notorious B.I.G.", "Guns N' Roses")

    private fun track(id: String, title: String, artist: String, seconds: Int) =
        MusicTrack(id, title, listOf(MusicArtistRef(artist)), seconds, 1, true)

    // Sentence chips

    @Test
    fun aKindWordBecomesAChipAndTheRestIsTheArtist() {
        val q = parseQuery("yelawolf remix", library)
        assertEquals("yelawolf", q.terms)
        assertEquals("Yelawolf", q.artist)
        assertEquals(QueryKind.Remix, q.kind)
        assertEquals(listOf("Yelawolf", "remix"), q.chips.map { (it as? QueryChip.Artist)?.name ?: it.word })
        assertEquals("yelawolf remix", q.searchText)
    }

    @Test
    fun aYearAndAnArtistWithMoreWordsAfterIt() {
        val q = parseQuery("2pac changes 1998", library)
        assertEquals("2Pac", q.artist)
        assertEquals(1998, q.year)
        assertEquals("2pac changes", q.terms)
    }

    @Test
    fun albumsOnlyFiltersButIsNotSearchedFor() {
        val q = parseQuery("dr dre albums", library)
        assertEquals(QueryKind.Albums, q.kind)
        assertEquals("Dr. Dre", q.artist)
        assertEquals("dr dre", q.searchText)
    }

    @Test
    fun aKindWordOnItsOwnIsAName() {
        val q = parseQuery("Live", library)
        assertNull(q.kind)
        assertEquals("Live", q.terms)
        assertNull(parseQuery("1999", library).year)
    }

    @Test
    fun namesFoldTheAndPunctuation() {
        assertEquals("notorious b i g", foldName("The Notorious B.I.G."))
        assertEquals("The Notorious B.I.G.", libraryArtistIn("notorious b i g", library))
        assertEquals("Guns N' Roses", libraryArtistIn("guns n roses", library))
        assertNull(libraryArtistIn("yelawolfish", library))
    }

    @Test
    fun kindWordsInTitles() {
        assertTrue(titleMatchesKind("Till It's Gone (Remix)", QueryKind.Remix))
        assertTrue(titleMatchesKind("Daddy's Lambo - Bass Boosted Edit", QueryKind.Remix))
        assertFalse(titleMatchesKind("Remixing Life", QueryKind.Remix))
        assertTrue(titleMatchesKind("Outer Space (LIVE) at Sturgis", QueryKind.Live))
        assertFalse(titleMatchesKind("Delivery", QueryKind.Live))
    }

    @Test
    fun officialUploadsByChannel() {
        assertTrue(isOfficialUpload("Yelawolf", listOf("Yelawolf")))
        assertTrue(isOfficialUpload("YelawolfVEVO", listOf("Yelawolf")))
        assertTrue(isOfficialUpload("Yelawolf - Topic", listOf("Yelawolf")))
        assertTrue(isOfficialUpload("Slumerican Records", listOf("Yelawolf")))
        assertFalse(isOfficialUpload("JBL Bass Testers", listOf("Yelawolf")))
        assertFalse(isOfficialUpload(null, listOf("Yelawolf")))
    }

    // Merged rows

    @Test
    fun aSongAndItsVideoBecomeOneRow() {
        val songs = listOf(track("a", "Till It's Gone", "Yelawolf", 240), track("b", "Box Chevy V", "Yelawolf", 252))
        val videos = listOf(
            track("v1", "Yelawolf - Till It's Gone (Official Music Video)", "YelawolfVEVO", 243),
            track("v2", "Box Chevy V (Extended Mix)", "Yelawolf", 400),
            track("v3", "Interview", "Some Radio", 700),
        )
        val merged = mergeSourceRows(songs, videos)
        assertEquals(listOf("v1", null), merged.rows.map { it.video?.videoId })
        assertEquals(listOf("v2", "v3"), merged.otherVideos.map { it.videoId })
    }

    @Test
    fun aVideoPairsOnceAndAnotherArtistNever() {
        val songs = listOf(track("a", "Changes", "2Pac", 269), track("b", "Changes", "2Pac", 269))
        val videos = listOf(track("v", "Changes", "2Pac", 270), track("w", "Changes", "Black Sabbath", 270))
        val merged = mergeSourceRows(songs, videos)
        assertEquals(listOf("v", null), merged.rows.map { it.video?.videoId })
        assertEquals(listOf("w"), merged.otherVideos.map { it.videoId })
    }

    @Test
    fun songKeysDropVideoWords() {
        assertEquals("till it s gone", songKey("Yelawolf - Till It's Gone (Official Music Video)"))
        assertEquals("best friend", songKey("Best Friend feat. Eminem"))
        assertEquals("hit em up", songKey("Hit 'Em Up [Official Audio] HD"))
    }

    // Letter strip

    @Test
    fun likelyLettersComeFromLibraryFirst() {
        val candidates = listOf(
            "Yelawolf" to LetterSource.Library,
            "Yeat" to LetterSource.Suggestion,
            "Yellowcard" to LetterSource.Suggestion,
            "Yes" to LetterSource.History,
        )
        assertEquals(listOf('L', 'A', 'S'), nextLetterRanks("ye", candidates))
        assertEquals(listOf('A', 'L'), nextLetterRanks("yel", candidates))
        assertEquals(listOf(' '), nextLetterRanks("dr", listOf("Dr. Dre" to LetterSource.Library)))
        assertTrue(nextLetterRanks("zz", candidates).isEmpty())
    }

    @Test
    fun theStripPullsLikelyLettersBesideTheFocus() {
        val arranged = arrangeStrip('L', listOf('A', 'W', 'O'))
        val at = arranged.indexOf('L')
        assertEquals('A', arranged[at + 1])
        assertEquals('W', arranged[at - 1])
        assertEquals('O', arranged[at + 2])
        assertEquals(26, arranged.size)
        assertEquals(STRIP_LETTERS.toSet(), arranged.toSet())
    }

    @Test
    fun theStripRestsAToZWithNothingToPull() {
        assertEquals(STRIP_LETTERS, arrangeStrip(null, listOf('A')))
        assertEquals(STRIP_LETTERS, arrangeStrip('L', emptyList()))
        assertEquals(STRIP_LETTERS, arrangeStrip('L', listOf(' ', '7')))
        assertEquals(STRIP_LETTERS.size, arrangeStrip('A', listOf('Z', 'Y', 'X', 'W')).size)
        assertEquals(STRIP_LETTERS.size, arrangeStrip('Z', listOf('A', 'B', 'C')).size)
    }

    @Test
    fun ghostCompletionKeepsTheCandidatesSpelling() {
        assertEquals("wolf", ghostCompletion("yela", listOf("yelawolf best friend", "Yelawolf").sortedBy { it.length }))
        assertEquals("", ghostCompletion("", listOf("Yelawolf")))
        assertEquals("", ghostCompletion("yelawolf", listOf("Yelawolf")))
    }

    // Settling

    @Test
    fun aMatchSettlesAfterTwoSearchesInARow() {
        val s = MatchSettler()
        assertNull(s.offer("UCyeat"))
        assertEquals("UCyela", s.offer("UCyela").let { s.offer("UCyela") })
        assertEquals("UCyela", s.offer("UCother"))
        assertEquals("UCyela", s.offer(null))
        assertEquals("UCother", s.offer("UCother").let { s.offer("UCother") })
        s.reset()
        assertNull(s.settled)
    }
}
