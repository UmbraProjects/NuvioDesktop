package com.nuvio.app.features.tonight

import com.nuvio.app.core.ui.requestFocusLogged
import com.nuvio.app.core.ui.keyLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.smoothBorder

/*
 * fork:tonight - phase 5: The Map. No rows: every title that fits tonight sits on a field, Light
 * to Heavy across and Comfort to Something new up. A lens steered by the arrows enlarges what is
 * near it; OK plays the title nearest its centre. The side panel has a fixed size and fixed lines.
 */

private const val LENS_RADIUS = 0.14f
private const val LENS_STEP = 0.05f
private val MapPanelWidth = 360.dp

private val SpotColors = listOf(
    Color(0xFF3A5A7A), Color(0xFF7A4F5C), Color(0xFF3F7D6E), Color(0xFFA8743A),
    Color(0xFF5A6470), Color(0xFF6A5A3A), Color(0xFF2A3A5A), Color(0xFF5A3A5A),
)

@Composable
internal fun TonightMapView(
    pool: List<TonightTitle>,
    clock: TonightClock,
    settings: TonightSettings,
    minutesUntilBed: Int,
    bedtimeFilters: Boolean,
    loading: Boolean,
    onPlay: (TonightTitle) -> Unit,
    onBack: () -> Unit,
) {
    val accent = MaterialTheme.nuvio.colors.accent
    remember { TonightMapAi.ensureLoaded() }
    val tones by TonightMapAi.tones.collectAsStateWithLifecycle()
    val spots = remember(pool, tones) { tonightMapSpots(pool, tones) }
    var lensX by remember { mutableFloatStateOf(0.5f) }
    var lensY by remember { mutableFloatStateOf(0.5f) }
    var lensPlaced by remember { mutableStateOf(false) }
    if (!lensPlaced && spots.isNotEmpty()) {
        val (x, y) = tonightLensStart(spots)
        lensX = x
        lensY = y
        lensPlaced = true
    }
    val ranked = remember(spots, lensX, lensY) { spots.nearest(lensX, lensY) }
    val under = ranked.firstOrNull()
    val focus = rememberScreenFocus(Unit)

    // Ask the AI about titles it has not placed yet, only while The Map is open.
    LaunchedEffect(pool, settings.mapAiEnabled) { TonightMapAi.fill(pool, clock.isoDate) }
    DisposableEffect(Unit) { onDispose { TonightMapAi.stop() } }

    fun move(dx: Float, dy: Float) {
        lensX = (lensX + dx).coerceIn(0f, 1f)
        lensY = (lensY + dy).coerceIn(0f, 1f)
    }

    // fork:phone-remote tonight - the phone is a touchpad: its drags arrive as arrows and move the lens; it shows
    // the title under the lens and the two beside it.
    MirrorOnPhone(
        com.nuvio.app.features.remote.RemoteTonightScreen(
            view = com.nuvio.app.features.remote.RemoteTonightScreen.MAP,
            loading = loading && spots.isEmpty(),
            total = spots.size,
            cards = (listOfNotNull(under) + ranked.drop(1).filter { it.distanceTo(lensX, lensY) <= LENS_RADIUS }.take(2))
                .mapIndexed { i, spot -> spot.title.remoteCard(if (i == 0) "UNDER THE LENS" else "NEARBY", spot.readingLabel(), clock, minutesUntilBed) },
            extra = under?.readingLabel(),
            // Where the lens sits, in hundredths, so the phone can draw it.
            number = (lensX * 100).toInt(),
            countLeft = (lensY * 100).toInt(),
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Focus router fallback (KeyLayers): an arrow or OK that went to no one puts focus back here.
            .keyLayer("Tonight") { focus.requestFocusLogged() }
            .focusRequester(focus)
            .focusable()
            .tonightKeys { key ->
                when (key) {
                    TonightKey.Left -> move(-LENS_STEP, 0f)
                    TonightKey.Right -> move(LENS_STEP, 0f)
                    TonightKey.Up -> move(0f, -LENS_STEP)
                    TonightKey.Down -> move(0f, LENS_STEP)
                    TonightKey.Ok -> under?.let { onPlay(it.title) }
                    TonightKey.Back -> onBack()
                }
                true
            }
            .padding(horizontal = Side, vertical = 32.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("THE MAP · ${spots.size} TITLES", color = TextDim, fontSize = 15.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            Text(
                if (bedtimeFilters) "Ends before ${clockLabel(settings.bedMinuteOfDay)}" else "Bedtime ${clockLabel(settings.bedMinuteOfDay)} · not filtering",
                color = Color.White, fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(accent.copy(alpha = 0.16f))
                    .smoothBorder(1.dp, accent, RoundedCornerShape(999.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            MapField(
                spots = spots,
                lensX = lensX,
                lensY = lensY,
                under = under,
                accent = accent,
                loading = loading,
                onSpotClick = { spot ->
                    if (spot.title.key == under?.title?.key) onPlay(spot.title) else { lensX = spot.x; lensY = spot.y }
                },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            Spacer(Modifier.width(24.dp))
            MapPanel(
                under = under,
                nearby = ranked.drop(1).filter { it.distanceTo(lensX, lensY) <= LENS_RADIUS }.take(2),
                aiPlaced = spots.count { it.aiRefined },
                aiOn = settings.mapAiEnabled,
                clock = clock,
                minutesUntilBed = minutesUntilBed,
                accent = accent,
                onPlay = { under?.let { onPlay(it.title) } },
            )
        }
    }
}

@Composable
private fun MapField(
    spots: List<MapSpot>,
    lensX: Float,
    lensY: Float,
    under: MapSpot?,
    accent: Color,
    loading: Boolean,
    onSpotClick: (MapSpot) -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    BoxWithConstraints(
        modifier = modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.02f))
            .smoothBorder(1.dp, Color.White.copy(alpha = 0.10f), shape),
    ) {
        val w = maxWidth
        val h = maxHeight
        // Cross-hairs and the four ends of the two axes.
        Box(Modifier.offset(x = w / 2).width(1.dp).fillMaxHeight().padding(vertical = 20.dp).background(Color.White.copy(alpha = 0.08f)))
        Box(Modifier.offset(y = h / 2).height(1.dp).fillMaxWidth().padding(horizontal = 20.dp).background(Color.White.copy(alpha = 0.08f)))
        AxisLabel("SOMETHING NEW", Modifier.align(Alignment.TopCenter).padding(top = 14.dp))
        AxisLabel("COMFORT", Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
        AxisLabel("LIGHT", Modifier.align(Alignment.CenterStart).padding(start = 16.dp))
        AxisLabel("HEAVY", Modifier.align(Alignment.CenterEnd).padding(end = 16.dp))

        if (spots.isEmpty()) {
            Text(
                if (loading) "Still gathering tonight's titles…" else "Nothing fits tonight yet.",
                color = TextDim, fontSize = 22.sp, modifier = Modifier.align(Alignment.Center),
            )
        }

        // The lens: a ring of fixed size wherever it sits.
        val lensSize = w * (LENS_RADIUS * 2f)
        Box(
            Modifier
                .offset(x = w * lensX - lensSize / 2, y = h * lensY - lensSize / 2)
                .size(lensSize)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.16f), Color.Transparent)))
                .border(2.dp, accent, CircleShape),
        )

        // Far titles first, so the ones near the lens are drawn on top.
        val ordered = spots.sortedByDescending { it.distanceTo(lensX, lensY) }
        for (spot in ordered) {
            val d = spot.distanceTo(lensX, lensY)
            val near = d <= LENS_RADIUS
            val isUnder = spot.title.key == under?.title?.key
            val tileW = when {
                isUnder -> 84.dp
                near -> (52f + 24f * (1f - d / LENS_RADIUS)).dp
                else -> 30.dp
            }
            val tileH = tileW * 1.47f
            val color = SpotColors[(spot.title.key.hashCode() and 0x7fffffff) % SpotColors.size]
            Box(
                modifier = Modifier
                    .offset(x = w * spot.x - tileW / 2, y = h * spot.y - tileH / 2)
                    .size(width = tileW, height = tileH)
                    .graphicsLayer { alpha = if (near) 1f else 0.35f }
                    .clip(RoundedCornerShape(6.dp))
                    .background(color)
                    .smoothBorder(if (isUnder) 2.dp else 1.dp, if (isUnder) Color.White else Color.White.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                    .clickable { onSpotClick(spot) },
            ) {
                val art = spot.title.poster
                if (near && art != null) {
                    NuvioAsyncImage(model = art, contentDescription = spot.title.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
        }
    }
}

@Composable
private fun AxisLabel(text: String, modifier: Modifier) {
    Text(text, color = TextFaint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.8.sp, modifier = modifier)
}

@Composable
private fun MapPanel(
    under: MapSpot?,
    nearby: List<MapSpot>,
    aiPlaced: Int,
    aiOn: Boolean,
    clock: TonightClock,
    minutesUntilBed: Int,
    accent: Color,
    onPlay: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .width(MapPanelWidth)
            .fillMaxHeight()
            .clip(shape)
            .background(GlassFill)
            .smoothBorder(1.dp, GlassEdge, shape)
            .padding(24.dp),
    ) {
        Text("UNDER THE LENS", color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.8.sp, modifier = Modifier.height(20.dp))
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.05f))) {
            val art = under?.title?.backdrop ?: under?.title?.poster
            if (art != null) {
                NuvioAsyncImage(model = art, contentDescription = under?.title?.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(under?.title?.name.orEmpty(), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 32.sp, modifier = Modifier.height(68.dp))
        Text(under?.title?.tonightMetaLine(clock, minutesUntilBed).orEmpty(), color = Color(0xFFCFCCC3), fontSize = 15.sp, maxLines = 1, modifier = Modifier.height(22.dp))
        Spacer(Modifier.height(8.dp))
        Text(under?.readingLabel().orEmpty(), color = TextDim, fontSize = 15.sp, maxLines = 1, modifier = Modifier.height(22.dp))
        Text(
            if (nearby.isEmpty()) "" else "Nearby: " + nearby.joinToString(", ") { it.title.name },
            color = TextDim, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(44.dp),
        )
        Spacer(Modifier.weight(1f))
        GlassPill("OK · Play it", selected = true, accent = accent, onClick = onPlay, enabled = under != null)
        Spacer(Modifier.height(14.dp))
        Text(
            when {
                !aiOn -> "Placed by Nuvio's own rules. The AI can refine this in Settings."
                aiPlaced == 0 -> "Placed by Nuvio's own rules; the AI is reading the titles…"
                else -> "$aiPlaced placed with the AI's reading of their tone"
            },
            color = TextFaint, fontSize = 13.sp, maxLines = 2, modifier = Modifier.height(36.dp),
        )
        Text("Arrows steer the lens · OK plays · Back", color = TextFaint, fontSize = 13.sp, maxLines = 1, modifier = Modifier.height(18.dp))
    }
}
