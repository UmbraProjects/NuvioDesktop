package com.nuvio.app.features.player

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
    fun `next episode does not promote binge group when reuse is disabled`() {
        assertFalse(
            shouldReuseNextEpisodeBingeGroup(
                sourceAffinity = PlayerSourceAffinity.Stream,
                reuseEnabled = false,
                preferenceEnabled = true,
            ),
        )
        assertTrue(
            shouldReuseNextEpisodeBingeGroup(
                sourceAffinity = PlayerSourceAffinity.Stream,
                reuseEnabled = true,
                preferenceEnabled = true,
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
}
