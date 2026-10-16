package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopAdvancedVideoOptionsTest {
    @Test
    fun filmGrainDefaultKeepsTheProfileValue() {
        assertEquals("0", desktopDebandGrain(null, "0"))
        assertEquals("20", desktopDebandGrain("default", "20"))
        assertEquals("0", desktopDebandGrain(DESKTOP_FILM_GRAIN_NONE, "20"))
        assertEquals("16", desktopDebandGrain(DESKTOP_FILM_GRAIN_LIGHT, "0"))
        assertEquals("32", desktopDebandGrain(DESKTOP_FILM_GRAIN_HEAVY, "0"))
    }

    @Test
    fun oledForcesInfiniteContrastInEveryHdrMode() {
        assertEquals("inf", desktopTargetContrast(DESKTOP_DISPLAY_PANEL_OLED, "auto"))
        assertEquals("inf", desktopTargetContrast(DESKTOP_DISPLAY_PANEL_OLED, "1000"))
        assertEquals("1000", desktopTargetContrast(null, "1000"))
        assertEquals("auto", desktopTargetContrast("lcd", "auto"))
    }

    @Test
    fun interpolationNeverStacksWithSvp() {
        assertEquals(
            DESKTOP_INTERPOLATION_OFF,
            desktopInterpolationProperties(choice = "yes", svpActive = true, audioPassthrough = false),
        )
        assertEquals(
            DESKTOP_INTERPOLATION_OFF,
            desktopInterpolationProperties(choice = null, svpActive = true, audioPassthrough = false),
        )
    }

    @Test
    fun interpolationNeedsResampleableAudio() {
        assertEquals(
            DESKTOP_INTERPOLATION_OFF,
            desktopInterpolationProperties(choice = "yes", svpActive = false, audioPassthrough = true),
        )
        val on = desktopInterpolationProperties(choice = "yes", svpActive = false, audioPassthrough = false).toMap()
        assertEquals("display-resample", on["video-sync"])
        assertEquals("yes", on["interpolation"])
    }

    @Test
    fun interpolationOffLeavesCustomOptionsAlone() {
        assertTrue(desktopInterpolationProperties(choice = null, svpActive = false, audioPassthrough = false).isEmpty())
    }
}
