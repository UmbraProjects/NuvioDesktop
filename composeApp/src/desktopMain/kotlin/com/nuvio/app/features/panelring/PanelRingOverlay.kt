package com.nuvio.app.features.panelring

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PowerSettingsNew
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Dark obsidian palette for the segmented radial wheel; highlights use the active theme's accent. */
private val WheelInnerFill = Color(0xEA101319)
private val WheelOuterFill = Color(0xF50B0D12)
private val WheelActiveBase = Color(0xF20F131B)
private val WheelSectorBorder = Color(0x16FFFFFF)
private val WheelOuterHairline = Color(0x18FFFFFF)
private val WheelShadow = Color(0x5A000000)
private val HubTopFill = Color(0xF71A1F28)
private val HubEdgeFill = Color(0xFC0C0E13)
private val HubBorder = Color(0x24FFFFFF)
private val KeycapIdleFill = Color(0x18FFFFFF)
private val KeycapIdleBorder = Color(0x24FFFFFF)
private val KeycapDarkText = Color(0xFF090B0F)
private val ScrimCenter = Color(0xB806080C)
private val ScrimMid = Color(0x8406080C)
private val ScrimEdge = Color(0xB006080C)
private val KeycapShape = RoundedCornerShape(5.dp)

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
    val includeCloseApp by remember {
        GamepadSettingsRepository.ensureLoaded()
        GamepadSettingsRepository.ringCloseAppState
    }.collectAsState()
    val slots = remember(entries, includeCloseApp) { panelRingSlots(entries, includeCloseApp = includeCloseApp) }
    SideEffect { PanelRingController.slots = slots }

    val state by PanelRingController.state.collectAsState()
    val size by GamepadSettingsRepository.ringSizeState.collectAsState()
    val padButton by GamepadSettingsRepository.ringButtonState.collectAsState()
    val shown = state != null
    val progress by animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) 110 else 80), label = "ring")
    // The fade-out draws the ring as it last stood: a fresh default state has no mouse anchor,
    // which made a ring opened with the middle button jump to the window's centre as it closed.
    // A plain holder, not state: it is only read on the recompositions the fade already drives.
    val lastShown = remember { arrayOfNulls<PanelRingState>(1) }
    state?.let { lastShown[0] = it }
    if (!shown && progress == 0f) return
    val current = state ?: lastShown[0] ?: PanelRingState(PanelRingSource.Keyboard)
    val density = LocalDensity.current
    val accent = rememberRingAccent()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .alpha(progress)
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
        val outerRadius = radius + tile * 0.45f
        val innerRadius = (outerRadius * 0.50f).coerceAtLeast(78.dp)
        val hubRadius = innerRadius - 8.dp
        val hoverPop = 6.dp
        val margin = outerRadius + hoverPop + 16.dp
        // Centre: at the pointer for a ring the mouse opened (kept inside the window), else the
        // middle of the window.
        val anchor = mouseAnchorDp(window, current, density.density)
        val centerX = (anchor?.first ?: (maxWidth / 2)).coerceIn(margin, (maxWidth - margin).coerceAtLeast(margin))
        val centerY = (anchor?.second ?: (maxHeight / 2)).coerceIn(margin, (maxHeight - margin).coerceAtLeast(margin))
        SideEffect { PanelRingInput.centerOnScreen = screenPoint(window, centerX, centerY, density.density) }

        val showNumbers = current.source == PanelRingSource.Keyboard
        val bandThickness = outerRadius - innerRadius
        val iconRadius = if (showNumbers) {
            innerRadius + bandThickness * 0.58f
        } else {
            innerRadius + bandThickness * 0.50f
        }
        val keycapRadius = innerRadius + bandThickness * 0.19f
        val hoveredSlot = current.hovered?.let { slots.getOrNull(it) }

        Box(Modifier.fillMaxSize()) {
            // Radial vignette scrim centered on the wheel so poster art stays subtle underneath.
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(centerX.toPx(), centerY.toPx())
                val scrimRadius = maxOf(this.size.width, this.size.height) * 0.72f
                drawRect(
                    brush = Brush.radialGradient(
                        0.0f to ScrimCenter,
                        0.52f to ScrimMid,
                        1.0f to ScrimEdge,
                        center = center,
                        radius = scrimRadius.coerceAtLeast(1f),
                    ),
                )
            }

            // Segmented wheel geometry + center hub + directional pointer.
            SegmentedWheelCanvas(
                centerX = centerX,
                centerY = centerY,
                innerRadius = innerRadius,
                outerRadius = outerRadius,
                hubRadius = hubRadius,
                hoverPop = hoverPop,
                hovered = current.hovered,
                count = slots.size,
                accent = accent,
                openScale = 0.94f + 0.06f * progress,
            )

            // Icons & inner-rim keycaps for each sector.
            val openScale = 0.94f + 0.06f * progress
            val iconSize = (tile * 0.36f).coerceIn(22.dp, 34.dp)
            val keycapOnAccent = if (accent.luminance() > 0.45f) KeycapDarkText else Color.White
            slots.forEachIndexed { index, slot ->
                val isHovered = current.hovered == index
                val (ux, uy) = PanelRingMath.slotUnitOffset(index, slots.size)
                val effectiveIconRadius = (if (isHovered) iconRadius + 2.dp else iconRadius) * openScale
                val effectiveKeycapRadius = keycapRadius * openScale
                val iconScale by animateFloatAsState(
                    targetValue = if (isHovered) 1.16f else 1f,
                    animationSpec = tween(120),
                    label = "slotIconScale",
                )

                Box(
                    modifier = Modifier
                        .offset {
                            val half = (iconSize / 2).toPx()
                            IntOffset(
                                (centerX.toPx() + effectiveIconRadius.toPx() * ux - half).roundToInt(),
                                (centerY.toPx() + effectiveIconRadius.toPx() * uy - half).roundToInt(),
                            )
                        }
                        .size(iconSize)
                        .scale(iconScale * openScale),
                    contentAlignment = Alignment.Center,
                ) {
                    SlotIcon(
                        slot = slot,
                        tint = if (isHovered) Color.White else Color.White.copy(alpha = 0.72f),
                        size = iconSize,
                    )
                }

                if (showNumbers) {
                    val number = panelRingNumberLabel(index)
                    if (number != null) {
                        val badgeW = 19.dp
                        val badgeH = 16.dp
                        Box(
                            modifier = Modifier
                                .offset {
                                    IntOffset(
                                        (centerX.toPx() + effectiveKeycapRadius.toPx() * ux - (badgeW / 2).toPx()).roundToInt(),
                                        (centerY.toPx() + effectiveKeycapRadius.toPx() * uy - (badgeH / 2).toPx()).roundToInt(),
                                    )
                                }
                                .size(badgeW, badgeH)
                                .scale(openScale)
                                .background(
                                    color = if (isHovered) accent else KeycapIdleFill,
                                    shape = KeycapShape,
                                )
                                .then(
                                    if (isHovered) Modifier
                                    else Modifier.border(0.75.dp, KeycapIdleBorder, KeycapShape),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = number,
                                color = if (isHovered) keycapOnAccent else Color.White.copy(alpha = 0.70f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                lineHeight = 10.sp,
                            )
                        }
                    }
                }
            }

            CentreLabel(
                centerX = centerX,
                centerY = centerY,
                hubRadius = hubRadius,
                title = hoveredSlot?.let { slotLabel(it) }
                    ?: stringResource(Res.string.panel_ring_hint_aim),
                hint = when (current.source) {
                    PanelRingSource.Keyboard -> stringResource(Res.string.panel_ring_hint_keyboard)
                    PanelRingSource.Mouse -> stringResource(Res.string.panel_ring_hint_mouse)
                    PanelRingSource.Gamepad -> stringResource(
                        Res.string.panel_ring_hint_gamepad,
                        padButton?.displayName ?: "A",
                    )
                },
                hasSelection = hoveredSlot != null,
            )
        }
    }
}

