package com.nuvio.app.features.music

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MusicRateLimitTest {
    @AfterTest
    fun liftTheRateLimitPause() {
        MusicRateLimit.resetForTests()
    }

    private val farFuture = Long.MAX_VALUE / 2

    @Test
    fun googlesSorryPageAndA429PauseRequests() {
        assertTrue(MusicRateLimit.noteRefusal(403, "<html><title>Sorry...</title>"))
        assertTrue(MusicRateLimit.isBlocked())
        assertTrue(MusicRateLimit.secondsLeft() in 1..45)
        assertTrue(MusicRateLimit.noteRefusal(429, ""))
    }

    @Test
    fun anOrdinaryRefusalIsNotTheRateLimit() {
        assertFalse(MusicRateLimit.noteRefusal(403, "{\"error\":\"forbidden\"}"))
        assertFalse(MusicRateLimit.noteRefusal(500, "Sorry, something broke"))
    }

    @Test
    fun theCooldownEnds() {
        MusicRateLimit.noteRefusal(429, "")
        assertFalse(MusicRateLimit.isBlocked(now = farFuture))
        assertEquals(0, MusicRateLimit.secondsLeft(now = farFuture))
    }

    @Test
    fun aPausedYouTubeOnlyAnswersALookupSomeoneWaitsOn() {
        assertTrue(mayAskYouTube(paused = false, urgent = false))
        assertFalse(mayAskYouTube(paused = true, urgent = false))
        assertTrue(mayAskYouTube(paused = true, urgent = true))
    }

    @Test
    fun aPausedLookupTriesOnlyTheFirstPlayerClient() {
        assertEquals(6, playerClientsToTry(paused = false, total = 6))
        assertEquals(1, playerClientsToTry(paused = true, total = 6))
        assertEquals(0, playerClientsToTry(paused = true, total = 0))
    }
}
