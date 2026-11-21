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
        PlaceholderSourceMemory.clearForTest()
        ProviderProbeGate.clearForTest()
    }

    @Test
    fun `an addon that gave a placeholder goes last for that show only`() {
        PlaceholderSourceMemory.record("tt0121955", comet)

        assertEquals(listOf(usenet), PlaceholderSourceMemory.prefer("tt0121955", listOf(comet, usenet)))
        assertEquals(listOf(comet, usenet), PlaceholderSourceMemory.prefer("tt0000001", listOf(comet, usenet)))
        // Another release from the same addon is skipped too.
        assertTrue(PlaceholderSourceMemory.isMarked("tt0121955", stream("comet", "Comet | TB", "S01E05")))
    }

    @Test
    fun `marked addons are still used when nothing else is left, once every answer is in`() {
        PlaceholderSourceMemory.record("tt0121955", comet)

        assertEquals(listOf(comet), PlaceholderSourceMemory.prefer("tt0121955", listOf(comet)))
        assertEquals(emptyList(), PlaceholderSourceMemory.prefer("tt0121955", listOf(comet), keepMarkedIfOnly = false))
    }

    @Test
    fun `failover tries the marked addon last`() {
        PlaceholderSourceMemory.record("tt0121955", comet)
        val other = stream("comet", "Comet | TB", "other")

        val next = nextFailoverStream(
            streams = listOf(other, usenet),
            activeIdentityKey = null,
            triedIdentityKeys = emptySet(),
            placeholderTitleId = "tt0121955",
        )
        assertEquals(usenet, next)
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
