package com.nuvio.app.features.player.desktop

import com.nuvio.app.core.storage.DesktopStorage

internal data class DesktopWindowGeometry(val x: Float, val y: Float, val width: Float, val height: Float)

internal object DesktopWindowModeStorage {
    private val store = DesktopStorage.store("nuvio_window_state")
    fun loadWindowedGeometry(): DesktopWindowGeometry? {
        val x = store.getFloat("window_x") ?: return null
        val y = store.getFloat("window_y") ?: return null
        val width = store.getFloat("window_width") ?: return null
        val height = store.getFloat("window_height") ?: return null
        return DesktopWindowGeometry(x, y, width, height)
    }
    fun saveWindowedGeometry(value: DesktopWindowGeometry) {
        store.putFloat("window_x", value.x)
        store.putFloat("window_y", value.y)
        store.putFloat("window_width", value.width)
        store.putFloat("window_height", value.height)
    }
}
