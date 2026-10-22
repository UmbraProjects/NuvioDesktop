package com.nuvio.app.features.cast

import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Drives the router with the request sequence recorded from an LG CX playing the Dolby Vision
 * remux (relay logs from 2026-10-22), byte offsets and times as the TV sent them.
 */
class DvRemuxRouterTest {
    private class FakeSession(override val requestedS: Double) : RemuxSession {
        override val keyS: Double = if (requestedS <= 0) 0.0 else requestedS - 1.5
        override var tvBase: Long = 0
        override var prefix: ByteArray = ByteArray(0)
        override val written: Long = 500L * 1024 * 1024
        var closed = false
        override fun initSegment(): ByteArray = ByteArray(1431)
        override fun stream(out: OutputStream, outOffset: Long): Long = 0
        override fun close() {
            closed = true
        }
    }

    private val created = mutableListOf<FakeSession>()
    private val router = DvRemuxRouter(
        byteRate = 3_184_170.0, // 10.79 GB over 56:28
        sessionFactory = { FakeSession(it).also(created::add) },
    )

    @Test
    fun `plain start is the start session from byte zero`() {
        val route = router.route(rangeStart = null, nptS = null)
        assertEquals(0.0, route.session.keyS)
        assertEquals(0L, route.outOffset)
        assertEquals(0, route.prefix.size)
    }

    @Test
    fun `mid-play reconnect with matching time resumes the spool`() {
        val start = router.route(null, null).session
        val route = router.route(rangeStart = 76_898_304, nptS = 32.026)
        assertSame(start, route.session)
        assertEquals(76_898_304L, route.outOffset)
        assertEquals(1, created.size)
    }

    @Test
    fun `time far from the offset is a seek answered with a whole MP4 from that time`() {
        router.route(null, null)
        val route = router.route(rangeStart = 80_265_216, nptS = 1878.06)
        assertEquals(1878.06, route.session.requestedS)
        assertEquals(1431, route.prefix.size, "the start session's header leads the reply")
        assertEquals(0L, route.outOffset)
        assertEquals(route.session.keyS, route.replyNptS)
    }

    @Test
    fun `the CX's seek straight to its resume time gets a fresh stream`() {
        // 2026-10-22: header and first fragments read (6.5 MB), then npt=0:29:31 at that offset.
        router.route(null, null)
        val route = router.route(rangeStart = 6_488_064, nptS = 1771.0)
        assertEquals(1771.0, route.session.requestedS)
        assertEquals(1431, route.prefix.size)
        assertEquals(0L, route.outOffset)
    }

    @Test
    fun `repeating a seek reuses its session`() {
        router.route(null, null)
        val first = router.route(80_265_216, 1878.06).session
        assertEquals(0.0, router.route(null, null).session.keyS)
        val route = router.route(rangeStart = 3_440_640, nptS = 1878.0)
        assertSame(first, route.session)
        assertEquals(1431, route.prefix.size)
    }

    @Test
    fun `reconnect inside a seek session resumes after the prefix`() {
        router.route(null, null)
        val seek = router.route(80_000_000, 1800.0).session
        val fiftyMiB = 50L * 1024 * 1024
        val route = router.route(rangeStart = fiftyMiB, nptS = seek.keyS + fiftyMiB / 3_184_170.0)
        assertSame(seek, route.session)
        assertEquals(0, route.prefix.size)
        assertEquals(fiftyMiB - 1431, route.outOffset)
        // A reconnect that lands inside the header gets the rest of the header first.
        val early = router.route(rangeStart = 1000, nptS = seek.keyS)
        assertSame(seek, early.session)
        assertEquals(431, early.prefix.size)
        assertEquals(0L, early.outOffset)
    }

    @Test
    fun `time without a range gets the start header plus fragments`() {
        val route = router.route(rangeStart = null, nptS = 600.0)
        assertEquals(1431, route.prefix.size)
        assertEquals(0L, route.outOffset)
    }

    @Test
    fun `eviction keeps the start session`() {
        router.route(null, null)
        router.route(1431, 600.0)
        router.route(1431, 1800.0)
        val start = created.first { it.requestedS == 0.0 }
        val first = created.first { it.requestedS == 600.0 }
        assertTrue(first.closed)
        assertTrue(!start.closed)
        router.close()
        assertTrue(created.all { it.closed })
    }
}
