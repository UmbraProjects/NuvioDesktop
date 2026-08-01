package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The "cached on debrid" trait and the structured/formatted signals it can fire from.
 *
 * Cache state has no field in the Stremio stream contract, so most debrid addons advertise it in
 * their formatted name. These fixtures mirror the formats emitted by the supported addons and keep
 * the fallback narrow enough that ordinary release text cannot accidentally receive the boost.
 */
class StreamDebridCachedTraitTest {

    private val name = "Movie.2024.2160p.UHD.BluRay.REMUX.HEVC-FraMeSToR"

    private fun stream(
        cacheStatus: StreamDebridCacheStatus? = null,
        clientResolve: StreamClientResolve? = null,
    ) = StreamItem(
        name = name,
        addonName = "addon",
        addonId = "addon:x",
        url = "https://cdn.example.invalid/a.mkv",
        behaviorHints = StreamBehaviorHints(filename = name),
        debridCacheStatus = cacheStatus,
        clientResolve = clientResolve,
    )

    private val profile = StreamScoreProfile(
        enabled = true,
        points = mapOf(StreamScoreTrait.DEBRID_CACHED.id to 50),
    )

    private fun score(item: StreamItem) =
        StreamScorer.score(item, profile, StreamScoreContext.MOVIE).total

    @Test
    fun firesFromTheAppsOwnCacheCheck() {
        val cached = stream(
            cacheStatus = StreamDebridCacheStatus(
                providerId = "realdebrid",
                providerName = "Real-Debrid",
                state = StreamDebridCacheState.CACHED,
            ),
        )
        assertEquals(50, score(cached))
    }

    @Test
    fun firesFromTheAddonsClientResolvePayload() {
        val cached = stream(clientResolve = StreamClientResolve(type = "debrid", service = "rd", isCached = true))
        assertEquals(50, score(cached))
    }

    @Test
    fun doesNotFireWhenTheStreamIsKnownNotCached() {
        val notCached = stream(
            cacheStatus = StreamDebridCacheStatus(
                providerId = "realdebrid",
                providerName = "Real-Debrid",
                state = StreamDebridCacheState.NOT_CACHED,
            ),
        )
        assertEquals(0, score(notCached))
        assertEquals(0, score(stream(clientResolve = StreamClientResolve(type = "debrid", isCached = false))))
    }

    @Test
    fun doesNotFireWhenTheAddonSaysNothingAboutCaching() {
        // A plain proxied URL with no clientResolve and no cache check carries no cache information
        // at all, so the trait stays silent rather than guessing.
        assertEquals(0, score(stream()))
        assertEquals(0, score(stream(clientResolve = StreamClientResolve(type = "debrid", service = "rd"))))
    }

    @Test
    fun aStillCheckingStatusFallsBackToTheAddonsAnswer() {
        // CHECKING/UNKNOWN defer to the addon's answer.
        val checking = stream(
            cacheStatus = StreamDebridCacheStatus(
                providerId = "rd",
                providerName = "Real-Debrid",
                state = StreamDebridCacheState.CHECKING,
            ),
            clientResolve = StreamClientResolve(type = "debrid", service = "rd", isCached = true),
        )
        assertEquals(50, score(checking))
    }

    // --- streamData.service.cached: what AIOStreams actually sends ---

    @Test
    fun firesFromTheAddonsStreamDataServiceBlock() {
        // AIOStreams emits no clientResolve at all; its cache flag lives here.
        val cached = stream().copy(
            streamData = StreamAddonData(type = "debrid", serviceId = "torbox", serviceCached = true),
        )
        assertEquals(50, score(cached))
    }

    @Test
    fun doesNotFireWhenStreamDataSaysNotCached() {
        val notCached = stream().copy(
            streamData = StreamAddonData(type = "debrid", serviceId = "torbox", serviceCached = false),
            name = "[TB ⚡] Comet 2160p",
            addonName = "Comet",
        )
        assertEquals(0, score(notCached))
        // Absent cache status is not the same as false and does not award a debrid result.
        assertEquals(0, score(stream().copy(streamData = StreamAddonData(type = "debrid"))))
    }

    @Test
    fun structuredUsenetStreamsReceiveTheInstantPlaybackBoost() {
        val aioStreams = stream().copy(
            streamData = StreamAddonData(type = "usenet", serviceId = "nzbdav"),
        )
        val standardTopLevelType = stream().copy(streamType = "usenet")

        assertTrue(StreamTraitDetector.detect(aioStreams).isDebridCached)
        assertTrue(StreamTraitDetector.detect(standardTopLevelType).isDebridCached)
        assertEquals(50, score(aioStreams))
        assertEquals(50, score(standardTopLevelType))
    }

