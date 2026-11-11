package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.booklet.SONG_PART_AFTER
import com.nuvio.app.features.music.booklet.SONG_PART_MADE
import com.nuvio.app.features.music.booklet.SONG_PART_MEANING
import com.nuvio.app.features.music.booklet.SongStory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LensStoryBoxTest {
    private val article = SongStory(
        made = "Written in 1980. Recorded in one take.",
        meaning = "It is about anger.",
        after = "Covered often.",
        quotes = emptyList(),
        ai = false,
    )

    @Test
    fun noStoryShowsNothing() {
        assertNull(lensStoryBox(null))
        assertNull(lensStoryBox(SongStory(null, null, null, emptyList(), ai = false)))
    }

    @Test
    fun anUnpinnedStoryIsWholeSentencesOfMadeThenMeaning() {
        val box = assertNotNull(lensStoryBox(article))
        assertEquals("Written in 1980. Recorded in one take.\n\nIt is about anger.", box.text)
        assertTrue(box.usedMeaning)
    }

    @Test
    fun theLastPartStandsWhenTheOthersAreMissing() {
        val box = assertNotNull(lensStoryBox(SongStory(null, null, "Covered often.", emptyList(), ai = false)))
        assertEquals("Covered often.", box.text)
        assertFalse(box.usedMeaning)
    }

    @Test
    fun aPinnedMadePartStandsAloneAndWordForWord() {
        val mine = "My own words.\n\nSecond paragraph, with   odd spacing kept."
        val pinned = article.copy(made = mine, pinned = setOf(SONG_PART_MADE))
        val box = assertNotNull(lensStoryBox(pinned))
        assertEquals(mine, box.text, "no sentence cutting, no whitespace collapsing")
        assertFalse(box.usedMeaning, "the meaning moves to the small card")
    }

    @Test
    fun aPinnedMadePartStandsWithNothingElse() {
        val box = assertNotNull(lensStoryBox(SongStory("Only mine", null, null, emptyList(), ai = false, pinned = setOf(SONG_PART_MADE))))
        assertEquals("Only mine", box.text)
    }

    @Test
    fun aPinnedMeaningMovesToTheSmallCardWhole() {
        val pinned = article.copy(meaning = "Mine, with no cut.", pinned = setOf(SONG_PART_MEANING))
        val box = assertNotNull(lensStoryBox(pinned))
        assertEquals("Written in 1980. Recorded in one take.", box.text)
        assertFalse(box.usedMeaning)
        val card = assertNotNull(lensCard(pinned, box, noteText = "The album's note.", boxHasStory = true))
        assertEquals(LensCardKind.Meaning, card.kind)
        assertEquals("Mine, with no cut.", card.text)
        assertTrue(card.pinned)
        assertEquals(SONG_PART_MEANING, card.part)
    }

    @Test
    fun aStoryWithOnlyAMeaningOrAfterShowsItInTheBoxAndEditsThatPart() {
        val meaningOnly = SongStory(null, "Mine.\n\nTwo paragraphs", null, emptyList(), ai = false, pinned = setOf(SONG_PART_MEANING))
        val box = assertNotNull(lensStoryBox(meaningOnly))
        assertEquals("Mine.\n\nTwo paragraphs", box.text)
        assertEquals(SONG_PART_MEANING, box.part)
        assertNull(lensCard(meaningOnly, box, noteText = null, boxHasStory = true), "the box already shows it")
        val afterOnly = SongStory(null, null, "Mine after.", emptyList(), ai = false, pinned = setOf(SONG_PART_AFTER))
        val afterBox = assertNotNull(lensStoryBox(afterOnly))
        assertEquals("Mine after.", afterBox.text)
        assertEquals(SONG_PART_AFTER, afterBox.part)
        assertNull(lensCard(afterOnly, afterBox, null, true))
    }

    @Test
    fun theSmallCardPrefersWhatThePersonPinnedThenAfterThenMeaningThenTheNote() {
        val box = lensStoryBox(article)
        assertEquals(LensCardKind.After, assertNotNull(lensCard(article, box, "Note.", true)).kind)
        val pinnedAfter = article.copy(after = "My after.", pinned = setOf(SONG_PART_AFTER))
        assertEquals("My after.", assertNotNull(lensCard(pinnedAfter, lensStoryBox(pinnedAfter), "Note.", true)).text)
        val noAfter = article.copy(after = null)
        val longMade = noAfter.copy(made = "A sentence that is long enough. ".repeat(40))
        val longBox = lensStoryBox(longMade)
        assertEquals(LensCardKind.Meaning, assertNotNull(lensCard(longMade, longBox, "Note.", true)).kind)
        val madeOnly = SongStory("Only made.", null, null, emptyList(), ai = false)
        val note = assertNotNull(lensCard(madeOnly, lensStoryBox(madeOnly), "The album's note.", true))
        assertEquals(LensCardKind.Note, note.kind)
        assertEquals(SONG_PART_AFTER, note.part, "editing the note card writes the after part")
        assertNull(lensCard(madeOnly, lensStoryBox(madeOnly), "The album's note.", boxHasStory = false))
        assertNull(lensCard(null, null, null, false))
    }

    @Test
    fun savingAnUnchangedTextIsNotAChange() {
        assertFalse(isPinChange("Same text", "  Same text \n"))
        assertFalse(isPinChange(null, "   "))
        assertTrue(isPinChange("Same text", "Same text, now longer"))
        assertTrue(isPinChange(null, "Something"))
        assertTrue(isPinChange("Written by the model", ""), "blank over text clears the pin")
    }
}
