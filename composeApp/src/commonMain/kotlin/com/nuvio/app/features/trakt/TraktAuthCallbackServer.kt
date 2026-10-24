package com.nuvio.app.features.trakt

internal expect object TraktAuthCallbackServer {
    fun ensureStarted()
    fun stop()
}
