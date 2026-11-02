package com.nuvio.app.features.trailer

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YouTubeHdrFormatTest {
    @Test
    fun pqHlgOrAnHdrLabelIsHdr() {
        assertTrue(isHdrYouTubeFormat("COLOR_TRANSFER_CHARACTERISTICS_SMPTEST2084", "1440p60"))
        assertTrue(isHdrYouTubeFormat("COLOR_TRANSFER_CHARACTERISTICS_ARIB_STD_B67", null))
        assertTrue(isHdrYouTubeFormat(null, "2160p60 HDR"))
    }

    @Test
    fun anSdrEncodeIsNot() {
        assertFalse(isHdrYouTubeFormat("COLOR_TRANSFER_CHARACTERISTICS_BT709", "1440p60"))
        assertFalse(isHdrYouTubeFormat(null, "1080p"))
    }
}