    @Test
    fun customUsenetFormatterDoesNotMasqueradeAsStructuredMetadata() {
        // Sanitised from the visible AIOStreams result. The newspaper is user-controlled
        // presentation and must not carry scoring semantics.
        val usenet = stream().copy(
            name = "📀 2160p",
            description = """
                📰 Station Eleven | S01 E01
                17500 | 42.7 Mbps | 1238d | Ninja
                🥇 FraMeSToR | DTS-HD MA • 5.1
            """.trimIndent(),
            streamData = null,
            streamType = null,
        )

        assertEquals(0, score(usenet))
    }

    @Test
    fun usenetServiceLabelsAloneDoNotReceiveTheBoost() {
        val labels = listOf(
            "[Usenet] 2160p",
            "NNTP | 1080p",
            "NzbDAV 2160p",
            "AltMount | 1080p",
            "StremThru Newz 720p",
        )

        labels.forEach { label ->
            assertEquals(0, score(stream().copy(name = label)), label)
        }
    }

    @Test
    fun usenetTypeWinsOverAnInapplicableFalseCacheFlag() {
        // AIOStreams can describe a direct-streaming Usenet result as uncached because the debrid
        // cache concept does not apply to it. Its structured stream type is authoritative.
        val usenet = stream().copy(
            streamData = StreamAddonData(
                type = "UsEnEt",
                serviceId = "aiostreams",
                serviceCached = false,
            ),
        )

        assertEquals(50, score(usenet))
    }

    @Test
    fun ordinaryDirectUrlsAndTextMentionsOfUsenetDoNotReceiveTheBoost() {
        assertEquals(0, score(stream().copy(streamType = "http")))
        assertEquals(0, score(stream().copy(streamType = "live")))
        assertEquals(
            0,
            score(
                stream().copy(
                    name = "2160p",
                    description = """
                        Direct download from a file host
                        Usenet.2024.2160p.WEB-DL
                    """.trimIndent(),
                    behaviorHints = StreamBehaviorHints(
                        filename = "Usenet.2024.2160p.WEB-DL.mkv",
                    ),
                ),
            ),
        )
    }

    @Test
    fun usenetTypeIsParsedFromBothSupportedWireShapes() {
        val payload = """
            {"streams":[
              {
                "name":"2160p",
                "url":"https://nzbdav.example.invalid/play/one.mkv",
                "streamData":{"type":"usenet","service":{"id":"nzbdav","cached":false}}
              },
              {
                "name":"1080p",
                "url":"https://nntp.example.invalid/play/two.mkv",
                "type":"usenet"
              }
            ]}
        """.trimIndent()

        val parsed = StreamParser.parse(payload, addonName = "AIOStreams", addonId = "addon:aio")

        assertEquals("usenet", parsed[0].streamData?.type)
        assertEquals("usenet", parsed[1].streamType)
        assertEquals(50, score(parsed[0]))
        assertEquals(50, score(parsed[1]))
    }

    @Test
    fun streamDataIsParsedOffTheWirePayload() {
        // Shape emitted by AIOStreams when its provideStreamData option is enabled.
        val payload = """
            {"streams":[{
              "name":" 📀  2160p",
              "description":"⚡ Station Eleven | S01 E01",
              "url":"https://example.invalid/playback",
              "streamData":{"type":"debrid","service":{"id":"torbox","cached":true}}
            }]}
        """.trimIndent()
        val parsed = StreamParser.parse(payload, addonName = "AIOUmbra", addonId = "addon:aio").single()

        assertEquals(true, parsed.streamData?.serviceCached)
        assertEquals("torbox", parsed.streamData?.serviceId)
        assertEquals("debrid", parsed.streamData?.type)
        assertTrue(StreamTraitDetector.detect(parsed).isDebridCached)
        assertEquals(50, score(parsed))
    }

    @Test
    fun anUncachedWirePayloadDoesNotScore() {
        val payload = """
            {"streams":[{
              "name":"2160p",
              "url":"https://example.invalid/playback",
              "streamData":{"type":"debrid","service":{"id":"torbox","cached":false}}
            }]}
        """.trimIndent()
        val parsed = StreamParser.parse(payload, addonName = "AIOUmbra", addonId = "addon:aio").single()
        assertEquals(false, parsed.streamData?.serviceCached)
        assertEquals(0, score(parsed))
    }

    @Test
    fun theTraitIsDetectedIndependentlyOfTheProfile() {
        val traits = StreamTraitDetector.detect(
            stream(clientResolve = StreamClientResolve(type = "debrid", service = "rd", isCached = true)),
        )
        assertTrue(traits.isDebridCached)
        assertFalse(StreamTraitDetector.detect(stream()).isDebridCached)
    }

    @Test
    fun formatterCacheGlyphDoesNotMasqueradeAsStructuredMetadata() {
        // Sanitised from a live AIOStreams response with streamData omitted.
        val payload = """
            {"streams":[{
              "name":" 📀  2160p",
              "description":"⚡ Station Eleven | S01 E01\n13.3 GB | STorz\nStation.Eleven.S01E01.mkv",
              "url":"https://example.invalid/playback",
              "behaviorHints":{"filename":"Station.Eleven.S01E01.mkv","videoSize":13331638502}
            }]}
        """.trimIndent()

        val parsed = StreamParser.parse(payload, addonName = "AIOStreams", addonId = "addon:aio").single()
        assertEquals(null, parsed.streamData)
        assertFalse(StreamTraitDetector.detect(parsed).isDebridCached)
        assertEquals(0, score(parsed))
    }

