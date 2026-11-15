package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IptvViewsTest {
    private val minute = 60_000L
    private val now = 999_990 * minute // on a half-hour

    private fun channel(uid: Int, name: String = "Channel $uid") =
        IptvMergedChannel(listOf(IptvChannel(uid = uid, key = "k$uid", num = uid, name = name, categoryId = "c")))

    private fun on(uid: Int, category: String, vararg shows: Triple<String, Int, Int>, kind: String? = null, name: String = "Channel $uid") =
        IptvChannelNow(channel(uid, name), shows.map { (t, from, to) -> IptvEpgEntry(t, now + from * minute, now + to * minute, kind) }, category)

    private val picks = listOf(
        on(1, "UK | Sports", Triple("Saracens v Wasps", -20, 60), kind = IptvProgrammeKind.SPORT),
        on(2, "UK | Sports", Triple("Saracens v Wasps ᴸⁱᵛᵉ", -20, 60), kind = IptvProgrammeKind.SPORT, name = "Channel 2 HD"),
        on(3, "UK | Movies", Triple("Paddington 2", -5, 95), Triple("Late Film", 125, 230), kind = IptvProgrammeKind.FILM),
        on(4, "UK | Entertainment", Triple("Frasier", -10, 20), Triple("Raymond", 20, 50)),
        on(5, "UK | News", Triple("BBC News", -30, 30), kind = IptvProgrammeKind.NEWS),
    )
    private val rank = { p: IptvWallProgramme -> if (p.entry.title.startsWith("Saracens")) 100 else if (p.entry.title.startsWith("Paddington")) 50 else 0 }

    @Test
    fun `the map groups what is on by kind, copies of one programme once`() {
        val blocks = IptvViewRules.blocks(picks, now, rank)
        assertEquals(setOf(IptvChannelKind.Sport, IptvChannelKind.Films, IptvChannelKind.Entertainment, IptvChannelKind.News), blocks.map { it.kind }.toSet())
        assertEquals(1, blocks.first { it.kind == IptvChannelKind.Sport }.programmes.size, "the live-marked copy is the same match")
    }

    @Test
    fun `the mosaic puts the likeliest first`() {
        val mosaic = IptvViewRules.mosaic(picks, now, rank, bigCount = 2, midCount = 1, smallCount = 5)
        assertEquals(listOf("Saracens v Wasps", "Paddington 2"), mosaic.big.map { IptvSportTitle.clean(it.entry.title) })
        assertEquals(1, mosaic.mid.size)
        assertEquals(1, mosaic.small.size)
    }

    @Test
    fun `river lanes never overlap and run in time order`() {
        val lanes = IptvViewRules.river(picks, now - 30 * minute, now + 180 * minute, rank)
        assertEquals(IptvViewRules.LANES, lanes.map { it.kind })
        val ent = lanes.first { it.kind == IptvChannelKind.Entertainment }.items.map { it.entry.title }
        assertEquals(listOf("Frasier", "Raymond"), ent)
        lanes.forEach { lane -> lane.items.zipWithNext().forEach { (a, b) -> assertTrue(a.entry.endMs <= b.entry.startMs) } }
    }

    @Test
    fun `the grid counts only what starts in each half-hour`() {
        val cells = IptvViewRules.grid(picks, now, rank, columns = 4)
        assertEquals(IptvViewRules.LANES.size * 4, cells.size)
        val films = cells.filter { it.kind == IptvChannelKind.Films }
        assertEquals(listOf(0, 0, 0, 0), films.map { it.programmes.size }, "Paddington began before; Late Film starts after the four columns")
        val ent = cells.filter { it.kind == IptvChannelKind.Entertainment }.map { it.programmes.size }
        assertEquals(listOf(1, 0, 0, 0), ent, "Raymond starts at 20 past, inside the first half-hour; Frasier began before")
        assertEquals(0, IptvViewRules.shade(0, 5))
        assertEquals(6, IptvViewRules.shade(5, 5))
    }

    @Test
    fun `the dial keeps Your 40's numbers and numbers the rest on from there`() {
        val lineup = listOf(IptvStageChannel(1, channel(5), null, null), IptvStageChannel(2, channel(3), null, null))
        val cards = IptvViewRules.dial(lineup, picks, now)
        assertEquals(listOf(1, 2, 3, 4, 5), cards.map { it.number })
        assertEquals(listOf(5, 3, 1, 2, 4), cards.map { it.channel.primary.uid })
        assertEquals(2, IptvViewRules.dialIndexOf(cards, 3))
    }
}
