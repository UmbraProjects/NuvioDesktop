package com.nuvio.app.core.ui

import androidx.compose.ui.focus.FocusTargetModifierNode
import kotlin.test.Test
import kotlin.test.assertTrue

class FocusedControlTest {
    /**
     * focusedControl() reads Compose's focus owner reflectively. If an upgrade renames or moves the member, the music
     * player shortcut quietly loses its way back to the exact control; this says so instead.
     */
    @Test
    fun `the focus owner still exposes its active focus node`() {
        val getter = Class.forName("androidx.compose.ui.focus.FocusOwnerImpl").getMethod("getActiveFocusTargetNode")
        assertTrue(FocusTargetModifierNode::class.java.isAssignableFrom(getter.returnType))
    }
}
