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
    volume: Int,
    backgroundColor: Color,
    logoUrl: String?,
    title: String,
    meta: String,
    description: String,
    modifier: Modifier,
    // Which screen edge holds the navigation chrome ("top"/"left"/"none"). When the pointer enters
    // that edge band over the trailer, [onNavChromeDismiss] fires so the caller can stop the trailer
    // and uncover the navbar the heavyweight video surface would otherwise paint over.
    navDismissEdge: String = "none",
    // Top nav-dismiss band height as a fraction of the surface height. Smaller for the compact,
    // resizable adaptive hero than for TV mode's full-viewport hero.
    navDismissBandFraction: Float = 0.22f,
    onReady: () -> Unit,
    onEnded: () -> Unit,
    onError: () -> Unit,
    onNavChromeDismiss: () -> Unit = {},
    onVolumeChange: (Int) -> Unit = {},
    // Called when this surface leaves composition — including when the default (non-adaptive,
    // non-TV) home layout scrolls the hero out of the LazyColumn's viewport and Compose
    // recycles it mid-playback. The native panel can be left holding real OS keyboard focus at
    // that point, silently swallowing all further key presses; the caller should reclaim
    // keyboard focus for its own focusable content here.
    onSurfaceDisposed: () -> Unit = {},
)
