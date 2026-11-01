package com.nuvio.app.features.whatsnew

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhatsNewPlanTest {

    private fun entry(
        number: Int,
        weight: WhatsNewWeight = WhatsNewWeight.Small,
        action: WhatsNewAction = WhatsNewAction.None,
    ) = WhatsNewEntry(
        number = number,
        title = "Feature $number",
        pitch = "What feature $number does.",
        weight = weight,
        action = action,
    )

    private fun spotlight(number: Int) = entry(number, WhatsNewWeight.Spotlight)

    private fun numbers(entries: List<WhatsNewEntry>) = entries.map { it.number }

    // ---- what a visit shows ----

    @Test
    fun `nothing to show without entries`() {
        assertNull(planWhatsNew(emptyList(), lastSeen = 0))
    }

    @Test
    fun `nothing to show when everything was seen`() {
        assertNull(planWhatsNew(listOf(entry(1), entry(2)), lastSeen = 2))
        assertNull(planWhatsNew(listOf(entry(1), entry(2)), lastSeen = 9))
    }

    @Test
    fun `only entries newer than the last seen one are shown`() {
        val plan = planWhatsNew(listOf(entry(1), entry(2), entry(3)), lastSeen = 1)!!
        assertEquals(setOf(2, 3), numbers(plan.cards).toSet())
    }

    @Test
    fun `the spotlight is the newest entry flagged as one and the rest follow newest first`() {
        val plan = planWhatsNew(
            listOf(entry(1), spotlight(2), entry(3), entry(4)),
            lastSeen = 0,
        )!!
        assertEquals(2, plan.spotlight.number)
        assertEquals(listOf(4, 3, 1), numbers(plan.small))
        assertEquals(0, plan.moreCount)
    }

    @Test
    fun `with several spotlights the newest leads and the others are drawn small`() {
        val plan = planWhatsNew(listOf(spotlight(1), spotlight(2), entry(3)), lastSeen = 0)!!
        assertEquals(2, plan.spotlight.number)
        assertEquals(listOf(3, 1), numbers(plan.small))
    }

    @Test
    fun `an older build's spotlight still leads a catch-up over newer small entries`() {
        val plan = planWhatsNew(listOf(spotlight(5), entry(6), entry(7)), lastSeen = 4)!!
        assertEquals(5, plan.spotlight.number)
        assertEquals(listOf(7, 6), numbers(plan.small))
    }

    @Test
    fun `with no spotlight flagged the newest entry leads`() {
        val plan = planWhatsNew(listOf(entry(1), entry(2), entry(3)), lastSeen = 0)!!
        assertEquals(3, plan.spotlight.number)
        assertEquals(listOf(2, 1), numbers(plan.small))
    }

    @Test
    fun `a catch-up over two builds comes in one go`() {
        val all = (1..8).map { entry(it) }
        val plan = planWhatsNew(all, lastSeen = 3, maxCards = 10)!!
        assertEquals(listOf(8, 7, 6, 5, 4), numbers(plan.cards))
    }

    // ---- the card cap ----

    @Test
    fun `a visit has at most five cards and counts the rest`() {
        val all = (1..9).map { entry(it) }
        val plan = planWhatsNew(all, lastSeen = 0)!!
        assertEquals(WHATS_NEW_MAX_CARDS, plan.cards.size)
        assertEquals(listOf(9, 8, 7, 6, 5), numbers(plan.cards))
        assertEquals(4, plan.moreCount)
    }

    @Test
    fun `exactly the cap leaves nothing over`() {
        val plan = planWhatsNew((1..5).map { entry(it) }, lastSeen = 0)!!
        assertEquals(5, plan.cards.size)
        assertEquals(0, plan.moreCount)
    }

    @Test
    fun `a flagged spotlight always makes the cut however old`() {
        val all = listOf(spotlight(1)) + (2..9).map { entry(it) }
        val plan = planWhatsNew(all, lastSeen = 0)!!
        assertEquals(1, plan.spotlight.number)
        assertEquals(listOf(1, 9, 8, 7, 6), numbers(plan.cards))
        assertEquals(4, plan.moreCount)
    }

    @Test
    fun `a cap of one shows only the spotlight`() {
        val plan = planWhatsNew((1..4).map { entry(it) }, lastSeen = 0, maxCards = 1)!!
        assertEquals(listOf(4), numbers(plan.cards))
        assertEquals(3, plan.moreCount)
    }

    @Test
    fun `a cap below one is treated as one`() {
        val plan = planWhatsNew((1..3).map { entry(it) }, lastSeen = 0, maxCards = 0)!!
        assertEquals(1, plan.cards.size)
        assertEquals(2, plan.moreCount)
    }

    @Test
    fun `an entry listed twice under one number counts once`() {
        val plan = planWhatsNew(listOf(entry(1), entry(1), entry(2)), lastSeen = 0)!!
        assertEquals(listOf(2, 1), numbers(plan.cards))
        assertEquals(0, plan.moreCount)
    }

    // ---- the Settings row ----

    @Test
    fun `history shows every entry newest first whether or not it was seen`() {
        val plan = planWhatsNewHistory((1..20).map { entry(it) })!!
        assertEquals((20 downTo 1).toList(), numbers(plan.cards))
        assertEquals(0, plan.moreCount)
    }

    @Test
    fun `history of nothing is null`() {
        assertNull(planWhatsNewHistory(emptyList()))
    }

    // ---- launch ----

    @Test
    fun `a fresh account is seeded with the newest number and shown nothing`() {
        val launch = decideWhatsNewOnLaunch(
            listOf(entry(1), entry(2), entry(3)),
            lastSeen = null,
            freshAccount = true,
        )
        assertEquals(WhatsNewLaunch.Seed(3), launch)
    }

    @Test
    fun `a fresh account with no entries yet is seeded with zero`() {
        val launch = decideWhatsNewOnLaunch(emptyList(), lastSeen = null, freshAccount = true)
        assertEquals(WhatsNewLaunch.Seed(0), launch)
    }

    @Test
    fun `an account that existed before the feature sees the cards at its first update`() {
        val launch = decideWhatsNewOnLaunch(
            listOf(spotlight(1), entry(2)),
            lastSeen = null,
            freshAccount = false,
        )
        val show = assertIs<WhatsNewLaunch.Show>(launch)
        assertEquals(1, show.plan.spotlight.number)
        assertEquals(listOf(2), numbers(show.plan.small))
    }

    @Test
    fun `once a number is recorded a fresh-account flag no longer seeds`() {
        val launch = decideWhatsNewOnLaunch(
            listOf(entry(1), entry(2), entry(3)),
            lastSeen = 1,
            freshAccount = true,
        )
        val show = assertIs<WhatsNewLaunch.Show>(launch)
        assertEquals(setOf(2, 3), numbers(show.plan.cards).toSet())
    }

    @Test
    fun `nothing newer means nothing is shown`() {
        val launch = decideWhatsNewOnLaunch(listOf(entry(1), entry(2)), lastSeen = 2, freshAccount = false)
        assertEquals(WhatsNewLaunch.NothingNew, launch)
        assertEquals(
            WhatsNewLaunch.NothingNew,
            decideWhatsNewOnLaunch(emptyList(), lastSeen = null, freshAccount = false),
        )
    }

    // ---- closing ----

    @Test
    fun `closing records the newest number even for cards that did not fit`() {
        val all = (1..9).map { entry(it) }
        assertEquals(9, lastSeenAfterClosing(all, previous = 2))
    }

    @Test
    fun `the recorded number never goes backwards`() {
        val all = (1..4).map { entry(it) }
        assertEquals(7, lastSeenAfterClosing(all, previous = 7))
        assertEquals(4, lastSeenAfterClosing(all, previous = null))
        assertEquals(0, lastSeenAfterClosing(emptyList(), previous = null))
    }

    @Test
    fun `closing the cards leaves nothing to show at the next launch`() {
        val all = (1..9).map { entry(it) }
        val shown = assertIs<WhatsNewLaunch.Show>(
            decideWhatsNewOnLaunch(all, lastSeen = 2, freshAccount = false),
        )
        assertTrue(shown.plan.moreCount > 0)
        val recorded = lastSeenAfterClosing(all, previous = 2)
        assertEquals(
            WhatsNewLaunch.NothingNew,
            decideWhatsNewOnLaunch(all, lastSeen = recorded, freshAccount = false),
        )
    }

    // ---- the entries themselves ----

    @Test
    fun `a sound list has no problems`() {
        val list = listOf(
            spotlight(1),
            entry(2, action = WhatsNewAction.TurnOn("previously-on")),
            entry(3, action = WhatsNewAction.OpenSettings("preshow")),
        )
        assertEquals(emptyList(), whatsNewEntryProblems(list))
    }

    @Test
    fun `a repeated number is reported`() {
        val problems = whatsNewEntryProblems(listOf(entry(1), entry(1)))
        assertEquals(1, problems.size)
        assertTrue("number 1" in problems.single())
    }

    @Test
    fun `a number below one is reported`() {
        assertTrue(whatsNewEntryProblems(listOf(entry(0))).any { "1 or more" in it })
        assertTrue(whatsNewEntryProblems(listOf(entry(-3))).any { "1 or more" in it })
    }

    @Test
    fun `blank text is reported`() {
        val blank = WhatsNewEntry(number = 1, title = " ", pitch = "")
        val problems = whatsNewEntryProblems(listOf(blank))
        assertTrue(problems.any { "title is blank" in it })
        assertTrue(problems.any { "pitch is blank" in it })
    }

    @Test
    fun `text too long for a card is reported`() {
        val long = WhatsNewEntry(
            number = 1,
            title = "t".repeat(WHATS_NEW_MAX_TITLE_LENGTH + 1),
            pitch = "p".repeat(WHATS_NEW_MAX_PITCH_LENGTH + 1),
        )
        val problems = whatsNewEntryProblems(listOf(long))
        assertTrue(problems.any { "title is over" in it })
        assertTrue(problems.any { "pitch is over" in it })
    }

    @Test
    fun `text at the limit is fine`() {
        val atLimit = WhatsNewEntry(
            number = 1,
            title = "t".repeat(WHATS_NEW_MAX_TITLE_LENGTH),
            pitch = "p".repeat(WHATS_NEW_MAX_PITCH_LENGTH),
        )
        assertEquals(emptyList(), whatsNewEntryProblems(listOf(atLimit)))
    }

    @Test
    fun `an action with a blank key is reported`() {
        val turnOn = entry(1, action = WhatsNewAction.TurnOn(" "))
        val open = entry(2, action = WhatsNewAction.OpenSettings(""))
        val problems = whatsNewEntryProblems(listOf(turnOn, open))
        assertTrue(problems.any { "entry 1" in it && "turn-on key" in it })
        assertTrue(problems.any { "entry 2" in it && "settings page key" in it })
    }

    @Test
    fun `the shipped entries are sound`() {
        assertEquals(emptyList(), whatsNewEntryProblems(whatsNewEntries))
    }
}
