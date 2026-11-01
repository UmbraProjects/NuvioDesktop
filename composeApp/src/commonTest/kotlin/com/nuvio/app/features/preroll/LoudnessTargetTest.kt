package com.nuvio.app.features.preroll

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoudnessTargetTest {
    @Test
    fun trailerExpressionCarriesTheSharedTarget() {
        assertEquals("loudnorm=I=-24:LRA=7:TP=-2", loudnessFilterExpression())
    }

    @Test
    fun musicKeepsTheSameTargetWithAWiderRange() {
        val music = loudnessFilterExpression(MUSIC_LOUDNESS_RANGE_LU)
        assertTrue(music.contains("I=$LOUDNESS_TARGET_LUFS"))
        assertTrue(music.contains("LRA=$MUSIC_LOUDNESS_RANGE_LU"))
        assertTrue(MUSIC_LOUDNESS_RANGE_LU > TRAILER_LOUDNESS_RANGE_LU)
    }

    @Test
    fun theFilterIsWrappedAsOneLibavfilterChain() {
        assertEquals("lavfi=[loudnorm=I=-24:LRA=7:TP=-2]", mpvAudioFilterValue(loudnessFilterExpression()))
        assertEquals(
            "lavfi=[loudnorm=I=-24:LRA=7:TP=-2,afade=t=in:st=0:d=0.12]",
            mpvAudioFilterValue(loudnessFilterExpression(), "afade=t=in:st=0:d=0.12"),
        )
    }

    @Test
    fun theTargetIsQuietEnoughNotToPassAFilm() {
        // Full scale is 0; the old trailer chain pulled peaks to about -13 LUFS, which is what was too loud.
        assertTrue(LOUDNESS_TARGET_LUFS <= -20)
        assertFalse(loudnessFilterExpression().contains("dynaudnorm"))
    }
}
