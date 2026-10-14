package com.nuvio.app.features.streams

import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One open source picker: what it is choosing a stream for, and the screen that asked for it. */
data class StreamPickerRequest(
    val launchId: Long,
    val launch: StreamLaunch,
    /** Back-stack entry that opened the picker; leaving it for anything but the player closes it. */
    val ownerEntryId: String?,
)

/**
 * Where a screen offers to hold the picker, in window pixels: the details hero's trailer area, or
 * the TV-mode home hero's backdrop. [ownerKey] says whose it is ([forMeta] / [HOME]); a request
 * docks only into its own screen's area, and everything else gets the centred modal.
 */
data class StreamPickerDock(
    val ownerKey: String,
    val bounds: Rect,
) {
    companion object {
        const val HOME = "home"

        fun forMeta(metaId: String): String = "meta:$metaId"
    }
}

/**
 * The source picker used to be its own route. It is now a panel the app draws over whatever
 * opened it — docked into the details hero when the details page of that title asked, a centred
 * modal from everywhere else (continue watching, playlists, cloud library) — so picking a source
 * never leaves the page the user was on.
 */
object StreamPickerController {
    private var nextLaunchId = 1L

    private val _request = MutableStateFlow<StreamPickerRequest?>(null)
    val request: StateFlow<StreamPickerRequest?> = _request.asStateFlow()

    private val _dock = MutableStateFlow<StreamPickerDock?>(null)
    val dock: StateFlow<StreamPickerDock?> = _dock.asStateFlow()

    /** Bumped when the user dismisses the picker, so the screen beneath can take keyboard focus back. */
    private val _closedCount = MutableStateFlow(0)
    val closedCount: StateFlow<Int> = _closedCount.asStateFlow()

    val isOpen: Boolean get() = _request.value != null

    fun open(launch: StreamLaunch, ownerEntryId: String?) {
        val current = _request.value
        // Held Enter on Play repeats the same request every key-repeat; reopening would restart the
        // whole search each time.
        if (current != null && current.launch == launch && current.ownerEntryId == ownerEntryId) return
        _request.value = StreamPickerRequest(
            launchId = nextLaunchId++,
            launch = launch,
            ownerEntryId = ownerEntryId,
        )
    }

    /**
     * @param clearStreams false when playback is starting from this search: the player still reads
     * the loaded source list (its sources panel), so only a user closing the picker drops it.
     */
    fun close(clearStreams: Boolean = true) {
        if (_request.value == null) return
        _request.value = null
        if (clearStreams) {
            StreamsRepository.clear()
            // Only a dismissal hands focus back; closing for playback leaves it to the player.
            _closedCount.value += 1
        }
    }

    fun reportDock(ownerKey: String, bounds: Rect) {
        val next = StreamPickerDock(ownerKey, bounds)
        if (_dock.value != next) _dock.value = next
    }

    fun clearDock(ownerKey: String) {
        if (_dock.value?.ownerKey == ownerKey) _dock.value = null
    }

    /** Account switch / data wipe. */
    fun reset() {
        _request.value = null
        _dock.value = null
    }
}
