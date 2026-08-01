package com.nuvio.app.core.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable internal expect fun NuvioDesktopVerticalScrollbar(state: LazyListState, modifier: Modifier = Modifier)
@Composable internal expect fun NuvioDesktopVerticalScrollbar(state: LazyGridState, modifier: Modifier = Modifier)
@Composable internal expect fun NuvioDesktopVerticalScrollbar(state: ScrollState, modifier: Modifier = Modifier)
