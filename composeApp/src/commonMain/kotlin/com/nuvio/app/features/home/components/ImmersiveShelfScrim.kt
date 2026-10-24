package com.nuvio.app.features.home.components

import androidx.compose.ui.graphics.Color

/**
 * The left-hand darkening that keeps the hero's logo, synopsis, ratings and cast panel legible when
 * the backdrop runs the full width of the window.
 *
 * The shelf-bounded layout does not need this: its backdrop only occupies the right-hand
 * `HERO_BACKDROP_WIDTH_FRACTION` and fades out over a short ramp just past its own left edge, so the
 * metadata column sits on background rather than on artwork. Full backdrop paints artwork under the
 * text as well, and reusing that short ramp left the column on an unscrimmed picture — with a bright
 * backdrop, the title and synopsis simply could not be read.
 *
 * So this is the game library's Full backdrop ramp, which exists for exactly this problem: a long
 * fall across the left 70% of the window rather than a quick one over the left 30%, holding a heavy
 * scrim through the whole text column and reaching zero well clear of it. The right-hand 30% is
 * never touched, so the artwork is still shown as supplied where nothing is written over it.
 */
internal fun immersiveHeroSideScrimStops(
    backgroundColor: Color,
): Array<Pair<Float, Color>> = arrayOf(
    0f to backgroundColor.copy(alpha = 0.96f),
    0.22f to backgroundColor.copy(alpha = 0.74f),
    0.42f to backgroundColor.copy(alpha = 0.44f),
    0.60f to backgroundColor.copy(alpha = 0.125f),
    0.70f to backgroundColor.copy(alpha = 0f),
    1f to backgroundColor.copy(alpha = 0f),
)

/**
 * The darkening laid over the bottom of a TV-style screen so the shelf's titles and posters stay
 * legible against whatever artwork is behind them.
 *
 * Two separate layers land in this region and multiply together: the hero's own bottom fade
 * ([immersiveHeroBottomFadeStops]) and the shelf container's background
 * ([immersiveShelfScrimStops]). Tuning either in isolation is how the region ends up either
 * unreadable or solid black, so both live here and `ImmersiveShelfScrimTest` renders them stacked
 * and reads the result back.
 *
 * With [fullBackdrop] off, the stack reaches the opaque background colour — that is TV Mode's black
 * shelf, and the hero deliberately stops painting artwork above it. With it on, the artwork runs to
 * the bottom of the screen and both layers stop short, so the shelf reads as floating over the
 * picture. This mirrors the game library's Black shelf / Full backdrop choice.
 */
internal fun immersiveShelfScrimStops(
    backgroundColor: Color,
    fullBackdrop: Boolean,
    // Collections' folder view dims its shelf less when the ambient background is on, since that
    // already tints the whole screen. Irrelevant under full backdrop, which never reaches opaque.
    ambientBackgroundEnabled: Boolean = false,
): Array<Pair<Float, Color>> = if (fullBackdrop) {
    arrayOf(
        0f to backgroundColor.copy(alpha = 0f),
        0.30f to backgroundColor.copy(alpha = 0.08f),
        0.62f to backgroundColor.copy(alpha = 0.38f),
        1f to backgroundColor.copy(alpha = 0.68f),
    )
} else {
    arrayOf(
        0f to backgroundColor.copy(alpha = 0f),
        0.30f to backgroundColor.copy(alpha = 0.28f),
        0.62f to backgroundColor.copy(alpha = if (ambientBackgroundEnabled) 0.74f else 0.88f),
        1f to backgroundColor.copy(alpha = if (ambientBackgroundEnabled) 0.82f else 1f),
    )
}

/**
 * The hero's own bottom fade, which sits under [immersiveShelfScrimStops] in the shelf region.
 *
 * Under [fullBackdrop] this is deliberately much lighter than the shelf-bounded version: it is no
 * longer the thing ending the artwork, only a gradient easing into the shelf's own scrim, and at
 * the old strength the two multiplied out to very nearly opaque.
 */
internal fun immersiveHeroBottomFadeStops(
    backgroundColor: Color,
    fullBackdrop: Boolean,
): Array<Pair<Float, Color>> = if (fullBackdrop) {
    arrayOf(
        0f to backgroundColor.copy(alpha = 0f),
        0.30f to backgroundColor.copy(alpha = 0.10f),
        0.60f to backgroundColor.copy(alpha = 0.28f),
        1f to backgroundColor.copy(alpha = 0.42f),
    )
} else {
    arrayOf(
        0f to backgroundColor.copy(alpha = 0f),
        0.38f to backgroundColor.copy(alpha = 0.18f),
        0.68f to backgroundColor.copy(alpha = 0.72f),
        1f to backgroundColor,
    )
}
