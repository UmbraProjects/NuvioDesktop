package com.nuvio.app.features.player.desktop

import kotlin.math.abs

/**
 * fork:voice-control - turns every mpv instance down while voice control listens (the film, a hero
 * trailer, the music player are all [NativePlayerController]s), then back to where each was.
 *
 * Goes straight to mpv's `volume`, not [NativePlayerController.setVolume], so the level the person
 * chose is neither saved nor carried to the next episode as the ducked one.
 *
 * Handles are registered and unregistered under the controller's own handle lock, and every mpv call
 * here is made under [lock]: a handle being torn down is unregistered (waiting for any duck in
 * progress) before it is disposed, so this never touches a freed handle.
 */
internal object NativeAudioDuck {
    private val lock = Any()
    private val live = LinkedHashSet<Long>()

    /** Volume (mpv's 0..200) of each handle before the duck, while ducked. */
    private val before = HashMap<Long, Double>()
    private var ducked = false
    private var factor = 1.0

    fun register(handle: Long) = synchronized(lock) {
        if (handle == 0L) return
        live += handle
        // One that starts mid-duck (a trailer, the next episode) joins it.
        if (ducked) duckOne(handle)
    }

    fun unregister(handle: Long) = synchronized(lock) {
        live -= handle
        before -= handle
    }

    /** Every player handle alive now (full player and hero trailers), for the memory diagnostics. */
    fun liveHandles(): List<Long> = synchronized(lock) { live.toList() }

    /** Sets every live player to [toFraction] of its volume until [restore]. */
    fun duck(toFraction: Double) = synchronized(lock) {
        if (ducked) return
        ducked = true
        factor = toFraction
        live.forEach(::duckOne)
    }

    fun restore() = synchronized(lock) {
        if (!ducked) return
        ducked = false
        before.forEach { (handle, volume) ->
            if (handle !in live) return@forEach
            runCatching {
                // Changed while ducked (the person turned it up or down): theirs wins.
                val now = NativePlayerBridge.volume(handle).toDouble()
                if (abs(now - volume * factor) < 1.0) NativePlayerBridge.setVolume(handle, volume.toFloat())
            }
        }
        before.clear()
    }

    private fun duckOne(handle: Long) {
        runCatching {
            val volume = NativePlayerBridge.volume(handle).toDouble()
            before[handle] = volume
            NativePlayerBridge.setVolume(handle, (volume * factor).toFloat())
        }
    }
}
