package com.nuvio.app.core.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier

internal actual fun Modifier.horizontalListMouseInput(state: LazyListState, onFocusRequest: () -> Unit): Modifier = this
