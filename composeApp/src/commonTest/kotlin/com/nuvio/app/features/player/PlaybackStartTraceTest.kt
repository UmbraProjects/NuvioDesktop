package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlaybackStartTraceTest {
    @Test
    fun pendingClickAndNativeAttachShareTheSameTrace() {
        PlaybackStartTrace.beginPending("test click")
        val clickId = PlaybackStartTrace.currentId
        assertTrue(clickId > 0)
        PlaybackStartTrace.markPending("test preparation")
        PlaybackStartTrace.begin("test source load")
        PlaybackStartTrace.mark("test native attach")
        assertEquals(clickId, PlaybackStartTrace.currentId)
        PlaybackStartTrace.complete("test playback restart")
    }

    @Test
    fun nextSourceGetsANewTraceWithoutAnotherClick() {
        PlaybackStartTrace.begin("test first source")
        val previous = PlaybackStartTrace.currentId
        PlaybackStartTrace.complete("test restart")
        PlaybackStartTrace.mark("test next source attach")
        assertNotEquals(previous, PlaybackStartTrace.currentId)
        PlaybackStartTrace.complete("test next restart")
    }

    @Test
    fun renderAndShieldDetailsDoNotCreatePhantomPlaybackAttempts() {
        PlaybackStartTrace.begin("test source")
        val source = PlaybackStartTrace.currentId
        PlaybackStartTrace.complete("test restart")
        PlaybackStartTrace.markStartupDetail("profileRendered")
        PlaybackStartTrace.markStartupDetail("launchShield:hidden")
        assertEquals(source, PlaybackStartTrace.currentId)
    }
}
