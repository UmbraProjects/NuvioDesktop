package com.nuvio.app.core.network

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How a host that answers 429 is waited out: the rules, with a clock and a sleep the test controls. */
class RateLimitGateTest {
    private class FakeClock { var now = 1_000_000L }

    private fun cooldowns(clock: FakeClock) = HostCooldowns(clock = { clock.now })

    private fun gate(clock: FakeClock, slept: MutableList<Long>, attempts: Int = 3, longest: Long = 10_000L) = RateLimitGate(
        cooldowns = cooldowns(clock),
        sleep = { ms -> slept += ms; clock.now += ms },
        attempts = attempts,
        longestWaitMs = longest,
    )

    private fun tooMany(retryAfter: Long? = null) = HttpStatusException(429, retryAfter, "Request failed with HTTP 429")

    @Test
    fun retryAfterIsReadOnlyWhenItIsANumberOfSeconds() {
        assertEquals(7L, parseRetryAfterSeconds("7"))
        assertEquals(12L, parseRetryAfterSeconds(" 12 "))
        assertNull(parseRetryAfterSeconds("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(parseRetryAfterSeconds("-3"))
        assertNull(parseRetryAfterSeconds(null))
    }

    @Test
    fun theHostIsTheOneBetweenTheSchemeAndThePath() {
        assertEquals("aiometadata.elfhosted.com", hostOf("https://AIOMetadata.ElfHosted.com/abc/meta/series/tt1.json?x=1"))
        assertEquals("host:8080", hostOf("http://user:pw@host:8080/p"))
        assertEquals("example.com", hostOf("https://example.com"))
    }

    @Test
    fun aHostThatSaidSlowDownIsLeftAloneForWhatItAskedOrAGrowingDefault() {
        val clock = FakeClock()
        val c = cooldowns(clock)
        assertEquals(0L, c.waitMs("a"))
        c.rateLimited("a", null)
        assertEquals(5_000L, c.waitMs("a"))
        clock.now += 2_000L
        assertEquals(3_000L, c.waitMs("a"))
        // Requests that fail together do not restart the pause.
        c.rateLimited("a", null)
        assertEquals(3_000L, c.waitMs("a"))
        clock.now += 3_000L
        c.rateLimited("a", null)
        assertEquals(10_000L, c.waitMs("a"))
        // What the host asked beats the default, within limits.
        c.rateLimited("b", 3L)
        assertEquals(3_000L, c.waitMs("b"))
        c.rateLimited("c", 999L)
        assertEquals(HostCooldowns.MAX_WAIT_MS, c.waitMs("c"))
        c.rateLimited("d", 0L)
        assertEquals(500L, c.waitMs("d"))
    }

    @Test
    fun aSuccessAfterThePauseStartsTheDoublingOver() {
        val clock = FakeClock()
        val c = cooldowns(clock)
        c.rateLimited("a", null)
        clock.now += 5_000L
        c.rateLimited("a", null)
        assertEquals(10_000L, c.waitMs("a"))
        clock.now += 10_000L
        c.succeeded("a")
        c.rateLimited("a", null)
        assertEquals(5_000L, c.waitMs("a"))
    }

    @Test
    fun slowModeLastsAMinuteAfterTheLastTooManyRequests() {
        val clock = FakeClock()
        val c = cooldowns(clock)
        assertFalse(c.slowMode("a"))
        c.rateLimited("a", null)
        assertTrue(c.slowMode("a"))
        clock.now += HostCooldowns.SLOW_MODE_MS - 1
        assertTrue(c.slowMode("a"))
        clock.now += 2
        assertFalse(c.slowMode("a"))
        assertFalse(c.slowMode("other"))
    }

    @Test
    fun aRequestToldToWaitWaitsAndIsThenSentAgain() = runBlocking<Unit> {
        val clock = FakeClock()
        val slept = ArrayList<Long>()
        var calls = 0
        val result = gate(clock, slept).run("https://h.example/meta/x.json") {
            calls++
            if (calls < 3) throw tooMany() else "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
        // The first 429 rests the host 5 s; asked again at once, the second doubles it.
        assertEquals(listOf(5_000L, 10_000L), slept)
    }

    @Test
    fun otherFailuresAreNotRetriedAndAStubbornTooManyRequestsIsThrownAsItCame() = runBlocking<Unit> {
        val clock = FakeClock()
        val slept = ArrayList<Long>()
        var calls = 0
        val notFound = assertFailsWith<HttpStatusException> {
            gate(clock, slept).run("https://h.example/x") { calls++; throw HttpStatusException(404, null, "Request failed with HTTP 404") }
        }
        assertEquals(404, notFound.status)
        assertEquals(1, calls)
        assertTrue(slept.isEmpty())

        calls = 0
        val stubborn = assertFailsWith<HttpStatusException> {
            gate(clock, slept, attempts = 3).run("https://h.example/x") { calls++; throw tooMany() }
        }
        assertEquals(429, stubborn.status)
        assertEquals(3, calls)
        assertEquals(2, slept.size)
    }

    @Test
    fun aPauseAlreadyRunningIsWaitedBeforeTheFirstRequestButNoLongerThanTheLimit() = runBlocking<Unit> {
        val clock = FakeClock()
        val slept = ArrayList<Long>()
        val g = RateLimitGate(cooldowns(clock).also { it.rateLimited("h.example", 30L) }, { ms -> slept += ms; clock.now += ms }, 3, 10_000L)
        assertEquals("ok", g.run("https://h.example/a") { "ok" })
        assertEquals(10_000L, slept.first())
    }

    @Test
    fun aHostThatNeverAnswersTooManyRequestsIsNeverDelayedAndOthersAreNotAffected() = runBlocking<Unit> {
        val clock = FakeClock()
        val slept = ArrayList<Long>()
        val g = gate(clock, slept)
        assertEquals(1, g.run("https://quiet.example/a") { 1 })
        assertTrue(slept.isEmpty())
        // One host is limited; another is not held up by it.
        assertFailsWith<HttpStatusException> { g.run("https://busy.example/a", attemptsFail()) }
        slept.clear()
        assertEquals(2, g.run("https://quiet.example/b") { 2 })
        assertTrue(slept.isEmpty())
    }

    private fun attemptsFail(): suspend () -> Int = { throw tooMany() }
}
