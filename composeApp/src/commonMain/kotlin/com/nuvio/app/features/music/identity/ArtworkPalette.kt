package com.nuvio.app.features.music.identity

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The colours an artist's room is built from, derived once from artwork and stored with the library.
 *
 * Colours are opaque ARGB held as [Long] (`0xFFRRGGBB`) so the stored JSON stays plain and this file
 * needs no Compose types: everything here is a pure function and runs in common tests.
 */
@Serializable
data class ArtworkPalette(
    /** Buttons, progress lines, links. Readable on a dark surface (>= 4.5:1). */
    val accent: Long,
    /** Text or icons drawn on [accent] (>= 4.5:1). */
    val onAccent: Long,
    /** Mid-dark wash of the same hue: era tints, chip fills, glows. */
    val tint: Long,
    /** Near-black of the same hue: the ground behind a room. */
    val ground: Long,
    /**
     * The image URL this palette was asked for (the first candidate, whether or not it was the one
     * that yielded a colour). When the artist's art changes this stops matching and the palette is
     * derived again; otherwise it is never recomputed.
     */
    val forUrl: String? = null,
)

/** Pure colour maths for [ArtworkPalette]. Inputs and outputs are `0xRRGGBB` / `0xAARRGGBB` ints. */
object ArtworkPaletteMath {
    /** The darkest surface an accent must stand out against (the mock's warm ground, #0E0C0D). */
    private const val SURFACE_RGB = 0x0E0C0D
    private const val MIN_ACCENT_CONTRAST = 4.5
    private const val MIN_SATURATION = 0.50f
    private const val MAX_SATURATION = 0.90f
    private const val GREY_BELOW = 0.12f
    private const val MIN_LIGHTNESS = 0.50f
    private const val MAX_LIGHTNESS = 0.66f
    private const val LIGHTNESS_CEILING = 0.85f
    private const val NEAR_BLACK = 0x0B0B0B
    private const val WHITE = 0xFFFFFF

    /**
     * Turns a raw sampled colour [seedRgb] into a full palette. The hue is kept; saturation and
     * lightness are clamped so the accent is vivid but never muddy or neon, then lightened until it
     * reads on a dark surface. A near-grey seed gives a neutral grey accent rather than inventing a hue.
     */
    fun derive(seedRgb: Int, forUrl: String? = null): ArtworkPalette {
        val (h, s0, l0) = rgbToHsl(seedRgb)
        val grey = s0 < GREY_BELOW
        val s = if (grey) 0f else s0.coerceIn(MIN_SATURATION, MAX_SATURATION)
        var l = l0.coerceIn(MIN_LIGHTNESS, MAX_LIGHTNESS)
        var accent = hslToRgb(h, s, l)
        while (contrast(accent, SURFACE_RGB) < MIN_ACCENT_CONTRAST && l < LIGHTNESS_CEILING) {
            l += 0.01f
            accent = hslToRgb(h, s, l)
        }
        val on = if (contrast(accent, NEAR_BLACK) >= contrast(accent, WHITE)) NEAR_BLACK else WHITE
        return ArtworkPalette(
            accent = opaque(accent),
            onAccent = opaque(on),
            tint = opaque(hslToRgb(h, (s * 0.6f).coerceAtMost(0.6f), 0.28f)),
            ground = opaque(hslToRgb(h, (s * 0.35f).coerceAtMost(0.3f), 0.055f)),
            forUrl = forUrl,
        )
    }

    /** WCAG contrast ratio between two `0xRRGGBB` colours, 1.0 to 21.0. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    fun luminance(rgb: Int): Double {
        fun channel(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel((rgb shr 16) and 0xFF) +
            0.7152 * channel((rgb shr 8) and 0xFF) +
            0.0722 * channel(rgb and 0xFF)
    }

    private fun opaque(rgb: Int): Long = 0xFF000000L or (rgb.toLong() and 0xFFFFFFL)

    /** Hue 0..1, saturation 0..1, lightness 0..1. */
    internal fun rgbToHsl(rgb: Int): Triple<Float, Float, Float> {
        val r = ((rgb shr 16) and 0xFF) / 255f
        val g = ((rgb shr 8) and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val l = (maxC + minC) / 2f
        val d = maxC - minC
        if (d == 0f) return Triple(0f, 0f, l)
        val s = d / (1f - abs(2f * l - 1f))
        val h = when (maxC) {
            r -> ((g - b) / d).mod(6f)
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } / 6f
        return Triple(h, s.coerceIn(0f, 1f), l)
    }

    internal fun hslToRgb(h: Float, s: Float, l: Float): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((h * 6f).mod(2f) - 1f))
        val m = l - c / 2f
        val (r, g, b) = when ((h * 6f).toInt().mod(6)) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun byte(v: Float) = ((v + m) * 255f).roundToInt().coerceIn(0, 255)
        return (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
    }
}