    @Test
    fun fixedFormatterMarkersFromSupportedDebridAddonsAwardTheBoost() {
        val fixtures = listOf(
            Triple("Comet", "[TB ⚡] Comet 2160p", "Movie.2024.2160p"),
            Triple("StremThru Torz", "[RD ⚡] StremThru Torz 2160p", "Movie.2024.2160p"),
            Triple("Meteor", "[TB 🌩️] Meteor 2160p", "Movie.2024.2160p"),
            Triple("Torrentio", "[TB+]Torrentio\n4K DV", "Movie.2024.2160p"),
            Triple("MediaFusion", "[RD ⚡] MediaFusion 2160p", "Movie.2024.2160p"),
            Triple("Debridio", "[ED ⚡] Debridio 1080p", "Movie.2024.1080p"),
        )

        fixtures.forEach { (addon, streamName, description) ->
            val item = stream().copy(name = streamName, description = description, addonName = addon)
            assertTrue(StreamTraitDetector.detect(item).isDebridCached, addon)
            assertEquals(50, score(item), addon)
        }
    }

    @Test
    fun uncachedFixedFormatterResultsDoNotAwardTheBoost() {
        val fixtures = listOf(
            Triple("Comet", "[PM⬇️] Comet 2160p", "Movie.2024.2160p"),
            Triple("StremThru Torz", "[RD]\nStremThru Torz\n2160p", "Movie.2024.2160p"),
            Triple("Meteor", "[TB ☁️] Meteor 2160p", "Movie.2024.2160p"),
            Triple("Torrentio", "[RD download] Torrentio\n2160p", "Movie.2024.2160p"),
            Triple("MediaFusion", "[RD] MediaFusion 2160p", "Movie.2024.2160p"),
            Triple("Debridio", "[ED]\nDebridio 1080p", "Movie.2024.1080p"),
        )

        fixtures.forEach { (addon, streamName, description) ->
            val item = stream().copy(name = streamName, description = description, addonName = addon)
            assertFalse(StreamTraitDetector.detect(item).isDebridCached, addon)
            assertEquals(0, score(item), addon)
        }
    }

    @Test
    fun aioStreamsNeverUsesItsCustomFormatterForAvailability() {
        val customFormats = listOf(
            "[TB ⚡] Comet 2160p",
            "[TB+]Torrentio\n4K DV",
            "[TB 🌩️] Meteor 2160p",
            "[RD ⚡] MediaFusion 2160p",
        )

        customFormats.forEach { streamName ->
            val item = stream().copy(
                name = streamName,
                addonName = "AIOStreams",
                addonId = "addon:com.aiostreams.viren070",
            )
            assertFalse(StreamTraitDetector.detect(item).isDebridCached, streamName)
            assertEquals(0, score(item), streamName)
        }
    }

    @Test
    fun fixedMarkersOnUnrelatedAddonsDoNotAwardTheBoost() {
        val unrelated = listOf(
            stream().copy(name = "[TB ⚡] 2160p", addonName = "Custom Addon"),
            stream().copy(name = "[TB+] 2160p", addonName = "Custom Addon"),
            stream().copy(name = "[TB 🌩️] 2160p", addonName = "Custom Addon"),
        )

        unrelated.forEach { item ->
            assertEquals(0, score(item), item.name)
        }
    }

    @Test
    fun torrentioDoesNotMistakeHdr10PlusForACachedService() {
        val item = stream().copy(
            name = "[HDR10+] Torrentio 2160p",
            addonName = "Torrentio",
        )

        assertEquals(0, score(item))
    }

    @Test
    fun releaseTextCannotMasqueradeAsACacheDeclaration() {
        assertEquals(
            0,
            score(
                stream().copy(
                    name = "Movie.Cached.Memories.2024.1080p.WEB-DL-DownloadHub",
                    description = "Movie.Cached.Memories.2024.1080p.WEB-DL-DownloadHub",
                ),
            ),
        )
    }

    @Test
    fun strongerNegativeSignalsVetoAStaleCachedLabel() {
        val notCached = stream(
            cacheStatus = StreamDebridCacheStatus(
                providerId = "realdebrid",
                providerName = "Real-Debrid",
                state = StreamDebridCacheState.NOT_CACHED,
            ),
        ).copy(name = "[RD+] Torrentio")
        assertEquals(0, score(notCached))

        val clientSaysNo = stream(
            clientResolve = StreamClientResolve(type = "debrid", service = "rd", isCached = false),
        ).copy(
            name = "[RD ⚡] Comet 2160p",
            addonName = "Comet",
        )
        assertEquals(0, score(clientSaysNo))
    }
}
