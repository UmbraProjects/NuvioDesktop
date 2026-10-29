package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.MusicAlbumSummary
import com.nuvio.app.features.music.MusicArtistRef
import com.nuvio.app.features.music.MusicArtistSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchSectionsModelTest {
    private fun artist(name: String) = MusicArtistSummary(browseId = "UC$name", name = name, thumbnailUrl = null)
    private fun album(title: String, by: String?) = MusicAlbumSummary(
        browseId = "MPREb_$title", title = title, artists = listOfNotNull(by?.let { MusicArtistRef(name = it, browseId = null) }),
        year = "1991", kind = "Album", thumbnailUrl = null,
    )

    @Test
    fun cappedRowsAddsOneSeeAllRowOnlyWhenThereIsMore() {
        assertEquals(0, cappedRows(0, 6))
        assertEquals(4, cappedRows(4, 6))
        assertEquals(6, cappedRows(6, 6))
        assertEquals(7, cappedRows(7, 6))
        assertEquals(7, cappedRows(40, 6))
    }

    @Test
    fun headersSitAfterEverythingBeforeThem() {
        val slots = sectionSlots(ladderOffers = 2, artists = 20, albums = 5)
        assertEquals(listOf(SearchSection.Ladder, SearchSection.Artists, SearchSection.Albums), slots.map { it.section })
        // Ladder: header 0, rows 1-2. Artists: header 3, six rows plus See all (4-10). Albums: header 11.
        assertEquals(listOf(0, 3, 11), slots.map { it.headerIndex })
        assertEquals(listOf(2, 20, 5), slots.map { it.total })
        assertEquals(listOf(2, 7, 5), slots.map { it.rows })
    }

    @Test
    fun emptySectionsAreLeftOutAndTheRestShiftUp() {
        val slots = sectionSlots(ladderOffers = 0, artists = 0, albums = 3)
        assertEquals(listOf(SearchSection.Albums), slots.map { it.section })
        assertEquals(0, slots.single().headerIndex)
        assertTrue(sectionSlots(0, 0, 0).isEmpty())
    }

    @Test
    fun chipsNeedTwoSections() {
        assertFalse(showsChips(sectionSlots(0, 0, 3)))
        assertTrue(showsChips(sectionSlots(0, 2, 3)))
    }

    @Test
    fun currentSectionIsTheLastHeaderAtOrAboveTheFirstVisibleItem() {
        val slots = sectionSlots(2, 20, 5) // headers at 0, 3, 11
        assertEquals(SearchSection.Ladder, currentSection(slots, 0))
        assertEquals(SearchSection.Ladder, currentSection(slots, 2))
        assertEquals(SearchSection.Artists, currentSection(slots, 3))
        assertEquals(SearchSection.Artists, currentSection(slots, 10))
        assertEquals(SearchSection.Albums, currentSection(slots, 11))
        assertEquals(SearchSection.Albums, currentSection(slots, 99))
        assertNull(currentSection(emptyList(), 0))
    }

    @Test
    fun chipLabelsCountResultsExceptForLadder() {
        val slots = sectionSlots(2, 12, 5)
        assertEquals(listOf("Ladder", "Artists 12", "Albums 5"), slots.map { chipLabel(it) })
    }

    @Test
    fun aGenreQueryGivesTheGenreCardAndAnArtistQueryGivesTwoClimbCards() {
        val artists = listOf(artist("My Bloody Valentine"))
        val genre = groupedLadderOffers("shoegaze", artists, emptyList())
        assertEquals(1, genre.size)
        assertTrue(genre.single().request is LadderRequest.Genre)
        val offers = groupedLadderOffers("my bloody", artists, emptyList())
        assertEquals(2, offers.size)
        assertTrue(offers.all { it.request is LadderRequest.Artist })
    }

    @Test
    fun withNoArtistResultTheFirstAlbumGivesTheOffers() {
        val offers = groupedLadderOffers("loveless", emptyList(), listOf(album("Loveless", "My Bloody Valentine")))
        assertEquals(2, offers.size)
        assertTrue(offers.all { it.request is LadderRequest.Album })
    }

    @Test
    fun noResultsAndNoGenreGiveNoOffers() {
        assertTrue(groupedLadderOffers("zzzz", emptyList(), emptyList()).isEmpty())
        assertTrue(groupedLadderOffers("zzzz", emptyList(), listOf(album("X", null))).isEmpty())
        assertTrue(groupedLadderOffers("a", listOf(artist("A")), emptyList()).isEmpty())
    }
}
