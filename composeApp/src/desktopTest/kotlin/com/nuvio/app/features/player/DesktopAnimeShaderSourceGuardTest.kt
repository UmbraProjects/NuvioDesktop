package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopVideoSourceSizeTest {

    @Test
    fun `a packed size round-trips`() {
        val size = DesktopVideoSourceSize.unpack(1920.0 * 65536 + 1080)
        assertEquals(DesktopVideoSourceSize(1920, 1080), size)
    }

    @Test
    fun `a 4K packed size round-trips`() {
        val size = DesktopVideoSourceSize.unpack(3840.0 * 65536 + 2160)
        assertEquals(DesktopVideoSourceSize(3840, 2160), size)
    }

    @Test
    fun `the largest dimension the bridge can send round-trips exactly`() {
        // The packing is only safe while it stays exact in a double; 65535x65535 is the ceiling the
        // native side enforces, so it is the value worth pinning.
        val size = DesktopVideoSourceSize.unpack(65535.0 * 65536 + 65535)
        assertEquals(DesktopVideoSourceSize(65535, 65535), size)
    }

    @Test
    fun `a zero or negative packed size is not a size`() {
        assertNull(DesktopVideoSourceSize.unpack(0.0))
        assertNull(DesktopVideoSourceSize.unpack(-1.0))
    }

    @Test
    fun `a zero height is not a size`() {
        assertNull(DesktopVideoSourceSize.unpack(1920.0 * 65536))
    }

    @Test
    fun `1080p and 1440p are not ultra hd`() {
        assertFalse(DesktopVideoSourceSize(1920, 1080).isUltraHd)
        assertFalse(DesktopVideoSourceSize(2560, 1440).isUltraHd)
    }

    @Test
    fun `4K is ultra hd`() {
        assertTrue(DesktopVideoSourceSize(3840, 2160).isUltraHd)
        assertTrue(DesktopVideoSourceSize(4096, 2160).isUltraHd)
    }

    @Test
    fun `a cropped scope 4K master is ultra hd despite its short side`() {
        assertTrue(DesktopVideoSourceSize(3840, 1600).isUltraHd)
    }

    @Test
    fun `a portrait 4K source is ultra hd despite its width`() {
        assertTrue(DesktopVideoSourceSize(2160, 3840).isUltraHd)
    }
}

private const val ARTCNN = "~~/shaders/artcnn.glsl"

class DesktopAnimeShaderPlanTest {

    private fun plan(
        preset: DesktopAnimeMode? = null,
        customShaderChain: String = "",
        isSessionForced: Boolean = false,
        skipUltraHdSources: Boolean = true,
        sourceSize: DesktopVideoSourceSize? = DesktopVideoSourceSize(3840, 2160),
    ) = planDesktopAnimeShaders(
        requestedPreset = preset,
        requestedCustomShaderChain = customShaderChain,
        isSessionForced = isSessionForced,
        skipUltraHdSources = skipUltraHdSources,
        sourceSize = sourceSize,
    )

    @Test
    fun `an auto-applied custom shader is skipped on a 4K source`() {
        // The configuration this guard originally missed entirely: a custom ArtCNN chain reaching
        // the profile through the same automatic anime gate a built-in preset would.
        val result = plan(customShaderChain = ARTCNN)
        assertEquals("", result.customShaderChain)
        assertNull(result.preset)
        assertEquals(DesktopAnimeShaderSkipReason.UltraHdSource, result.skipReason)
    }

    @Test
    fun `an auto-applied custom shader runs on a 1080p source`() {
        val result = plan(customShaderChain = ARTCNN, sourceSize = DesktopVideoSourceSize(1920, 1080))
        assertEquals(ARTCNN, result.customShaderChain)
        assertNull(result.skipReason)
    }

    @Test
    fun `a session-forced custom shader runs on a 4K source`() {
        val result = plan(customShaderChain = ARTCNN, isSessionForced = true)
        assertEquals(ARTCNN, result.customShaderChain)
        assertNull(result.skipReason)
    }

