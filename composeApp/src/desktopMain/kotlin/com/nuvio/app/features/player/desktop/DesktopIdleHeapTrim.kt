package com.nuvio.app.features.player.desktop

import com.nuvio.app.core.ui.trimDecodedAnimationCache

/**
 * Returns committed-but-unused heap to the OS at the one moment a collection pause cannot be seen:
 * while the window is hidden or minimised.
 *
 * G1 only uncommits regions at the end of a *full* collection. Nothing in normal operation triggers
 * one, so the heap ratchets up to whatever the busiest moment needed and stays there — measured on
 * a real session, 640 MB committed against a 165 MB live set, holding for hours. `MaxHeapFreeRatio`
 * (set in the launch flags) decides how far a full collection then shrinks it; on that same session
 * the pair took the heap to 260 MB and the process working set down by 390 MB.
 *
 * The launch flags also run this on a timer, but only every 15 minutes, because a full collection
 * is a ~58 ms stop-the-world and on an idle-but-visible window that is a few dropped frames of any
 * animation on screen. Hiding the window removes that objection entirely, so when it happens the
 * trim is free and there is no reason to wait for the timer. Video playback is unaffected either
 * way: the native player owns its own window and threads, outside the JVM.
 *
 * Deliberately a plain [System.gc] rather than anything cleverer — with the JVM's default
 * `ExplicitGCInvokesConcurrent=false` that is exactly the full collection this needs, and it is the
 * same collection the periodic timer performs.
 *
 * The collector is only half the story on this app, though. Decoded animation frames are Skia
 * bitmaps in native memory, which no collection can see or reclaim, and they are much the larger
 * number — several hundred megabytes against the heap's few. So the same moment also drops that
 * cache back to its floor.
 */
internal object DesktopIdleHeapTrim {

    private var trimmedWhileHidden = false

    /**
     * Call whenever the window's visibility changes. [visible] is false for both a minimised window
     * and one hidden to the tray.
     *
     * Latched so a run of visibility events cannot chain full collections back to back: hiding
     * trims once, and nothing trims again until the window has been shown and hidden anew. The
     * latch is unsynchronised because every caller is on the AWT event thread; keep it that way.
     */
    fun onWindowVisibilityChanged(visible: Boolean) {
        if (visible) {
            trimmedWhileHidden = false
            return
        }
        if (trimmedWhileHidden) return
        trimmedWhileHidden = true
        // Native frames first: this is the bigger reclaim, and unlike the heap it is unaffected by
        // whether the collection below actually decides to uncommit anything.
        trimDecodedAnimationCache()
        System.gc()
    }
}