/**
 * Draws the unified segmented wheel annulus, luminous active sector + outer rim cap, elevated
 * center hub disc, and directional hub chevron pointing toward [hovered].
 */
@Composable
private fun SegmentedWheelCanvas(
    centerX: Dp,
    centerY: Dp,
    innerRadius: Dp,
    outerRadius: Dp,
    hubRadius: Dp,
    hoverPop: Dp,
    hovered: Int?,
    count: Int,
    accent: Color,
    openScale: Float,
) {
    Canvas(Modifier.fillMaxSize()) {
        if (count <= 0) return@Canvas
        val center = Offset(centerX.toPx(), centerY.toPx())
        val rIn = innerRadius.toPx() * openScale
        val rOut = outerRadius.toPx() * openScale
        val rHub = hubRadius.toPx() * openScale
        val popPx = hoverPop.toPx() * openScale
        val step = 360f / count
        val gap = if (count <= 1) 0f else (18f / count).coerceIn(1.4f, 3.2f)

        // Outer soft drop shadow and subtle bounding hairline.
        drawCircle(color = WheelShadow, radius = rOut + 10.dp.toPx(), center = center)
        drawCircle(
            color = WheelOuterHairline,
            radius = rOut + 1.dp.toPx(),
            center = center,
            style = Stroke(width = 1.dp.toPx()),
        )

        val idleBrush = Brush.radialGradient(
            0.46f to WheelInnerFill,
            1.0f to WheelOuterFill,
            center = center,
            radius = rOut.coerceAtLeast(1f),
        )
        val activeWashBrush = Brush.radialGradient(
            0.44f to accent.copy(alpha = 0.14f),
            1.0f to accent.copy(alpha = 0.36f),
            center = center,
            radius = (rOut + popPx).coerceAtLeast(1f),
        )

        // Draw idle sectors first, then hovered sector on top so its pop & border are crisp.
        for (pass in 0..1) {
            for (i in 0 until count) {
                val isHovered = hovered == i
                if ((pass == 0 && isHovered) || (pass == 1 && !isHovered)) continue

                val sectorOuter = if (isHovered) rOut + popPx else rOut
                if (count == 1) {
                    val bandMid = (rIn + sectorOuter) / 2f
                    val bandW = sectorOuter - rIn
                    drawCircle(
                        color = if (isHovered) WheelActiveBase else WheelOuterFill,
                        radius = bandMid,
                        center = center,
                        style = Stroke(width = bandW),
                    )
                    if (isHovered) {
                        drawCircle(
                            brush = activeWashBrush,
                            radius = bandMid,
                            center = center,
                            style = Stroke(width = bandW),
                        )
                        drawCircle(
                            color = accent.copy(alpha = 0.24f),
                            radius = sectorOuter,
                            center = center,
                            style = Stroke(width = 8.dp.toPx()),
                        )
                        drawCircle(
                            color = accent,
                            radius = sectorOuter,
                            center = center,
                            style = Stroke(width = 3.dp.toPx()),
                        )
                    }
                    continue
                }

                // Compose arcs start at 3 o'clock; PanelRingMath angles start at 12 o'clock.
                val centerAngle = PanelRingMath.slotAngleDegrees(i, count) - 90f
                val startAngle = centerAngle - step / 2f + gap / 2f
                val sweepAngle = step - gap
                val wedge = buildWedgePath(center, rIn, sectorOuter, startAngle, sweepAngle)

                if (isHovered) {
                    drawPath(path = wedge, color = WheelActiveBase)
                    drawPath(path = wedge, brush = activeWashBrush)
                    drawPath(
                        path = wedge,
                        color = accent.copy(alpha = 0.74f),
                        style = Stroke(width = 1.5.dp.toPx(), join = StrokeJoin.Round),
                    )
                    // Luminous outer rim cap (soft halo + crisp core).
                    val capInset = (sweepAngle * 0.08f).coerceAtMost(2.5f)
                    val capTopLeft = Offset(center.x - sectorOuter, center.y - sectorOuter)
                    val capSize = Size(sectorOuter * 2f, sectorOuter * 2f)
                    drawArc(
                        color = accent.copy(alpha = 0.28f),
                        startAngle = startAngle + capInset,
                        sweepAngle = (sweepAngle - capInset * 2f).coerceAtLeast(1f),
                        useCenter = false,
                        topLeft = capTopLeft,
                        size = capSize,
                        style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round),
                    )
                    drawArc(
                        color = accent,
                        startAngle = startAngle + capInset,
                        sweepAngle = (sweepAngle - capInset * 2f).coerceAtLeast(1f),
                        useCenter = false,
                        topLeft = capTopLeft,
                        size = capSize,
                        style = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round),
                    )
                } else {
                    drawPath(path = wedge, brush = idleBrush)
                    drawPath(
                        path = wedge,
                        color = WheelSectorBorder,
                        style = Stroke(width = 1.dp.toPx(), join = StrokeJoin.Round),
                    )
                }
            }
        }

        // Elevated center hub disc.
        val hubBrush = Brush.radialGradient(
            0.0f to HubTopFill,
            1.0f to HubEdgeFill,
            center = Offset(center.x, center.y - rHub * 0.25f),
            radius = rHub * 1.25f,
        )
        drawCircle(brush = hubBrush, radius = rHub, center = center)
        drawCircle(
            color = HubBorder,
            radius = rHub,
            center = center,
            style = Stroke(width = 1.dp.toPx()),
        )

        // Directional pointer arc + outward chevron on the hub rim when aiming at a sector.
        if (hovered != null && hovered in 0 until count) {
            val aimDeg = PanelRingMath.slotAngleDegrees(hovered, count) - 90f
            val arcSweep = (360f / count).coerceIn(22f, 38f) * 0.85f
            val hubTopLeft = Offset(center.x - rHub, center.y - rHub)
            val hubSize = Size(rHub * 2f, rHub * 2f)

            drawArc(
                color = accent.copy(alpha = 0.26f),
                startAngle = aimDeg - arcSweep / 2f,
                sweepAngle = arcSweep,
                useCenter = false,
                topLeft = hubTopLeft,
                size = hubSize,
                style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round),
            )
            drawArc(
                color = accent,
                startAngle = aimDeg - arcSweep / 2f,
                sweepAngle = arcSweep,
                useCenter = false,
                topLeft = hubTopLeft,
                size = hubSize,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
            )

            // Outward chevron pointing from the hub rim toward the aimed sector.
            val tip = polarPoint(center, rHub + 6.5.dp.toPx(), aimDeg)
            val baseLeft = polarPoint(center, rHub - 1.dp.toPx(), aimDeg - 5.5f)
            val baseRight = polarPoint(center, rHub - 1.dp.toPx(), aimDeg + 5.5f)
            val chevron = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(baseLeft.x, baseLeft.y)
                lineTo(baseRight.x, baseRight.y)
                close()
            }
            drawPath(path = chevron, color = accent)
        }
    }
}

