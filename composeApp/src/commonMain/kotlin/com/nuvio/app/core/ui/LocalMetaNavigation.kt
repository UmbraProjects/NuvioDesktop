package com.nuvio.app.core.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Opens a title's details screen from anywhere in the tree, without threading a callback through
 * every intermediate composable.
 *
 * Exists for the screens that sit outside the browse flow — the local-library and monitored-titles
 * pages live under Settings, which is composed far from the navigation host and had no route to
 * details at all. Null on any platform or surface where details navigation is not available, so
 * callers must treat "no handler" as "hide the affordance" rather than assuming it is present.
 */
val LocalOpenMetaDetails = staticCompositionLocalOf<((type: String, id: String) -> Unit)?> { null }
