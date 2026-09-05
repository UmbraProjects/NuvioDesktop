package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A selected menu option is marked by its own colour rather than by a fill behind it, so the mark
 * only exists if that colour differs from ordinary label text. On most themes the accent does; on
 * one it does not, and that theme is the reason this function is not simply `accent`.
 */
class SelectionTextColorTest {

    private fun tokens(
        accent: Color,
        onAccent: Color = Color(0xFF111111),
        gradientEnd: Color? = null,
    ) = defaultNuvioThemeTokens(
        palette = ThemeColors.White.copy(
            secondary = accent,
            onSecondary = onAccent,
            accentGradientEnd = gradientEnd,
        ),
        amoled = false,
        colorScheme = null,
    ).colors

    /**
     * The White theme's accent is `#F5F5F5` and its body text `#F5F7F8`. Painting a selected label
     * in that accent leaves it identical to every unselected one, so the mark has to come from the
     * other end of the palette instead.
     */
    @Test
    fun `a near-white accent falls back to on-accent`() {
        val colors = tokens(accent = Color(0xFFF5F5F5))

        assertEquals(colors.onAccent, colors.selectionTextColor())
    }

    @Test
    fun `a coloured accent marks the selection itself`() {
        listOf(Color(0xFF00E5FF), Color(0xFF7C5CFF), Color(0xFFE36A8A), Color(0xFF66BB6A))
            .forEach { accent ->
                val colors = tokens(accent = accent)
                assertEquals(accent, colors.selectionTextColor(), "accent $accent should be used")
            }
    }

    /**
     * The fallback is about telling two colours apart, not about brightness: an accent far enough
     * from the text colour is used whichever side of it the accent sits on.
     */
    @Test
    fun `a near-black accent is used as it is`() {
        val accent = Color(0xFF101010)
        val colors = tokens(accent = accent)

        assertEquals(accent, colors.selectionTextColor())
    }

    /**
     * A `Color` carries one stop, so on a gradient theme the mark has to go on as a brush or the
     * sweep arrives as its first colour alone.
     */
    @Test
    fun `a gradient accent marks the selection with a brush`() {
        val colors = tokens(accent = Color(0xFF7C5CFF), gradientEnd = Color(0xFF00E5FF))

        assertEquals(colors.accentFill, colors.selectionTextBrush())
    }

    @Test
    fun `a flat accent needs no brush`() {
        assertNull(tokens(accent = Color(0xFF7C5CFF)).selectionTextBrush())
    }

    /**
     * When the accent is too pale to mark text at all, the label falls back to on-accent — and a
     * brush would then paint it the very colour the fallback exists to avoid.
     */
    @Test
    fun `a gradient accent too pale to mark text gets no brush either`() {
        val colors = tokens(accent = Color(0xFFF5F5F5), gradientEnd = Color(0xFFFAFAFA))

        assertEquals(colors.onAccent, colors.selectionTextColor())
        assertNull(colors.selectionTextBrush())
    }
}
