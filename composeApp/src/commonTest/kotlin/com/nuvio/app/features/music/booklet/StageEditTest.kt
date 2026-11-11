package com.nuvio.app.features.music.booklet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StageEditTest {
    private val request = BookletRequest("Phil Collins", "Face Value", "1981", listOf(BookletTrack("In the Air Tonight", 330)))
    private val facts = List(7) { BookletFact(if (it % 2 == 0) FactKind.Album else FactKind.Artist, "Q$it?", "A$it.") }
    private val booklet = AlbumBooklet("Intro text that is long enough.", emptyList(), emptyMap(), BookletSource.Ai, facts, pinned = setOf("intro", "fact:2"))

    @Test
    fun theCoverEditsTheIntro() {
        val groups = stageEditGroups(LinerPage.Cover, booklet, request)
        assertEquals(1, groups.size)
        assertEquals(PinKeys.intro("Phil Collins", "Face Value"), groups[0].pinKey)
        assertTrue(groups[0].pinned)
        assertEquals("Intro text that is long enough.", groups[0].text)
        assertNull(groups[0].question)
    }

    @Test
    fun aFactsPageEditsEachCardBySlotAndSkipsThosePastTheFifth() {
        val page = LinerPage.Facts(2, 3, 3, facts.subList(3, 6))
        val groups = stageEditGroups(page, booklet, request)
        assertEquals(listOf(4, 5), groups.map { it.pinKey.substringAfterLast('|').toInt() }, "slots 4 and 5; the sixth has no place")
        assertEquals("Q3?", groups[0].question)
        assertEquals("A3.", groups[0].text)
        assertEquals(FactKind.Artist, groups[0].kind)
        val first = stageEditGroups(LinerPage.Facts(1, 3, 0, facts.subList(0, 3)), booklet, request)
        assertEquals(listOf(false, true, false), first.map { it.pinned }, "card 2 is pinned")
    }

    @Test
    fun aTrackPageEditsTheStoryPartsOrTheNote() {
        val story = SongStory("Made.", null, "After.", emptyList(), ai = true, pinned = setOf(SONG_PART_AFTER))
        val groups = stageEditGroups(LinerPage.Track(1, "In the Air Tonight", "A note.", story), booklet, request)
        assertEquals(listOf("How it was made", "Sampled, covered, remembered"), groups.map { it.heading })
        assertEquals(listOf(false, true), groups.map { it.pinned })
        assertEquals(PinKeys.songPart("Phil Collins", "In the Air Tonight", SONG_PART_MADE), groups[0].pinKey)
        val note = stageEditGroups(LinerPage.Track(1, "In the Air Tonight", "A note.", null), booklet.copy(pinned = setOf("tnote:1")), request)
        assertEquals(PinKeys.trackNote("Phil Collins", "Face Value", 1), note.single().pinKey)
        assertTrue(note.single().pinned)
        assertTrue(stageEditGroups(LinerPage.Track(1, "x", "", null), booklet, request).isEmpty())
    }

    @Test
    fun pagesWithNothingToEditOfferNothing() {
        assertTrue(stageEditGroups(LinerPage.Guests(emptyList()), booklet, request).isEmpty())
    }

    @Test
    fun savingWritesOnlyWhatChanged() {
        val card = StageEditGroup("Card 1", "pin1|fact|a|b|1", false, "A answer", "A question?", FactKind.Album)
        assertNull(stageEditSave(card, "A question?  ", " A answer"))
        assertEquals(StageEditSave(card.pinKey, "New answer", "A question?", FactKind.Album), stageEditSave(card, "A question?", "New answer"))
        assertEquals("New question?", stageEditSave(card, "New question?", "A answer")?.question)
        val plain = StageEditGroup("Introduction", "pin1|intro|a|b", false, "Old")
        assertEquals(StageEditSave(plain.pinKey, "New", null, null), stageEditSave(plain, "", "New"))
    }
}
