package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.DesktopMpvConfigMode
import com.nuvio.app.features.player.DesktopBufferPreset
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
}
