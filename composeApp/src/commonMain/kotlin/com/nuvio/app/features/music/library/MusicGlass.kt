package com.nuvio.app.features.music.library

import com.nuvio.app.core.ui.smoothBorder
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.nuvio.app.core.ui.NuvioGlass
import com.nuvio.app.core.ui.KeyboardInputMode
import com.nuvio.app.core.ui.ditheredBackground
import com.nuvio.app.core.ui.nuvio

/*
 * fork:music
 *
 * The app's frosted glass (Settings, Live TV) for the Music section, so its pills, cards and panels
 * read as part of the same app rather than as flat grey slabs. One place, so a change to the look
 * reaches every music screen.
 */

/** A hovered or focused piece of glass: the same fill, a step lighter. */
internal val MusicGlassRaised = Color.White.copy(alpha = 0.06f)

/**
 * The glass fill and edge on [shape]: lighter while [raised] (hovered), the app accent while
 * [selected], and the focus ring in place of the edge while [keyFocused].
 */
@Composable
internal fun Modifier.musicGlass(
    shape: Shape,
    raised: Boolean = false,
    selected: Boolean = false,
    keyFocused: Boolean = false,
): Modifier {
    val tokens = MaterialTheme.nuvio
    // A ring only while the keyboard is in use: a click focuses too, and a ring under the pointer means nothing.
    val ringed = keyFocused && KeyboardInputMode.active
    return this
        .clip(shape)
        .then(
            if (selected) Modifier.background(tokens.colors.accent, shape)
            else Modifier.ditheredBackground(NuvioGlass.Fill, shape),
        )
        .background(if (raised && !selected) MusicGlassRaised else Color.Transparent, shape)
        .smoothBorder(
            width = if (ringed) 2.dp else 1.dp,
            brush = if (ringed) SolidColor(tokens.colors.borderFocus) else NuvioGlass.Edge,
            shape = shape,
        )
}

/**
 * The focus ring for a music control that has no glass of its own (icon buttons, rows, covers): drawn on [shape]
 * while the control, or something inside it, holds keyboard or pad focus. Goes before the control's clickable.
 */
@Composable
internal fun Modifier.musicKeyFocusRing(shape: Shape = RoundedCornerShape(10.dp)): Modifier {
    var focused by remember { mutableStateOf(false) }
    val ring = MaterialTheme.nuvio.colors.borderFocus
    return this
        .onFocusChanged { focused = it.hasFocus }
        .then(if (focused && KeyboardInputMode.active) Modifier.smoothBorder(2.dp, ring, shape) else Modifier)
}
