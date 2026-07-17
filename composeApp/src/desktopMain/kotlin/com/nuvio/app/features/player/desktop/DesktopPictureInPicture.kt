package com.nuvio.app.features.player.desktop

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import java.awt.Window
import javax.swing.SwingUtilities

private object DesktopPictureInPicture {
    private var handler: ((Boolean, Window?) -> Unit)? = null
    val isActive: MutableState<Boolean> = mutableStateOf(false)

    fun setHandler(next: ((Boolean, Window?) -> Unit)?): () -> Unit {
        handler = next
        return {
            if (handler === next) handler = null
        }
    }

    fun setActive(active: Boolean, window: Window? = null) {
        if (isActive.value == active && handler != null) return
        isActive.value = active
        val currentHandler = handler ?: return
        SwingUtilities.invokeLater { currentHandler(active, window) }
    }
}

internal fun registerDesktopPictureInPictureHandler(handler: (Boolean, Window?) -> Unit): () -> Unit =
    DesktopPictureInPicture.setHandler(handler)

internal fun setDesktopPictureInPicture(active: Boolean, window: Window? = null) {
    DesktopPictureInPicture.setActive(active, window)
}

internal val desktopPictureInPictureState: MutableState<Boolean>
    get() = DesktopPictureInPicture.isActive
