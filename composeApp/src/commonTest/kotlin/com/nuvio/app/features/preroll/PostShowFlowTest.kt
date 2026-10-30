package com.nuvio.app.features.preroll

import com.nuvio.app.features.player.skip.SkipInterval
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PostShowFlowTest {
    private val duration = 7_200_000L
    private val credits = listOf(SkipInterval(6_600.0, 7_150.0, "outro", "introdb"))

    private fun PostShowTracker.tick(pos: Long, showing: Boolean = false) =
        onTick(pos, duration, credits, showing)

    @Test
    fun `prefetch fires once at the halfway point`() {
        val t = PostShowTracker()
        assertTrue(t.tick(3_000_000L).isEmpty())
        assertEquals(listOf(PostShowSignal.Prefetch), t.tick(3_600_000L))
        assertTrue(t.tick(3_601_000L).isEmpty())
    }

    @Test
    fun `starts when playing crosses into the credits, once`() {
        val t = PostShowTracker()
        t.tick(6_598_000L)
        assertTrue(PostShowSignal.Start in t.tick(6_600_500L))
        // Stopped by the viewer, then seeking back across the start: spent for this playback.
        t.tick(6_650_000L, showing = true)
        t.tick(6_000_000L)
        assertTrue(PostShowSignal.Start !in t.tick(6_600_500L))
    }

    @Test
    fun `a resume or seek into the credits never starts`() {
        val t = PostShowTracker()
        assertTrue(PostShowSignal.Start !in t.tick(6_700_000L))
        assertTrue(PostShowSignal.Start !in t.tick(6_701_000L))
        val s = PostShowTracker()
        s.tick(1_000_000L)
        assertTrue(PostShowSignal.Start !in s.tick(6_650_000L))
    }

    @Test
    fun `stops when the position leaves the window while showing`() {
        val t = PostShowTracker()
        t.tick(6_600_500L, showing = true)
        assertEquals(listOf(PostShowSignal.Stop), t.tick(2_000_000L, showing = true))
    }

    @Test
    fun `reset allows a new playback to start again`() {
        val t = PostShowTracker()
        t.tick(6_598_000L); t.tick(6_600_500L)
        t.reset()
        t.tick(6_598_000L)
        assertTrue(PostShowSignal.Start in t.tick(6_600_500L))
    }

    private fun start() = PostShowView(initialPreShowStage(slideCount = 2, questionCount = 1))
    private fun PostShowView.key(k: PostShowKey, options: Int = 4) = press(k, 2, 1, options)

    @Test
    fun `select walks cards then the question then finishes`() {
        var v = start()
        v = v.key(PostShowKey.Select)
        assertEquals(PreShowStage.Slide(1), v.stage)
        v = v.key(PostShowKey.Select)
        assertEquals(PreShowStage.Question(0, null), v.stage)
        v = v.key(PostShowKey.Down).key(PostShowKey.Down)
        assertEquals(2, v.cursor)
        v = v.key(PostShowKey.Select)
        assertEquals(PreShowStage.Question(0, 2), v.stage)
        v = v.key(PostShowKey.Select)
        assertEquals(PreShowStage.Finished, v.stage)
    }

    @Test
    fun `up and down hold a card and a stage change releases it`() {
        var v = start().key(PostShowKey.Up)
        assertTrue(v.held)
        v = v.key(PostShowKey.Right)
        assertEquals(PostShowView(PreShowStage.Slide(1)), v)
    }

    @Test
    fun `number keys answer directly and respect the option count`() {
        val atQuestion = PostShowView(PreShowStage.Question(0, null))
        assertEquals(PreShowStage.Question(0, 1), atQuestion.key(PostShowKey.Option2).stage)
        assertEquals(PreShowStage.Question(0, null), atQuestion.key(PostShowKey.Option4, options = 3).stage)
    }

    @Test
    fun `cursor stops at the ends and left returns to the last card`() {
        val q = PostShowView(PreShowStage.Question(0, null))
        assertEquals(0, q.key(PostShowKey.Up).cursor)
        assertEquals(PreShowStage.Slide(1), q.key(PostShowKey.Left).stage)
    }

    @Test
    fun `key codes map and unknown codes do not`() {
        assertEquals(PostShowKey.Select, PostShowKey.fromCode(5))
        assertEquals(PostShowKey.Option3, PostShowKey.fromCode(13))
        assertEquals(null, PostShowKey.fromCode(99))
    }
}
