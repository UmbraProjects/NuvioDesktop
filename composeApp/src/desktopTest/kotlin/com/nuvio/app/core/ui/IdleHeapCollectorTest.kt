package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdleHeapCollectorTest {
    private val mb = 1024L * 1024

    private fun collect(
        memoryUse: MemoryUse = MemoryUse.Balanced,
        sinceLastRunMs: Long = 60_000,
        sinceInputMs: Long = 5_000,
        motionQuietMs: Long = 5_000,
        playbackActive: Boolean = false,
        grownBytes: Long = 200 * mb,
        sinceLastFullMs: Long = 30_000,
        decodedArtworkBytes: Long = 0L,
    ) = IdleHeapCollector.shouldCollect(
        memoryUse.idleGrowthMb * mb, memoryUse.idleSlowGrowthMb * mb,
        sinceLastRunMs, sinceInputMs, motionQuietMs, playbackActive, grownBytes, sinceLastFullMs,
        decodedArtworkBytes = decodedArtworkBytes,
    )

    @Test
    fun `collects once the user has paused and the heap has grown`() {
        assertTrue(collect())
    }

    @Test
    fun `waits out input, animation, playback, small growth and recent runs`() {
        assertFalse(collect(sinceInputMs = 1_000))
        assertFalse(collect(motionQuietMs = 0))
        assertFalse(collect(playbackActive = true))
        assertFalse(collect(grownBytes = 32 * mb))
        assertFalse(collect(sinceLastRunMs = 10_000))
        assertFalse(collect(sinceLastFullMs = 5_000))
    }

    @Test
    fun `slow growth is collected once a minute has passed since the last full collection`() {
        assertFalse(collect(grownBytes = 20 * mb, sinceLastFullMs = 30_000))
        assertTrue(collect(grownBytes = 20 * mb, sinceLastFullMs = 61_000))
        // Nothing worth collecting, however long it has been.
        assertFalse(collect(grownBytes = 8 * mb, sinceLastFullMs = 600_000))
    }

    @Test
    fun `slow rule runs after heavy artwork decoding even with zero old-gen growth`() {
        assertFalse(collect(grownBytes = 0, sinceLastFullMs = 61_000, decodedArtworkBytes = 64 * mb))
        assertTrue(collect(grownBytes = 0, sinceLastFullMs = 61_000, decodedArtworkBytes = 150 * mb))
        assertFalse(collect(grownBytes = 0, sinceLastFullMs = 30_000, decodedArtworkBytes = 200 * mb))
        // Very heavy native artwork backlog (512 MB+ decoded) triggers on the fast 20 s idle rule.
        assertTrue(collect(grownBytes = 0, sinceLastFullMs = 25_000, decodedArtworkBytes = 520 * mb))
    }

    @Test
    fun `lean collects sooner than balanced`() {
        assertTrue(collect(memoryUse = MemoryUse.Lean, grownBytes = 30 * mb))
        assertFalse(collect(memoryUse = MemoryUse.Balanced, grownBytes = 30 * mb))
    }
}
