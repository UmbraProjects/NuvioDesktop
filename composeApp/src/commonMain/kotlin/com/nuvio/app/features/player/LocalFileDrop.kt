package com.nuvio.app.features.player

import kotlinx.coroutines.flow.SharedFlow

/**
 * Carries drag-and-drop file URIs from the OS into the player navigation path.
 * Desktop actual: attaches an AWT DropTarget to the window and emits file:// URIs.
 * Android/iOS actuals: no-op stubs (feature is desktop-only).
 */
expect object LocalFileDrop {
    val events: SharedFlow<String>
}
