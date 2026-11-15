package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IptvLineupTest {
    private fun channel(uid: Int, key: String, category: String = "c") = IptvChannel(uid = uid, key = key, num = uid, name = key, categoryId = category)

    /** Ten one-copy channels a..j, and a channel "x" with two copies. */
    private val single = ('a'..'j').mapIndexed { i, c -> IptvMergedChannel(listOf(channel(i, c.toString()))) }
    private val twin = IptvMergedChannel(listOf(channel(20, "x-hd"), channel(21, "x-sd")))
    private val all = single + twin
    private fun ref(channel: IptvChannel) = "p:${channel.key}"

    @Test
    fun `the first lineup holds the most watched, then the starred, then fills from the playlist`() {
        val seeded = IptvLineupRules.seed(
            merged = all, refOf = ::ref,
            plays = listOf("p:c", "p:c", "p:c", "p:f", "p:f", "p:x-sd"),
            favourites = listOf("p:h"), never = emptySet(), keptCategories = null, limit = 6,
        )
        // c (3 plays), f (2), then x (1 play on a copy) and h (starred), then a and b from the playlist's order.
        assertEquals(listOf("c", "f", "x-hd", "h", "a", "b"), seeded)
    }

    @Test
    fun `never again keeps a channel out of the first lineup, whichever copy was thrown out`() {
        val seeded = IptvLineupRules.seed(all, ::ref, listOf("p:x-hd", "p:c"), emptyList(), setOf("x-sd"), null, limit = 3)
        assertEquals(listOf("c", "a", "b"), seeded)
        assertEquals(10, IptvLineupRules.visible(all, setOf("x-sd")).size)
    }

    @Test
    fun `the triage's categories limit what fills the lineup`() {
        val mixed = listOf(
            IptvMergedChannel(listOf(channel(1, "k1", "kept"))), IptvMergedChannel(listOf(channel(2, "o1", "out"))),
            IptvMergedChannel(listOf(channel(3, "k2", "kept"))),
        )
        assertEquals(listOf("k1", "k2"), IptvLineupRules.seed(mixed, ::ref, emptyList(), emptyList(), emptySet(), setOf("kept")))
    }

    @Test
    fun `add, remove and move change one channel and the numbers follow the list`() {
        val a = single[0]
        val b = single[1]
        val c = single[2]
        var lineup = listOf("a", "b")
        lineup = IptvLineupRules.add(lineup, c)!!
        assertEquals(listOf("a", "b", "c"), lineup)
        assertEquals(lineup, IptvLineupRules.add(lineup, c), "adding twice changes nothing")
        lineup = IptvLineupRules.move(lineup, c, -2)
        assertEquals(listOf("c", "a", "b"), lineup)
        assertEquals(lineup, IptvLineupRules.move(lineup, c, -1), "the top stays the top")
        lineup = IptvLineupRules.move(lineup, c, 1)
        assertEquals(listOf("a", "c", "b"), lineup)
        lineup = IptvLineupRules.remove(lineup, a)
        assertEquals(listOf("c", "b"), lineup)
        assertEquals(1, IptvLineupRules.position(lineup, b))
        assertEquals(-1, IptvLineupRules.position(lineup, a))
    }

    @Test
    fun `a full lineup takes no more`() {
        val full = (1..3).map { "k$it" }
        assertNull(IptvLineupRules.add(full, single[0], limit = 3))
        assertEquals(listOf(IptvLineupAction.Never), IptvLineupAction.forChannel(-1, 3, limit = 3))
    }

    @Test
    fun `a channel is found by any of its copies`() {
        val lineup = listOf("a", "x-sd")
        assertEquals(1, IptvLineupRules.position(lineup, twin))
        val index = IptvLineupRules.keyIndex(all)
        assertEquals(listOf(single[0], twin), IptvLineupRules.resolve(lineup, index))
        // A key whose channel is gone is skipped.
        assertEquals(listOf(single[0]), IptvLineupRules.resolve(listOf("gone", "a"), index))
    }

    @Test
    fun `a typed number is the lineup's place counted from one`() {
        val channels = listOf(channel(1, "a"), channel(2, "b"))
        assertEquals("a", IptvLineupRules.byNumber(channels, 1)?.key)
        assertEquals("b", IptvLineupRules.byNumber(channels, 2)?.key)
        assertNull(IptvLineupRules.byNumber(channels, 3))
        assertNull(IptvLineupRules.byNumber(channels, 0))
    }

    @Test
    fun `the menu offers what the channel's place allows`() {
        assertEquals(listOf(IptvLineupAction.MoveDown, IptvLineupAction.Remove, IptvLineupAction.Never), IptvLineupAction.forChannel(0, 5))
        assertEquals(listOf(IptvLineupAction.MoveUp, IptvLineupAction.Remove, IptvLineupAction.Never), IptvLineupAction.forChannel(4, 5))
        assertEquals(listOf(IptvLineupAction.MoveUp, IptvLineupAction.MoveDown, IptvLineupAction.Remove, IptvLineupAction.Never), IptvLineupAction.forChannel(2, 5))
        assertEquals(listOf(IptvLineupAction.Add, IptvLineupAction.Never), IptvLineupAction.forChannel(-1, 5))
        assertTrue(IptvLineupRules.neverKeys(twin).containsAll(listOf("x-hd", "x-sd")))
    }
}
