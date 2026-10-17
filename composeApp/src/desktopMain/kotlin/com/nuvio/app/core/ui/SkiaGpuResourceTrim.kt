package com.nuvio.app.core.ui

import co.touchlab.kermit.Logger
import java.awt.Component
import java.awt.Container
import java.awt.Window
import java.lang.reflect.Field
import java.util.WeakHashMap
import javax.swing.SwingUtilities
import org.jetbrains.skia.DirectContext
import org.jetbrains.skiko.SkiaLayer

/**
 * Lowers Skia's GPU resource cache while the UI is in the background (full player open, window
 * hidden) and restores it afterwards.
 *
 * Measured during 4K playback: the Compose renderer kept ~870 MB of write-combined upload memory
 * (artwork textures from the screen behind the player) plus its VRAM textures, while mpv — which
 * draws the video into its own D3D11 surface — needed none of it. Lowering the limit makes Skia
 * purge every texture and staging buffer that is not in use by the frame being recorded; the UI
 * re-uploads what it draws once it is visible again.
 *
 * Skiko does not expose the [DirectContext] of a window, so it is reached by reflection
 * (`SkiaLayer.redrawer → contextHandler → context`). Any failure leaves the cache untouched.
 * The context is only touched on the EDT, the thread skiko's redrawers record frames on.
 */
internal object SkiaGpuResourceTrim {

    private const val BackgroundCacheLimitBytes = 32L * 1024 * 1024

    private val log = Logger.withTag("SkiaGpuTrim")

    /** Browsing limit per context, so a context created while restricted is still restored. */
    private val browsingLimits = WeakHashMap<DirectContext, Long>()

    fun setBackgroundMode(restricted: Boolean) {
        SwingUtilities.invokeLater { apply(restricted) }
    }

    private fun apply(restricted: Boolean) {
        val contexts = Window.getWindows().flatMap { window -> skiaLayers(window) }
            .mapNotNull { layer -> runCatching { directContext(layer) }.getOrNull() }
            .distinct()
        if (contexts.isEmpty()) {
            log.i { "no Skia GPU context found (restricted=$restricted)" }
            return
        }
        contexts.forEach { context ->
            runCatching {
                val browsing = browsingLimits.getOrPut(context) { context.resourceCacheLimit }
                val target = if (restricted) minOf(browsing, BackgroundCacheLimitBytes) else browsing
                if (restricted) {
                    // Lowering the limit alone left one 256 MB write-combined upload chunk
                    // committed for the whole of playback (memory map, 2026-10-16). Skiko has no
                    // freeGpuResources(), so: purge everything unlocked (limit 0), then flush and
                    // submit with a CPU sync so the GPU is done with the staging/upload buffers and
                    // Skia can release them. One short stall, while the player covers the window.
                    context.resourceCacheLimit = 0
                    context.flush()
                    context.submit(true)
                }
                context.resourceCacheLimit = target
                log.i { "resource cache limit ${target / MB}MB (browsing=${browsing / MB}MB restricted=$restricted)" }
            }.onFailure { log.i { "resource cache limit change failed: ${it.message}" } }
        }
    }

    private fun skiaLayers(component: Component): List<SkiaLayer> = when (component) {
        is SkiaLayer -> listOf(component)
        is Container -> component.components.flatMap(::skiaLayers)
        else -> emptyList()
    }

    private fun directContext(layer: SkiaLayer): DirectContext? {
        val redrawer = SkiaLayer::class.java.getMethod("getRedrawer\$skiko").invoke(layer) ?: return null
        val handler = field(redrawer.javaClass, "contextHandler")?.get(redrawer) ?: return null
        return field(handler.javaClass, "context")?.get(handler) as? DirectContext
    }

    private fun field(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { current.getDeclaredField(name) }.getOrNull()?.let {
                it.isAccessible = true
                return it
            }
            current = current.superclass
        }
        return null
    }

    private const val MB = 1024 * 1024
}
