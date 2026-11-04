package com.nuvio.app.features.music.library

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
    fun otherPinsDoNotChangeTheBoxRule() {
        val pinned = article.copy(meaning = "Mine.", pinned = setOf(SONG_PART_MEANING))
        val box = assertNotNull(lensStoryBox(pinned))
        assertEquals("Written in 1980. Recorded in one take.\n\nMine.", box.text)
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
