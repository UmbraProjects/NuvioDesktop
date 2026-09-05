package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

data class ThemeColorPalette(
    val secondary: Color,
    val secondaryVariant: Color,
    val nativeAccentHex: String,
    /**
     * Second stop of the accent gradient. Null means the theme paints accent surfaces flat, which
     * is what every built-in palette does; only the custom theme opts in to a gradient.
     */
    val accentGradientEnd: Color? = null,
    val onSecondary: Color = Color.White,
    val onSecondaryVariant: Color = Color.White,
    val focusRing: Color,
    val focusBackground: Color,
    val background: Color = Color(0xFF0D0D0D),
    val backgroundElevated: Color = Color(0xFF1A1A1A),
    val backgroundCard: Color = Color(0xFF242424),
)

/**
 * Which way the accent gradient runs across a surface it fills.
 *
 * Only meaningful when the theme actually has a second accent stop — every built-in palette paints
 * accents flat, so this is a custom-theme control. It is a display preference rather than palette
 * data, so it rides alongside amoled through [NuvioTheme] instead of living in [ThemeColorPalette].
 *
 * [cssAngle] keeps the player HUD's `linear-gradient(…)` string in step with the Compose brush from
 * one definition; CSS angles are measured clockwise from "to top", so 90deg is left-to-right.
 */
enum class AccentGradientDirection(val cssAngle: Int) {
    Horizontal(90),
    Vertical(180),
    Diagonal(135),
    DiagonalReverse(45),
    ;

    companion object {
        val Default = Horizontal

        fun fromStorageOrDefault(value: String?): AccentGradientDirection =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Default
    }
}

object ThemeColors {
    const val DefaultCustomAccentHex = "#FFD700"
    // Defaults to the accent itself so an upgrade keeps the flat accent the user already had.
    const val DefaultCustomAccentEndHex = DefaultCustomAccentHex
    const val DefaultCustomBackgroundHex = "#0B0F10"
    const val DefaultCustomElevatedHex = "#151D1F"
    const val DefaultCustomCardHex = "#182427"

    val Crimson = ThemeColorPalette(
        secondary = Color(0xFFE53935),
        secondaryVariant = Color(0xFFC62828),
        nativeAccentHex = "#E53935",
        focusRing = Color(0xFFFF5252),
        focusBackground = Color(0xFF3D1A1A),
        background = Color(0xFF0D0D0D),
        backgroundElevated = Color(0xFF1A1A1A),
        backgroundCard = Color(0xFF241A1A),
    )

    val Ocean = ThemeColorPalette(
        secondary = Color(0xFF1E88E5),
        secondaryVariant = Color(0xFF1565C0),
        nativeAccentHex = "#1E88E5",
        focusRing = Color(0xFF42A5F5),
        focusBackground = Color(0xFF1A2D3D),
        background = Color(0xFF0D0D0F),
        backgroundElevated = Color(0xFF1A1A1E),
        backgroundCard = Color(0xFF1A1F24),
    )

    val Violet = ThemeColorPalette(
        secondary = Color(0xFF8E24AA),
        secondaryVariant = Color(0xFF6A1B9A),
        nativeAccentHex = "#8E24AA",
        focusRing = Color(0xFFAB47BC),
        focusBackground = Color(0xFF2D1A3D),
        background = Color(0xFF0D0D0F),
        backgroundElevated = Color(0xFF1A1A1E),
        backgroundCard = Color(0xFF1F1A24),
    )

    val Emerald = ThemeColorPalette(
        secondary = Color(0xFF43A047),
        secondaryVariant = Color(0xFF2E7D32),
        nativeAccentHex = "#43A047",
        focusRing = Color(0xFF66BB6A),
        focusBackground = Color(0xFF1A3D1E),
        background = Color(0xFF0D0D0D),
        backgroundElevated = Color(0xFF1A1A1A),
        backgroundCard = Color(0xFF1A241A),
    )

