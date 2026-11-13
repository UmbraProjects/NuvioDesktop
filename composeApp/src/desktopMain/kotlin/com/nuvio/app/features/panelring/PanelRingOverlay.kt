package com.nuvio.app.features.panelring

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.awt.ComposeWindow
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.ThemeColors
import com.nuvio.app.features.input.GamepadSettingsRepository
import com.nuvio.app.features.settings.ThemeSettingsRepository
import com.nuvio.app.features.navbar.NavBarEntry
import com.nuvio.app.features.navbar.rememberNavBarEntries
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import java.awt.Point
import kotlin.math.roundToInt

/** Dark, nearly opaque discs, so a tile reads the same over any screen. The highlight is the theme's accent. */
private val TileFill = Color(0xF8121418)
private val TileBorder = Color(0x1FFFFFFF)
private val HubTrack = Color(0x40FFFFFF)
private val Scrim = Color(0x8C000000)

/**
 * The theme's accent, read from the theme settings: the overlay is hosted in Main, outside App()'s
 * NuvioTheme, so MaterialTheme.nuvio here would be the default palette, not the user's.
 */
@Composable
private fun rememberRingAccent(): Color {
    val theme by remember {
        ThemeSettingsRepository.ensureLoaded()
        ThemeSettingsRepository.selectedTheme
    }.collectAsState()
    val custom by remember { ThemeSettingsRepository.customTheme }.collectAsState()
    return if (theme == AppTheme.CUSTOM) custom.palette.secondary else ThemeColors.getColorPalette(theme).secondary
}

/**
 * Draws the panel ring over the whole window while [PanelRingController] says it is open, and
 * publishes the slots (the navigation's visible entries in order, then Settings) at all times so
 * the pad thread can aim before the first frame. Hosted in Main beside the on-screen keyboard.
 *
 * Nothing in it moves with content: every tile has the same size and sits on the same circle; only
 * the accent highlight, the pointer and the centre label change with the aim.
 */
@Composable
internal fun PanelRingOverlay(window: ComposeWindow) {
    val entries = rememberNavBarEntries()
    val slots = remember(entries) { panelRingSlots(entries) }
    SideEffect { PanelRingController.slots = slots }

    val state by PanelRingController.state.collectAsState()
    val size by GamepadSettingsRepository.ringSizeState.collectAsState()
    val padButton by GamepadSettingsRepository.ringButtonState.collectAsState()
    val shown = state != null
    val progress by animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) 110 else 80), label = "ring")
    if (!shown && progress == 0f) return
    val current = state ?: PanelRingState(PanelRingSource.Keyboard)
    val density = LocalDensity.current
    val accent = rememberRingAccent()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .alpha(progress)
            .background(Scrim)
            // The scrim takes every pointer event: the AWT listener acts on the clicks, and none
            // may reach the screen underneath.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
    ) {
        val radius = size.radiusDp.dp
        val tile = size.tileDp.dp
        val margin = radius + tile / 2 + 16.dp
        // Centre: at the pointer for a ring the mouse opened (kept inside the window), else the
        // middle of the window.
        val anchor = mouseAnchorDp(window, current, density.density)
        val centerX = (anchor?.first ?: (maxWidth / 2)).coerceIn(margin, (maxWidth - margin).coerceAtLeast(margin))
        val centerY = (anchor?.second ?: (maxHeight / 2)).coerceIn(margin, (maxHeight - margin).coerceAtLeast(margin))
        SideEffect { PanelRingInput.centerOnScreen = screenPoint(window, centerX, centerY, density.density) }

        Box(Modifier.fillMaxSize()) {
            val hubRadius = (radius * 0.34f).coerceAtLeast(46.dp)
            RingHub(
                centerX = centerX,
                centerY = centerY,
                hubRadius = hubRadius,
                hovered = current.hovered,
                count = slots.size,
                accent = accent,
            )
            CentreLabel(
                centerX = centerX,
                centerY = centerY,
                width = hubRadius * 2 - 16.dp,
                title = current.hovered?.let { slots.getOrNull(it) }?.let { slotLabel(it) }
                    ?: stringResource(Res.string.panel_ring_hint_aim),
                hint = when (current.source) {
                    PanelRingSource.Keyboard -> stringResource(Res.string.panel_ring_hint_keyboard)
                    PanelRingSource.Mouse -> stringResource(Res.string.panel_ring_hint_mouse)
                    PanelRingSource.Gamepad -> stringResource(
                        Res.string.panel_ring_hint_gamepad,
                        padButton?.displayName ?: "A",
                    )
                },
            )
            slots.forEachIndexed { index, slot ->
                val (ux, uy) = PanelRingMath.slotUnitOffset(index, slots.size)
                RingTile(
                    slot = slot,
                    number = if (current.source == PanelRingSource.Keyboard) panelRingNumberLabel(index) else null,
                    hovered = current.hovered == index,
                    tile = tile,
                    accent = accent,
                    modifier = Modifier.offset {
                        IntOffset(
                            ((centerX + radius * ux - tile / 2).toPx()).roundToInt(),
                            ((centerY + radius * uy - tile / 2).toPx()).roundToInt(),
                        )
                    },
                )
            }
        }
    }
}

/**
 * The centre: a thin ring, and the pointer as a short arc of it in the accent, turned towards the
 * aimed slot.
 */
