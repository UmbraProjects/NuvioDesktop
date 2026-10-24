package com.nuvio.app.core.storage

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole point of this class is that a burst of cache changes costs one write, so that is what
 * these assert. The counting is deliberately of *writes*, not of timing: the delay window only has
 * to be long enough to swallow a burst, and short enough to reach disk before the user quits.
 */
class CoalescingCachePersisterTest {

    private companion object {
        /** Short enough to keep the suite fast, long enough that a burst lands inside one window. */
        const val WINDOW_MS = 120L

        /** Generous multiple of the window, so a slow CI box does not read as a missing write. */
        const val SETTLE_MS = 600L
    }

    private class Recorder {
        var writes = 0
            private set

        fun record() {
            writes++
        }
    }

    @Test
    fun `a burst of changes collapses into a single write`() = runBlocking {
        val recorder = Recorder()
        val persister = CoalescingCachePersister("test", WINDOW_MS) { recorder.record() }

        repeat(50) { persister.schedule() }
        delay(SETTLE_MS)

        assertEquals(1, recorder.writes, "50 changes inside one window should cost one write")
    }

    @Test
    fun `changes in separate windows are written separately`() = runBlocking {
        val recorder = Recorder()
        val persister = CoalescingCachePersister("test", WINDOW_MS) { recorder.record() }

        persister.schedule()
        delay(SETTLE_MS)
        persister.schedule()
        delay(SETTLE_MS)

        assertEquals(2, recorder.writes)
    }

    @Test
    fun `a change arriving mid-write is not swallowed by that write`() = runBlocking {
        val recorder = Recorder()
        lateinit var persister: CoalescingCachePersister
        persister = CoalescingCachePersister("test", WINDOW_MS) {
            recorder.record()
            // Land a change while this write is in flight — the dirty flag is cleared before the
            // write starts, so without care this one would be lost.
            if (recorder.writes == 1) {
                persister.schedule()
                delay(WINDOW_MS)
            }
        }

        persister.schedule()
        delay(SETTLE_MS * 2)

        assertEquals(2, recorder.writes, "the change made during the first write must reach disk")
    }

    @Test
    fun `flush writes immediately rather than waiting for the window`() = runBlocking {
        val recorder = Recorder()
        val persister = CoalescingCachePersister("test", writeDelayMs = 60_000L) { recorder.record() }

        persister.schedule()
        // Let schedule()'s own launch reach the mutex before flushing.
        delay(SETTLE_MS)
        assertEquals(0, recorder.writes, "nothing should have been written this early")

        persister.flush()
        assertEquals(1, recorder.writes)
    }

    @Test
    fun `flush with nothing pending does not write`() = runBlocking {
        val recorder = Recorder()
        val persister = CoalescingCachePersister("test", WINDOW_MS) { recorder.record() }

        persister.flush()
        persister.flush()

        assertEquals(0, recorder.writes)
    }

    @Test
    fun `flush does not double-write alongside a scheduled window`() = runBlocking {
        val recorder = Recorder()
        val persister = CoalescingCachePersister("test", WINDOW_MS) { recorder.record() }

        persister.schedule()
        delay(SETTLE_MS / 6)
        persister.flush()
        delay(SETTLE_MS)

        assertEquals(1, recorder.writes, "the scheduled write should have been cancelled by flush")
    }

    @Test
    fun `a failed write is retried rather than dropped`() = runBlocking {
        val recorder = Recorder()
        var failNext = true
        val persister = CoalescingCachePersister("test", WINDOW_MS) {
            recorder.record()
            if (failNext) {
                failNext = false
                error("disk full")
            }
        }

        persister.schedule()
        delay(SETTLE_MS)
        assertEquals(1, recorder.writes)

        // The failure must have left the cache marked dirty, so the next change writes everything
        // accumulated since — including what the failed attempt was carrying.
        persister.schedule()
        delay(SETTLE_MS)
        assertEquals(2, recorder.writes)
    }

    @Test
    fun `schedule never blocks the caller`() = runBlocking {
        val persister = CoalescingCachePersister("test", WINDOW_MS) { delay(SETTLE_MS) }

        val startedAt = kotlin.time.TimeSource.Monotonic.markNow()
        repeat(200) { persister.schedule() }
        val elapsedMs = startedAt.elapsedNow().inWholeMilliseconds

        // The call sites hold the cache mutex — and on desktop are on the UI thread — when they call
        // this, so it must return without waiting on the write or on anything holding its lock.
        assertTrue(elapsedMs < WINDOW_MS, "schedule() took ${elapsedMs}ms for 200 calls")
    }
}