private fun buildWedgePath(
    center: Offset,
    rIn: Float,
    rOut: Float,
    startAngleDeg: Float,
    sweepAngleDeg: Float,
): Path {
    val outerRect = Rect(center.x - rOut, center.y - rOut, center.x + rOut, center.y + rOut)
    val innerRect = Rect(center.x - rIn, center.y - rIn, center.x + rIn, center.y + rIn)
    return Path().apply {
        arcTo(outerRect, startAngleDeg, sweepAngleDeg, forceMoveTo = true)
        arcTo(innerRect, startAngleDeg + sweepAngleDeg, -sweepAngleDeg, forceMoveTo = false)
        close()
    }
}

private fun polarPoint(center: Offset, radius: Float, angleDegFrom3OClock: Float): Offset {
    val rad = angleDegFrom3OClock * PI / 180.0
    return Offset(
        x = center.x + (radius * cos(rad)).toFloat(),
        y = center.y + (radius * sin(rad)).toFloat(),
    )
}

@Composable
private fun CentreLabel(
    centerX: Dp,
    centerY: Dp,
    hubRadius: Dp,
    title: String,
    hint: String,
    hasSelection: Boolean,
) {
    val diameter = (hubRadius * 2 - 22.dp).coerceAtLeast(108.dp)
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (centerX - diameter / 2).roundToPx(),
                    (centerY - diameter / 2).roundToPx(),
                )
            }
            .size(diameter),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.width(diameter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = if (hasSelection) 17.sp else 15.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = hint,
                color = Color.White.copy(alpha = 0.54f),
                fontSize = 11.sp,
                lineHeight = 14.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
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
        PanelRingSlot.CloseApp -> Icon(Icons.Rounded.PowerSettingsNew, label, modifier, tint)
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
    PanelRingSlot.CloseApp -> stringResource(Res.string.panel_ring_close_app)
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
