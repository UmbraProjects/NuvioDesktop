package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WhereNextBookTest {
    private class Disk(var payload: String? = null)

    private fun candidate(id: String, name: String) =
        LadderCandidate(id = id, name = name, votes = mapOf("jazz" to 12, "soul" to 4), mood = "calm", style = "Fusion", fans = 4_200L, playable = false, beginYear = 1984)

    private fun ready(name: String = "Sting") = ArtistLadderOutcome.Ready(
        target = ArtistTarget(candidate("a1", name), "rock", listOf("rock", "jazz"), borrowedFrom = listOf("The Police")),
        ladder = Ladder(
            target = "jazz",
            rungs = listOf(
                LadderRung(1, candidate("c1", "First"), purity = 0.31, proximity = 0.8, anchorName = name, why = "Shares rock with $name."),
                LadderRung(2, candidate("c2", "Second"), purity = 0.55, proximity = 0.6, anchorName = null, why = "More jazz."),
            ),
            coldStart = false,
        ),
        mode = LadderMode.OutFrom,
        destination = "jazz",
        alternatives = listOf("pop", "soul"),
    )

    private fun book(disk: Disk, now: () -> Long) = WhereNextBook({ disk.payload }, { disk.payload = it }, now)

    @Test
    fun aSavedLadderComesBackExactly() {
        val disk = Disk()
        val original = ready()
        book(disk, { 1_000L }).put("a1|jazz", original)
        // A new book over the same payload is a restart: nothing is held in memory.
        assertEquals(original, book(disk, { 2_000L }).get("a1|jazz"))
    }

    @Test
    fun nothingIsReturnedForAnotherKeyOrAnEmptyDisk() {
        val disk = Disk()
        assertNull(book(disk, { 0L }).get("a1|jazz"))
        book(disk, { 0L }).put("a1|jazz", ready())
        assertNull(book(disk, { 0L }).get("a1|soul"))
        assertNull(book(disk, { 0L }).get("other|jazz"))
    }

    @Test
    fun aLadderIsGoodForASevenDays() {
        val disk = Disk()
        book(disk, { 0L }).put("a1|jazz", ready())
        assertEquals(ready(), book(disk, { WHERE_NEXT_TTL_MS }).get("a1|jazz"))
        assertNull(book(disk, { WHERE_NEXT_TTL_MS + 1 }).get("a1|jazz"))
    }

    @Test
    fun aClockSetBackDoesNotKeepALadderForever() {
        val disk = Disk()
        book(disk, { 5_000_000L }).put("a1|jazz", ready())
        assertNull(book(disk, { 1_000L }).get("a1|jazz"))
    }

    @Test
    fun aPutReplacesTheOldLadderForTheKey() {
        val disk = Disk()
        book(disk, { 0L }).put("a1|jazz", ready("Sting"))
        book(disk, { 10L }).put("a1|jazz", ready("Police"))
        assertEquals("Police", book(disk, { 20L }).get("a1|jazz")?.target?.artist?.name)
    }

    @Test
    fun removeDropsOneKeyOnly() {
        val disk = Disk()
        val b = book(disk, { 0L })
        b.put("a1|jazz", ready())
        b.put("a1|soul", ready())
        b.remove("a1|jazz")
        assertNull(b.get("a1|jazz"))
        assertEquals(ready(), b.get("a1|soul"))
    }

    @Test
    fun anUnreadablePayloadIsAMissNotACrash() {
        val disk = Disk("{ not json")
        val b = book(disk, { 0L })
        assertNull(b.get("a1|jazz"))
        b.put("a1|jazz", ready())
        assertEquals(ready(), b.get("a1|jazz"))
    }

    @Test
    fun expiredLaddersAreDroppedAndTheOldestGoFirstPastTheCap() {
        val disk = Disk()
        var clock = 0L
        val b = book(disk) { clock }
        b.put("old", ready())
        clock = WHERE_NEXT_TTL_MS + 5
        repeat(MAX_STORED_WHERE_NEXT + 3) { i ->
            clock += 1
            b.put("k$i", ready())
        }
        assertNull(b.get("old"))
        assertNull(b.get("k0"))
        assertNull(b.get("k2"))
        assertEquals(ready(), b.get("k3"))
        assertEquals(ready(), b.get("k${MAX_STORED_WHERE_NEXT + 2}"))
    }
}
