package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaceholderSourcesTest {
    private val comet = stream("comet", "Comet | TB")
    private val usenet = stream("usenet:built-in", "Built-in Usenet")

    @AfterTest
    fun reset() {
        ProviderProbeGate.clearForTest()
    }

    @Test
    fun `an addon that gave a placeholder goes last for the rest of the binge`() {
        val memory = PlaceholderSourceMemory()
        memory.record(comet)

        assertEquals(listOf(usenet), memory.prefer(listOf(comet, usenet)))
        // Another release from the same addon is skipped too.
        assertTrue(memory.isMarked(stream("comet", "Comet | TB", "S01E05")))
    }

    @Test
    fun `a new player session starts with nothing marked`() {
        PlaceholderSourceMemory().record(comet)

        assertEquals(listOf(comet, usenet), PlaceholderSourceMemory().prefer(listOf(comet, usenet)))
    }

    @Test
    fun `marked addons are still used when nothing else is left, once every answer is in`() {
        val memory = PlaceholderSourceMemory()
        memory.record(comet)

        assertEquals(listOf(comet), memory.prefer(listOf(comet)))
        assertEquals(emptyList(), memory.prefer(listOf(comet), keepMarkedIfOnly = false))
    }

    @Test
    fun `autoplay only avoids marked addons when it is given the memory`() {
        val memory = PlaceholderSourceMemory()
        memory.record(comet)

        assertEquals(listOf(usenet), eligibleAutoPlayStreams(listOf(comet, usenet), PlayerSourceAffinity.Stream, avoid = memory))
        assertEquals(listOf(comet, usenet), eligibleAutoPlayStreams(listOf(comet, usenet), PlayerSourceAffinity.Stream))
    }

    @Test
    fun `failover does not use the memory`() {
        val next = nextFailoverStream(
            streams = listOf(comet, usenet),
            activeIdentityKey = null,
            triedIdentityKeys = emptySet(),
        )
        assertEquals(comet, next)
    }

    @Test
    fun `preload starts with a tenth of the episode left, at least two minutes`() {
        val min = 60_000L
        // 22-minute episode: 10% is 2.2 min.
        assertEquals(132_000L, nextEpisodePreloadWindowMs(22 * min, cacheMinutes = 10))
        // 15-minute episode: the two-minute floor.
        assertEquals(2 * min, nextEpisodePreloadWindowMs(15 * min, cacheMinutes = 10))
        // 60-minute episode: 6 min.
        assertEquals(6 * min, nextEpisodePreloadWindowMs(60 * min, cacheMinutes = 10))
        // 2-hour special: capped a minute short of the 10-minute cache.
        assertEquals(9 * min, nextEpisodePreloadWindowMs(120 * min, cacheMinutes = 10))
        assertFalse(isNextEpisodePreloadDue(positionMs = 19 * min, durationMs = 22 * min, cacheMinutes = 10))
        assertTrue(isNextEpisodePreloadDue(positionMs = 20 * min, durationMs = 22 * min, cacheMinutes = 10))
        assertFalse(isNextEpisodePreloadDue(positionMs = 0, durationMs = 0, cacheMinutes = 10))
    }

    @Test
    fun `a link nobody is probing attaches straight away`() = runTest {
        assertTrue(ProviderProbeGate.awaitAttach("https://comet/playback/a"))
    }

    @Test
    fun `the attach waits for the probe and skips a placeholder once`() = runTest {
        val url = "https://comet/playback/a"
        ProviderProbeGate.pending(url)
        val attach = async { ProviderProbeGate.awaitAttach(url) }
        runCurrent()
        assertFalse(attach.isCompleted)

        ProviderProbeGate.skip(url)
        runCurrent()
        assertFalse(attach.await())
        // Used once: replaying the same link later opens it.
        assertTrue(ProviderProbeGate.awaitAttach(url))
    }

    @Test
    fun `a real link attaches when the probe clears it`() = runTest {
        val url = "https://comet/playback/b"
        ProviderProbeGate.pending(url)
        val attach = async { ProviderProbeGate.awaitAttach(url) }
        runCurrent()
        ProviderProbeGate.attach(url)
        runCurrent()
        assertTrue(attach.await())
    }

    @Test
    fun `a slow probe does not hold the attach forever`() = runTest {
        val url = "https://comet/playback/c"
        ProviderProbeGate.pending(url)
        val attach = async { ProviderProbeGate.awaitAttach(url, timeoutMs = 4_000L) }
        advanceTimeBy(4_001L)
        runCurrent()
        assertTrue(attach.await())
    }

    @Test
    fun `a cancelled probe releases the wait`() = runTest {
        val url = "https://comet/playback/d"
        ProviderProbeGate.pending(url)
        val attach = async { ProviderProbeGate.awaitAttach(url) }
        runCurrent()
        ProviderProbeGate.abandon(url)
        runCurrent()
        assertTrue(attach.await())
    }

    private fun stream(addonId: String, addonName: String, id: String = "S01E04") = StreamItem(
        name = "$addonName $id",
        url = "https://example.com/$addonId/$id",
        addonName = addonName,
        addonId = addonId,
    )
}
