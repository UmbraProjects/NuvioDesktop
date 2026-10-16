package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.DesktopMpvConfigMode
import com.nuvio.app.features.player.DesktopBufferPreset
import com.nuvio.app.features.player.shouldEnableDesktopRtxSuperResolution
import com.nuvio.app.features.player.shouldEnableDesktopRtxTrueHdr
import com.nuvio.app.features.player.desktopRtxVideoFilter
import com.nuvio.app.features.player.desktopVsrScaleText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopMpvRuntimeOverrideTest {
    private val reportedOptions = """
        cache=yes
        cache-secs=5
        demuxer-max-bytes=50M
        demuxer-max-back-bytes=1M
        demuxer-readahead-secs=10
    """.trimIndent()

    @Test
    fun replaceModeKeepsReportedCacheOptionsAboveRuntimeBufferPreset() {
        val customOptionNames = desktopCustomMpvOptionNames(reportedOptions)

        listOf(
            "cache",
            "cache-secs",
            "demuxer-max-bytes",
            "demuxer-max-back-bytes",
            "demuxer-readahead-secs",
        ).forEach { propertyName ->
            assertFalse(
                shouldApplyNuvioRuntimeMpvProperty(
                    DesktopMpvConfigMode.Replace,
                    customOptionNames,
                    propertyName,
                ),
                "$propertyName must remain controlled by the custom Replace configuration",
            )
        }
        assertTrue(
            shouldApplyNuvioRuntimeMpvProperty(
                DesktopMpvConfigMode.Replace,
                customOptionNames,
                "stream-buffer-size",
            ),
        )
    }

    @Test
    fun addAndOffModesKeepNuvioRuntimeBufferPropertiesAuthoritative() {
        val customOptionNames = desktopCustomMpvOptionNames(reportedOptions)

        assertTrue(
            shouldApplyNuvioRuntimeMpvProperty(
                DesktopMpvConfigMode.Add,
                customOptionNames,
                "cache-secs",
            ),
        )
        assertTrue(
            shouldApplyNuvioRuntimeMpvProperty(
                DesktopMpvConfigMode.Off,
                customOptionNames,
                "cache-secs",
            ),
        )
    }

    @Test
    fun fullModeNeverAppliesNuvioRuntimeBufferProperties() {
        assertFalse(
            shouldApplyNuvioRuntimeMpvProperty(
                DesktopMpvConfigMode.Full,
                emptySet(),
                "cache-secs",
            ),
        )
    }

    @Test
    fun optionNameParsingIgnoresCommentsAndMalformedLines() {
        val names = desktopCustomMpvOptionNames(
            """
                # cache-secs=1
                malformed
                =missing-name
                cache-secs=5
            """.trimIndent(),
        )

        assertTrue("cache-secs" in names)
        assertFalse("malformed" in names)
        assertFalse("" in names)
    }

    @Test
    fun lowDataPresetUsesSmallIoBufferBeforeStreamOpen() {
        val options = desktopBufferPresetMpvOptions(
            preset = DesktopBufferPreset.LowData,
            hostOs = DesktopHostOs.WINDOWS,
        ).toMap()

        assertEquals("15.0", options["demuxer-readahead-secs"])
        assertEquals("30.0", options["cache-secs"])
        assertEquals("64MiB", options["demuxer-max-bytes"])
        assertEquals("16MiB", options["demuxer-max-back-bytes"])
        assertEquals("1MiB", options["stream-buffer-size"])
        assertEquals("0.25", options["cache-pause-wait"])
    }

    @Test
    fun meteredPresetIsStricterThanLowDataOnEveryLimit() {
        val metered = desktopBufferPresetMpvOptions(
            preset = DesktopBufferPreset.Metered,
            hostOs = DesktopHostOs.WINDOWS,
        ).toMap()

        assertEquals("10.0", metered["demuxer-readahead-secs"])
        assertEquals("10.0", metered["cache-secs"])
        assertEquals("32MiB", metered["demuxer-max-bytes"])
        assertEquals("8MiB", metered["demuxer-max-back-bytes"])

        val lowData = desktopBufferPresetMpvOptions(
            preset = DesktopBufferPreset.LowData,
            hostOs = DesktopHostOs.WINDOWS,
        ).toMap()
        // cache-secs overrides demuxer-readahead-secs for seekable cached streams, so it is the
        // limit that actually decides how much a paused player pulls.
        assertTrue(
            metered.getValue("cache-secs").toDouble() < lowData.getValue("cache-secs").toDouble(),
            "Metered must prefetch less time than Low Data",
        )
    }

    @Test
    fun speedScalesTimeLimitsButNotByteOrIoLimits() {
        val options = desktopBufferPresetMpvOptions(
            preset = DesktopBufferPreset.Balanced,
            hostOs = DesktopHostOs.WINDOWS,
            playbackSpeed = 2f,
        ).toMap()

        assertEquals("120.0", options["demuxer-readahead-secs"])
        assertEquals("240.0", options["cache-secs"])
        assertEquals("256MiB", options["demuxer-max-bytes"])
        assertEquals("64MiB", options["demuxer-max-back-bytes"])
        assertEquals("1MiB", options["stream-buffer-size"])
        assertEquals("0.5", options["cache-pause-wait"])
    }

    @Test
    fun rtxSuperResolutionOnlyRunsOnConfirmedSdrVideo() {
        assertTrue(
            shouldEnableDesktopRtxSuperResolution(
                enabled = true,
                isHdr = false,
                animeLayerActive = false,
                scale = 2.0,
            ),
        )
        assertFalse(
            shouldEnableDesktopRtxSuperResolution(
                enabled = true,
                isHdr = true,
                animeLayerActive = false,
                scale = 2.0,
            ),
            "Native HDR/Dolby Vision must bypass d3d11vpp VSR to avoid green video output",
        )
        assertFalse(
            shouldEnableDesktopRtxSuperResolution(
                enabled = true,
                isHdr = null,
                animeLayerActive = false,
                scale = 2.0,
            ),
            "VSR must wait until the stream is positively identified as SDR",
        )
        assertFalse(
            shouldEnableDesktopRtxSuperResolution(
                enabled = true,
                isHdr = false,
                animeLayerActive = true,
                scale = 2.0,
            ),
            "VSR must not stack on a running Anime4K/shader/SVP layer",
        )
    }

    @Test
    fun rtxScaleIsSpelledLikeTheNativeFileLoadInstall() {
        // Must match roundTripFloatText in player_bridge.cpp, or mpv rebuilds d3d11vpp for nothing.
        assertEquals("2", desktopVsrScaleText(2.0))
        assertEquals("1.90277779", desktopVsrScaleText(2055.0 / 1080.0))
        assertEquals("1.5", desktopVsrScaleText(1.5))
        assertEquals(
            "d3d11vpp=scale=2:scaling-mode=nvidia:nvidia-true-hdr=yes",
            desktopRtxVideoFilter(vsrActive = true, trueHdrActive = true, scale = 2.0),
        )
        assertEquals("", desktopRtxVideoFilter(vsrActive = false, trueHdrActive = false, scale = 2.0))
    }

    @Test
    fun rtxTrueHdrOnAnimeNeedsOptInAndNoAnimeLayer() {
        assertTrue(
            shouldEnableDesktopRtxTrueHdr(
                enabled = true, isHdr = false, displayHdr = true, isEffectivelyAnime = false,
                animeOptIn = false, animeLayerActive = false,
            ),
        )
        assertFalse(
            shouldEnableDesktopRtxTrueHdr(
                enabled = true, isHdr = false, displayHdr = true, isEffectivelyAnime = true,
                animeOptIn = false, animeLayerActive = false,
            ),
            "Detected anime stays off True HDR without the opt-in",
        )
        assertTrue(
            shouldEnableDesktopRtxTrueHdr(
                enabled = true, isHdr = false, displayHdr = true, isEffectivelyAnime = true,
                animeOptIn = true, animeLayerActive = false,
            ),
        )
        assertFalse(
            shouldEnableDesktopRtxTrueHdr(
                enabled = true, isHdr = false, displayHdr = true, isEffectivelyAnime = true,
                animeOptIn = true, animeLayerActive = true,
            ),
            "The opt-in never stacks True HDR on SVP",
        )
    }

    @Test
    fun rtxTrueHdrNeedsWindowsHdrOnTheDisplay() {
        assertFalse(
            shouldEnableDesktopRtxTrueHdr(
                enabled = true, isHdr = false, displayHdr = false, isEffectivelyAnime = false,
                animeOptIn = false, animeLayerActive = false,
            ),
            "An SDR desktop has nowhere to send True HDR's PQ output",
        )
        assertFalse(
            shouldEnableDesktopRtxTrueHdr(
                enabled = true, isHdr = false, displayHdr = null, isEffectivelyAnime = false,
                animeOptIn = false, animeLayerActive = false,
            ),
            "An unreported display state counts as SDR",
        )
    }
}
