package com.nuvio.app.features.music.library

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchStartModelTest {
    private fun artist(id: String, vararg genres: String) = LibraryArtist(id = id, name = "Artist $id", genres = genres.toList())

    private val library = listOf(
        artist("1", "rock", "drum and bass"),
        artist("2", "rock"),
        artist("3", "rock", "soul"),
        artist("4", "soul", "drum and bass"),
        artist("5", "rock", "jazz"),
    )

    @Test
    fun countsDistinctArtistsPerGenreBiggestFirst() {
        val counts = genreCounts(library)
        assertEquals("rock", counts.first().genre)
        assertEquals(4, counts.first().artists)
        assertEquals(2, counts.first { it.genre == "soul" }.artists)
    }

    @Test
    fun startersAreTheThinnestGenresAndSingleArtistGenresComeLast() {
        val starters = ladderStarters(library)
        assertEquals(listOf("drum and bass", "soul", "rock", "jazz"), starters.map { it.genre })
        assertEquals("Into Drum and Bass", starters.first().title)
        assertEquals(LadderRequest.Genre("drum and bass"), starters.first().request)
        assertEquals(2, ladderStarters(library, limit = 2).size)
    }

    @Test
    fun genreTilesAreTheBiggestGenres() {
        assertEquals("rock", genreTiles(library, limit = 1).single().genre)
    }

    @Test
    fun continueCardsNeedARequestAndAClimbedRung() {
        val rung = ClimbedRung("a", "A", "why", 0.5, 0.4, feedback = RungFeedback.Love)
        val shoegaze = LadderProgress(climbed = listOf(rung, rung.copy(id = "b")), request = LadderRequest.Genre("shoegaze"))
        val old = LadderProgress(climbed = listOf(rung))
        val none = LadderProgress(request = LadderRequest.Genre("jazz"))
        val cards = continueCards(listOf(shoegaze, old, none))
        assertEquals(1, cards.size)
        assertEquals("Into Shoegaze", cards.single().title)
        assertEquals("2 of 5 rungs climbed", cards.single().detail)
    }

    @Test
    fun requestsSurviveSerialisation() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        for (request in listOf(
            LadderRequest.Genre("shoegaze"),
            LadderRequest.Artist("Sting", LadderMode.OutFrom, "jazz"),
            LadderRequest.Album("Nevermind", "Nirvana", "1991", LadderMode.Into),
        )) {
            val back = json.decodeFromString<LadderProgress>(json.encodeToString(LadderProgress(request = request)))
            assertEquals(request, back.request)
        }
        assertNull(json.decodeFromString<LadderProgress>("{\"dial\":0.25}").request)
    }

    @Test
    fun savingAnExistingLadderMovesItToTheEnd() {
        var payload: String? = null
        val book = LadderProgressBook({ payload }, { payload = it })
        val rung = ClimbedRung("a", "A", "why", 0.5, 0.4, feedback = RungFeedback.Love)
        book.put("one", LadderProgress(climbed = listOf(rung), request = LadderRequest.Genre("one")))
        book.put("two", LadderProgress(climbed = listOf(rung), request = LadderRequest.Genre("two")))
        book.put("one", LadderProgress(climbed = listOf(rung, rung.copy(id = "b")), request = LadderRequest.Genre("one")))
        assertEquals(listOf("two", "one"), book.all().map { (it.request as LadderRequest.Genre).genre })
    }

    @Test
    fun recentChipsDropBlanksAndRepeats() {
        assertEquals(listOf("Sting", "jazz"), recentChips(listOf("Sting", " ", "sting", "jazz")))
        assertTrue(startIsEmpty(emptyList(), emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun fragmentsAndSingleUnknownTagsAreNotGenreCards() {
        assertTrue(plausibleGenre("acid jazz"))
        assertTrue(plausibleGenre("lo-fi house"))
        assertTrue(!plausibleGenre("aln sh"))
        assertTrue(!plausibleGenre("x"))
        val lib = listOf(artist("1", "aln sh", "jazz"), artist("2", "aln sh", "jazz"), artist("3", "zork wave"))
        assertEquals(setOf("jazz", "zork wave"), genreCounts(lib).map { it.genre }.toSet())
        assertEquals(listOf("jazz"), ladderStarters(lib).map { it.genre })
    }

    @Test
    fun genresAreShownAsTitles() {
        assertEquals("Acid Jazz", displayGenre("acid jazz"))
        assertEquals("Hip-Hop", displayGenre("hip-hop"))
        assertEquals("R&B", displayGenre("r&b"))
        assertEquals("Drum and Bass", displayGenre("drum and bass"))
        assertEquals("IDM", displayGenre("idm"))
    }

    @Test
    fun startersCarryTheirArtistsAndNeighbouringGenres() {
        val starter = ladderStarters(library).first { it.genre == "soul" }
        assertEquals(listOf("3", "4"), starter.artistIds)
        assertEquals(listOf("Rock", "Drum and Bass"), starter.neighbours)
    }

    @Test
    fun startersDoNotRepeatEachOther() {
        // One big name carries both thin genres, and the same two neighbours sit beside each.
        val lib = listOf(
            artist("b", "country", "teen pop", "hip-hop", "pop"),
            artist("d", "country", "folk"),
            artist("s", "teen pop", "pop"),
            artist("x", "hip-hop"), artist("y", "hip-hop"), artist("z", "pop"), artist("w", "pop"),
        )
        val starters = ladderStarters(lib, limit = 2)
        assertEquals(2, starters.size)
        assertTrue(starters[0].artistIds.first() != starters[1].artistIds.first())
        assertTrue(starters[0].neighbours != starters[1].neighbours || starters[0].neighbours.size < 2)
    }

    @Test
    fun genreLadderTitleIsCapitalised() {
        assertEquals("Into Americana", requestTitle(LadderRequest.Genre("americana")))
    }

    @Test
    fun continueCardsKeepTheClimbedArtistNames() {
        val progress = LadderProgress(
            climbed = listOf(ClimbedRung("a", "Nina Simone", "why", 0.2, 0.5, feedback = RungFeedback.Love)),
            request = LadderRequest.Genre("soul"),
        )
        assertEquals(listOf("Nina Simone"), continueCards(listOf(progress)).single().names)
    }

    @Test
    fun startArtUsesPortraitThenCover() {
        val withThumb = LibraryArtist(id = "1", name = "A", art = ArtistArtwork(thumb = "t"))
        val withBackdrop = LibraryArtist(id = "2", name = "B", art = ArtistArtwork(backgrounds = listOf("bg")))
        val none = LibraryArtist(id = "3", name = "C")
        val art = startArt(listOf(withThumb, withBackdrop, none))
        assertEquals("t", art.getValue("1").picture)
        assertEquals("bg", art.getValue("2").picture)
        assertNull(art.getValue("3").picture)
    }
}
