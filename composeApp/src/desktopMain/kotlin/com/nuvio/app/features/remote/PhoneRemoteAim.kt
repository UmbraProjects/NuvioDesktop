package com.nuvio.app.features.remote

import androidx.compose.ui.awt.ComposeWindow
import java.awt.Frame
import java.awt.Point
import java.awt.Rectangle
import javax.accessibility.Accessible
import javax.accessibility.AccessibleContext
import javax.swing.SwingUtilities
import kotlin.math.hypot
import kotlin.math.min

/**
 * fork:phone-remote pointer - aim assist, as consoles do it for a thumbstick: two light touches, only while the
 * phone moves slowly, so a deliberate sweep is never bent and a still phone never moves the cursor.
 *
 * - Friction: over something clickable, slow movement is scaled down, so the cursor is easier to stop on it. Moving
 *   away from its centre gets only a quarter of that, so leaving a target never feels sticky.
 * - Pull: near something clickable, a slow movement already heading toward it is bent a little toward its centre, at
 *   most a fraction of the movement itself. A movement going past or away is never pulled back (Nov 16: pulling every
 *   slow move felt like the assist was working against the hand).
 *
 * Both scale with the strength chosen in Settings (AimStrength).
 *
 * Pure maths; the targets come from [AimTargets].
 */
internal object AimAssist {
    /** Below this cursor speed (pixels a second, on a 1080p-wide screen) the assist starts to act. */
    const val SLOW_PX_PER_SECOND = 1_100.0

    /** The step over a target at a standstill-slow speed: just over half. */
    const val FRICTION = 0.55

    /** How far toward a near target's centre a slow step is turned, as a share of the gap. */
    const val PULL_STRENGTH = 0.14

    /** The pull is never more than this share of the step's own length. */
    const val PULL_CAP = 0.4

    /** How far away (pixels on a 1080p-wide screen) a target still pulls. */
    const val PULL_RADIUS_PX = 90.0

    /**
     * The step to take instead of ([dx], [dy]), from [cursor], moving at [pxPerSecond]. [over] is the target under the
     * cursor, [near] the nearest one within the pull radius (either may be null). [scale] converts this screen's
     * pixels to a 1080p width (screen width / 1920), so the feel is the same on a 4K TV.
     */
    fun adjust(
        dx: Double,
        dy: Double,
        cursor: Point,
        pxPerSecond: Double,
        over: Rectangle?,
        near: Rectangle?,
        scale: Double = 1.0,
        strength: Double = 1.0,
    ): Pair<Double, Double> {
        if (dx == 0.0 && dy == 0.0) return 0.0 to 0.0
        if (strength <= 0.0) return dx to dy
        val slowLimit = SLOW_PX_PER_SECOND * scale
        if (pxPerSecond >= slowLimit) return dx to dy
        // 0 at the fastest the assist acts on, 1 at a crawl.
        val slowness = 1.0 - pxPerSecond / slowLimit
        val step = hypot(dx, dy)
        val target = over ?: near
        val gapX = target?.let { it.centerX - cursor.x } ?: 0.0
        val gapY = target?.let { it.centerY - cursor.y } ?: 0.0
        val gap = hypot(gapX, gapY)
        // 1 heading straight at the target's centre, 0 across it, -1 straight away.
        val approach = if (target != null && gap > 0.5) (dx * gapX + dy * gapY) / (step * gap) else 0.0
        var x = dx
        var y = dy
        if (over != null) {
            val weight = AWAY_FRICTION_SHARE + (1.0 - AWAY_FRICTION_SHARE) * approach.coerceAtLeast(0.0)
            val factor = 1.0 - (1.0 - FRICTION) * slowness * strength * weight
            x *= factor
            y *= factor
        }
        // The pull only helps onto a target: once over one, friction alone (a pull there outran the hand).
        if (over == null && target != null && gap > 0.5 && approach > MIN_APPROACH &&
            gap <= PULL_RADIUS_PX * scale + target.width / 2.0
        ) {
            val pull = min(gap * PULL_STRENGTH * slowness * approach, step * PULL_CAP) * strength
            x += gapX / gap * pull
            y += gapY / gap * pull
        }
        return x to y
    }

    /** Moving away from a target's centre keeps this share of the friction: enough to stop on it, not to stick. */
    const val AWAY_FRICTION_SHARE = 0.25