    val Amber = ThemeColorPalette(
        secondary = Color(0xFFFB8C00),
        secondaryVariant = Color(0xFFEF6C00),
        nativeAccentHex = "#FB8C00",
        focusRing = Color(0xFFFFA726),
        focusBackground = Color(0xFF3D2D1A),
        background = Color(0xFF0F0D0D),
        backgroundElevated = Color(0xFF1E1A1A),
        backgroundCard = Color(0xFF24201A),
    )

    val Rose = ThemeColorPalette(
        secondary = Color(0xFFD81B60),
        secondaryVariant = Color(0xFFC2185B),
        nativeAccentHex = "#D81B60",
        focusRing = Color(0xFFEC407A),
        focusBackground = Color(0xFF3D1A2D),
        background = Color(0xFF0D0D0D),
        backgroundElevated = Color(0xFF1A1A1A),
        backgroundCard = Color(0xFF241A1F),
    )

    val White = ThemeColorPalette(
        secondary = Color(0xFFF5F5F5),
        secondaryVariant = Color(0xFFE0E0E0),
        nativeAccentHex = "#F5F5F5",
        onSecondary = Color(0xFF111111),
        onSecondaryVariant = Color(0xFF111111),
        focusRing = Color(0xFFFFFFFF),
        focusBackground = Color(0xFF303030),
        background = Color(0xFF0D0D0D),
        backgroundElevated = Color(0xFF1A1A1A),
        backgroundCard = Color(0xFF222222),
    )

    val Custom = customPalette(
        accentHex = DefaultCustomAccentHex,
        accentEndHex = DefaultCustomAccentEndHex,
        backgroundHex = DefaultCustomBackgroundHex,
        elevatedHex = DefaultCustomElevatedHex,
        cardHex = DefaultCustomCardHex,
    )

    fun customPalette(
        accentHex: String,
        accentEndHex: String = accentHex,
        backgroundHex: String,
        elevatedHex: String,
        cardHex: String,
    ): ThemeColorPalette {
        val accent = accentHex.toThemeColor(Color(0xFFFFD700))
        val accentEnd = accentEndHex.toThemeColor(accent)
        val background = backgroundHex.toThemeColor(Color(0xFF0B0F10))
        val elevated = elevatedHex.toThemeColor(Color(0xFF151D1F))
        val card = cardHex.toThemeColor(Color(0xFF182427))
        return ThemeColorPalette(
            secondary = accent,
            secondaryVariant = accentEnd,
            nativeAccentHex = accentHex.normalizedThemeHex(DefaultCustomAccentHex),
            accentGradientEnd = accentEnd.takeIf { it != accent },
            onSecondary = contentColorFor(accent),
            onSecondaryVariant = Color.White,
            focusRing = accent,
            focusBackground = card,
            background = background,
            backgroundElevated = elevated,
            backgroundCard = card,
        )
    }

    fun getColorPalette(theme: AppTheme): ThemeColorPalette = when (theme) {
        AppTheme.CRIMSON -> Crimson
        AppTheme.OCEAN -> Ocean
        AppTheme.VIOLET -> Violet
        AppTheme.EMERALD -> Emerald
        AppTheme.AMBER -> Amber
        AppTheme.ROSE -> Rose
        AppTheme.WHITE -> White
        AppTheme.CUSTOM -> Custom
    }
}

private fun contentColorFor(background: Color): Color =
    if (background.luminance() > 0.5f) Color(0xFF111111) else Color.White

private fun String.toThemeColor(fallback: Color): Color {
    val cleaned = trim().removePrefix("#")
    return runCatching {
        when (cleaned.length) {
            6 -> Color(("FF$cleaned").toLong(16))
            8 -> Color(cleaned.toLong(16))
            else -> fallback
        }
    }.getOrDefault(fallback)
}

fun String.normalizedThemeHex(fallback: String): String {
    val cleaned = trim().removePrefix("#")
    val valid = cleaned.length == 6 && cleaned.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    return if (valid) "#${cleaned.uppercase()}" else fallback
}
