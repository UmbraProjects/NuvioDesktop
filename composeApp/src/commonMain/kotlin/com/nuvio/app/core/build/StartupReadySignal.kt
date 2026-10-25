package com.nuvio.app.core.build

/**
 * Fires once per process when the Home screen has rendered its first content — the point the
 * launch overlay comes down. Platform startup work that wants to know when "startup" ended (the
 * desktop class preloader's learning run, for one) registers here from platform code, so the app
 * layer does not need to know it exists.
 */
object StartupReadySignal {
    // Several listeners: the class preloader's learning run and deferred startup work both wait here.
    private val listeners = mutableListOf<() -> Unit>()

    private var fired = false

    fun onHomeReady(block: () -> Unit) {
        val runNow = synchronized(listeners) {
            if (!fired) listeners += block
            fired
        }
        if (runNow) block()
    }

    fun notifyHomeReady() {
        val toRun = synchronized(listeners) {
            if (fired) return
            fired = true
            listeners.toList().also { listeners.clear() }
        }
        toRun.forEach { it() }
    }
}
