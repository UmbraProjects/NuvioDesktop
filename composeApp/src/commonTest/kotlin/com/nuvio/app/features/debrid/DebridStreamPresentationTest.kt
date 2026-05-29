package com.nuvio.app.features.debrid

import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamClientResolve
import com.nuvio.app.features.streams.StreamClientResolveParsed
import com.nuvio.app.features.streams.StreamClientResolveRaw
import com.nuvio.app.features.streams.StreamClientResolveStream
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamDebridCacheStatus
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
                providerApiKeys = mapOf(DebridProviders.TORBOX_ID to "key"),
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
    fun `blank templates preserve original stream name and description`() {
        val stream = localTorboxStream(
            name = "Original torrent",
            filename = "Movie.2026.1080p.WEB-DL.H265-GRP.mkv",
            size = 4_000_000_000,
        ).copy(description = "Original addon details")

        val formatted = DebridStreamFormatter().format(
            stream = stream,
            settings = DebridSettings(
                enabled = true,
                providerApiKeys = mapOf(DebridProviders.TORBOX_ID to "key"),
                streamNameTemplate = "",
                streamDescriptionTemplate = "",
            ),
        )

        assertEquals("Original torrent", formatted.name)
        assertEquals("Original addon details", formatted.description)
    }

    @Test
    fun `formats imported badge matches from fusion badge rules`() {
        val stream = localTorboxStream(
            filename = "Movie.2024.2160p.BluRay.REMUX.TrueHD.7.1-GRP.mkv",
            size = 40_000_000_000,
        )

        val formatted = DebridStreamFormatter().format(
            stream = stream,
            settings = DebridSettings(
                enabled = true,
                providerApiKeys = mapOf(DebridProviders.TORBOX_ID to "key"),
                streamNameTemplate = "{stream.rseMatched::join(' | ')}",
                streamDescriptionTemplate = "{stream.regexMatched::~REMUX[\"has-remux\"||\"missing-remux\"]}",
                streamBadgeRules = StreamBadgeRules(
                    imports = listOf(
                        StreamBadgeImport(
                            sourceUrl = "https://example.test/media-badges.json",
                            isActive = false,
                            filters = listOf(
                                StreamBadgeFilter(
                                    name = "REMUX",
                                    pattern = "(?i)\\bremux\\b",
                                    imageURL = "https://example.test/remux.png",
                                    tagColor = "#27C04F",
                                    tagStyle = "filled",
                                    textColor = "#FFFFFF",
                                    borderColor = "#27C04F",
                                ),
                                StreamBadgeFilter(
                                    name = "Disabled",
                                    pattern = "(?i)\\bbluray\\b",
                                    isEnabled = false,
                                ),
                            ),
                        ),
                        StreamBadgeImport(
                            sourceUrl = "https://example.test/audio-badges.json",
                            isActive = true,
                            filters = listOf(
                                StreamBadgeFilter(
                                    name = "TRUEHD",
                                    pattern = "(?i)\\btruehd\\b",
                                    imageURL = "https://example.test/truehd.png",
                                    tagColor = "#B968FF",
                                    tagStyle = "filled",
                                    textColor = "#FFFFFF",
                                    borderColor = "#B968FF",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals("TRUEHD", formatted.name)
        assertEquals("missing-remux", formatted.description)
        assertEquals(listOf("TRUEHD"), formatted.badges.map { it.name })
        assertEquals(listOf("https://example.test/truehd.png"), formatted.badges.map { it.imageURL })
    }

    @Test
    fun `parses fusion badge url payload shape`() {
        val importedRules = StreamBadgeRulesParser.parse(
            sourceUrl = "https://example.test/fusion-tags-ume.json",
            payload = """
                {
                  "filters": [
                    {
                      "borderColor": "#27C04F",
                      "groupId": "media",
                      "id": "remux",
                      "imageURL": "https://example.test/remux.png",
                      "isEnabled": true,
                      "name": "REMUX",
                      "pattern": "(?i)\\bremux\\b",
                      "tagColor": "#27C04F",
                      "tagStyle": "filled",
                      "textColor": "#FFFFFF",
                      "type": "filter"
                    }
                  ],
                  "groups": [
                    {
                      "color": "#96CEB4",
                      "id": "media",
                      "isExpanded": true,
                      "name": "Media Source"
                    }
                  ]
                }
            """.trimIndent(),
        )

        assertEquals("https://example.test/fusion-tags-ume.json", importedRules.sourceUrl)
        assertEquals(1, importedRules.filters.size)
        assertEquals("REMUX", importedRules.filters.single().name)
        assertEquals("(?i)\\bremux\\b", importedRules.filters.single().pattern)
        assertEquals("https://example.test/remux.png", importedRules.filters.single().imageURL)
        assertEquals("Media Source", importedRules.groups.single().name)
    }

    @Test
    fun `attaches imported badge urls to presented debrid streams`() {
        val stream = localTorboxStream(
            filename = "Movie.2024.2160p.BluRay.REMUX.TrueHD.7.1-GRP.mkv",
            size = 40_000_000_000,
        )

        val presented = DebridStreamPresentation.apply(
            groups = listOf(
                AddonStreamGroup(
                    addonName = "Addon",
                    addonId = "addon:test",
                    streams = listOf(stream),
                ),
            ),
            settings = DebridSettings(
                enabled = true,
                providerApiKeys = mapOf(DebridProviders.TORBOX_ID to "key"),
                streamBadgeRules = StreamBadgeRules(
                    imports = listOf(
                        StreamBadgeImport(
                            sourceUrl = "https://example.test/badges.json",
                            filters = listOf(
                                StreamBadgeFilter(
                                    name = "REMUX 1",
                                    pattern = "(?i)\\bremux\\b",
                                    imageURL = "https://example.test/remux-t1.png",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ).single().streams.single()

        assertEquals(listOf("REMUX 1"), presented.badges.map { it.name })
        assertEquals("https://example.test/remux-t1.png", presented.badges.single().imageURL)
    }

    @Test
    fun `default formatter replaces addon source labels for managed streams`() {
        val stream = premiumizeDirectStream(
            name = "[P2P] Torrentio 2160p - PM Instant",
            filename = "The.Boys.S03E01.Payback.2160p.WEB-DL.H265.mkv",
            size = 12_000_000_000,
        )

        val presented = DebridStreamPresentation.apply(
            groups = listOf(
                AddonStreamGroup(
                    addonName = "Torrentio",
                    addonId = "addon:torrentio",
                    streams = listOf(stream),
                ),
            ),
            settings = DebridSettings(
                enabled = true,
                providerApiKeys = mapOf(DebridProviders.PREMIUMIZE_ID to "pm_key"),
            ),
        ).single().streams.single()

        val name = presented.name.orEmpty()
        assertEquals("2160p PM Instant", name)
        assertFalse(name.contains("P2P", ignoreCase = true))
        assertFalse(name.contains("torrent", ignoreCase = true))
        assertFalse(name.contains("Torrentio", ignoreCase = true))
        assertFalse(name.contains("Comet", ignoreCase = true))
    }

    @Test
    fun `preserves original addon order by default`() {
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

        val presented = DebridStreamPresentation.apply(
            groups = listOf(
                AddonStreamGroup(
                    addonName = "Addon",
                    addonId = "addon:test",
                    streams = listOf(low, large, mid),
                ),
            ),
            settings = DebridSettings(
                enabled = true,
                providerApiKeys = mapOf(DebridProviders.TORBOX_ID to "key"),
            ),
        ).single().streams

        assertEquals(listOf("720p TB Instant", "2160p TB Instant", "1080p TB Instant"), presented.map { it.name })
    }

    @Test
    fun `sorts by best quality when quality criteria are selected`() {
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

        val presented = DebridStreamPresentation.apply(
            groups = listOf(
                AddonStreamGroup(
                    addonName = "Addon",
                    addonId = "addon:test",
                    streams = listOf(low, large, mid),
                ),
            ),
            settings = DebridSettings(
                enabled = true,
                providerApiKeys = mapOf(DebridProviders.TORBOX_ID to "key"),
                streamPreferences = DebridStreamPreferences(
                    sortCriteria = DebridStreamSortCriterion.defaultOrder,
                ),
            ),
        ).single().streams

        assertEquals(listOf("2160p TB Instant", "1080p TB Instant", "720p TB Instant"), presented.map { it.name })
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
                providerApiKeys = mapOf(DebridProviders.TORBOX_ID to "key"),
                streamMaxResults = 2,
                streamSortMode = DebridStreamSortMode.QUALITY_DESC,
                streamMinimumQuality = DebridStreamMinimumQuality.P1080,
                streamCodecFilter = DebridStreamCodecFilter.HEVC,
            ),
        ).single().streams

        assertEquals(listOf("2160p TB Instant", "1080p TB Instant", "Resolved addon URL"), presented.map { it.name })
    }

    @Test
    fun `hides addon torrent streams that are not cached`() {
        val cached = localTorboxStream(
            name = "Cached",
            filename = "Movie.1080p.WEB-DL.HEVC-GRP.mkv",
            size = 10_000_000_000,
        )
        val uncached = localTorboxStream(
            name = "Uncached",
            filename = "Movie.2160p.WEB-DL.HEVC-GRP.mkv",
            size = 20_000_000_000,
            cacheState = StreamDebridCacheState.NOT_CACHED,
        )

        val presented = DebridStreamPresentation.apply(
            groups = listOf(
                AddonStreamGroup(
                    addonName = "Addon",
                    addonId = "addon:test",
                    streams = listOf(cached, uncached),
                ),
            ),
            settings = DebridSettings(
                enabled = true,
                providerApiKeys = mapOf(DebridProviders.TORBOX_ID to "key"),
            ),
        ).single().streams

        assertEquals(listOf("1080p TB Instant"), presented.map { it.name })
    }

    @Test
    fun `leaves cloud-service results untouched when link resolving is off`() {
        val uncached = localTorboxStream(
            name = "Uncached",
            filename = "Movie.2160p.WEB-DL.HEVC-GRP.mkv",
            size = 20_000_000_000,
            cacheState = StreamDebridCacheState.NOT_CACHED,
        )

        val presented = DebridStreamPresentation.apply(
            groups = listOf(
                AddonStreamGroup(
                    addonName = "Addon",
                    addonId = "addon:test",
                    streams = listOf(uncached),
                ),
            ),
            settings = DebridSettings(
                enabled = false,
                providerApiKeys = mapOf(DebridProviders.TORBOX_ID to "key"),
            ),
        ).single().streams

        assertEquals(listOf("Uncached"), presented.map { it.name })
    }

    private fun localTorboxStream(
        name: String = "Torrent",
        filename: String,
        size: Long,
        cacheState: StreamDebridCacheState = StreamDebridCacheState.CACHED,
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
                state = cacheState,
                cachedName = filename,
                cachedSize = size,
            ),
        )

    private fun premiumizeDirectStream(
        name: String,
        filename: String,
        size: Long,
    ): StreamItem =
        StreamItem(
            name = name,
            addonName = "Torrentio",
            addonId = "addon:torrentio",
            clientResolve = StreamClientResolve(
                type = "debrid",
                service = DebridProviders.PREMIUMIZE_ID,
                filename = filename,
                isCached = true,
                stream = StreamClientResolveStream(
                    raw = StreamClientResolveRaw(
                        filename = filename,
                        size = size,
                        parsed = StreamClientResolveParsed(
                            resolution = "2160p",
                        ),
                    ),
                ),
            ),
        )
}
