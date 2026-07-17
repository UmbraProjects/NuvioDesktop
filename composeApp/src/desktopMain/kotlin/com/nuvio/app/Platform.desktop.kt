package com.nuvio.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import com.nuvio.app.features.player.desktop.desktopAppFullscreenState
import com.nuvio.app.features.player.desktop.toggleDesktopAppFullscreen
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.beans.PropertyChangeListener
import kotlin.math.roundToInt

class DesktopPlatform : Platform {
    override val name: String = "Desktop ${System.getProperty("os.name").orEmpty()}".trim()
}

actual fun getPlatform(): Platform = DesktopPlatform()

internal actual val isIos: Boolean = false
internal actual val isDesktop: Boolean = true
internal actual val isWindows: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")

private var activeDesktopDisplayMetrics by mutableStateOf(defaultDesktopDisplayMetrics())

internal actual fun desktopDisplayMetrics(): DesktopDisplayMetrics? = activeDesktopDisplayMetrics

/**
 * Re-evaluates the display containing the centre of [window]. AWT's `graphicsConfiguration` can
 * lag behind the pointer while a window is crossing monitors, so selecting from all configurations
 * makes the hand-off deterministic as soon as most of the window reaches the destination display.
 */
private fun updateDesktopDisplayMetrics(window: Window) {
    activeDesktopDisplayMetrics = runCatching {
        metricsForConfiguration(configurationForWindow(window))
    }.getOrNull() ?: activeDesktopDisplayMetrics
}

internal fun installDesktopDisplayMetricsTracking(window: Window): () -> Unit {
    val componentListener = object : ComponentAdapter() {
        override fun componentMoved(event: ComponentEvent) = updateDesktopDisplayMetrics(window)
        override fun componentResized(event: ComponentEvent) = updateDesktopDisplayMetrics(window)
        override fun componentShown(event: ComponentEvent) = updateDesktopDisplayMetrics(window)
    }
    val graphicsConfigurationListener = PropertyChangeListener {
        updateDesktopDisplayMetrics(window)
    }
    val displayChangeListener = PropertyChangeListener {
        updateDesktopDisplayMetrics(window)
    }

    window.addComponentListener(componentListener)
    window.addPropertyChangeListener("graphicsConfiguration", graphicsConfigurationListener)
    Toolkit.getDefaultToolkit().addPropertyChangeListener("displayChange", displayChangeListener)
    updateDesktopDisplayMetrics(window)

    return {
        window.removeComponentListener(componentListener)
        window.removePropertyChangeListener("graphicsConfiguration", graphicsConfigurationListener)
        Toolkit.getDefaultToolkit().removePropertyChangeListener("displayChange", displayChangeListener)
    }
}

private fun configurationForWindow(window: Window): GraphicsConfiguration {
    val centreX = window.x + window.width / 2
    val centreY = window.y + window.height / 2
    val configurations = GraphicsEnvironment.getLocalGraphicsEnvironment()
        .screenDevices
        .map { it.defaultConfiguration }
    return configurations.firstOrNull { it.bounds.contains(centreX, centreY) }
        ?: configurations.maxByOrNull { configuration ->
            val intersection = configuration.bounds.intersection(window.bounds)
            intersection.width.coerceAtLeast(0).toLong() * intersection.height.coerceAtLeast(0).toLong()
        }
        ?: window.graphicsConfiguration
        ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
}

private fun defaultDesktopDisplayMetrics(): DesktopDisplayMetrics? = runCatching {
    metricsForConfiguration(
        GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration,
    )
}.getOrNull()

private fun metricsForConfiguration(configuration: GraphicsConfiguration): DesktopDisplayMetrics {
    val transform = configuration.defaultTransform
    val density = transform.scaleX.toFloat().takeIf { it.isFinite() && it > 0f } ?: 1f
    val verticalDensity = transform.scaleY.toFloat().takeIf { it.isFinite() && it > 0f } ?: density
    val bounds = configuration.bounds
    return DesktopDisplayMetrics(
        sizePx = IntSize(
            width = (bounds.width * density).roundToInt().coerceAtLeast(1),
            height = (bounds.height * verticalDensity).roundToInt().coerceAtLeast(1),
        ),
        density = density,
    )
}

@Composable
internal actual fun isAppFullscreen(): Boolean = desktopAppFullscreenState.value

internal actual fun toggleAppFullscreen() = toggleDesktopAppFullscreen()
