package com.nuvio.app.features.whatsnew

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WhatsNewReturnTest {

    private val plan = planWhatsNew(
        listOf(
            WhatsNewEntry(number = 1, title = "One", pitch = "One."),
            WhatsNewEntry(number = 2, title = "Two", pitch = "Two."),
            WhatsNewEntry(number = 3, title = "Three", pitch = "Three."),
        ),
        lastSeen = 0,
    )!!
    private val resume = WhatsNewUiState(plan = plan, index = 2)
    private val pending = WhatsNewReturn(page = "Music", resume = resume)

    @Test
    fun `the page Settings starts on does not count as leaving`() {
        val step = whatsNewReturnOnPage(pending, "Addons") as WhatsNewReturnStep.Wait
        assertEquals(pending, step.pending)
        assertFalse(step.pending.reached)
    }

    @Test
    fun `reaching the requested page arms the return`() {
        val step = whatsNewReturnOnPage(pending, "Music") as WhatsNewReturnStep.Wait
        assertTrue(step.pending.reached)
    }

    @Test
    fun `staying on the page keeps waiting`() {
        val armed = pending.copy(reached = true)
        val step = whatsNewReturnOnPage(armed, "Music") as WhatsNewReturnStep.Wait
        assertEquals(armed, step.pending)
    }

    @Test
    fun `leaving the page brings the cards back on the same card`() {
        val step = whatsNewReturnOnPage(pending.copy(reached = true), "Root") as WhatsNewReturnStep.Resume
        assertEquals(resume, step.state)
        assertEquals(2, step.state.index)
    }

    @Test
    fun `the full run from arriving to leaving`() {
        var current = pending
        listOf("Addons", "Music", "Music").forEach { page ->
            current = (whatsNewReturnOnPage(current, page) as WhatsNewReturnStep.Wait).pending
        }
        assertTrue(whatsNewReturnOnPage(current, "Account") is WhatsNewReturnStep.Resume)
    }

    @Test
    fun `leaving the tab resumes even when the page was never reached`() {
        val step = whatsNewReturnOnTabLeft(pending) as WhatsNewReturnStep.Resume
        assertEquals(resume, step.state)
    }
}
