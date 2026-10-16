package com.nuvio.app.features.player.desktop

import java.awt.Canvas
import java.awt.Color
import java.awt.Graphics
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

internal class NativePlayerHost : Canvas() {
    var onPeerReady: (() -> Unit)? = null
    var onDisplayableChanged: ((Boolean) -> Unit)? = null
    var onFirstPaint: (() -> Unit)? = null
    var onFirstFullSizePaint: (() -> Unit)? = null
    private var firstPaintNotified = false
    private var firstFullSizePaintNotified = false

    init {
        background = Color.BLACK
        ignoreRepaint = false
        // The attach waits on a full-size paint, but Windows does not always send one: re-entering
        // the player shortly after leaving it painted once at 0x0 and then got no paint for the
        // resize, leaving playback stalled until something else exposed the window. Ask for one.
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (!firstFullSizePaintNotified && width > 1 && height > 1) repaint()
            }
        })
    }

    override fun update(graphics: Graphics) {
        paint(graphics)
    }

    override fun paint(graphics: Graphics) {
        graphics.color = Color.BLACK
        graphics.fillRect(0, 0, width, height)
        if (!firstPaintNotified) {
            firstPaintNotified = true
            onFirstPaint?.invoke()
        }
        if (!firstFullSizePaintNotified && width > 1 && height > 1) {
            firstFullSizePaintNotified = true
            onFirstFullSizePaint?.invoke()
        }
    }

    /**
     * Last resort for when the requested repaint never arrives either: if the surface is on screen
     * at full size, treat it as painted. Call on the EDT. Returns true when this call fired it.
     */
    fun notifyFullSizeIfReady(): Boolean {
        if (firstFullSizePaintNotified || !isShowing || width <= 1 || height <= 1) return false
        firstPaintNotified = true
        firstFullSizePaintNotified = true
        onFirstFullSizePaint?.invoke()
        return true
    }

    override fun addNotify() {
        super.addNotify()
        onDisplayableChanged?.invoke(true)
        repaint()
        onPeerReady?.invoke()
    }

    override fun removeNotify() {
        onDisplayableChanged?.invoke(false)
        firstPaintNotified = false
        firstFullSizePaintNotified = false
        onPeerReady = null
        onFirstPaint = null
        onFirstFullSizePaint = null
        super.removeNotify()
    }
}
