package com.nuvio.app.core.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable internal expect fun NuvioDesktopVerticalScrollbar(state: LazyListState, modifier: Modifier = Modifier)
@Composable internal expect fun NuvioDesktopVerticalScrollbar(state: LazyGridState, modifier: Modifier = Modifier)
@Composable internal expect fun NuvioDesktopVerticalScrollbar(state: ScrollState, modifier: Modifier = Modifier, showInTvMode: Boolean = false)
/** A slim frosted-glass thumb for long menus over glass (the Discover menus), shown in TV mode too. */
@Composable internal expect fun NuvioGlassVerticalScrollbar(state: ScrollState, modifier: Modifier = Modifier)
