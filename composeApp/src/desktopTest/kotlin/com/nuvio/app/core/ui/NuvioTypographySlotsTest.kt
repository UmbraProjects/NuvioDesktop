package com.nuvio.app.core.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The theme tunes eight typography slots by hand and Material fills the rest from its own defaults,
 * which carry `FontFamily.Default`. Naming the slots one at a time is exactly how `bodySmall` help
 * text ended up on the host's system sans while everything around it was JetBrains Sans, so the
 * list is checked by reflection: a slot Material adds in a later version fails here rather than
 * quietly rendering in the wrong font.
 */
class NuvioTypographySlotsTest {

    private val typographySlots = Typography::class.java.methods
        .filter { it.parameterCount == 0 && it.returnType == TextStyle::class.java }
        .sortedBy { it.name }

    @Test
    fun `every material typography slot takes the app font`() {
        // Monospace only because it is trivially distinguishable from the default; nothing about
        // the substitution is specific to it.
        val family = FontFamily.Monospace
        val typography = Typography().withFontFamily(family)

        assertTrue(typographySlots.isNotEmpty(), "no typography slots were found by reflection")
        typographySlots.forEach { slot ->
            val style = slot.invoke(typography) as TextStyle
            assertEquals(family, style.fontFamily, "${slot.name} did not take the app font")
        }
    }

    @Test
    fun `material defaults are what makes this necessary`() {
        // Guards the premise. Every untouched slot comes back as FontFamily.SansSerif — the host's
        // generic sans, which on Windows is Segoe UI. That is why settings help text rendered in a
        // different face from the row titles above it, and why naming eight slots was not enough.
        val defaults = Typography()
        typographySlots.forEach { slot ->
            val style = slot.invoke(defaults) as TextStyle
            assertEquals(
                FontFamily.SansSerif,
                style.fontFamily,
                "${slot.name} no longer defaults to the generic sans; re-check whether the copy above is still needed",
            )
        }
    }
}
