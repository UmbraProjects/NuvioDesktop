package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerStreamFailoverTest {

    @Test
    fun `failover chooses the highest ranked untried source`() {
        val first = stream("first")
        val active = stream("active")
        val next = stream("next")

        assertEquals(
            first,
            nextFailoverStream(
                streams = listOf(first, active, next),
                activeIdentityKey = active.playerSourceIdentityKey(),
                triedIdentityKeys = setOfNotNull(active.playerSourceIdentityKey()),
            ),
        )
    }

    @Test
    fun `failover skips tried and unplayable sources`() {
        val active = stream("active")
        val alreadyTried = stream("tried")
        val unplayable = StreamItem(addonName = "empty", addonId = "empty")
        val fallback = stream("fallback")

        assertEquals(
            fallback,
            nextFailoverStream(
                streams = listOf(active, alreadyTried, unplayable, fallback),
                activeIdentityKey = active.playerSourceIdentityKey(),
                triedIdentityKeys = setOfNotNull(
                    active.playerSourceIdentityKey(),
                    alreadyTried.playerSourceIdentityKey(),
                ),
            ),
        )
    }

    @Test
    fun `failover considers sources ranked before the active source`() {
        val earlier = stream("earlier")
        val active = stream("active")

        assertEquals(
            earlier,
            nextFailoverStream(
                streams = listOf(earlier, active),
                activeIdentityKey = active.playerSourceIdentityKey(),
                triedIdentityKeys = setOfNotNull(active.playerSourceIdentityKey()),
            ),
        )
    }

    @Test
    fun `playing badge follows identity when playback url has been resolved`() {
        val selected = stream("selected")

        assertTrue(
            isCurrentStream(
                stream = selected,
                currentIdentityKey = selected.playerSourceIdentityKey(),
                currentUrl = "https://resolved.example/video",
                currentName = selected.streamLabel,
            ),
        )
    }

    @Test
    fun `playing badge does not fall back to stale clicked url after failover`() {
        val failed = stream("failed")
        val playing = stream("playing")

        assertFalse(
            isCurrentStream(
                stream = failed,
                currentIdentityKey = playing.playerSourceIdentityKey(),
                currentUrl = failed.playableDirectUrl,
                currentName = failed.streamLabel,
            ),
        )
        assertTrue(
            isCurrentStream(
                stream = playing,
                currentIdentityKey = playing.playerSourceIdentityKey(),
                currentUrl = failed.playableDirectUrl,
                currentName = failed.streamLabel,
            ),
        )
    }

    @Test
    fun `active source is moved first while other source order remains stable`() {
        val sources = listOf("first", "second", "active", "fourth")

        assertEquals(
            listOf("active", "first", "second", "fourth"),
            prioritizeCurrentItem(sources) { it == "active" },
        )
    }

    @Test
    fun `source order is unchanged when there is no active match`() {
        val sources = listOf("first", "second")

        assertEquals(sources, prioritizeCurrentItem(sources) { it == "missing" })
    }

    @Test
    fun `failover resume ignores a synthetic EOF snapshot`() {
        assertEquals(
            1_026_776L,
            selectFailoverResumePositionMs(
                lastTrustedPositionMs = 1_026_776L,
                initialPositionMs = 1_026_776L,
                snapshotPositionMs = 1_365_948L,
            ),
        )
    }

    @Test
    fun `early failover falls back to the requested initial resume`() {
        assertEquals(
            1_026_776L,
            selectFailoverResumePositionMs(
                lastTrustedPositionMs = 0L,
                initialPositionMs = 1_026_776L,
                snapshotPositionMs = 1_365_948L,
            ),
        )
    }

    private fun stream(id: String) = StreamItem(
        name = id,
        url = "https://example.com/$id",
        addonName = id,
        addonId = id,
    )
}
