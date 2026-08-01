package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkipDbSubmitOutcomeTest {

    @Test
    fun `a published submission is accepted`() {
        val outcome = SkipDbSubmitResponse(
            id = 42,
            status = "approved",
            autoApproved = true,
            message = "Submission accepted and published.",
        ).toOutcome()

        assertTrue(outcome.accepted)
        assertEquals("Submission accepted and published.", outcome.message)
    }

    @Test
    fun `a submission held for review still counts as contributed`() {
        val outcome = SkipDbSubmitResponse(status = "pending", message = "Queued for review.").toOutcome()

        assertTrue(outcome.accepted, "pending is a contribution awaiting moderation, not a failure")
    }

    @Test
    fun `re-submitting something already published is not an error`() {
        val outcome = SkipDbSubmitResponse(status = "already_approved").toOutcome()

        assertTrue(outcome.accepted)
    }

    @Test
    fun `a rejection is reported as one`() {
        val outcome = SkipDbSubmitResponse(status = "rejected").toOutcome()

        assertFalse(outcome.accepted)
        assertEquals("SkipDB rejected the submission.", outcome.message)
    }

    @Test
    fun `the server's explanation wins over anything written here`() {
        val outcome = SkipDbSubmitResponse(
            error = "Overlaps an existing segment.",
            status = "approved",
        ).toOutcome()

        assertFalse(outcome.accepted, "an error is a failure even when a status came back with it")
        assertEquals("Overlaps an existing segment.", outcome.message)
    }

    @Test
    fun `falls back to the first reason when there is no message`() {
        val outcome = SkipDbSubmitResponse(
            status = "approved",
            reasons = listOf("matches an existing approved segment (consensus)"),
        ).toOutcome()

        assertTrue(outcome.accepted)
        assertEquals("matches an existing approved segment (consensus)", outcome.message)
    }

    @Test
    fun `a reply with nothing usable in it is treated as a failure, not a silent success`() {
        val outcome = SkipDbSubmitResponse().toOutcome()

        assertFalse(outcome.accepted)
        assertEquals("SkipDB rejected the submission.", outcome.message)
    }

    @Test
    fun `blank fields do not become the message shown to the user`() {
        val outcome = SkipDbSubmitResponse(
            status = "approved",
            message = "  ",
            reasons = listOf("", "consensus"),
            error = "",
        ).toOutcome()

        assertTrue(outcome.accepted)
        assertEquals("consensus", outcome.message)
    }

    @Test
    fun `the segment kinds stay in the order both player UIs index into`() {
        // controls.js sends the position of the chosen button in this list, and Kotlin resolves it
        // back through SKIP_SEGMENT_TYPES. Reordering one without the other misfiles submissions.
        assertEquals(listOf("intro", "recap", "outro", "preview"), SKIP_SEGMENT_TYPES)
    }
}
