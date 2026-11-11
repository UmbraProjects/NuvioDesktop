package com.nuvio.app.features.music.booklet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackNotesTest {
    @Test
    fun notesAreReadFromAFencedReplyAndKeptToRealTracks() {
        val reply = "```json\n{\"1\": \"A moody opener built on a fretless bass line.\", \"2\": \"short\", \"9\": \"Not a track on this album at all.\", \"x\": \"Not a number either, so skipped.\"}\n```"
        val notes = parseTrackNotes(reply, trackCount = 3)
        assertEquals(setOf(1), notes.keys)
    }

    @Test
    fun anUnreadableReplyGivesNoNotes() {
        assertTrue(parseTrackNotes("I cannot help with that", 5).isEmpty())
    }

    @Test
    fun theAiLineWinsAndCreditsStandInForIt() {
        val notes = SongNotes(ai = mapOf(1 to "AI line"), credits = mapOf(1 to "Written by A", 2 to "Written by B"))
        assertEquals("AI line", notes.lineFor(1))
        assertEquals("Written by B", notes.lineFor(2))
        assertEquals(null, notes.lineFor(3))
    }

    @Test
    fun thePromptListsEveryTrackWithItsCredits() {
        val request = BookletRequest("Artist", "Album", "1982", listOf(BookletTrack("One", 200), BookletTrack("Two", 180)))
        val prompt = trackNotesPrompt(request, mapOf(2 to "Produced by P"), "An intro.")
        assertTrue("1. One" in prompt && "2. Two [Produced by P]" in prompt && "An intro." in prompt)
    }
}
