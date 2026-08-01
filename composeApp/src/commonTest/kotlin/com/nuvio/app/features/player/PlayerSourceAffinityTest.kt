package com.nuvio.app.features.player

import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamAutoPlaySource
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerSourceAffinityTest {

    @Test
    fun `stream affinity excludes local files from autoplay candidates`() {
        val local = stream("local")
        val remote = stream("url")

        assertEquals(
            listOf(remote),
            eligibleAutoPlayStreams(listOf(local, remote), PlayerSourceAffinity.Stream),
        )
    }

    @Test
    fun `local affinity retains local files and remote fallback candidates`() {
        val local = stream("local")
        val remote = stream("url")

        assertEquals(
            listOf(local, remote),
            eligibleAutoPlayStreams(listOf(local, remote), PlayerSourceAffinity.Local),
        )
    }

    @Test
    fun `initial local selection creates local affinity`() {
        assertEquals(PlayerSourceAffinity.Local, PlayerSourceAffinity.fromInitialStreamType("LOCAL"))
        assertEquals(PlayerSourceAffinity.Stream, PlayerSourceAffinity.fromInitialStreamType("url"))
    }

    @Test
    fun `next episode prefers binge group on the prefer toggle alone`() {
        // Prefer on → promote, regardless of the separate cross-session Reuse toggle.
        assertTrue(
            shouldPreferNextEpisodeBingeGroup(
                sourceAffinity = PlayerSourceAffinity.Stream,
                preferenceEnabled = true,
            ),
        )
        // Prefer off → never promote.
        assertFalse(
            shouldPreferNextEpisodeBingeGroup(
                sourceAffinity = PlayerSourceAffinity.Stream,
                preferenceEnabled = false,
            ),
        )
        // Local affinity never promotes a binge group even with Prefer on.
        assertFalse(
            shouldPreferNextEpisodeBingeGroup(
                sourceAffinity = PlayerSourceAffinity.Local,
                preferenceEnabled = true,
            ),
        )
    }

    @Test
    fun `score override beats the binge group preference for the next episode`() {
        // Reported case: the profile scored the playing source 290 and another 1076, and the binge
        // group promoted the 290 anyway. With the override on, binge affinity stops being consulted.
        assertFalse(
            shouldPreferNextEpisodeBingeGroup(
                sourceAffinity = PlayerSourceAffinity.Stream,
                preferenceEnabled = true,
                scoreOverridesBingeGroup = true,
            ),
        )
        // Override off leaves the existing behaviour exactly as it was.
        assertTrue(
            shouldPreferNextEpisodeBingeGroup(
                sourceAffinity = PlayerSourceAffinity.Stream,
                preferenceEnabled = true,
                scoreOverridesBingeGroup = false,
            ),
        )
    }

    @Test
    fun `empty next episode search retries only while retry budget remains`() {
        assertTrue(shouldRetryEmptyNextEpisodeSearch(selectedStream = null, retriesRemaining = 1))
        assertFalse(shouldRetryEmptyNextEpisodeSearch(selectedStream = null, retriesRemaining = 0))
        assertFalse(shouldRetryEmptyNextEpisodeSearch(selectedStream = stream("url"), retriesRemaining = 1))
    }

    @Test
    fun `scoped autoplay waits only on providers it could select from`() {
        val installed = setOf("TVFlix", "Comet")
        val scopedToTvflix = setOf("TVFlix")

        // The scoped addon still loading is the one case worth waiting for.
        assertTrue(
            hasScopedAutoPlayProviderInFlight(
                groups = listOf(group("TVFlix", isLoading = true), group("Comet", isLoading = false)),
                installedAddonNames = installed,
                source = StreamAutoPlaySource.ALL_SOURCES,
                selectedAddons = scopedToTvflix,
                selectedPlugins = emptySet(),
            ),
        )
        // An unscoped provider in flight can never produce a selectable stream, so it is not a
        // reason to keep the search alive — this is what made S9E2 wait out the full window.
        assertFalse(
            hasScopedAutoPlayProviderInFlight(
                groups = listOf(group("TVFlix", isLoading = false), group("Comet", isLoading = true)),
                installedAddonNames = installed,
                source = StreamAutoPlaySource.ALL_SOURCES,
                selectedAddons = scopedToTvflix,
                selectedPlugins = emptySet(),
            ),
        )
    }

    @Test
    fun `unscoped autoplay waits on any loading provider`() {
        assertTrue(
            hasScopedAutoPlayProviderInFlight(
                groups = listOf(group("Comet", isLoading = true)),
                installedAddonNames = setOf("TVFlix", "Comet"),
                source = StreamAutoPlaySource.ALL_SOURCES,
                selectedAddons = emptySet(),
                selectedPlugins = emptySet(),
            ),
        )
    }

    @Test
    fun `source scoping excludes the other provider kind`() {
        val installed = setOf("TVFlix")
        // A plugin (not an installed addon) is out of scope under INSTALLED_ADDONS_ONLY.
        assertFalse(
            hasScopedAutoPlayProviderInFlight(
                groups = listOf(group("SomePlugin", isLoading = true)),
                installedAddonNames = installed,
                source = StreamAutoPlaySource.INSTALLED_ADDONS_ONLY,
                selectedAddons = emptySet(),
                selectedPlugins = emptySet(),
            ),
        )
        assertTrue(
            hasScopedAutoPlayProviderInFlight(
                groups = listOf(group("SomePlugin", isLoading = true)),
                installedAddonNames = installed,
                source = StreamAutoPlaySource.ENABLED_PLUGINS_ONLY,
                selectedAddons = emptySet(),
                selectedPlugins = emptySet(),
            ),
        )
    }

    private fun stream(type: String) = StreamItem(
        name = type,
        url = "https://example.com/$type",
        addonName = type,
        addonId = type,
        streamType = type,
    )

    private fun group(addonName: String, isLoading: Boolean) = AddonStreamGroup(
        addonName = addonName,
        addonId = addonName,
        streams = emptyList(),
        isLoading = isLoading,
    )
}
