package com.nuvio.app.features.home.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Renders the TV-mode home hero trailer over the focused item's backdrop. Only desktop has
 * a real implementation (native mpv surface with a web-overlay fade); other platforms are
 * no-ops because the feature is desktop-only for now.
 *
 * [backgroundColor] is the hero background used by the desktop overlay to draw the edge and
 * bottom fade gradients (equivalent to the Compose backdrop fade masks) on top of the
 * heavyweight video panel, which Compose cannot paint over.
 */
@Composable
expect fun HomeHeroTrailerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    playWhenReady: Boolean,
    muted: Boolean,
    backgroundColor: Color,
    logoUrl: String?,
    title: String,
    meta: String,
    description: String,
    modifier: Modifier,
    onReady: () -> Unit,
    onEnded: () -> Unit,
    onError: () -> Unit,
)
