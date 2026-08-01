package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderDiagnosticVideoTest {

    @Test
    fun `provider diagnostic video is skipped only while failover is enabled`() {
        assertTrue(shouldSkipProviderDiagnosticVideo(streamFailoverEnabled = true))
        assertFalse(shouldSkipProviderDiagnosticVideo(streamFailoverEnabled = false))
    }

    @Test
    fun `two minute provider clip cannot satisfy a later episode resume`() {
        assertTrue(
            isLikelyProviderWaitVideo(
                durationMs = 120_000L,
                requestedResumePositionMs = 293_502L,
                isSeries = true,
            ),
        )
        assertFalse(
            isLikelyProviderWaitVideo(
                durationMs = 120_000L,
                requestedResumePositionMs = 60_000L,
                isSeries = true,
            ),
        )
        assertFalse(
            isLikelyProviderWaitVideo(
                durationMs = 120_000L,
                requestedResumePositionMs = 293_502L,
                isSeries = false,
            ),
        )
    }

    @Test
    fun recognisesDiagnosticMessageQueryWithoutDependingOnAddonDomain() {
        assertTrue(
            isExplicitProviderDiagnosticVideoUrl(
                "https://addon.example/cache/result/seg.ts?title=Too+many+requests&body=Try+again",
            ),
        )
    }

    @Test
    fun recognisesDiagnosticPath() {
        assertTrue(isExplicitProviderDiagnosticVideoUrl("https://addon.example/status.mp4"))
        assertTrue(isExplicitProviderDiagnosticVideoUrl("https://addon.example/error/unavailable.webm"))
        assertTrue(
            isExplicitProviderDiagnosticVideoUrl(
                "https://stremthru.example/v0/store/_/static/429.mp4",
            ),
        )
    }

    @Test
    fun doesNotClassifyOrdinaryMediaAsExplicitDiagnosticVideo() {
        assertFalse(
            isExplicitProviderDiagnosticVideoUrl(
                "https://cdn.example/movies/title.mp4?name=Feature&quality=1080p",
            ),
        )
    }

    @Test
    fun recognisesProviderPlaybackEndpointSignature() {
        assertTrue(
            isProviderPlaybackEndpoint(
                "https://addon.example/playback/hash/0?torrent_name=Title.mkv&name=Title&media_id=tt1",
            ),
        )
        assertFalse(isProviderPlaybackEndpoint("https://cdn.example/playback/title.mp4"))
    }

    @Test
    fun recognisesStremThruPlaybackEndpointSignatures() {
        assertTrue(
            isProviderPlaybackEndpoint(
                "https://stremthru.example/stremio/wrap/user/_/strem/hash/0/title.mkv",
            ),
        )
        assertTrue(
            isProviderPlaybackEndpoint(
                "https://stremthru.example/stremio/torz/user/_/strem/id/rd/hash/0/title.mkv",
            ),
        )
        assertTrue(
            isProviderPlaybackEndpoint(
                "https://stremthru.example/stremio/newz/user/playback/id/store/rd/nzb/title.mkv",
            ),
        )
    }
}
