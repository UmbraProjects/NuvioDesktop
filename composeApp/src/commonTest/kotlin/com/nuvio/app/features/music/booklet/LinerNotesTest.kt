package com.nuvio.app.features.music.booklet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinerNotesTest {
    private val artistInfo = BookletArtist(
        type = "Person",
        beginYear = "1958",
        country = "United States",
        genres = listOf("pop", "soul"),
        biography = "Michael Jackson was an American singer. He was known as the King of Pop. He began his career as a child.",
        albums = listOf(
            BookletRelease("Off the Wall", 1979, "MPRE1"),
            BookletRelease("Thriller", 1982, "MPRE2"),
            BookletRelease("Bad", 1987, "MPRE3"),
            BookletRelease("Dangerous", 1991, "MPRE4"),
        ),
    )
    private val request = BookletRequest(
        artist = "Michael Jackson",
        album = "Dangerous",
        year = "1991",
        tracks = listOf(BookletTrack("Jam", 339), BookletTrack("Why You Wanna Trip on Me", 324), BookletTrack("In the Closet", 392)),
        artistInfo = artistInfo,
    )

    private val factsReply = """
        {"intro":"An intro that is long enough to keep.",
         "facts":[
           {"kind":"album","q":"Who did he leave behind for this album?","a":"Quincy Jones, who produced his last three albums."},
           {"kind":"artist","q":"What is he known as?","a":"The King of Pop."},
           {"kind":"artist","q":"What is he known as?","a":"A repeat that must be dropped."},
           {"kind":"album","q":"?","a":"too short a question"},
           {"kind":"album","q":"Missing the answer entirely?"}
         ],
         "guests":[{"name":"Slash","track":2},{"name":"Heavy D","track":9},{"name":"slash","track":1},{"name":"Nobody"}]}
    """.trimIndent()

    @Test
    fun readsTriviaCardsAndDropsBadOnes() {
        val booklet = parseAiBooklet(factsReply, trackCount = 3)!!
        assertEquals(2, booklet.facts.size)
        assertEquals(FactKind.Album, booklet.facts[0].kind)
        assertEquals(FactKind.Artist, booklet.facts[1].kind)
    }

    @Test
    fun readsGuestsKeepingOnlyRealTracks() {
        val booklet = parseAiBooklet(factsReply, trackCount = 3)!!
        assertEquals(listOf("Slash", "Heavy D", "Nobody"), booklet.guests.map { it.name })
        assertEquals(2, booklet.guests[0].trackNumber)
        assertNull(booklet.guests[1].trackNumber) // track 9 is not on a 3 track album
        assertNull(booklet.guests[2].trackNumber)
    }

    @Test
    fun anAnswerWithoutCardsStillGivesABooklet() {
        val booklet = parseAiBooklet("{\"intro\":\"An intro that is long enough to keep.\"}", 3)!!
        assertTrue(booklet.facts.isEmpty())
        assertTrue(booklet.guests.isEmpty())
    }

    @Test
    fun cardsAndGuestsSurviveTheCache() {
        val booklet = parseAiBooklet(factsReply, 3)!!
        val back = decodeBookletEntry(encodeBookletEntry(BookletEntry(5L, booklet)))!!.booklet!!
        assertEquals(booklet.facts, back.facts)
        assertEquals(booklet.guests, back.guests)
    }

    @Test
    fun anOldEntryWithoutCardsStillReads() {
        val old = """{"at":1,"s":"wiki","i":"An intro that is long enough.","c":[],"t":{}}"""
        val entry = decodeBookletEntry(old)!!
        assertEquals(emptyList(), entry.booklet!!.facts)
    }

    @Test
    fun cacheKeysMovedOnToTheNewVersion() {
        assertTrue(bookletKey(request, "wiki").startsWith("v2|"))
    }

    @Test
    fun promptCarriesTheArtistAndAsksForCards() {
        val prompt = bookletPrompt(request, "Album text.", "Artist text here.")
        assertTrue("Artist text here." in prompt)
        assertTrue("Artist albums: Off the Wall (1979)" in prompt)
        assertFalse("\"facts\"" in prompt) // the trivia is its own request
        assertTrue("\"guests\"" in prompt)
    }

    @Test
    fun sentencesSplitButNotOnInitials() {
        assertEquals(listOf("It came out in 1991.", "It sold well."), bookletSentences("It came out in 1991. It sold well."))
        assertEquals(1, bookletSentences("Dr. Dre produced it.").size)
        assertEquals(listOf("Vol. 2 is next."), bookletSentences("Vol. 2 is next."))
    }

    @Test
    fun timelineMarksTheAlbumPlaying() {
        val timeline = timelineFor(artistInfo, "Dangerous", "1991")!!
        assertEquals(4, timeline.entries.size)
        assertEquals(3, timeline.currentIndex)
        assertEquals(1979, timeline.entries.first().year)
    }

    @Test
    fun timelineAddsAnAlbumTheLibraryLacks() {
        val timeline = timelineFor(artistInfo, "HIStory", "1995")!!
        assertEquals("HIStory", timeline.entries[timeline.currentIndex].title)
        assertEquals(5, timeline.entries.size)
    }

    @Test
    fun timelineIsLeftOutWithNothingToWalk() {
        assertNull(timelineFor(null, "Dangerous", "1991"))
        assertNull(timelineFor(BookletArtist(albums = listOf(BookletRelease("Dangerous", 1991, null))), "Dangerous", "1991"))
        assertNull(timelineFor(BookletArtist(), "Dangerous", null))
    }

    @Test
    fun timelineIsWindowedToNineAroundTheAlbum() {
        val many = (1..20).map { BookletRelease("Album $it", 1980 + it, null) }
        val timeline = timelineFor(BookletArtist(albums = many), "Album 10", "1990")!!
        assertEquals(9, timeline.entries.size)
        assertEquals("Album 10", timeline.entries[timeline.currentIndex].title)
    }

    @Test
    fun noKeyCardsComeFromWhatIsKnown() {
        val facts = fallbackFacts(request, "Dangerous is the eighth studio album by Michael Jackson. It was released in 1991. It was recorded across several studios in Los Angeles. Riley produced many tracks.", artistText = null)
        val questions = facts.map { it.question }
        assertTrue(questions.any { it.startsWith("Who is Michael Jackson") })
        assertTrue(questions.any { it.startsWith("Which album came before Dangerous") })
        assertTrue(facts.first { it.question.startsWith("Which album came before") }.answer.contains("Bad (1987)"))
        assertEquals(3, facts.size)
        assertTrue(facts.any { it.kind == FactKind.Artist } && facts.any { it.kind == FactKind.Album })
    }

    @Test
    fun noKeyCardsLeaveOutWhatIsMissing() {
        val bare = BookletRequest("Nobody", "Nothing", null, emptyList())
        assertEquals(emptyList(), fallbackFacts(bare, null, null))
    }

    @Test
    fun wikipediaBookletCarriesTheCardsWhenAsked() {
        val text = "Dangerous is the eighth studio album by Michael Jackson. It was released in November 1991 by Epic Records."
        assertTrue(wikipediaBooklet(text, request).let { it != null && it.facts.isNotEmpty() })
        assertTrue(wikipediaBooklet(text)!!.facts.isEmpty())
    }

    @Test
    fun theDeckIsBuiltFromWhatThereIs() {
        val booklet = parseAiBooklet(factsReply, 3)!!.copy(trackNotes = mapOf(2 to "A note about the second song."))
        val pages = linerPages(booklet, request, playing = 2)
        assertEquals(LinerPage.Cover, pages.first())
        assertEquals(1, pages.count { it is LinerPage.Facts })
        assertTrue(pages.any { it is LinerPage.Guests })
        assertTrue(pages.any { it is LinerPage.Timeline })
        assertEquals(2, (pages.last() as LinerPage.Track).number)
    }

    @Test
    fun emptyPagesAreLeftOut() {
        val plain = AlbumBooklet("An intro that is long enough.", emptyList(), emptyMap(), BookletSource.Wikipedia)
        val pages = linerPages(plain, request.copy(artistInfo = null), playing = 1)
        assertEquals(listOf<LinerPage>(LinerPage.Cover), pages)
    }

    @Test
    fun coverStatsOnlyListWhatIsKnown() {
        val stats = coverStats(request, "1991").toMap()
        assertEquals("1991", stats["Released"])
        assertEquals("3", stats["Tracks"])
        assertEquals("Bad, 1987", stats["Before it"])
        assertEquals("pop", stats["Sound"])
        assertTrue(coverStats(BookletRequest("A", "B", null, emptyList()), null).isEmpty())
    }

    @Test
    fun guestTrackMustBeOnTheAlbum() {
        assertEquals(2, guestTrack(BookletGuest("Slash", 2), 3))
        assertNull(guestTrack(BookletGuest("Slash", 4), 3))
        assertNull(guestTrack(BookletGuest("Slash", null), 3))
    }

    @Test
    fun leftAndRightOnlyTurnPages() {
        val onList = DeckPosition(page = 2, cursor = 1)
        assertEquals(DeckPosition(3, 0), deckTurn(onList, pageCount = 5, direction = 1))
        assertEquals(DeckPosition(1, 0), deckTurn(onList, 5, -1))
    }

    @Test
    fun upAndDownWalkTheEntriesAndStopAtTheEnds() {
        val list = DeckPosition(page = 2, cursor = 1)
        assertEquals(DeckPosition(2, 2), cursorStep(list, cursorCount = 3, direction = 1))
        assertEquals(DeckPosition(2, 0), cursorStep(list, 3, -1))
        assertEquals(DeckPosition(2, 2), cursorStep(DeckPosition(2, 2), 3, 1))
        assertEquals(DeckPosition(2, 0), cursorStep(DeckPosition(2, 0), 3, -1))
        assertEquals(DeckPosition(2, 0), cursorStep(DeckPosition(2, 0), 0, 1))
    }

    @Test
    fun theDeckStopsAtItsEnds() {
        assertEquals(DeckPosition(0, 0), deckTurn(DeckPosition(0, 0), 5, -1))
        assertEquals(DeckPosition(4, 0), deckTurn(DeckPosition(4, 0), 5, 1))
    }

    private val triviaReply = """
        {"known":true,"facts":[
          {"kind":"album","q":"Which producer did he bring in for new jack swing?","a":"Teddy Riley, who co-wrote and produced many tracks.","confidence":"high"},
          {"kind":"album","q":"Where was much of it recorded?","a":"Several studios in Los Angeles.","confidence":"medium"},
          {"kind":"album","q":"Which guess should be dropped?","a":"Something unsure indeed.","confidence":"low"},
          {"kind":"artist","q":"What did he start as a child?","a":"He fronted the Jackson 5 from a young age.","confidence":"high"}
        ]}
    """.trimIndent()

    @Test
    fun triviaDropsWhatTheModelIsUnsureOf() {
        val facts = parseAiFacts(triviaReply)!!
        assertEquals(3, facts.size)
        assertFalse(facts.any { it.question.contains("dropped") })
        assertEquals(FactKind.Artist, facts.last().kind)
    }

    @Test
    fun triviaCapsAtFive() {
        val many = (1..9).joinToString(",") { """{"kind":"album","q":"Question number $it here?","a":"Answer $it"}""" }
        assertEquals(5, parseAiFacts("{\"facts\":[$many]}")!!.size)
    }

    @Test
    fun anAlbumTheModelDoesNotKnowGivesNoCards() {
        assertEquals(emptyList(), parseAiFacts("{\"known\": false, \"facts\": []}"))
        assertNull(parseAiFacts("I am not sure."))
    }

    @Test
    fun triviaPromptTellsTheModelWhatIsAlreadyShown() {
        val prompt = factsPrompt(request, "The eighth studio album, released in 1991.", null)
        assertTrue("The eighth studio album, released in 1991." in prompt)
        assertTrue("Give 5 facts: 3 about the album" in prompt)
        assertTrue("\"known\": false" in prompt)
        assertTrue("Dangerous" in prompt && "Michael Jackson" in prompt && "1991" in prompt)
    }

    @Test
    fun cardsThatRepeatTheIntroAreDropped() {
        val intro = "Dangerous was recorded in Los Angeles with Teddy Riley producing many tracks."
        val facts = listOf(
            BookletFact(FactKind.Album, "Where was it made?", "It was recorded in Los Angeles with Teddy Riley producing many tracks."),
            BookletFact(FactKind.Album, "Who sang the opening?", "A choir of schoolchildren opened the final track."),
        )
        assertEquals(listOf("Who sang the opening?"), dropRepeats(facts, intro).map { it.question })
    }

    @Test
    fun fiveCardsMakeTwoPagesOfThreeAndTwo() {
        val cards = (1..5).map { BookletFact(FactKind.Album, "Question $it of five?", "Answer $it") }
        val booklet = AlbumBooklet("An intro that is long enough.", emptyList(), emptyMap(), BookletSource.Ai, cards)
        val pages = linerPages(booklet, request.copy(artistInfo = null), playing = null).filterIsInstance<LinerPage.Facts>()
        assertEquals(listOf(3, 2), pages.map { it.facts.size })
        assertEquals(listOf(0, 3), pages.map { it.startIndex })
        assertEquals(listOf(1, 2), pages.map { it.number })
    }

    @Test
    fun selectRevealsTheNextHiddenCardInOrder() {
        assertEquals(0, nextHiddenFact(0, 3, emptySet()))
        assertEquals(1, nextHiddenFact(0, 3, setOf(0)))
        assertEquals(3, nextHiddenFact(3, 2, setOf(0, 1, 2)))
        assertNull(nextHiddenFact(0, 3, setOf(0, 1, 2)))
    }
}
