package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IptvWeedTest {
    @Test
    fun `only the quiet hours`() {
        assertFalse(IptvWeedRules.quietHour(1))
        assertTrue(IptvWeedRules.quietHour(2))
        assertTrue(IptvWeedRules.quietHour(4))
        assertFalse(IptvWeedRules.quietHour(5))
        assertFalse(IptvWeedRules.quietHour(20))
    }

    @Test
    fun `three bad nights in a row make a channel dead, a good one clears it`() {
        var r = emptyMap<String, IptvWeedRecord>()
        r = IptvWeedRules.record(r, "a", false, 10)
        r = IptvWeedRules.record(r, "a", false, 11)
        assertFalse(IptvWeedRules.isDead(r["a"]))
        r = IptvWeedRules.record(r, "a", false, 12)
        assertTrue(IptvWeedRules.isDead(r["a"]))
        assertEquals(setOf("a"), IptvWeedRules.dead(r))
        r = IptvWeedRules.record(r, "a", true, 13)
        assertTrue(IptvWeedRules.dead(r).isEmpty())
        assertEquals(0, r["a"]!!.bad)
    }

    @Test
    fun `two failures in one night count once, and a good night in between restarts the count`() {
        var r = IptvWeedRules.record(emptyMap(), "a", false, 10)
        r = IptvWeedRules.record(r, "a", false, 10)
        assertEquals(1, r["a"]!!.bad)
        r = IptvWeedRules.record(r, "a", false, 11)
        r = IptvWeedRules.record(r, "a", true, 12)
        r = IptvWeedRules.record(r, "a", false, 13)
        assertEquals(1, r["a"]!!.bad)
    }

    @Test
    fun `targets are the lineup then the recents, once, not checked tonight, capped`() {
        val records = mapOf("b" to IptvWeedRecord(bad = 0, checked = 10))
        assertEquals(listOf("a", "c", "d"), IptvWeedRules.targets(listOf("a", "b", "c"), listOf("c", "d"), records, 10))
        assertEquals(listOf("a", "b", "c", "d"), IptvWeedRules.targets(listOf("a", "b", "c"), listOf("c", "d"), records, 11))
        assertEquals(2, IptvWeedRules.targets(listOf("a", "b", "c"), emptyList(), emptyMap(), 10, limit = 2).size)
    }

    @Test
    fun `forgets channels that are gone`() {
        val r = mapOf("a" to IptvWeedRecord(3), "b" to IptvWeedRecord(1))
        assertEquals(setOf("b"), IptvWeedRules.prune(r, setOf("b", "z")).keys)
    }
}
