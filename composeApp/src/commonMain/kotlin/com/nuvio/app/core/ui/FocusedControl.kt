package com.nuvio.app.core.ui

import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusTargetModifierNode

/**
 * The control that holds focus right now, so focus can be put back on exactly it later (the music player shortcut
 * leaves a page and returns). Compose keeps this internal; null where it cannot be read.
 */
expect fun FocusManager.focusedControl(): FocusTargetModifierNode?
