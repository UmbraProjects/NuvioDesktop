package com.nuvio.app.features.music

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MusicAutoPauseControllerTest {
    private class FakeMusic {
        var active = true
        var playing = true
    }

    private fun TestScope.controller(music: FakeMusic) = MusicAutoPauseController(
        isPlaying = { music.playing },
        isActive = { music.active },
        pause = { music.playing = false },
        resume = { music.playing = true },
        scope = this,
        graceMs = 1_000L,
    )

    @Test
    fun pausesAndResumesAfterGrace() = runTest {
        val music = FakeMusic()
        val c = controller(music)
        val owner = Any()
        c.hold(owner)
        assertFalse(music.playing)
        c.release(owner)
        advanceTimeBy(500L); runCurrent()
        assertFalse(music.playing)
        advanceTimeBy(600L); runCurrent()
        assertTrue(music.playing)
    }

    @Test
    fun handOverWithinGraceKeepsMusicPaused() = runTest {
        val music = FakeMusic()
        val c = controller(music)
        val first = Any()
        val second = Any()
        c.hold(first)
        c.release(first)
        advanceTimeBy(300L); runCurrent()
        c.hold(second)
        advanceTimeBy(2_000L); runCurrent()
        assertFalse(music.playing)
        c.release(second)
        advanceTimeBy(1_100L); runCurrent()
        assertTrue(music.playing)
    }

    @Test
    fun musicAlreadyPausedStaysPaused() = runTest {
        val music = FakeMusic().apply { playing = false }
        val c = controller(music)
        val owner = Any()
        c.hold(owner)
        c.release(owner)
        advanceTimeBy(2_000L); runCurrent()
        assertFalse(music.playing)
    }

    @Test
    fun holdWithoutResumeLeavesMusicPaused() = runTest {
        val music = FakeMusic()
        val c = controller(music)
        val owner = Any()
        c.hold(owner, resumeAfter = false)
        assertFalse(music.playing)
        c.release(owner)
        advanceTimeBy(2_000L); runCurrent()
        assertFalse(music.playing)
    }

    @Test
    fun closedPlayerIsNotRestarted() = runTest {
        val music = FakeMusic()
        val c = controller(music)
        val owner = Any()
        c.hold(owner)
        music.active = false
        c.release(owner)
        advanceTimeBy(2_000L); runCurrent()
        assertFalse(music.playing)
    }

    @Test
    fun overlappingHoldsResumeOnlyAfterTheLast() = runTest {
        val music = FakeMusic()
        val c = controller(music)
        val film = Any()
        val trailer = Any()
        c.hold(film)
        c.hold(trailer)
        c.release(trailer)
        advanceTimeBy(2_000L); runCurrent()
        assertFalse(music.playing)
        c.release(film)
        advanceTimeBy(1_100L); runCurrent()
        assertTrue(music.playing)
    }
}
