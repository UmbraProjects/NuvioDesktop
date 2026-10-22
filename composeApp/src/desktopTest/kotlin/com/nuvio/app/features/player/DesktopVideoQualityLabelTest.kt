package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopVideoQualityLabelTest {
    @Test
    fun unknownSizeShowsNothing() {
        assertEquals("", desktopVideoQualityLabel(null, isHdr = true))
    }

    @Test
    fun scopeAndCroppedEncodesKeepTheirClass() {
        assertEquals("4K", desktopVideoQualityLabel(DesktopVideoSourceSize(3840, 1600), isHdr = false))
        assertEquals("1080p", desktopVideoQualityLabel(DesktopVideoSourceSize(1920, 800), isHdr = null))
        assertEquals("720p", desktopVideoQualityLabel(DesktopVideoSourceSize(1280, 536), isHdr = false))
        assertEquals("480p", desktopVideoQualityLabel(DesktopVideoSourceSize(854, 480), isHdr = false))
    }

    @Test
    fun hdrIsAppendedOnlyWhenDetected() {
        assertEquals("4K HDR", desktopVideoQualityLabel(DesktopVideoSourceSize(3840, 2160), isHdr = true))
        assertEquals("1080p", desktopVideoQualityLabel(DesktopVideoSourceSize(1920, 1080), isHdr = null))
    }
}
