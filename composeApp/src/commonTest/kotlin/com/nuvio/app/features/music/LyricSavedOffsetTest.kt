package com.nuvio.app.features.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Tap to sync, and the offsets the person set being kept. */
class LyricSavedOffsetTest {
    @Test
    fun tappingALineSetsTheOffsetThatPutsItWhereItWasHeard() {
        // The line is shown at 10.0s with no offset; it was heard starting at 22.0s, tapped 350ms after.
        val offset = tapSyncOffsetMs(currentOffsetMs = 0L, shownLineTimeMs = 10_000L, positionMs = 22_350L)
        assertEquals(12_000L, offset)
    }

    @Test
    fun tappingAgainRefinesFromWhateverIsShowing() {
        // After the first tap the line shows at 22.0s; heard at 22.5s.
        val offset = tapSyncOffsetMs(currentOffsetMs = 12_000L, shownLineTimeMs = 22_000L, positionMs = 22_850L)
        assertEquals(12_500L, offset)
    }

    @Test
    fun aLineShownEarlyGivesANegativeOffset() {
        assertEquals(-2_000L, tapSyncOffsetMs(0L, shownLineTimeMs = 30_000L, positionMs = 28_350L))
    }

    @Test
    fun savedOffsetsComeBackAsTheyWentIn() {
        val saved = withSavedOffset(withSavedOffset(emptyMap(), "a:video", 12_000L, 200_000L), "b:audio", -500L, 0L)
        val back = decodeSavedOffsets(encodeSavedOffsets(saved))
        assertEquals(saved, back)
        assertEquals(12_000L, back["a:video"]?.offsetMs)
        assertEquals(200_000L, back["a:video"]?.lyricsDurationMs)
    }

    @Test
    fun aMissingOrDamagedFileIsNoOffsets() {
        assertEquals(emptyMap(), decodeSavedOffsets(null))
        assertEquals(emptyMap(), decodeSavedOffsets(""))
        assertEquals(emptyMap(), decodeSavedOffsets("{not json"))
        assertEquals(emptyMap(), decodeSavedOffsets("[1,2]"))
    }

    @Test
    fun aSavedOffsetAppliesToTheSameLyricsOnly() {
        val saved = withSavedOffset(emptyMap(), "v:video", 12_000L, 200_000L)
        assertEquals(12_000L, savedOffsetFor(saved, "v:video", 200_000L))
        assertEquals(12_000L, savedOffsetFor(saved, "v:video", 201_200L)) // the same recording, rounded differently
        assertNull(savedOffsetFor(saved, "v:video", 214_000L)) // lyrics of another length now: analyse afresh
        assertNull(savedOffsetFor(saved, "v:audio", 200_000L)) // audio and video are different streams
    }

    @Test
    fun anUnknownLengthIsNotHeldAgainstASavedOffset() {
        val noLength = withSavedOffset(emptyMap(), "v:video", 4_000L, 0L)
        assertEquals(4_000L, savedOffsetFor(noLength, "v:video", 200_000L))
        val withLength = withSavedOffset(emptyMap(), "v:video", 4_000L, 200_000L)
        assertEquals(4_000L, savedOffsetFor(withLength, "v:video", 0L))
    }

    @Test
    fun settingAgainReplacesAndMakesItTheNewest() {
        var saved = withSavedOffset(emptyMap(), "a", 1L, 0L)
        saved = withSavedOffset(saved, "b", 2L, 0L)
        saved = withSavedOffset(saved, "a", 3L, 0L)
        assertEquals(listOf("b", "a"), saved.keys.toList())
        assertEquals(3L, saved["a"]?.offsetMs)
    }

    @Test
    fun theOldestAreDroppedPastTheLimit() {
        var saved = emptyMap<String, SavedLyricOffset>()
        repeat(SAVED_OFFSETS_MAX + 5) { saved = withSavedOffset(saved, "k$it", it.toLong(), 0L) }
        assertEquals(SAVED_OFFSETS_MAX, saved.size)
        assertNull(saved["k0"])
        assertEquals((SAVED_OFFSETS_MAX + 4).toLong(), saved["k${SAVED_OFFSETS_MAX + 4}"]?.offsetMs)
    }
}
