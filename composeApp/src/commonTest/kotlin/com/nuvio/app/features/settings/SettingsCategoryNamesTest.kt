package com.nuvio.app.features.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsCategoryNamesTest {
    @Test
    fun storesARenameAgainstThePageKey() {
        val result = applySettingsCategoryName(
            current = emptyMap(),
            pageKey = "Playback",
            name = "Video",
            defaultLabel = "Playback",
        )

        assertEquals(mapOf("Playback" to "Video"), result)
    }

    @Test
    fun clearsTheOverrideWhenTheNameIsEmptied() {
        val result = applySettingsCategoryName(
            current = mapOf("Playback" to "Video", "Games" to "Play"),
            pageKey = "Playback",
            name = "   ",
            defaultLabel = "Playback",
        )

        assertEquals(mapOf("Games" to "Play"), result)
    }

    @Test
    fun clearsTheOverrideWhenTheShippedLabelIsTypedBack() {
        val result = applySettingsCategoryName(
            current = mapOf("Playback" to "Video"),
            pageKey = "Playback",
            name = "Playback",
            defaultLabel = "Playback",
        )

        assertEquals(emptyMap(), result)
    }

    @Test
    fun collapsesWhitespaceAndCapsLength() {
        val result = applySettingsCategoryName(
            current = emptyMap(),
            pageKey = "Playback",
            name = "  My   very long category name that runs well past the sidebar  ",
            defaultLabel = "Playback",
        )

        assertEquals("My very long category name that", result.getValue("Playback"))
    }
}
