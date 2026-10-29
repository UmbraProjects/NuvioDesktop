package com.nuvio.app.features.music.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtworkPaletteTest {
    private fun rgb(p: Long) = (p and 0xFFFFFF).toInt()

    @Test
    fun accentAlwaysReadsOnADarkSurfaceAndCarriesReadableText() {
        val surface = 0x0E0C0D
        // A sweep of hues, saturations and lightnesses, including near-black, near-white and grey seeds.
        for (hue in 0 until 360 step 5) for (s in listOf(0f, 0.1f, 0.4f, 0.8f, 1f)) for (l in listOf(0.05f, 0.3f, 0.5f, 0.8f, 0.95f)) {
            val seed = ArtworkPaletteMath.hslToRgb(hue / 360f, s, l)
            val p = ArtworkPaletteMath.derive(seed)
            assertTrue(ArtworkPaletteMath.contrast(rgb(p.accent), surface) >= 4.49, "accent vs surface for seed ${seed.toString(16)}")
            assertTrue(ArtworkPaletteMath.contrast(rgb(p.accent), rgb(p.onAccent)) >= 4.5, "text on accent for seed ${seed.toString(16)}")
        }
    }

    @Test
    fun anAmberSeedStaysAmberAndKeepsDarkText() {
        val p = ArtworkPaletteMath.derive(0xE8913A, forUrl = "u")
        val (hSeed, _, _) = ArtworkPaletteMath.rgbToHsl(0xE8913A)
        val (hOut, sOut, _) = ArtworkPaletteMath.rgbToHsl(rgb(p.accent))
        assertTrue(kotlin.math.abs(hSeed - hOut) < 0.02f, "hue kept")
        assertTrue(sOut >= 0.5f)
        assertTrue(rgb(p.onAccent) == 0x0B0B0B, "amber takes dark text, like the mock's #1a1007 on #E8913A")
        assertEquals("u", p.forUrl)
    }

    @Test
    fun aGreySeedGivesANeutralAccentNotAnInventedHue() {
        val p = ArtworkPaletteMath.derive(0x808080)
        val c = rgb(p.accent)
        assertEquals((c shr 16) and 0xFF, (c shr 8) and 0xFF)
        assertEquals((c shr 8) and 0xFF, c and 0xFF)
    }

    @Test
    fun tintAndGroundAreDarkerWashesOfTheSameHueAndAllOpaque() {
        val p = ArtworkPaletteMath.derive(0x3A7BE8)
        listOf(p.accent, p.onAccent, p.tint, p.ground).forEach { assertEquals(0xFFL, (it shr 24) and 0xFF) }
        val lum = { v: Long -> ArtworkPaletteMath.luminance(rgb(v)) }
        assertTrue(lum(p.ground) < lum(p.tint) && lum(p.tint) < lum(p.accent))
        assertTrue(lum(p.ground) < 0.02, "ground is near black")
    }

    @Test
    fun hslRoundTripIsClose() {
        for (c in listOf(0xE8913A, 0x3A7BE8, 0x00FF00, 0x123456, 0xFFFFFF, 0x000000)) {
            val (h, s, l) = ArtworkPaletteMath.rgbToHsl(c)
            val back = ArtworkPaletteMath.hslToRgb(h, s, l)
            listOf(16, 8, 0).forEach { sh -> assertTrue(kotlin.math.abs(((c shr sh) and 0xFF) - ((back shr sh) and 0xFF)) <= 1) }
        }
    }
}
