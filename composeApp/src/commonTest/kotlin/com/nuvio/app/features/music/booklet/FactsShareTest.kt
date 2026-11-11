package com.nuvio.app.features.music.booklet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FactsShareTest {
    private fun aiValue(source: BookletSource = BookletSource.Ai) =
        encodeBookletEntry(BookletEntry(5L, AlbumBooklet("An intro that is long enough.", emptyList(), emptyMap(), source)))

    private val notesKey = "v4|ai:model-a|philcollins|facevalue"
    private val storyKey = "story1|philcollins|intheairtonight|ai:model-a:web"
    private val pinKey = "pin1|intro|philcollins|facevalue"
    private val pinValue = encodePin(PinEntry(1L, "My own words", origin = PIN_ORIGIN_YOU))

    @Test
    fun keysTellTheirKindAndPersonalOnesStayOut() {
        assertEquals(ShareKind.Notes, shareKindOf(notesKey))
        assertEquals(ShareKind.Story, shareKindOf(storyKey))
        assertEquals(ShareKind.TrackNotes, shareKindOf("tnotes1|a|b|m"))
        assertEquals(ShareKind.Start, shareKindOf("start1|a|m"))
        assertEquals(ShareKind.Pin, shareKindOf(pinKey))
        assertNull(shareKindOf("cut1|MPREb_x|mood|m"))
        assertNull(shareKindOf("v4|wiki2|a|b"))
        assertNull(shareKindOf("story1|a|b|wiki"))
    }

    @Test
    fun anExportReadsBackAndOnlyHonestEntriesPass() {
        val text = encodeShare(
            listOf(
                ShareEntry(notesKey, aiValue()),
                ShareEntry(pinKey, pinValue),
                ShareEntry("v4|wiki2|a|b", aiValue()),
                ShareEntry("story1|a|b|ai:m", aiValue(BookletSource.Wikipedia)),
                ShareEntry("pin1|intro|a|b", """{"at":1,"t":"   "}"""),
                ShareEntry(notesKey, aiValue()),
            ),
            exportedAtMs = 7L,
        )
        val parsed = assertNotNull(parseShare(text))
        assertEquals(listOf(notesKey, pinKey), parsed.entries.map { it.key })
        assertEquals(4, parsed.rejected, "a wiki key, a Wikipedia value under an AI key, a blank pin and a repeat")
    }

    @Test
    fun filesThatAreNotOursAreRefused() {
        assertNull(parseShare(null))
        assertNull(parseShare("not json"))
        assertNull(parseShare("""{"format":"other","version":1,"entries":[]}"""))
        assertNull(parseShare("""{"format":"$SHARE_FORMAT","version":2,"entries":[]}"""))
        assertNull(parseShare("""{"format":"$SHARE_FORMAT","version":1}"""))
        assertNull(parseShare("x".repeat(SHARE_MAX_FILE_CHARS + 1)))
        assertEquals(0, assertNotNull(parseShare("""{"format":"$SHARE_FORMAT","version":1,"entries":[]}""")).entries.size)
    }

    @Test
    fun anOversizedValueIsRejected() {
        assertFalse(checkShareEntry(notesKey, "x".repeat(SHARE_MAX_VALUE_CHARS + 1)))
        assertFalse(checkShareEntry("v4|ai:" + "k".repeat(500), aiValue()))
    }

    @Test
    fun importFillsGapsOnlyAndNeverReplaces() {
        val parsed = ParsedShare(
            listOf(ShareEntry(notesKey, aiValue()), ShareEntry(storyKey, aiValue()), ShareEntry(pinKey, pinValue)),
            rejected = 2,
        )
        val plan = planImport(parsed, haveFact = { it == notesKey }, havePin = { true })
        assertEquals(listOf(storyKey), plan.add.map { it.key })
        assertEquals(2, plan.alreadyHave)
        assertEquals(2, plan.rejected)
        assertEquals(mapOf(ShareKind.Story to 1), plan.counts)
        assertTrue(planImport(parsed, { true }, { true }).isEmpty)
    }

    @Test
    fun theCountsReadAsOneSentence() {
        assertEquals("nothing new", describeCounts(emptyMap()))
        assertEquals("3 pins", describeCounts(mapOf(ShareKind.Pin to 3)))
        assertEquals("12 Liner Notes and 3 pins", describeCounts(mapOf(ShareKind.Pin to 3, ShareKind.Notes to 12)))
        assertEquals("12 Liner Notes, 80 song stories and 3 pins", describeCounts(mapOf(ShareKind.Pin to 3, ShareKind.Story to 80, ShareKind.Notes to 12)))
    }

    @Test
    fun anImportRecordRoundTripsAndUndoLeavesEditedPins() {
        val record = ImportRecord("i1", 9L, "friend.json", listOf(notesKey, pinKey, "pin1|intro|a|b", "gone"))
        assertEquals(record, decodeImportRecord(encodeImportRecord(record)))
        assertNull(decodeImportRecord("junk"))
        val values = mapOf(
            notesKey to aiValue(),
            pinKey to encodePin(PinEntry(1L, "From a friend", origin = importOrigin("i1"))),
            "pin1|intro|a|b" to encodePin(PinEntry(2L, "Edited since", origin = PIN_ORIGIN_YOU)),
        )
        assertEquals(listOf(notesKey, pinKey), keysToUndo(record) { values[it] })
    }
}