    /** Only a movement at least this much toward a target (the cosine of about 70 degrees) is pulled. */
    const val MIN_APPROACH = 0.35

    /** Clickables larger than this share of the screen (a whole-page background) are not targets. */
    fun isTargetSized(target: Rectangle, screen: Rectangle): Boolean =
        target.width >= 8 && target.height >= 8 &&
            target.width.toLong() * target.height <= screen.width.toLong() * screen.height / 4
}

/**
 * Where the clickable things are, from Compose's own accessibility tree (every `clickable`, button, switch or row has
 * an action and its bounds on screen; plain text has none). Only asked while a phone is pointing and aim assist is
 * on, and at most every [REFRESH_MS] from the Swing thread, so pointing itself never waits on it. Nuvio's own windows
 * only: the player's HUD is a web page and is not covered.
 */
internal object AimTargets {
    private const val REFRESH_MS = 90L

    @Volatile
    var over: Rectangle? = null
        private set

    @Volatile
    var near: Rectangle? = null
        private set

    @Volatile
    private var lastRefreshMs = 0L

    @Volatile
    private var refreshing = false

    /** Asks for fresh targets around [cursor]; the answer lands in [over] and [near] a moment later. */
    fun refresh(cursor: Point, screen: Rectangle, radius: Int) {
        val now = System.currentTimeMillis()
        if (refreshing || now - lastRefreshMs < REFRESH_MS) return
        refreshing = true
        lastRefreshMs = now
        SwingUtilities.invokeLater {
            try {
                val window = Frame.getFrames().firstOrNull { it is ComposeWindow && it.isShowing && it.isActive }
                    ?: Frame.getFrames().firstOrNull { it is ComposeWindow && it.isShowing }
                if (window == null) {
                    over = null
                    near = null
                } else {
                    val root = window.accessibleContext
                    over = clickableAt(root, cursor, screen)
                    near = over ?: nearest(root, cursor, screen, radius)
                }
            } catch (_: Throwable) {
                // The tree changes under us while a screen recomposes; the next refresh tries again.
                over = null
                near = null
            } finally {
                refreshing = false
            }
        }
    }

    fun clear() {
        over = null
        near = null
    }

    /** Eight points around the cursor at two distances; the clickable with the nearest centre. */
    private fun nearest(root: AccessibleContext, cursor: Point, screen: Rectangle, radius: Int): Rectangle? {
        val found = mutableSetOf<Rectangle>()
        for (distance in listOf(radius / 2, radius)) {
            for (i in 0 until 8) {
                val angle = Math.PI / 4 * i
                val probe = Point(cursor.x + (Math.cos(angle) * distance).toInt(), cursor.y + (Math.sin(angle) * distance).toInt())
                clickableAt(root, probe, screen)?.let(found::add)
            }
        }
        return found.minByOrNull { hypot(it.centerX - cursor.x, it.centerY - cursor.y) }
    }

    /** The innermost accessible under [point], then up to the first one with an action (what a click would hit). */
    private fun clickableAt(root: AccessibleContext, point: Point, screen: Rectangle): Rectangle? {
        // Down through the children whose bounds hold the point, the last (topmost) first, as a click would land.
        var context = root
        var depth = 0
        while (depth++ < 60) {
            var next: AccessibleContext? = null
            for (i in context.accessibleChildrenCount - 1 downTo 0) {
                val child = (context.getAccessibleChild(i) as? Accessible)?.accessibleContext ?: continue
                val component = child.accessibleComponent ?: continue
                val origin = runCatching { component.locationOnScreen }.getOrNull() ?: continue
                val size = component.size ?: continue
                if (Rectangle(origin.x, origin.y, size.width, size.height).contains(point)) {
                    next = child
                    break
                }
            }
            context = next ?: break
        }
        var candidate: AccessibleContext? = context
        var up = 0
        while (candidate != null && up++ < 6) {
            if ((candidate.accessibleAction?.accessibleActionCount ?: 0) > 0) {
                val component = candidate.accessibleComponent ?: return null
                val origin = component.locationOnScreen ?: return null
                val size = component.size ?: return null
                val bounds = Rectangle(origin.x, origin.y, size.width, size.height)
                return bounds.takeIf { AimAssist.isTargetSized(it, screen) && it.contains(point) }
            }
            candidate = (candidate.accessibleParent as? Accessible)?.accessibleContext
        }
        return null
    }
}
