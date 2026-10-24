package com.nuvio.app.core.build

/**
 * Fires once per process when the Home screen has rendered its first content — the point the
 * launch overlay comes down. Platform startup work that wants to know when "startup" ended (the
 * desktop class preloader's learning run, for one) registers here from platform code, so the app
 * layer does not need to know it exists.
 */
object StartupReadySignal {
    @Volatile
    private var listener: (() -> Unit)? = null

    @Volatile
    private var fired = false

    fun onHomeReady(block: () -> Unit) {
        if (fired) block() else listener = block
    }

    fun notifyHomeReady() {
        if (fired) return
        fired = true
        listener?.invoke()
        listener = null
    }
}
