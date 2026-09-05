package com.nuvio.app.features.streams

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The guards are the feature. Every request a prefetch makes is speculative, so the interesting
 * behaviour is all in what stops one — and each of these fences exists because of a specific way
 * background scraping can hurt a user.
 */
class StreamPrefetchGuardsTest {

    private fun inputs(
        trigger: StreamPrefetchService.Trigger = StreamPrefetchService.Trigger.Details,
        scope: StreamPrefetchScope = StreamPrefetchScope.DETAILS_AND_CONTINUE_WATCHING,
        playbackActive: Boolean = false,
        withinCooldown: Boolean = false,
        interactiveFetchRunning: Boolean = false,
        hasInstantSourceAlready: Boolean = false,
    ) = PrefetchGateInputs(
        trigger = trigger,
        scope = scope,
        playbackActive = playbackActive,
        withinCooldown = withinCooldown,
        interactiveFetchRunning = interactiveFetchRunning,
        hasInstantSourceAlready = hasInstantSourceAlready,
    )

    @Test
    fun `a clean slate proceeds`() {
        assertEquals(PrefetchDecision.Proceed, evaluatePrefetchGate(inputs()))
    }

    @Test
    fun `the default scope searches nothing`() {
        assertEquals(
            PrefetchDecision.ScopeDisabled,
            evaluatePrefetchGate(inputs(scope = StreamPrefetchScope.OFF)),
        )
    }

    @Test
    fun `details-only scope refuses a continue watching trigger but allows a details one`() {
        assertEquals(
            PrefetchDecision.TriggerOutOfScope,
            evaluatePrefetchGate(
                inputs(
                    trigger = StreamPrefetchService.Trigger.ContinueWatching,
                    scope = StreamPrefetchScope.DETAILS,
                ),
            ),
        )
        assertEquals(
            PrefetchDecision.Proceed,
            evaluatePrefetchGate(
                inputs(
                    trigger = StreamPrefetchService.Trigger.Details,
                    scope = StreamPrefetchScope.DETAILS,
                ),
            ),
        )
    }

    @Test
    fun `nothing is searched while the player has the screen`() {
        assertEquals(
            PrefetchDecision.PlaybackActive,
            evaluatePrefetchGate(inputs(playbackActive = true)),
        )
    }

    @Test
    fun `a search the user actually asked for takes precedence`() {
        assertEquals(
            PrefetchDecision.InteractiveFetchRunning,
            evaluatePrefetchGate(inputs(interactiveFetchRunning = true)),
        )
    }

    @Test
    fun `a target already backed by an instant source is not searched`() {
        assertEquals(
            PrefetchDecision.AlreadyInstant,
            evaluatePrefetchGate(inputs(hasInstantSourceAlready = true)),
        )
    }

    @Test
    fun `a recently attempted target is not searched again`() {
        assertEquals(
            PrefetchDecision.CoolingDown,
            evaluatePrefetchGate(inputs(withinCooldown = true)),
        )
    }

    @Test
    fun `opting out outranks every transient condition`() {
        // If the user turned this off, no combination of screen state should produce a request.
        assertEquals(
            PrefetchDecision.ScopeDisabled,
            evaluatePrefetchGate(
                inputs(
                    scope = StreamPrefetchScope.OFF,
                    playbackActive = true,
                    withinCooldown = true,
                    interactiveFetchRunning = true,
                    hasInstantSourceAlready = true,
                ),
            ),
        )
    }

    // --- cooldown -----------------------------------------------------------------------------

    private val retentionMs = 10L * 60L * 1000L

    @BeforeTest
    fun setUp() = StreamPrefetchService.reset()

    @AfterTest
    fun tearDown() = StreamPrefetchService.reset()

    @Test
    fun `an untouched target is not cooling down`() {
        assertFalse(StreamPrefetchService.isWithinCooldown("movie::tt1::null::null", 1_000L, retentionMs))
    }

    @Test
    fun `an attempt cools the target down for the retention window and no longer`() {
        val key = "movie::tt1::null::null"
        StreamPrefetchService.recordAttempt(key, 1_000L)

        assertTrue(StreamPrefetchService.isWithinCooldown(key, 1_000L + retentionMs - 1L, retentionMs))
        assertFalse(StreamPrefetchService.isWithinCooldown(key, 1_000L + retentionMs + 1L, retentionMs))
    }

    @Test
    fun `a failed sweep still cools its target down`() {
        // Nothing is cached when a sweep finds nothing, so without the attempt record every dwell on
        // the same card would re-run the same fruitless search.
        val key = "movie::tt-nothing-found::null::null"
        StreamPrefetchService.recordAttempt(key, 1_000L)

        assertTrue(StreamPrefetchService.isWithinCooldown(key, 2_000L, retentionMs))
    }

    @Test
    fun `cooling one target down does not cool another`() {
        StreamPrefetchService.recordAttempt("movie::tt1::null::null", 1_000L)

        assertFalse(StreamPrefetchService.isWithinCooldown("movie::tt2::null::null", 1_000L, retentionMs))
    }

    // --- sweep budget -------------------------------------------------------------------------

    @Test
    fun `the sweep budget admits its quota then refuses`() {
        repeat(4) { assertTrue(StreamPrefetchService.consumeSweepBudget(1_000L)) }

        assertFalse(StreamPrefetchService.consumeSweepBudget(1_000L))
    }

    @Test
    fun `the budget window rolls forward`() {
        repeat(4) { StreamPrefetchService.consumeSweepBudget(1_000L) }
        assertFalse(StreamPrefetchService.consumeSweepBudget(1_000L))

        val pastWindow = 1_000L + 10L * 60L * 1000L + 1L
        assertTrue(StreamPrefetchService.consumeSweepBudget(pastWindow))
    }

    @Test
    fun `reset clears budget and cooldown together`() {
        repeat(4) { StreamPrefetchService.consumeSweepBudget(1_000L) }
        StreamPrefetchService.recordAttempt("movie::tt1::null::null", 1_000L)

        StreamPrefetchService.reset()

        assertTrue(StreamPrefetchService.consumeSweepBudget(1_000L))
        assertFalse(StreamPrefetchService.isWithinCooldown("movie::tt1::null::null", 1_000L, retentionMs))
    }

    // --- provider id translation ---------------------------------------------------------------

    @Test
    fun `scraper groups are re-keyed to the id the interactive paths look up`() {
        // StreamSearchService labels a scraper group `plugin:<id>`; the readers ask the cache under
        // `plugin-scraper:<id>` because their own grouping depends on a display setting. A mismatch
        // here would store every scraper result under a key nothing ever reads.
        with(StreamPrefetchService) {
            assertEquals("plugin-scraper:repo.json:acme", "plugin:repo.json:acme".toCacheProviderId())
        }
    }

    @Test
    fun `addon ids are stored unchanged`() {
        val addonId = "addon:com.example:https://example.com/manifest.json"

        with(StreamPrefetchService) {
            assertEquals(addonId, addonId.toCacheProviderId())
        }
    }
}