    @Test
    fun `a custom shader still runs at 4K with the guard off`() {
        val result = plan(customShaderChain = ARTCNN, skipUltraHdSources = false)
        assertEquals(ARTCNN, result.customShaderChain)
        assertNull(result.skipReason)
    }

    @Test
    fun `a built-in preset is skipped on a 4K source`() {
        val result = plan(preset = DesktopAnimeMode.Optimized)
        assertNull(result.preset)
        assertEquals("", result.customShaderChain)
        assertEquals(DesktopAnimeShaderSkipReason.UltraHdSource, result.skipReason)
    }

    @Test
    fun `a built-in preset runs on a 1080p source`() {
        val result = plan(preset = DesktopAnimeMode.Optimized, sourceSize = DesktopVideoSourceSize(1920, 1080))
        assertEquals(DesktopAnimeMode.Optimized, result.preset)
        assertNull(result.skipReason)
    }

    @Test
    fun `both kinds wait for an unknown source size`() {
        assertEquals(
            DesktopAnimeShaderSkipReason.SourceUnknown,
            plan(preset = DesktopAnimeMode.Optimized, sourceSize = null).skipReason,
        )
        assertEquals(
            DesktopAnimeShaderSkipReason.SourceUnknown,
            plan(customShaderChain = ARTCNN, sourceSize = null).skipReason,
        )
    }

    @Test
    fun `a session with no shader requested reports no skip`() {
        // Anime detection off, or the mode simply Off: nothing was held back, so the info panel
        // must not claim a 4K skip happened.
        val result = plan()
        assertNull(result.preset)
        assertEquals("", result.customShaderChain)
        assertNull(result.skipReason)
        assertNull(plan(sourceSize = null).skipReason)
    }
}

class DesktopAnimeShaderSkipReasonTest {

    @Test
    fun `an unknown source size holds the chain back`() {
        // The video-profile pass runs on attach, before any file is loaded — without this the chain
        // would already be installed by the time the size arrived.
        assertEquals(
            DesktopAnimeShaderSkipReason.SourceUnknown,
            desktopAnimeShaderSkipReason(
                isSessionForced = false,
                skipUltraHdSources = true,
                sourceSize = null,
            ),
        )
    }

    @Test
    fun `a 4K source is skipped`() {
        assertEquals(
            DesktopAnimeShaderSkipReason.UltraHdSource,
            desktopAnimeShaderSkipReason(
                isSessionForced = false,
                skipUltraHdSources = true,
                sourceSize = DesktopVideoSourceSize(3840, 2160),
            ),
        )
    }

    @Test
    fun `a 1080p source runs the chain`() {
        assertNull(
            desktopAnimeShaderSkipReason(
                isSessionForced = false,
                skipUltraHdSources = true,
                sourceSize = DesktopVideoSourceSize(1920, 1080),
            ),
        )
    }

    @Test
    fun `an explicit session force always wins`() {
        // F10 is the escape hatch for the case the automatic rule gets wrong, so it must bypass
        // both the unknown-size wait and the 4K skip.
        assertNull(
            desktopAnimeShaderSkipReason(
                isSessionForced = true,
                skipUltraHdSources = true,
                sourceSize = DesktopVideoSourceSize(3840, 2160),
            ),
        )
        assertNull(
            desktopAnimeShaderSkipReason(
                isSessionForced = true,
                skipUltraHdSources = true,
                sourceSize = null,
            ),
        )
    }

    @Test
    fun `turning the guard off restores unconditional applying`() {
        // Including the unknown-size case: with the guard off there is nothing to wait for, and a
        // GPU that is happy running Anime4K at 4K should behave exactly as it did before.
        assertNull(
            desktopAnimeShaderSkipReason(
                isSessionForced = false,
                skipUltraHdSources = false,
                sourceSize = DesktopVideoSourceSize(3840, 2160),
            ),
        )
        assertNull(
            desktopAnimeShaderSkipReason(
                isSessionForced = false,
                skipUltraHdSources = false,
                sourceSize = null,
            ),
        )
    }
}
