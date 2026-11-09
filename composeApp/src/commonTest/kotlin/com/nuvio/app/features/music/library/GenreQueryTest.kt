package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GenreQueryTest {
    @Test fun knownGenreAndSpellings() {
        assertEquals("shoegaze", detectGenreQuery("Shoegaze")?.tag)
        assertEquals("post-punk", detectGenreQuery("post punk")?.tag)
        assertEquals("post-punk", detectGenreQuery("postpunk")?.tag)
        assertEquals("hip-hop", detectGenreQuery("hip hop music")?.tag)
        assertEquals("trip-hop", detectGenreQuery("trip hop")?.tag)
    }

    @Test fun pluralsAndWordOrder() {
        assertEquals("punk", detectGenreQuery("punks")?.tag)
        assertEquals("indie rock", detectGenreQuery("rock indie")?.tag)
        assertEquals("dream pop", detectGenreQuery("pop dream")?.tag)
    }

    @Test fun fillerIsIgnored() {
        assertEquals("jazz", detectGenreQuery("the best jazz songs")?.tag)
        assertEquals("Jazz", detectGenreQuery("jazz radio")?.label)
    }

    @Test fun decades() {
        val d = detectGenreQuery("90s")!!
        assertEquals(TagKind.Decade, d.kind)
        assertEquals(1990, d.decade)
        assertEquals(1980, detectGenreQuery("the '80s")?.decade)
        assertEquals(2000, detectGenreQuery("00s")?.decade)
        assertEquals(1970, detectGenreQuery("seventies")?.decade)
        val withGenre = detectGenreQuery("90s rock")!!
        assertEquals("rock", withGenre.tag)
        assertEquals(1990, withGenre.decade)
        assertEquals("90s Rock", withGenre.label)
        assertNull(detectGenreQuery("1993s"))
    }

    @Test fun moodsAndActivities() {
        assertEquals(TagKind.Mood, detectGenreQuery("chill")?.kind)
        assertEquals(TagKind.Activity, detectGenreQuery("workout music")?.kind)
        assertEquals("ambient", detectGenreQuery("study")?.tag)
        assertEquals(TagKind.Activity, detectGenreQuery("road trip")?.kind)
    }

    @Test fun namesAreNotGenres() {
        assertNull(detectGenreQuery("Radiohead"))
        assertNull(detectGenreQuery("My Bloody Valentine"))
        assertNull(detectGenreQuery(""))
        assertNull(detectGenreQuery("one two three four five"))
    }

    @Test fun offlineCopyDecidesUnknownTags() {
        assertNull(detectGenreQuery("vaportrap"))
        assertEquals("vaportrap", detectGenreQuery("vaportrap") { it == "vaportrap" }?.tag)
        assertNull(detectGenreQuery("seen live") { true })
        assertNull(detectGenreQuery("a b c d e") { true })
    }

    @Test fun realTagNeedsManyVotes() {
        fun c(i: Int, votes: Int) = LadderCandidate("id$i", "a$i", mapOf("vaportrap" to votes))
        assertTrue(looksLikeRealTag("vaportrap", (1..10).map { c(it, 3) }))
        assertFalse(looksLikeRealTag("vaportrap", (1..10).map { c(it, 1) }))
        assertFalse(looksLikeRealTag("vaportrap", (1..3).map { c(it, 9) }))
    }
}

class GenreStationTest {
    private fun list(id: String, title: String) = com.nuvio.app.features.music.MusicPlaylistSummary(id, title, null, null)

    @Test fun radioNamedForTheGenreWins() {
        val g = detectGenreQuery("shoegaze")!!
        val lists = listOf(list("PL1", "Best of dreampop"), list("RDEM1", "Shoegaze Radio"), list("PL2", "Shoegaze essentials"))
        assertEquals("RDEM1", pickGenreStation(lists, g)?.playlistId)
    }

    @Test fun namedPlaylistBeatsAnyRadio() {
        val g = detectGenreQuery("jazz")!!
        val lists = listOf(list("RDEM9", "Pop mix"), list("PL3", "Late night jazz"))
        assertEquals("PL3", pickGenreStation(lists, g)?.playlistId)
    }

    @Test fun noneWhenNothingFits() {
        assertNull(pickGenreStation(listOf(list("PL4", "Workout")), detectGenreQuery("jazz")!!))
    }

    @Test fun ranksByTagVotesAndMarksOwned() {
        val c = listOf(
            LadderCandidate("1", "Slowdive", mapOf("shoegaze" to 20)),
            LadderCandidate("2", "Ride", mapOf("Shoegaze" to 30)),
            LadderCandidate("3", "Lush", mapOf("shoegaze" to 5)),
        )
        val ranked = rankTagArtists("shoegaze", c, setOf("slowdive"))
        assertEquals(listOf("Ride", "Slowdive", "Lush"), ranked.map { it.name })
        assertTrue(ranked[1].owned)
    }
}
