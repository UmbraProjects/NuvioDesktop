package com.nuvio.app.features.debrid

import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamDebridCacheStatus
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class DebridStreamPresentationTest {
    @Test
    fun `formats cached addon torrent streams with custom templates`() {
        val stream = localTorboxStream(
            filename = "Lost.S01E01.2160p.WEB-DL.H265.AAC-NAKSU.mkv",
            size = 8_589_934_592,
        )

        val formatted = DebridStreamFormatter().format(
            stream = stream,
            settings = DebridSettings(
                enabled = true,
                torboxApiKey = "key",
                streamNameTemplate = "{stream.resolution} {service.shortName} {service.cached::istrue[\"Ready\"||\"Not Ready\"]}",
                streamDescriptionTemplate = "{stream.quality} {stream.encode}\n{stream.size::bytes}\n{stream.filename}",
            ),
        )

        assertEquals("2160p TB Ready", formatted.name)
        val description = formatted.description.orEmpty()
        assertContains(description, "WEB-DL HEVC")
        assertContains(description, "8 GB")
        assertContains(description, "Lost.S01E01.2160p.WEB-DL.H265.AAC-NAKSU.mkv")
    }

    @Test
    fun `applies debrid sort filters and limits without removing normal urls`() {
        val low = localTorboxStream(
            name = "Low",
            filename = "Movie.720p.BluRay.x264-GRP.mkv",
            size = 4_000_000_000,
        )
        val large = localTorboxStream(
            name = "Large",
            filename = "Movie.2160p.BluRay.REMUX.HEVC-GRP.mkv",
            size = 40_000_000_000,
        )
        val mid = localTorboxStream(
            name = "Mid",
            filename = "Movie.1080p.WEB-DL.HEVC-GRP.mkv",
            size = 10_000_000_000,
        )
        val urlStream = StreamItem(
            name = "Resolved addon URL",
            url = "https://example.test/video.m3u8",
            addonName = "Addon",
            addonId = "addon:test",
        )

        val group = AddonStreamGroup(
            addonName = "Addon",
            addonId = "addon:test",
            streams = listOf(low, large, mid, urlStream),
        )
        val presented = DebridStreamPresentation.apply(
            groups = listOf(group),
            settings = DebridSettings(
                enabled = true,
                torboxApiKey = "key",
                streamMaxResults = 2,
                streamSortMode = DebridStreamSortMode.QUALITY_DESC,
                streamMinimumQuality = DebridStreamMinimumQuality.P1080,
                streamCodecFilter = DebridStreamCodecFilter.HEVC,
            ),
        ).single().streams

        assertEquals(listOf("4K TB Ready", "FHD TB Ready", "Resolved addon URL"), presented.map { it.name })
    }

    private fun localTorboxStream(
        name: String = "Torrent",
        filename: String,
        size: Long,
    ): StreamItem =
        StreamItem(
            name = name,
            infoHash = "abcdef1234567890abcdef1234567890abcdef12$size".take(40),
            addonName = "Addon",
            addonId = "addon:test",
            behaviorHints = StreamBehaviorHints(
                filename = filename,
                videoSize = size,
            ),
            debridCacheStatus = StreamDebridCacheStatus(
                providerId = DebridProviders.TORBOX_ID,
                providerName = DebridProviders.Torbox.displayName,
                state = StreamDebridCacheState.CACHED,
                cachedName = filename,
                cachedSize = size,
            ),
        )
}
