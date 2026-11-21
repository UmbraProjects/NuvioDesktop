package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamAddonData
import com.nuvio.app.features.streams.StreamScoreContext
import com.nuvio.app.features.streams.StreamScoreProfile
import com.nuvio.app.features.streams.StreamScoreTrait
import com.nuvio.app.features.streams.StreamSizeBand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun `failover never selects an uncached debrid source`() {
        val active = stream("active")
        val uncached = stream("uncached").copy(
            streamData = StreamAddonData(
                type = "debrid",
                serviceId = "torbox",
                serviceCached = false,
            ),
        )

        assertNull(
            nextFailoverStream(
                streams = listOf(active, uncached),
                activeIdentityKey = active.playerSourceIdentityKey(),
                triedIdentityKeys = setOfNotNull(active.playerSourceIdentityKey()),
            ),
        )
    }

    @Test
    fun `failover selects a confirmed cached debrid source instead of an uncached one`() {
        val active = stream("active")
        val uncached = stream("uncached").copy(
            streamData = StreamAddonData(
                type = "debrid",
                serviceId = "torbox",
                serviceCached = false,
            ),
        )
        val cached = stream("cached").copy(
            streamData = StreamAddonData(
                type = "debrid",
                serviceId = "torbox",
                serviceCached = true,
            ),
        )

        assertEquals(
            cached,
            nextFailoverStream(
                streams = listOf(active, uncached, cached),
                activeIdentityKey = active.playerSourceIdentityKey(),
                triedIdentityKeys = setOfNotNull(active.playerSourceIdentityKey()),
            ),
        )
    }

    @Test
    fun `an original source may keep loading when no safe fallback exists`() {
        assertFalse(
            isAutomaticFailoverReplacement(
                sourceIdentityKey = "original",
                triedIdentityKeys = emptySet(),
            ),
        )
    }

    @Test
    fun `a timed out failover replacement is bounded even when addon claimed it was cached`() {
        assertTrue(
            isAutomaticFailoverReplacement(
                sourceIdentityKey = "addon-reported-cached",
                triedIdentityKeys = setOf("original", "addon-reported-cached"),
            ),
        )
    }

    @Test
    fun `failover keeps direct usenet sources even when cache flag is false`() {
        val active = stream("active")
        val usenet = stream("usenet").copy(
            streamData = StreamAddonData(
                type = "usenet",
                serviceId = "nzbdav",
                serviceCached = false,
            ),
        )

        assertEquals(
            usenet,
            nextFailoverStream(
                streams = listOf(active, usenet),
                activeIdentityKey = active.playerSourceIdentityKey(),
                triedIdentityKeys = setOfNotNull(active.playerSourceIdentityKey()),
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
    fun `failover prefers requested audio language over higher ranked mismatch`() {
        val active = stream("active")
        val polish = stream("polish", audioLanguages = listOf("pl"))
        val english = stream("english", audioLanguages = listOf("eng"))

        assertEquals(
            english,
            nextFailoverStream(
                streams = listOf(active, polish, english),
                activeIdentityKey = active.playerSourceIdentityKey(),
                triedIdentityKeys = setOfNotNull(active.playerSourceIdentityKey()),
                preferredAudioLanguages = listOf("en"),
            ),
        )
    }

    @Test
    fun `failover uses secondary language before unknown and explicit mismatch`() {
        val active = stream("active")
        val polish = stream("polish", audioLanguages = listOf("Polish"))
        val unknown = stream("unknown")
        val japanese = stream("japanese", audioLanguages = listOf("ja"))

        assertEquals(
            japanese,
            nextFailoverStream(
                streams = listOf(active, polish, unknown, japanese),
                activeIdentityKey = active.playerSourceIdentityKey(),
                triedIdentityKeys = setOfNotNull(active.playerSourceIdentityKey()),
                preferredAudioLanguages = listOf("en", "ja"),
            ),
        )
    }

    @Test
    fun `scored failover does not let a language tag outrank an untagged better release`() {
        // Friends S01E01, 2026-09-14: after a TorBox 429 the 4K remuxes carried no language tag,
        // the 1080p HONE said "English" in its title, and the language tier handed it the win
        // over every 1500-point remux. With scoring driving failover, untagged and matched share
        // a tier and the score decides.
        val active = stream("active")
        val remux = scoredStream("Friends.S01E01.UHD.BluRay.2160p.DTS-HD.MA.5.1.DV.HEVC.REMUX-FraMeSToR", sizeGb = 8.0)
        val hone = scoredStream(
            "Friends.S01E01.1080p.AMZN.WEB-DL.H265.SDR.DDP.5.1.English-HONE",
            sizeGb = 1.2,
            audioLanguages = listOf("eng"),
        )
        val polish = scoredStream("Friends.S01E01.2160p.REMUX-PL", sizeGb = 8.0, audioLanguages = listOf("pl"))
        val scored = StreamScoreProfile(
            enabled = true,
            useForFailover = true,
            points = mapOf(
                StreamScoreTrait.QUALITY_2160P_REMUX.id to 1500,
                StreamScoreTrait.QUALITY_1080P_WEB_DL.id to 400,
            ),
        )

        assertEquals(
            remux,
            nextFailoverStream(
                streams = listOf(active, hone, remux, polish),
                activeIdentityKey = active.playerSourceIdentityKey(),
                triedIdentityKeys = setOfNotNull(active.playerSourceIdentityKey()),
                preferredAudioLanguages = listOf("en"),
                scoreProfile = scored,
                scoreContext = StreamScoreContext.EPISODE,
            ),
        )
        // An explicit mismatch still sits behind both, however well it scores.
        assertEquals(
            hone,
            nextFailoverStream(
                streams = listOf(active, hone, polish),
                activeIdentityKey = active.playerSourceIdentityKey(),
                triedIdentityKeys = setOfNotNull(active.playerSourceIdentityKey()),
                preferredAudioLanguages = listOf("en"),
                scoreProfile = scored,
                scoreContext = StreamScoreContext.EPISODE,
            ),
        )
    }

    @Test
    fun `failover keeps unknown language ahead of known mismatch`() {
        val active = stream("active")
        val polish = stream("polish", audioLanguages = listOf("pl"))
        val unknown = stream("unknown")

        assertEquals(
            unknown,
            nextFailoverStream(
                streams = listOf(active, polish, unknown),
                activeIdentityKey = active.playerSourceIdentityKey(),
                triedIdentityKeys = setOfNotNull(active.playerSourceIdentityKey()),
                preferredAudioLanguages = listOf("en"),
            ),
        )
    }

    @Test
    fun `playing badge follows identity when playback url has been resolved`() {
        val selected = stream("selected")
        val other = stream("other")

        assertEquals(
            selected,
            findCurrentStream(
                streams = listOf(other, selected),
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

        assertEquals(
            playing,
            findCurrentStream(
                streams = listOf(failed, playing),
                currentIdentityKey = playing.playerSourceIdentityKey(),
                currentUrl = failed.playableDirectUrl,
                currentName = failed.streamLabel,
            ),
        )
    }

    @Test
    fun `only one row is playing when two providers offer the same file`() {
        val fromA = stream("shared")
        val fromB = stream("shared")
        val streams = listOf(fromA, fromB)

        // Same file, same identity key — the old per-row predicate said yes to both.
        assertEquals(fromA.playerSourceIdentityKey(), fromB.playerSourceIdentityKey())

        val current = findCurrentStream(
            streams = streams,
            currentIdentityKey = fromA.playerSourceIdentityKey(),
            currentUrl = fromA.playableDirectUrl,
            currentName = fromA.streamLabel,
        )
        assertEquals(1, streams.count { it === current })
    }

    @Test
    fun `playing badge falls back to url when the identity key has drifted`() {
        val playing = stream("playing")
        val other = stream("other")

        assertEquals(
            playing,
            findCurrentStream(
                streams = listOf(other, playing),
                // A key minted before the list was re-fetched, matching nothing in it.
                currentIdentityKey = "file:gone:stale.mkv:1",
                currentUrl = playing.playableDirectUrl,
                currentName = playing.streamLabel,
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

    @Test
    fun `failover from a placeholder clip resumes where the source was asked to start`() {
        // The next episode's source turned out to be an 8 s error clip that had played to 7.4 s.
        assertEquals(
            0L,
            selectFailoverResumePositionMs(
                lastTrustedPositionMs = 7_389L,
                initialPositionMs = 0L,
                snapshotPositionMs = 7_944L,
                placeholderPlaying = true,
            ),
        )
        // A resumed episode keeps its resume point.
        assertEquals(
            1_026_776L,
            selectFailoverResumePositionMs(
                lastTrustedPositionMs = 7_389L,
                initialPositionMs = 1_026_776L,
                snapshotPositionMs = 7_944L,
                placeholderPlaying = true,
            ),
        )
    }

    @Test
    fun `failover does not select an external addon homepage`() {
        val active = stream("active")
        val homepage = StreamItem(
            name = "AIOStreams",
            externalUrl = "https://github.com/Viren070/AIOStreams",
            addonName = "AIOStreams",
            addonId = "aiostreams",
        )

        assertNull(
            nextFailoverStream(
                streams = listOf(active, homepage),
                activeIdentityKey = active.playerSourceIdentityKey(),
                triedIdentityKeys = setOfNotNull(active.playerSourceIdentityKey()),
            ),
        )
    }

    @Test
    fun `stale playback callbacks do not own the replacement attempt`() {
        assertFalse(isCurrentPlaybackAttempt(callbackAttemptId = 7L, activeAttemptId = 8L))
        assertTrue(isCurrentPlaybackAttempt(callbackAttemptId = 8L, activeAttemptId = 8L))
    }

    @Test
    fun `next episode latch requires matching video and first frame attempt`() {
        assertFalse(
            shouldReleaseNextEpisodeAdvanceLatch(
                advanceInProgress = true,
                targetVideoId = "series:1:7",
                activeVideoId = "series:1:7",
                activeAttemptId = 8L,
                startedAttemptId = 7L,
                isEnded = false,
                positionMs = 2_540_246L,
                minimumPositionMs = 3_000L,
            ),
        )
        assertFalse(
            shouldReleaseNextEpisodeAdvanceLatch(
                advanceInProgress = true,
                targetVideoId = "series:1:7",
                activeVideoId = "series:1:8",
                activeAttemptId = 8L,
                startedAttemptId = 8L,
                isEnded = false,
                positionMs = 3_253L,
                minimumPositionMs = 3_000L,
            ),
        )
        assertTrue(
            shouldReleaseNextEpisodeAdvanceLatch(
                advanceInProgress = true,
                targetVideoId = "series:1:7",
                activeVideoId = "series:1:7",
                activeAttemptId = 8L,
                startedAttemptId = 8L,
                isEnded = false,
                positionMs = 3_253L,
                minimumPositionMs = 3_000L,
            ),
        )
    }

    private fun scoredStream(
        name: String,
        sizeGb: Double,
        audioLanguages: List<String> = emptyList(),
    ) = StreamItem(
        name = name,
        url = "https://example.com/${name.hashCode()}.mkv",
        audioLanguages = audioLanguages,
        addonName = "addon",
        addonId = "addon:x",
        behaviorHints = StreamBehaviorHints(
            filename = name,
            videoSize = (sizeGb * StreamSizeBand.BYTES_PER_GB).toLong(),
        ),
    )

    private fun stream(
        id: String,
        audioLanguages: List<String> = emptyList(),
    ) = StreamItem(
        name = id,
        url = "https://example.com/$id",
        audioLanguages = audioLanguages,
        addonName = id,
        addonId = id,
    )
}
