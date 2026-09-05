package com.nuvio.app.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle

/**
 * Paints whatever this modifier wraps with the theme's accent gradient.
 *
 * `Icon` takes a `Color` tint and `Text` a `Color`, neither of which can express a [Brush], which is
 * why accent-coloured icons and labels stayed flat while filled accent surfaces picked the gradient
 * up for free through `accentFill`. Masking is the way round it: draw the content, then paint the
 * gradient over it with [BlendMode.SrcIn] so it survives only where the content already drew.
 *
 * Prefer wrapping the smallest element that should read as one sweep. Applying this to a row that
 * holds an icon and its label gives a single gradient running across the pair, which reads better
 * than each of them carrying its own miniature copy of the same ramp.
 *
 * Two things to know before reaching for it:
 * - It forces the content into an offscreen layer, which is not free. It is meant for the handful
 *   of accent-coloured icons and labels on screen at once, not for list items at scroll scale.
 * - It is a no-op when the theme paints accents flat (every built-in palette), so the layer is only
 *   ever paid for by a custom theme that actually defines a second accent stop. That also means the
 *   caller still needs a sensible flat `tint`/`color`; this replaces it when a gradient exists
 *   rather than being the only thing that colours the content.
 */
@Composable
fun Modifier.accentGradientMask(): Modifier {
    val tokens = MaterialTheme.nuvio
    if (tokens.colors.accentGradientEnd == null) return this
    val brush = tokens.colors.accentFill
    return this
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            drawRect(brush = brush, blendMode = BlendMode.SrcIn)
        }
}

/**
 * [this] painted with the accent gradient when the theme defines one, and untouched when it does
 * not.
 *
 * The text counterpart to [accentGradientMask], and the one to reach for on a label: `TextStyle`
 * can carry a `Brush` directly, so this costs no offscreen layer and is safe on rows inside a
 * list. The mask stays the only option for `Icon`, whose tint is a `Color`.
 *
 * Leaves the caller's `color` in place rather than replacing it. A brush on the style beats both
 * `LocalContentColor` and an explicit `color` argument — verified against rendered pixels, not
 * assumed — so the existing colour goes on being exactly right for the flat themes, where this
 * returns the style unchanged.
 */
@Composable
fun TextStyle.accentBrush(): TextStyle {
    val colors = MaterialTheme.nuvio.colors
    return if (colors.accentGradientEnd == null) this else copy(brush = colors.accentFill)
}