@Composable
private fun RingHub(centerX: Dp, centerY: Dp, hubRadius: Dp, hovered: Int?, count: Int, accent: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val center = Offset(centerX.toPx(), centerY.toPx())
        val r = hubRadius.toPx()
        drawCircle(HubTrack, radius = r, center = center, style = Stroke(width = 1.5.dp.toPx()))
        if (hovered == null || count <= 0) return@Canvas
        // Compose arcs start at three o'clock; the ring's angles start at twelve.
        val sweep = (360f / count).coerceAtMost(40f) * 0.8f
        val start = PanelRingMath.slotAngleDegrees(hovered, count) - 90f - sweep / 2f
        drawArc(
            color = accent,
            startAngle = start,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(center.x - r, center.y - r),
            size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
            style = Stroke(width = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}

@Composable
private fun CentreLabel(centerX: Dp, centerY: Dp, width: Dp, title: String, hint: String) {
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .offset { IntOffset((centerX - width / 2).roundToPx(), (centerY - 26.dp).roundToPx()) }
                .width(width),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = hint,
                color = Color.White.copy(alpha = 0.66f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun RingTile(slot: PanelRingSlot, number: String?, hovered: Boolean, tile: Dp, accent: Color, modifier: Modifier) {
    Box(
        modifier = modifier
            .size(tile)
            .scale(if (hovered) 1.08f else 1f)
            .background(TileFill, CircleShape)
            .border(if (hovered) 2.dp else 1.dp, if (hovered) accent else TileBorder, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        SlotIcon(slot = slot, tint = if (hovered) accent else Color.White, size = tile * 0.42f)
        if (number != null) {
            // A small disc on the tile's upper right, outside the circle like a badge.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
                    .background(if (hovered) accent else Color(0xFF2A2D33), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = number,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** The same icons the sidebar uses for each entry. */
@Composable
private fun SlotIcon(slot: PanelRingSlot, tint: Color, size: Dp) {
    val modifier = Modifier.size(size)
    val label = slotLabel(slot)
    when (slot) {
        PanelRingSlot.Settings -> Icon(Icons.Rounded.Settings, label, modifier, tint)
        is PanelRingSlot.Nav -> when (slot.entry) {
            NavBarEntry.Home -> Icon(Icons.Filled.Home, label, modifier, tint)
            NavBarEntry.Search -> Icon(painterResource(Res.drawable.sidebar_search), label, modifier, tint)
            NavBarEntry.Tonight -> Icon(Icons.Rounded.AutoAwesome, label, modifier, tint)
            NavBarEntry.Discover -> Icon(Icons.Rounded.Explore, label, modifier, tint)
            NavBarEntry.Library -> Icon(painterResource(Res.drawable.sidebar_library), label, modifier, tint)
            NavBarEntry.LiveTv -> Icon(Icons.Rounded.LiveTv, label, modifier, tint)
            NavBarEntry.YouTube -> Icon(Icons.Rounded.SmartDisplay, label, modifier, tint)
            NavBarEntry.Music -> Icon(Icons.Rounded.MusicNote, label, modifier, tint)
            NavBarEntry.Games -> Icon(Icons.Rounded.SportsEsports, label, modifier, tint)
        }
    }
}

/** The sidebar's names; YouTube, Music, What to watch and Games are literal there too. */
@Composable
private fun slotLabel(slot: PanelRingSlot): String = when (slot) {
    PanelRingSlot.Settings -> stringResource(Res.string.compose_settings_page_root)
    is PanelRingSlot.Nav -> when (slot.entry) {
        NavBarEntry.Home -> stringResource(Res.string.compose_nav_home)
        NavBarEntry.Search -> stringResource(Res.string.compose_nav_search)
        NavBarEntry.Discover -> stringResource(Res.string.compose_nav_discover)
        NavBarEntry.Library -> stringResource(Res.string.compose_nav_library)
        NavBarEntry.LiveTv -> stringResource(Res.string.iptv_settings_page)
        NavBarEntry.Tonight, NavBarEntry.YouTube, NavBarEntry.Music, NavBarEntry.Games -> slot.entry.label
    }
}

/**
 * The window point (in dp) a mouse-opened ring is anchored at, or null to centre it. The state
 * carries screen points (AWT units); the window's content origin and its pixel scale turn them
 * into this layer's dp, whatever the app's UI scale is.
 */
private fun mouseAnchorDp(window: ComposeWindow, state: PanelRingState, density: Float): Pair<Dp, Dp>? {
    val x = state.anchorX ?: return null
    val y = state.anchorY ?: return null
    val origin = runCatching { window.contentPane.locationOnScreen }.getOrNull() ?: return null
    val scale = window.graphicsConfiguration?.defaultTransform?.scaleX?.toFloat() ?: 1f
    return ((x - origin.x) * scale / density).dp to ((y - origin.y) * scale / density).dp
}

/** The inverse of [mouseAnchorDp]: where a point of this layer is on screen, for aiming with the mouse. */
private fun screenPoint(window: ComposeWindow, x: Dp, y: Dp, density: Float): Point? {
    val origin = runCatching { window.contentPane.locationOnScreen }.getOrNull() ?: return null
    val scale = window.graphicsConfiguration?.defaultTransform?.scaleX?.toFloat() ?: 1f
    return Point(
        (origin.x + x.value * density / scale).roundToInt(),
        (origin.y + y.value * density / scale).roundToInt(),
    )
}
