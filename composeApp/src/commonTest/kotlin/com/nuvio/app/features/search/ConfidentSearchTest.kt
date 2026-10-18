package com.nuvio.app.features.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConfidentSearchTest {
    private fun c(name: String, year: Int?, tv: Boolean, popularity: Double, anime: Boolean = false) =
        ConfidentSearchCandidate("$name/$year/${if (tv) "tv" else "movie"}", name, year, tv, anime, popularity)

    private val carries = listOf(
        c("Carrie", 1976, tv = false, popularity = 30.0),
        c("Carrie", 2013, tv = false, popularity = 40.0),
        c("Carrie", 2002, tv = false, popularity = 5.0),
        c("Carrie", 2026, tv = true, popularity = 50.0),
        c("Carrie Pilby", 2016, tv = false, popularity = 20.0),
    )

    @Test
    fun parsesTrailingQualifiers() {
        assertEquals(ConfidentSearchQuery("Carrie", year = 2026), parseConfidentSearchQuery("Carrie 2026"))
        assertEquals(ConfidentSearchQuery("carrie", kind = ConfidentSearchKind.Tv), parseConfidentSearchQuery("carrie tv"))
        assertEquals(ConfidentSearchQuery("carrie", kind = ConfidentSearchKind.Tv), parseConfidentSearchQuery("carrie tv show"))
        assertEquals(ConfidentSearchQuery("sword art online", season = 3), parseConfidentSearchQuery("sword art online s3"))
        assertEquals(ConfidentSearchQuery("sword art online", season = 3), parseConfidentSearchQuery("sword art online season 3"))
        assertEquals(ConfidentSearchQuery("Monster", kind = ConfidentSearchKind.Anime), parseConfidentSearchQuery("Monster anime"))
        assertEquals(
            ConfidentSearchQuery("Carrie", year = 2013, kind = ConfidentSearchKind.Movie),
            parseConfidentSearchQuery("Carrie (2013) movie"),
        )
    }

    @Test
    fun neverStripsTheWholeQuery() {
        assertEquals(ConfidentSearchQuery("1917"), parseConfidentSearchQuery("1917"))
        assertEquals(ConfidentSearchQuery("Movie"), parseConfidentSearchQuery("Movie"))
        assertEquals(ConfidentSearchQuery("tv", year = 2020), parseConfidentSearchQuery("tv 2020"))
    }

    @Test
    fun bareNameWithNamesakesIsNotConfident() {
        val pick = pickConfidentCandidate(ConfidentSearchQuery("Carrie"), carries)!!
        assertEquals("Carrie/2026/tv", pick.value)
        assertEquals(50.0 / 125.0, pick.confidence, 1e-9)
    }

    @Test
    fun yearOrTypeNarrowsToOne() {
        assertEquals(1.0, pickConfidentCandidate(ConfidentSearchQuery("Carrie", year = 2026), carries)!!.confidence)
        assertEquals("Carrie/2026/tv", pickConfidentCandidate(ConfidentSearchQuery("carrie", kind = ConfidentSearchKind.Tv), carries)!!.value)
        val movies = pickConfidentCandidate(ConfidentSearchQuery("Carrie", kind = ConfidentSearchKind.Movie), carries)!!
        assertEquals("Carrie/2013/movie", movies.value)
        assertEquals(40.0 / 75.0, movies.confidence, 1e-9)
    }

    @Test
    fun onlyExactNamesCount() {
        assertNull(pickConfidentCandidate(ConfidentSearchQuery("Carrie P"), carries))
        val spider = listOf(c("Spider-Man", 2002, tv = false, popularity = 10.0))
        assertEquals(1.0, pickConfidentCandidate(ConfidentSearchQuery("spiderman"), spider)!!.confidence)
        val amelie = listOf(c("Amélie", 2001, tv = false, popularity = 10.0))
        assertEquals(1.0, pickConfidentCandidate(ConfidentSearchQuery("amelie"), amelie)!!.confidence)
    }

    @Test
    fun farMorePopularWins() {
        val deathNote = listOf(
            c("Death Note", 2006, tv = true, popularity = 50.0, anime = true),
            c("Death Note", 2017, tv = false, popularity = 10.0),
        )
        val pick = pickConfidentCandidate(ConfidentSearchQuery("death note"), deathNote)!!
        assertEquals("Death Note/2006/tv", pick.value)
        assertEquals(50.0 / 60.0, pick.confidence, 1e-9)
        assertEquals(
            "Death Note/2006/tv",
            pickConfidentCandidate(ConfidentSearchQuery("death note", kind = ConfidentSearchKind.Anime), deathNote)!!.value,
        )
    }

    @Test
    fun seasonImpliesTv() {
        val pick = pickConfidentCandidate(ConfidentSearchQuery("Carrie", season = 1), carries)!!
        assertEquals("Carrie/2026/tv", pick.value)
        assertEquals(1.0, pick.confidence)
    }

    @Test
    fun countryNarrowsRemakes() {
        assertEquals(ConfidentSearchQuery("Shameless", country = "US"), parseConfidentSearchQuery("Shameless US"))
        assertEquals(ConfidentSearchQuery("The Office", country = "GB"), parseConfidentSearchQuery("the office (UK)").copy(title = "The Office"))
        val shameless = listOf(
            ConfidentSearchCandidate("us", "Shameless", 2011, true, false, 60.0, listOf("US")),
            ConfidentSearchCandidate("uk", "Shameless", 2004, true, false, 20.0, listOf("GB")),
            // A movie result carries no country, so it isn't ruled out.
            ConfidentSearchCandidate("film", "Shameless", 2012, false, false, 1.0),
        )
        val us = pickConfidentCandidate(ConfidentSearchQuery("Shameless", country = "US"), shameless)!!
        assertEquals("us", us.value)
        assertEquals(60.0 / 61.0, us.confidence, 1e-9)
        assertEquals("uk", pickConfidentCandidate(ConfidentSearchQuery("Shameless", kind = ConfidentSearchKind.Tv, country = "GB"), shameless)!!.value)
    }

    // Popularity figures from TMDB search on 2026-10-17.
    @Test
    fun endOfTitleCounts() {
        val chamber = listOf(
            c("Harry Potter and the Chamber of Secrets", 2002, tv = false, popularity = 61.1),
            c("Secrets of the Inner Chamber", 1985, tv = false, popularity = 1.0),
            c("CHIKARA: Aniversario: The Chamber Of Secrets", 2016, tv = false, popularity = 0.4),
            c("The Chamber of Secrets Escape", 2018, tv = true, popularity = 2.1),
        )
        val pick = pickConfidentCandidate(ConfidentSearchQuery("chamber of secrets"), chamber)!!
        assertEquals("Harry Potter and the Chamber of Secrets/2002/movie", pick.value)
        assertEquals(61.1 / 61.5, pick.confidence, 1e-9)

        val derry = listOf(c("IT: Welcome to Derry", 2025, tv = true, popularity = 33.5))
        assertEquals(1.0, pickConfidentCandidate(ConfidentSearchQuery("welcome to derry"), derry)!!.confidence)
    }

    @Test
    fun exactNamesBeatTitlesThatEndInThem() {
        val farms = listOf(
            c("TV Animal Farm", 2001, tv = true, popularity = 84.4),
            c("Animal Farm", 2026, tv = false, popularity = 18.1),
            c("Animal Farm", 1954, tv = false, popularity = 5.3),
        )
        val pick = pickConfidentCandidate(ConfidentSearchQuery("animal farm"), farms)!!
        assertEquals("Animal Farm/2026/movie", pick.value)
        assertEquals(18.1 / 23.4, pick.confidence, 1e-9)
    }

    @Test
    fun leadingArticleDoesNotMatter() {
        val knights = listOf(
            c("The Dark Knight", 2008, tv = false, popularity = 59.2),
            c("The Dark Knight Rises", 2012, tv = false, popularity = 43.2),
            c("Dark Knight", 2000, tv = true, popularity = 2.9),
        )
        val pick = pickConfidentCandidate(ConfidentSearchQuery("dark knight"), knights)!!
        assertEquals("The Dark Knight/2008/movie", pick.value)
        assertEquals(59.2 / 62.1, pick.confidence, 1e-9)
    }

    @Test
    fun oneWordIsNeverATail() {
        assertNull(pickConfidentCandidate(ConfidentSearchQuery("secrets"), listOf(c("Official Secrets", 2019, tv = false, popularity = 9.7))))
        assertNull(pickConfidentCandidate(ConfidentSearchQuery("the secrets"), listOf(c("Keeping the Secrets", 2019, tv = false, popularity = 9.7))))
        assertEquals(1.0, pickConfidentCandidate(ConfidentSearchQuery("greys anatomy"), listOf(c("Grey's Anatomy", 2005, tv = true, popularity = 9.7)))!!.confidence)
    }

    @Test
    fun takesTheAddonsSpellingOfATypo() {
        val results = listOf("Animal Farm", "Animal Kingdom", "George Orwell's Animal Farm")
        assertEquals("Animal Farm", closestSpelling("animal frm", results))
        assertEquals(
            "chamber of secrets",
            closestSpelling("chambr of secrets", listOf("Harry Potter and the Chamber of Secrets", "Secrets of the Inner Chamber")),
        )
        assertEquals("The Office", closestSpelling("teh office", listOf("The Office")))
        // Four letters short over 29: one edit per 7 letters allows it.
        assertEquals(
            "Harry Potter and the Chamber of Secrets",
            closestSpelling(
                "harr pottr an th chamber of secrets",
                listOf("Harry Potter and the Chamber of Secrets", "Harry Potter and the Goblet of Fire", "Fantastic Beasts: The Secrets of Dumbledore"),
            ),
        )
    }

    @Test
    fun noSpellingWhenNothingIsCloseOrTwoTie() {
        assertNull(closestSpelling("animal frm", listOf("Animal Kingdom", "Farm Animals")))
        assertNull(closestSpelling("animal frm", listOf("Animal Farm", "Animal Form")))
        // Too short to call anything a typo.
        assertNull(closestSpelling("dun", listOf("Dune")))
    }
}
