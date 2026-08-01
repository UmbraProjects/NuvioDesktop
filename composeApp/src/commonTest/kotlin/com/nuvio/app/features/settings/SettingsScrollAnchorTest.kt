package com.nuvio.app.features.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsScrollAnchorTest {
    @Test
    fun `exact destination consumes request before fallback`() {
        SettingsScrollAnchor.request(
            anchor = "child",
            fallbackAnchor = "parent",
            fallbackTitle = "Parent",
        )
        val request = requireNotNull(SettingsScrollAnchor.requested.value)

        assertFalse(SettingsScrollAnchor.consume("parent", request.sequence))
        assertTrue(SettingsScrollAnchor.consume("child", request.sequence))
        assertNull(SettingsScrollAnchor.requested.value)
    }

    @Test
    fun `visible fallback consumes unresolved exact destination`() {
        SettingsScrollAnchor.request(
            anchor = "hidden-child",
            fallbackAnchor = "visible-parent",
            fallbackTitle = "Visible parent",
        )
        val request = requireNotNull(SettingsScrollAnchor.requested.value)

        assertTrue(
            SettingsScrollAnchor.consume(
                anchor = "visible-parent",
                sequence = request.sequence,
                allowFallback = true,
            ),
        )
        assertNull(SettingsScrollAnchor.requested.value)
    }

    @Test
    fun `stale fallback cannot consume a newer request`() {
        SettingsScrollAnchor.request("old-child", fallbackAnchor = "old-parent")
        val oldRequest = requireNotNull(SettingsScrollAnchor.requested.value)
        SettingsScrollAnchor.request("new-child", fallbackAnchor = "new-parent")

        assertFalse(
            SettingsScrollAnchor.consume(
                anchor = "old-parent",
                sequence = oldRequest.sequence,
                allowFallback = true,
            ),
        )
        assertTrue(SettingsScrollAnchor.requested.value?.anchor == "new-child")
    }
}
