package com.nuvio.app.features.music.booklet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PinsTest {
    private val artist = "Phil Collins"
    private val album = "Face Value (Deluxe Edition)"

    private fun store(vararg pins: Pair<String, PinEntry>): (String) -> String? {
        val map = pins.associate { it.first to encodePin(it.second) }
        return { key -> map[key] }
    }

    private fun pin(text: String, question: String? = null, kind: FactKind? = null) = PinEntry(1L, text, question, kind)

    private val booklet = AlbumBooklet(
        intro = "Written by the model.",
        credits = listOf(BookletCredit("Producer", "Hugh Padgham")),
        trackNotes = mapOf(1 to "AI note one.", 2 to "AI note two."),
        source = BookletSource.Ai,
        facts = listOf(
            BookletFact(FactKind.Album, "Q1?", "A1."),
            BookletFact(FactKind.Artist, "Q2?", "A2."),
        ),
    )

    @Test
    fun keysIgnoreCasePunctuationAndHaveNoModel() {
        assertEquals("pin1|intro|philcollins|facevaluedeluxeedition", PinKeys.intro(artist, album))
        assertEquals(PinKeys.intro("PHIL  COLLINS", "face value - deluxe edition"), PinKeys.intro(artist, album))
        assertEquals("pin1|tnote|philcollins|facevaluedeluxeedition|3", PinKeys.trackNote(artist, album, 3))
        assertEquals("pin1|song|philcollins|intheairtonight|made", PinKeys.songPart(artist, "In the Air Tonight", SONG_PART_MADE))
    }

    @Test
    fun cleanTextTidiesAndCuts() {
        assertEquals("Two  spaces".replace("  ", " "), cleanPinText("  Two  spaces \t"))
        assertEquals("One\n\nTwo", cleanPinText("One\n\n\n\n  Two"))
        assertNull(cleanPinText("   \n  "))
        val long = "word ".repeat(1_000)
        val cut = cleanPinText(long)
        assertNotNull(cut)
        assertTrue(cut.length <= MAX_PIN_CHARS)
        assertFalse(cut.endsWith(" "))
        assertTrue(cut.endsWith("word"))
    }

    @Test
    fun entryRoundTripsWithQuotesNewlinesAndUnicode() {
        val entry = PinEntry(1_700_000_000_000L, "He said \"hi\".\nSecond line, café ♪ 日本語", "Who's \"there\"?", FactKind.Artist, "you")
        assertEquals(entry, decodePin(encodePin(entry)))
        val plain = PinEntry(5L, "Just text")
        assertEquals(plain, decodePin(encodePin(plain)))
    }

    @Test
    fun damagedOrBlankPinsReadAsNothing() {
        assertNull(decodePin(null))
        assertNull(decodePin("not json"))
        assertNull(decodePin("[1,2]"))
        assertNull(decodePin("""{"at":1}"""))
        assertNull(decodePin("""{"at":1,"t":"   "}"""))
        assertEquals("kept", decodePin("""{"t":"kept","extra":true}""")?.text)
    }

    @Test
    fun albumPinsReadOnlyThePlacesThatExist() {
        val get = store(
            PinKeys.intro(artist, album) to pin("My intro"),
            PinKeys.trackNote(artist, album, 2) to pin("My note"),
            PinKeys.trackNote(artist, album, 9) to pin("Past the end"),
            PinKeys.fact(artist, album, 1) to pin("A", "Q", FactKind.Album),
            PinKeys.fact(artist, album, 2) to pin("Answer without a question"),
            PinKeys.fact(artist, album, MAX_FACT_SLOTS + 1) to pin("A", "Q"),
        )
        val pins = albumPinsFrom(artist, album, trackCount = 3, get = get)
        assertEquals("My intro", pins.intro?.text)
        assertEquals(setOf(2), pins.trackNotes.keys)
        assertEquals(setOf(1), pins.facts.keys)
        assertFalse(pins.isEmpty)
        assertTrue(albumPinsFrom(artist, album, 3) { null }.isEmpty)
    }

    @Test
    fun noPinsLeavesTheBookletAlone() {
        assertEquals(booklet, booklet.withPins(AlbumPins()))
        assertNull((null as AlbumBooklet?).withPins(AlbumPins()))
    }

    @Test
    fun pinnedFieldsReplaceTheWrittenOnes() {
        val pins = AlbumPins(
            intro = pin("My intro"),
            trackNotes = mapOf(2 to pin("My note two"), 3 to pin("My note three")),
            facts = mapOf(2 to pin("My answer", "My question?", null)),
        )
        val shown = assertNotNull(booklet.withPins(pins))
        assertEquals("My intro", shown.intro)
        assertEquals(mapOf(1 to "AI note one.", 2 to "My note two", 3 to "My note three"), shown.trackNotes)
        assertEquals(BookletFact(FactKind.Album, "Q1?", "A1."), shown.facts[0])
        assertEquals(BookletFact(FactKind.Artist, "My question?", "My answer"), shown.facts[1], "replacing a card keeps its kind")
        assertEquals(setOf("intro", "tnote:2", "tnote:3", "fact:2"), shown.pinned)
        assertEquals(booklet.credits, shown.credits)
        assertEquals(BookletSource.Ai, shown.source)
    }

    @Test
    fun aPinnedCardAfterTheLastIsAddedAndOneBeyondThatSkipped() {
        val pins = AlbumPins(facts = mapOf(3 to pin("A3", "Q3?", FactKind.Artist), 5 to pin("A5", "Q5?")))
        val shown = assertNotNull(booklet.withPins(pins))
        assertEquals(3, shown.facts.size)
        assertEquals(BookletFact(FactKind.Artist, "Q3?", "A3"), shown.facts[2])
        assertEquals(setOf("fact:3"), shown.pinned)
    }

    @Test
    fun aPinnedIntroStandsWithoutABookletButOtherPinsNeedOne() {
        val withIntro = assertNotNull((null as AlbumBooklet?).withPins(AlbumPins(intro = pin("Mine"), trackNotes = mapOf(1 to pin("Note")))))
        assertEquals("Mine", withIntro.intro)
        assertEquals(mapOf(1 to "Note"), withIntro.trackNotes)
        assertEquals(BookletSource.Wikipedia, withIntro.source)
        assertTrue(withIntro.facts.isEmpty())
        assertNull((null as AlbumBooklet?).withPins(AlbumPins(trackNotes = mapOf(1 to pin("Note")))))
    }

    @Test
    fun overlayIsNotWhatTheCacheEncodes() {
        val shown = assertNotNull(booklet.withPins(AlbumPins(intro = pin("Mine"))))
        val back = decodeBookletEntry(encodeBookletEntry(BookletEntry(1L, shown)))
        assertEquals(emptySet(), back?.booklet?.pinned, "the pin set is for showing, not stored in the cache")
    }

    @Test
    fun songPinsReadAllThreeParts() {
        val get = store(
            PinKeys.songPart(artist, "In the Air Tonight", SONG_PART_MADE) to pin("Made it"),
            PinKeys.songPart(artist, "In the Air Tonight", SONG_PART_AFTER) to pin("After it"),
        )
        val pins = songPinsFrom(artist, "in the air tonight!", get)
        assertEquals(setOf(SONG_PART_MADE, SONG_PART_AFTER), pins.parts.keys)
    }

    @Test
    fun storyPinsReplacePartsAndKeepQuotes() {
        val story = SongStory("Made.", "Meaning.", "After.", listOf(SongQuote("quote", "someone")), ai = true)
        val shown = assertNotNull(story.withPins(SongPins(mapOf(SONG_PART_MEANING to pin("Mine")))))
        assertEquals("Made.", shown.made)
        assertEquals("Mine", shown.meaning)
        assertEquals(story.quotes, shown.quotes)
        assertTrue(shown.ai, "two parts are still the AI's")
        assertEquals(setOf(SONG_PART_MEANING), shown.pinned)
        assertEquals(story, story.withPins(SongPins()))
    }

    @Test
    fun storyIsNotLabelledAiWhenEveryShownPartIsPinned() {
        val story = SongStory("Made.", null, null, emptyList(), ai = true)
        val shown = assertNotNull(story.withPins(SongPins(mapOf(SONG_PART_MADE to pin("Mine")))))
        assertFalse(shown.ai)
        val withQuote = SongStory("Made.", null, null, listOf(SongQuote("q", "w")), ai = true)
        assertTrue(assertNotNull(withQuote.withPins(SongPins(mapOf(SONG_PART_MADE to pin("Mine"))))).ai, "a quote is still the AI's pick")
    }

    @Test
    fun aPinnedPartStandsWithoutAStory() {
        val shown = assertNotNull((null as SongStory?).withPins(SongPins(mapOf(SONG_PART_AFTER to pin("Covered by many")))))
        assertNull(shown.made)
        assertEquals("Covered by many", shown.after)
        assertFalse(shown.ai)
        assertFalse(shown.isEmpty)
        assertNull((null as SongStory?).withPins(SongPins()))
    }

    @Test
    fun trackNotesPinsBeatTheAiAndNotTheCredits() {
        val notes = SongNotes(ai = mapOf(1 to "AI one"), credits = mapOf(1 to "Credit one", 2 to "Credit two"))
        val shown = notes.withPins(AlbumPins(trackNotes = mapOf(1 to pin("Mine"), 2 to pin("Mine two"))))
        assertEquals("Mine", shown.lineFor(1))
        assertEquals("Mine two", shown.lineFor(2))
        assertEquals(setOf(1, 2), shown.pinned)
        assertEquals(notes, notes.withPins(AlbumPins()))
    }
}
