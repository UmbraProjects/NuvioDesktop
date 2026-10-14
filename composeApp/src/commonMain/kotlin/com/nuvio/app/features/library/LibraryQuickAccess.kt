package com.nuvio.app.features.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class LibraryQuickAccessTarget { LocalLibrary, LocalLibrarySettings }

/**
 * Shortcuts from the Library nav item's right-click menu into the local library. The menu lives in
 * several nav chromes (floating top bar, hover sidebar, bottom bar); App collects [requests] and
 * opens the LocalLibrary settings page. [browserPending] says whether the full-screen titles browser
 * should be open over that page (true for LocalLibrary, false for its settings, so a browser left
 * open there earlier doesn't hide them); the settings screen applies it once the page is showing.
 */
object LibraryQuickAccess {
    private val _requests = MutableSharedFlow<LibraryQuickAccessTarget>(extraBufferCapacity = 1)
    val requests: SharedFlow<LibraryQuickAccessTarget> = _requests.asSharedFlow()

    var browserPending by mutableStateOf<Boolean?>(null)

    fun open(target: LibraryQuickAccessTarget) {
        browserPending = target == LibraryQuickAccessTarget.LocalLibrary
        _requests.tryEmit(target)
    }
}
