package com.nuvio.app.features.player

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The HUD's custom-colour prompt has no string channel to Kotlin, so a colour crosses the controls
 * bridge as one packed ARGB integer riding in a Double. Opaque colours exceed `Int.MAX_VALUE` on
 * the way, which is exactly where a naive conversion loses them.
 */
class SubtitleColorArgbTest {
    @Test
    fun unpacksAnOpaqueColourAboveIntMaxValue() {
        // 0xFFFF66CC — the case a signed 32-bit round trip would mangle.
        assertEquals(Color(0xFFFF66CC), subtitleColorFromArgb(4294928076.0))
    }

    @Test
    fun keepsThePackedAlpha() {
        val color = subtitleColorFromArgb(2164221644.0) // 0x80FF66CC
        assertEquals(Color(0x80FF66CC), color)
        assertEquals(0x80 / 255f, color.alpha)
    }

    @Test
    fun unpacksBlackAndWhiteEndsOfTheRange() {
        assertEquals(Color.Black, subtitleColorFromArgb(4278190080.0)) // 0xFF000000
        assertEquals(Color.White, subtitleColorFromArgb(4294967295.0)) // 0xFFFFFFFF
        assertEquals(Color.Transparent, subtitleColorFromArgb(0.0))
    }

    @Test
    fun roundTripsThroughTheStorageHexTheSettingsPageWrites() {
        val color = subtitleColorFromArgb(2164221644.0)
        assertEquals("#80FF66CC", color.toStorageHexString())
        assertEquals(color, subtitleColorFromStorage("#80FF66CC"))
    }
}
