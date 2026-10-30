package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnplacedGenresTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000_000_000_000L

    private fun artist(
        id: String,
        name: String = id,
        genres: List<String> = emptyList(),
        sources: List<String> = emptyList(),
        releases: List<LibraryRelease> = emptyList(),
        foundVia: String? = null,
    ) = LibraryArtist(id = id, name = name, genres = genres, sources = sources, releases = releases, foundVia = foundVia)

    private fun release(title: String, vararg genres: String) =
        LibraryRelease(key = title, title = title, kind = ReleaseKind.Album, genres = genres.toList())

    // region backoff

    @Test
    fun retryDelayGrowsThreeSevenFourteenDaysAndStaysThere() {
        assertEquals(0L, tagRetryDelayMs(0))
        assertEquals(3 * day, tagRetryDelayMs(1))
        assertEquals(7 * day, tagRetryDelayMs(2))
        assertEquals(14 * day, tagRetryDelayMs(3))
        assertEquals(14 * day, tagRetryDelayMs(9))
    }

    @Test
    fun aReliableEmptyLookupIsRecordedAndWaitedOut() {
        val missed = artist("x").withTagCheck(now, reliable = true)
        assertEquals(1, missed.tagMisses)
        assertEquals(now, missed.tagsCheckedAtEpochMs)
        assertEquals(3 * day, missed.retryTagsInMs(now))
        assertEquals(day, missed.retryTagsInMs(now + 2 * day))
        assertTrue(missed.canRetryTags(now + 3 * day))
        assertFalse(missed.canRetryTags(now + 3 * day - 1))
    }

    @Test
    fun anOutageIsNotAMiss() {
        val after = artist("x").withTagCheck(now, reliable = false)
        assertEquals(0, after.tagMisses)
        assertTrue(after.canRetryTags(now))
    }

    @Test
    fun aSecondLookupInsideTheWaitDoesNotMakeTheWaitLonger() {
        val first = artist("x").withTagCheck(now, reliable = true)
        assertEquals(first, first.withTagCheck(now + day, reliable = true))
        val second = first.withTagCheck(now + 3 * day, reliable = true)
        assertEquals(2, second.tagMisses)
        assertEquals(7 * day, second.retryTagsInMs(now + 3 * day))
    }

    @Test
    fun findingGenresClearsTheRecord() {
        val missed = artist("x").withTagCheck(now, reliable = true)
        val found = missed.copy(genres = listOf("uk garage")).withTagCheck(now + 5 * day, reliable = true)
        assertEquals(0, found.tagMisses)
        assertEquals(0L, found.tagsCheckedAtEpochMs)
        assertTrue(found.canRetryTags(now + 5 * day))
    }

    @Test
    fun aClockSetBackCannotLockTheRetryForLongerThanOneDelay() {
        val missed = artist("x").withTagCheck(now, reliable = true)
        assertEquals(3 * day, missed.retryTagsInMs(now - 400 * day))
    }

    @Test
    fun aMusicBrainzAnswerOrANamesakeVerdictIsReliable() {
        assertTrue(artist("x", sources = listOf("MusicBrainz")).lookupWasReliable())
        assertTrue(artist("x").copy(channelMismatch = true).lookupWasReliable())
        assertFalse(artist("x", sources = listOf("Deezer")).lookupWasReliable())
    }

    @Test
    fun waitIsSpokenInPlainUnits() {
        assertEquals("an hour", retryWaitText(10 * 60 * 1000L))
        assertEquals("5 hours", retryWaitText(5 * 3_600_000L))
        assertEquals("a day", retryWaitText(day))
        assertEquals("3 days", retryWaitText(3 * day))
    }

    // endregion

    // region choosing

    @Test
    fun typedGenresAreCleanedDeduplicatedAndCapped() {
        assertEquals(listOf("uk garage", "bassline"), parseGenreInput("UK Garage, bassline; Bassline"))
        assertEquals(3, parseGenreInput("jazz, funk, soul, house, techno").size)
        assertTrue(parseGenreInput("  , ;").isEmpty())
    }

    @Test
    fun aPickedGenreLeadsAndIsNotRepeated() {
        val once = withManualGenre(listOf("jazz"), "bassline")
        assertEquals(listOf("bassline", "jazz"), once)
        assertEquals(once, withManualGenre(once, "Bassline"))
        assertEquals(listOf("jazz", "bassline"), withManualGenre(once, "jazz"))
        assertEquals(listOf("jazz"), withManualGenre(listOf("jazz"), "  "))
    }

    @Test
    fun typedTextAddsInFrontOrChangesNothing() {
        assertEquals(listOf("funk", "soul", "jazz"), withManualGenres(listOf("jazz"), "funk, soul"))
        assertEquals(listOf("jazz"), withManualGenres(listOf("jazz"), " ; "))
    }

    @Test
    fun aChosenGenreGivesAnUnplacedArtistAPlaceAndMovesTheKey() {
        val library = listOf(
            artist("a1", genres = listOf("shoegaze")),
            artist("a2", genres = listOf("shoegaze")),
            artist("a3", genres = listOf("shoegaze")),
            artist("n1"),
        )
        assertEquals(listOf("n1"), buildConstellationGraph(library).untagged.map { it.id })
        val chosen = library.map { if (it.id == "n1") it.copy(manualGenres = listOf("shoegaze")) else it }
        val graph = buildConstellationGraph(chosen)
        assertTrue(graph.untagged.isEmpty())
        assertTrue("n1" in graph.stars.map { it.id })
        assertTrue(constellationKey(library) != constellationKey(chosen), "the map rebuilds when a genre is chosen")
    }

    @Test
    fun aChosenGenreDoesNotCountAsALookupFinding() {
        val chosen = artist("n1").copy(manualGenres = listOf("shoegaze"))
        assertFalse(chosen.hasLookedUpGenres())
        assertEquals(1, chosen.withTagCheck(now, reliable = true).tagMisses)
    }

    @Test
    fun choicesAreTheGenresAlreadyOnTheMapBiggestFirst() {
        val library = listOf(
            artist("a1", genres = listOf("shoegaze")),
            artist("a2", genres = listOf("shoegaze")),
            artist("a3", genres = listOf("jazz")),
            artist("n1").copy(manualGenres = listOf("bassline")),
        )
        val choices = genreChoices(library)
        assertEquals("shoegaze", choices.first())
        assertTrue(choices.containsAll(listOf("jazz", "bassline")))
        assertEquals(1, genreChoices(library, limit = 1).size)
    }

    // endregion

    // region suggesting

    @Test
    fun suggestsFromTheGenresOnItsOwnReleases() {
        val a = artist(
            "x",
            releases = listOf(release("One", "uk garage"), release("Two", "uk garage", "bassline")),
        )
        val got = suggestGenres(a, listOf(a))
        assertEquals("uk garage", (got.first().genre))
        assertEquals("Listed on 2 of its releases", got.first().reason)
        assertTrue(got.map { it.genre }.contains("bassline"))
    }

    @Test
    fun anIntoLadderNamesTheGenre() {
        val a = artist("x", foundVia = "ladder: Into shoegaze")
        val got = suggestGenres(a, listOf(a))
        assertEquals("shoegaze", (got.single().genre))
        assertTrue(got.single().reason.contains("Into shoegaze"))
    }

    @Test
    fun anOutFromLadderLendsTheSourceArtistsGenres() {
        val sting = artist("s", name = "Sting", genres = listOf("pop rock", "reggae", "jazz"))
        val a = artist("x", foundVia = "ladder: Out from Sting")
        val got = suggestGenres(a, listOf(sting, a))
        assertEquals(listOf("pop rock", "reggae"), got.map { it.genre })
        assertTrue(got.all { it.reason == "Found from Sting" })
    }

    @Test
    fun aSharedReleaseTitleBringsInTheOtherArtistsGenres() {
        val other = artist("o", name = "Sunship", genres = listOf("uk garage"))
        val a = artist("x", name = "M-Dubs", releases = listOf(release("Have My Love (feat. Sunship)")))
        val got = suggestGenres(a, listOf(other, a))
        assertEquals("uk garage", (got.single().genre))
        assertEquals("Appears with Sunship", got.single().reason)
    }

    @Test
    fun theOtherArtistsReleasesCanNameThisOneToo() {
        val other = artist("o", name = "Sunship", genres = listOf("uk garage"), releases = listOf(release("Remix with M-Dubs")))
        val a = artist("x", name = "M-Dubs")
        assertEquals("uk garage", (suggestGenres(a, listOf(other, a)).single().genre))
    }

    @Test
    fun nameMatchingIsWholeWordsOnly() {
        assertTrue(titleMentions("Song (feat. Sting)", "Sting"))
        assertTrue(titleMentions("sting", "Sting"))
        assertFalse(titleMentions("Stinging Nettles", "Sting"))
        assertFalse(titleMentions("Remix of Aa", "Aa"), "names under three letters never match")
    }

    @Test
    fun aStrongerSignalOutranksAWeakerOne() {
        val other = artist("o", name = "Zed", genres = listOf("house"))
        val a = artist(
            "x",
            releases = listOf(release("Night with Zed", "uk garage")),
            foundVia = "ladder: Into jazz",
        )
        val got = suggestGenres(a, listOf(other, a))
        assertEquals("jazz", (got.first().genre))
        assertEquals(3, got.size)
        assertEquals(1, suggestGenres(a, listOf(other, a), limit = 1).size)
    }

    @Test
    fun anArtistAlreadyOnTheMapGetsNoSuggestionAndNeverItself() {
        val placed = artist("p", genres = listOf("jazz"), releases = listOf(release("One", "funk")))
        assertTrue(suggestGenres(placed, listOf(placed)).isEmpty())
        val a = artist("x", name = "Solo", releases = listOf(release("Solo")))
        assertTrue(suggestGenres(a, listOf(a)).isEmpty(), "an artist is not its own collaborator")
    }

    @Test
    fun suggestsNothingWhenThereIsNothingToGoOn() {
        assertTrue(suggestGenres(artist("x"), listOf(artist("x"), artist("y", genres = listOf("jazz")))).isEmpty())
    }

    // endregion
}
