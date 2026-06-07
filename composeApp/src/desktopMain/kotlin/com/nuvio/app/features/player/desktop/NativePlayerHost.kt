package com.nuvio.app.features.player.desktop

import java.awt.Canvas
import java.awt.Color

internal class NativePlayerHost : Canvas() {
    var onPeerReady: (() -> Unit)? = null

    init {
        background = Color.BLACK
        ignoreRepaint = true
    }

    override fun addNotify() {
        super.addNotify()
        onPeerReady?.invoke()
    }

    override fun removeNotify() {
        onPeerReady = null
        super.removeNotify()
    }
}
