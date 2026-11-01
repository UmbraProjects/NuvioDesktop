package com.nuvio.app.features.music

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.desktop.AwtNativeViewResolver
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import com.nuvio.app.features.player.desktop.NativePlayerHost
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.Area
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import javax.swing.SwingUtilities
import javax.swing.Timer

private val log = Logger.withTag("MusicBezel")

/**
 * fork:music-stage - what the glass bezel round the Now Playing video is made of, in pixels. The bezel is a ring laid over the
 * video's own window: its inner edge is the rounded opening the picture shows through (so the video's square corners are covered,
 * not cut), its outer edge stands [rim] outside the video, and [halo] more is a soft shadow.
 */
internal data class BezelSpec(
    /** The video window's size. */
    val innerWidth: Int,
    val innerHeight: Int,
    /** How far the ring reaches outside the video window. */
    val rim: Int,
    /** The soft shadow beyond the ring. */
    val halo: Int,
    /** How far the ring reaches over the video's edge, so no edge of the window shows. */
    val lip: Int,
    /** The opening's corner radius. */
    val radius: Int,
) {
    val margin: Int get() = rim + halo
    val width: Int get() = innerWidth + margin * 2
    val height: Int get() = innerHeight + margin * 2
}

/** The bezel as premultiplied ARGB pixels, [BezelSpec.width] x [BezelSpec.height], row by row. */
internal fun renderBezel(spec: BezelSpec): IntArray {
    val w = spec.width
    val h = spec.height
    val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE)
    val g = image.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        val m = spec.margin.toFloat()
        val rim = spec.rim.toFloat()
        val lip = spec.lip.toFloat()
        val outerRadius = (spec.radius + spec.rim).toFloat()

        fun outer(grow: Float) = RoundRectangle2D.Float(
            m - rim - grow, m - rim - grow,
            spec.innerWidth + (rim + grow) * 2, spec.innerHeight + (rim + grow) * 2,
            (outerRadius + grow) * 2, (outerRadius + grow) * 2,
        )
        val opening = RoundRectangle2D.Float(
            m + lip, m + lip, spec.innerWidth - lip * 2, spec.innerHeight - lip * 2,
            spec.radius * 2f, spec.radius * 2f,
        )

        // The shadow: many faint layers, so it is darkest against the ring and gone at the edge of the image.
        if (spec.halo > 0) {
            val layerAlpha = (70f / spec.halo).coerceAtLeast(1f).toInt()
            g.color = Color(0, 0, 0, layerAlpha)
            for (k in spec.halo downTo 1) g.fill(outer(k.toFloat()))
        }
        // The shadow layers cover the whole outer shape, the opening too: clear it, so the picture is not dimmed.
        val drawing = g.composite
        g.composite = AlphaComposite.Clear
        g.fill(opening)
        g.composite = drawing

        val ring = Area(outer(0f)).apply { subtract(Area(opening)) }
        // Silver, a little see-through, lighter at the top as the panels' glass is.
        g.paint = GradientPaint(0f, m - rim, Color(206, 209, 219, 236), 0f, h - (m - rim), Color(96, 100, 112, 236))
        g.fill(ring)
        // A sheen across the upper left.
        g.clip = ring
        g.paint = GradientPaint(m - rim, m - rim, Color(255, 255, 255, 96), w * 0.62f, h * 0.62f, Color(255, 255, 255, 0))
        g.fill(ring)
        g.clip = null

        // The hairlines the panels have: light on the outer edge, a dark line where the picture begins.
        g.stroke = BasicStroke(1f)
        g.color = Color(255, 255, 255, 170)
        g.draw(outer(-0.5f))
        g.stroke = BasicStroke(2f)
        g.color = Color(0, 0, 0, 150)
        g.draw(opening)
        g.stroke = BasicStroke(1f)
        g.color = Color(255, 255, 255, 70)
        g.draw(RoundRectangle2D.Float(opening.x - 1.5f, opening.y - 1.5f, opening.width + 3f, opening.height + 3f, opening.arcwidth + 3f, opening.archeight + 3f))
    } finally {
        g.dispose()
    }
    return (image.raster.dataBuffer as DataBufferInt).data.copyOf()
}

/**
 * fork:music-stage - keeps the bezel over whichever video window is on the Now Playing page. Used on the AWT event thread only.
 * Each track opens a new video window, so a window that goes away does not hide the bezel at once: a short grace lets the next
 * one take it over without a blink.
 */
internal object NowPlayingBezel {
    private var current: NativePlayerHost? = null
    private var lastSpec: BezelSpec? = null
    private var pixels: IntArray? = null
    private var hideTimer: Timer? = null

    /** True while the bezel is up, so the page leaves out the rim it draws without one ([MusicPlayback.inlineBezel]). */
    private var active: Boolean
        get() = MusicPlayback.inlineBezel.value
        set(value) { MusicPlayback.inlineBezel.value = value }

    /** Shows or re-fits the bezel round [host]; [scale] is the pixels in each of the host's own units, [density] the app's. */
    fun show(host: NativePlayerHost, scale: Double, density: Float) {
        check(SwingUtilities.isEventDispatchThread())
        hideTimer?.stop()
        val widthPx = (host.width * scale).toInt()
        val heightPx = (host.height * scale).toInt()
        if (widthPx <= 8 || heightPx <= 8) return
        val spec = BezelSpec(
            innerWidth = widthPx,
            innerHeight = heightPx,
            rim = (BEZEL_RIM_DP * density).toInt(),
            halo = (BEZEL_HALO_DP * density).toInt(),
            lip = (BEZEL_LIP_DP * density).toInt().coerceAtLeast(2),
            radius = (BEZEL_RADIUS_DP * density).toInt(),
        )
        val pointer = runCatching { AwtNativeViewResolver.resolveNativeViewPointer(host) }.getOrDefault(0L)
        if (pointer == 0L) return
        try {
            if (spec != lastSpec || pixels == null) {
                pixels = renderBezel(spec)
                lastSpec = spec
                NativePlayerBridge.bezelShow(pointer, pixels!!, spec.width, spec.height, spec.margin)
            } else if (current !== host) {
                NativePlayerBridge.bezelShow(pointer, pixels!!, spec.width, spec.height, spec.margin)
            } else {
                NativePlayerBridge.bezelMove(pointer)
            }
            current = host
            active = true
        } catch (error: UnsatisfiedLinkError) {
            // An older player library has no bezel: the page keeps the rim it draws itself.
            log.w { "no bezel in this player library: ${error.message}" }
            active = false
        }
    }

    /** Re-places the bezel after the app window moved. */
    fun follow(host: NativePlayerHost) {
        if (current !== host || !active) return
        val pointer = runCatching { AwtNativeViewResolver.resolveNativeViewPointer(host) }.getOrDefault(0L)
        if (pointer != 0L) runCatching { NativePlayerBridge.bezelMove(pointer) }
    }

    /** [host] is going away (or is no longer the Now Playing picture): hides the bezel unless another window takes it over. */
    fun release(host: NativePlayerHost) {
        if (current !== host) return
        hideTimer?.stop()
        hideTimer = Timer(BEZEL_GRACE_MS) {
            if (current === host) {
                current = null
                active = false
                runCatching { NativePlayerBridge.bezelHide() }
            }
        }.apply {
            isRepeats = false
            start()
        }
    }
}

private const val BEZEL_RIM_DP = 9f
private const val BEZEL_HALO_DP = 8f
private const val BEZEL_LIP_DP = 3f
private const val BEZEL_RADIUS_DP = 16f
private const val BEZEL_GRACE_MS = 450
