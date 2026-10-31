package com.nuvio.app.features.music.library

import com.nuvio.app.core.ui.collectIsKeyboardFocusedAsState
import com.nuvio.app.core.ui.smoothBorder
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import com.nuvio.app.features.library.LibraryClock
import com.nuvio.app.core.ui.NuvioGlass
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.alpha
import com.nuvio.app.core.ui.NuvioAsyncImage
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.LocalPlatformContext
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioTextField
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.MusicCover
import com.nuvio.app.features.music.MusicMessage
import com.nuvio.app.features.music.identity.MusicPaletteResolver
import com.nuvio.app.features.music.identity.identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Library views, phases C3 and C4: the Constellation drawn and made interactive. Each placed artist is a star in
 * their palette accent, sized by release count, ringed in gold when a favourite; a line joins stars that sound alike;
 * groups of two or more stars get a faint halo and the genre's name. Artists with no tags yet wait in the "Not placed
 * yet" tray under the map; with fewer than [CONSTELLATION_MIN_ARTISTS] placeable artists the map is replaced by a message.
 *
 * Interaction (C4), all decided by the pure helpers in `ConstellationInteract.kt`:
 *  - **Hover** a star: its name and top three genres, and the screen glow takes its accent ([onFocusArtist]).
 *  - **Click** selects a star and opens the side panel (artist, genres, how it was found, closest neighbours, Open
 *    artist room). Clicking empty space clears it. **Drag** pans, **scroll** zooms about the cursor.
 *  - **Keys** (map focused): arrows move the selection to the nearest star that way, Tab and Shift+Tab walk every star,
 *    Enter opens the artist room, + and - zoom, Escape clears the selection and resets the view.
 *  - **Genre chips** and the **search box** dim the stars that do not match; clearing restores them.
 *
 * The map itself is built by [buildConstellationModel] (C0 to C2) off the main thread, and only when something that
 * moves it changes ([constellationKey]); palettes and favourites are read live, so they never rebuild it.
 *
 * HTPC layout: the side panel is wider and lists the top albums with Ladder from here and Climb into; a "Thin spots" row
 * offers a ladder into genres you barely own; the key is a corner chip; artists not placed yet are cards with Fetch tags.
 *
 * Hosted by [MusicLibraryShelf] behind one `// fork:music` switch.
 */
@Composable
internal fun ConstellationView(
    artists: List<LibraryArtist>,
    pending: List<String>,
    onArtistClick: (artistId: String) -> Unit,
    /** The Music tab's filter box (the right half of its search bar while this view is up): dims what does not match. */
    query: String,
    modifier: Modifier = Modifier,
    /** HTPC phase 5: "Ladder from here" and the gap cards open a ladder; the host swaps its body for the ladder screen. */
    onOpenLadder: (LadderRequest) -> Unit = {},
) {
    val colors = MaterialTheme.nuvio.colors
    val byId = remember(artists) { artists.associateBy { it.id } }

    var model by remember { mutableStateOf<ConstellationModel?>(null) }
    LaunchedEffect(artists) {
        val current = model?.key
        val next = withContext(Dispatchers.Default) {
            val key = constellationKey(artists)
            if (key == current) null else buildConstellationModel(artists, key)
        }
        if (next != null) model = next
    }

    var chipKey by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var hoverId by remember { mutableStateOf<String?>(null) }
    var legendOpen by rememberSaveable { mutableStateOf(false) } // HTPC phase 5: the key is a corner chip, not a block over the left cluster
    var camera by remember { mutableStateOf(Camera()) }
    val mapFocus = remember { FocusRequester() }

    val m = model
    val canDraw = m != null && m.graph.canDraw
    // A chip or selection that no longer exists (the library changed) is ignored rather than leaving the map dimmed for nothing.
    val activeChip = if (m != null) chipKey?.takeIf { key -> m.clusters.clusters.any { it.key == key } } else null
    val focus = if (m != null) focusIds(m, byId, query, activeChip) else null
    val selected = if (m != null) selectedId?.takeIf { id -> m.layout.point(id) != null && byId.containsKey(id) } else null

    Column(modifier.fillMaxWidth()) {
        pending.forEach { status ->
            Text(
                "Adding… $status",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when {
                    m == null -> Text(
                        "Mapping your library…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMuted,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    !m.graph.canDraw -> MusicMessage(
                        "A constellation needs a few artists with tags. " +
                            if (m.graph.placedCount == 0) "None of your artists have tags yet." else "${m.graph.placedCount} placed so far.",
                    )
                    else -> {
                        ConstellationMap(
                            model = m,
                            byId = byId,
                            focus = focus,
                            selectedId = selected,
                            hoverId = hoverId,
                            camera = camera,
                            fallback = colors.accent,
                            focusRequester = mapFocus,
                            onSize = { camera = camera.clamped(it.width.toFloat(), it.height.toFloat()) },
                            onCamera = { camera = it },
                            onSelect = { selectedId = it },
                            onHover = { hoverId = it },
                            onOpen = onArtistClick,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Column(
                            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (legendOpen) ConstellationLegend()
                            ConstellationControls(
                                model = m,
                                activeChip = activeChip,
                                onChip = { key -> chipKey = if (activeChip == key) null else key },
                                litCount = focus?.size,
                                legendOpen = legendOpen,
                                onLegend = { legendOpen = !legendOpen },
                            )
                        }
                    }
                }
            }
            val panelArtist = selected?.let { byId[it] }
            if (m != null && panelArtist != null) {
                ConstellationPanel(
                    artist = panelArtist,
                    clusterLabel = m.clusters.clusterOf(panelArtist.id)?.label,
                    neighbours = m.graph.neighbours(panelArtist.id).mapNotNull { (id, similarity) -> byId[id]?.let { it to similarity } },
                    onSelect = { selectedId = it },
                    onOpen = { onArtistClick(panelArtist.id) },
                    onLadderFrom = { onOpenLadder(LadderRequest.Artist(panelArtist.name, LadderMode.OutFrom)) },
                    onLadderInto = { onOpenLadder(LadderRequest.Artist(panelArtist.name, LadderMode.Into)) },
                    onClose = { selectedId = null },
                    modifier = Modifier.width(PANEL_WIDTH).fillMaxHeight(),
                )
            }
        }
        val waiting = m?.graph?.untagged?.mapNotNull { byId[it.id] }.orEmpty()
        if (waiting.isNotEmpty()) NotPlacedTray(waiting, library = artists, onArtistClick = onArtistClick)
    }
}

private val PANEL_WIDTH = 380.dp

/** A genre held by this many artists or fewer counts as a gap worth a ladder. */
private const val GAP_MAX_ARTISTS = 8

/**
 * HTPC phase 5: the genres you tag but barely own, as places to grow (they would be the ghost halos
 * on the map). The Music tab lists them under its search bars while this view is up; each opens a
 * ladder into that genre.
 */
internal fun constellationGaps(artists: List<LibraryArtist>): List<LadderStarter> =
    ladderStarters(artists, limit = 4).filter { it.artists in 1..GAP_MAX_ARTISTS }

// region controls

/**
 * The strip along the bottom of the map: the key, how many stars the filter leaves lit, and the genre
 * chips. A chip, like the filter box in the tab's header, dims what does not match.
 */
@Composable
private fun ConstellationControls(
    model: ConstellationModel,
    activeChip: String?,
    onChip: (String) -> Unit,
    litCount: Int?,
    legendOpen: Boolean,
    onLegend: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BigChip(if (legendOpen) "Hide key" else "Key", selected = legendOpen, onClick = onLegend)
        if (litCount != null) {
            Text(
                "$litCount of ${model.graph.placedCount}",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.musicGlass(RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(end = 8.dp)) {
            items(model.clusters.chips, key = { it.key }) { cluster ->
                BigChip("${cluster.label}  ${cluster.size}", selected = activeChip == cluster.key, onClick = { onChip(cluster.key) })
            }
        }
    }
}

// endregion

// region map

@Composable
private fun ConstellationMap(
    model: ConstellationModel,
    byId: Map<String, LibraryArtist>,
    focus: Set<String>?,
    selectedId: String?,
    hoverId: String?,
    camera: Camera,
    fallback: Color,
    focusRequester: FocusRequester,
    onSize: (IntSize) -> Unit,
    onCamera: (Camera) -> Unit,
    onSelect: (String?) -> Unit,
    onHover: (String?) -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val padPx = with(density) { 24.dp.toPx() }
    val revealMarginPx = with(density) { 56.dp.toPx() }
    val minStarPx = with(density) { MIN_STAR_RADIUS.toPx() }
    var canvasPx by remember { mutableStateOf(IntSize.Zero) }
    val width = canvasPx.width.toFloat()
    val height = canvasPx.height.toFloat()
    val frame = remember(width, height, padPx) { paddedFrame(width, height, padPx) }
    val screen = ScreenMap(frame, camera, minStarPx)
    val points = model.layout.points

    // The pointer and key handlers outlive any one composition, so they read the latest values through these.
    val screenNow by rememberUpdatedState(screen)
    val frameNow by rememberUpdatedState(frame)
    val cameraNow by rememberUpdatedState(camera)
    val sizeNow by rememberUpdatedState(canvasPx)
    val pointsNow by rememberUpdatedState(points)
    val focusNow by rememberUpdatedState(focus)
    val selectedNow by rememberUpdatedState(selectedId)
    val onCameraNow by rememberUpdatedState(onCamera)
    val onSelectNow by rememberUpdatedState(onSelect)
    val onHoverNow by rememberUpdatedState(onHover)
    val onOpenNow by rememberUpdatedState(onOpen)


    // Selecting a star (click, key, or a neighbour in the panel) brings it into view if it is near or past an edge.
    LaunchedEffect(selectedId) {
        val id = selectedId ?: return@LaunchedEffect
        val p = pointsNow.firstOrNull { it.id == id } ?: return@LaunchedEffect
        val s = sizeNow
        if (s == IntSize.Zero) return@LaunchedEffect
        val now = screenNow
        onCameraNow(cameraNow.revealing(now.x(p.x), now.y(p.y), s.width.toFloat(), s.height.toFloat(), revealMarginPx))
    }

    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.62f))
    val labelled = remember(model) { model.clusters.clusters.filter(::drawsClusterLabel) }
    val labels = remember(model, labelStyle) {
        labelled.associate { it.key to measurer.measure(it.label, labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
    val pointById = remember(model) { points.associateBy { it.id } }

    // One colour for every star: the app accent. Size, rings and dimming carry the meaning.
    @Suppress("UNUSED_PARAMETER")
    fun colourOf(id: String): Color = fallback
    fun lit(id: String): Boolean = focus == null || id in focus

    Box(
        modifier
            .onSizeChanged { canvasPx = it; onSize(it) }
            .focusRequester(focusRequester)
            .focusable()
            .pointerHoverIcon(if (hoverId != null) PointerIcon.Hand else PointerIcon.Default)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val w = sizeNow.width.toFloat()
                val h = sizeNow.height.toFloat()
                val direction = when (event.key) {
                    Key.DirectionLeft -> StepDirection.Left
                    Key.DirectionRight -> StepDirection.Right
                    Key.DirectionUp -> StepDirection.Up
                    Key.DirectionDown -> StepDirection.Down
                    else -> null
                }
                when {
                    // No star that way: the arrow is let go, so focus can leave the map (up to the view pills, say).
                    direction != null -> {
                        val next = stepStar(pointsNow, selectedNow, direction, focusNow)
                        next?.let { onSelectNow(it) }
                        next != null
                    }
                    event.key == Key.Tab -> {
                        cycleStar(pointsNow, selectedNow, if (event.isShiftPressed) -1 else 1, focusNow)?.let { onSelectNow(it) }
                        true
                    }
                    // With no star selected Enter selects one rather than going unhandled, which desktop Compose takes as
                    // "focus the first thing inside the map" (focus seemed to drop to the panel below).
                    event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                        val id = selectedNow
                        if (id != null) onOpenNow(id)
                        else cycleStar(pointsNow, null, 1, focusNow)?.let { onSelectNow(it) }
                        true
                    }
                    event.key == Key.Plus || event.key == Key.Equals || event.key == Key.NumPadAdd -> {
                        onCameraNow(cameraNow.zoomedAt(w / 2, h / 2, ZOOM_PER_NOTCH, w, h))
                        true
                    }
                    event.key == Key.Minus || event.key == Key.NumPadSubtract -> {
                        onCameraNow(cameraNow.zoomedAt(w / 2, h / 2, 1f / ZOOM_PER_NOTCH, w, h))
                        true
                    }
                    event.key == Key.Escape -> {
                        val had = selectedNow != null || cameraNow != Camera()
                        onSelectNow(null)
                        onCameraNow(Camera())
                        had
                    }
                    else -> false
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { at ->
                        runCatching { focusRequester.requestFocus() }
                        onSelectNow(hitStar(pointsNow, screenNow, at.x, at.y))
                    },
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, drag ->
                        change.consume()
                        onCameraNow(cameraNow.panned(drag.x, drag.y, sizeNow.width.toFloat(), sizeNow.height.toFloat()))
                    },
                )
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        when (event.type) {
                            PointerEventType.Move, PointerEventType.Enter ->
                                onHoverNow(hitStar(pointsNow, screenNow, change.position.x, change.position.y))
                            PointerEventType.Exit -> onHoverNow(null)
                            PointerEventType.Scroll -> {
                                val notches = change.scrollDelta.y
                                if (notches != 0f) {
                                    val w = sizeNow.width.toFloat()
                                    val h = sizeNow.height.toFloat()
                                    val next = cameraNow.zoomedAt(change.position.x, change.position.y, ZOOM_PER_NOTCH.pow(-notches), w, h)
                                    onCameraNow(next)
                                    // The stars moved under a still pointer: find what is under it now.
                                    onHoverNow(hitStar(pointsNow, ScreenMap(frameNow, next, minStarPx), change.position.x, change.position.y))
                                    change.consume()
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize().clipToBounds()) {
            // 1. Halos: a faint ellipse round each group (the box is stretched along one side, so the halo is too).
            for (cluster in labelled) {
                val lead = clusterLead(cluster, model.layout) ?: continue
                val c = colourOf(lead)
                val on = focus == null || cluster.memberIds.any { it in focus }
                val k = if (on) 1f else 0.3f
                val rx = screen.haloRadiusX(cluster.haloRadius + HALO_PAD)
                val ry = screen.haloRadiusY(cluster.haloRadius + HALO_PAD)
                val topLeft = Offset(screen.x(cluster.x) - rx, screen.y(cluster.y) - ry)
                drawOval(c.copy(alpha = 0.06f * k), topLeft, Size(2 * rx, 2 * ry))
                drawOval(c.copy(alpha = 0.14f * k), topLeft, Size(2 * rx, 2 * ry), style = Stroke(1.dp.toPx()))
            }

            // 2. Lines: a gradient between the two stars' colours, firmer for closer matches. Lines on the selected star glow.
            for (link in model.graph.links) {
                val a = pointById[link.a] ?: continue
                val b = pointById[link.b] ?: continue
                val from = Offset(screen.x(a.x), screen.y(a.y))
                val to = Offset(screen.x(b.x), screen.y(b.y))
                val onSelected = selectedId != null && (link.a == selectedId || link.b == selectedId)
                var alpha = linkAlpha(link.similarity, link.mutual)
                if (!(lit(a.id) && lit(b.id))) alpha *= 0.25f
                if (onSelected) alpha = maxOf(alpha, 0.75f)
                drawLine(
                    brush = Brush.linearGradient(listOf(colourOf(a.id).copy(alpha = alpha), colourOf(b.id).copy(alpha = alpha)), start = from, end = to),
                    start = from,
                    end = to,
                    strokeWidth = (if (onSelected) 2.2.dp else if (link.mutual) 1.6.dp else 1.1.dp).toPx(),
                    cap = StrokeCap.Round,
                )
            }

            // 3. Stars, with a soft glow, a gold ring for a favourite, and a white ring for the hovered or selected one.
            for (p in points) {
                val center = Offset(screen.x(p.x), screen.y(p.y))
                val r = screen.starRadius(p.radius)
                val c = colourOf(p.id)
                val on = lit(p.id)
                if (on) {
                    drawCircle(
                        brush = Brush.radialGradient(listOf(c.copy(alpha = 0.28f), Color.Transparent), center = center, radius = r * 2.4f),
                        radius = r * 2.4f,
                        center = center,
                    )
                }
                drawCircle(c.copy(alpha = if (on) 1f else 0.16f), r, center)
                drawCircle(Color.Black.copy(alpha = if (on) 0.25f else 0.1f), r, center, style = Stroke(1.dp.toPx()))
                val favourite = byId[p.id]?.favourite == true
                if (favourite) drawCircle(GOLD.copy(alpha = if (on) 1f else 0.25f), r + 3.dp.toPx(), center, style = Stroke(2.dp.toPx()))
                if (p.id == selectedId) {
                    drawCircle(Color.White, r + (if (favourite) 8 else 5).dp.toPx(), center, style = Stroke(2.dp.toPx()))
                } else if (p.id == hoverId) {
                    drawCircle(Color.White.copy(alpha = 0.7f), r + (if (favourite) 7 else 4).dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
                }
            }

            // 4. Group names, just above each halo and kept on the canvas; one whose group has left the canvas is not drawn.
            for (cluster in labelled) {
                val text = labels[cluster.key] ?: continue
                val cx = screen.x(cluster.x)
                val cy = screen.y(cluster.y)
                if (cx < 0f || cx > this.size.width || cy < 0f || cy > this.size.height) continue
                val margin = 4.dp.toPx()
                val ry = screen.haloRadiusY(cluster.haloRadius + HALO_PAD)
                val x = (cx - text.size.width / 2f).coerceIn(margin, (this.size.width - text.size.width - margin).coerceAtLeast(margin))
                val y = (cy - ry - text.size.height - margin).coerceAtLeast(margin)
                val on = focus == null || cluster.memberIds.any { it in focus }
                drawText(text, topLeft = Offset(x, y), alpha = if (on) 1f else 0.3f)
            }
        }

        // Each star is the artist's portrait, so the map says who is who without hovering. The canvas's disc stays
        // underneath as the placeholder (and for an artist with no picture); the rings and glow sit outside it.
        for (p in points) {
            val thumb = byId[p.id]?.art?.thumb ?: continue
            key(p.id) {
                val r = screen.starRadius(p.radius)
                val diameter = with(density) { (r * 2).toDp() }
                NuvioAsyncImage(
                    model = thumb,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .offset { IntOffset((screen.x(p.x) - r).roundToInt(), (screen.y(p.y) - r).roundToInt()) }
                        .size(diameter)
                        .alpha(if (lit(p.id)) 1f else 0.2f)
                        .clip(CircleShape),
                )
            }
        }

        // Hover: name and top three genres next to the star, flipped to its other side near the right edge.
        val hovered = hoverId?.let { id -> byId[id]?.let { artist -> pointById[id]?.let { artist to it } } }
        if (hovered != null) {
            val (artist, p) = hovered
            val gap = with(density) { 12.dp.toPx() }
            val tipWidth = with(density) { 220.dp.toPx() }
            val sx = screen.x(p.x)
            val sy = screen.y(p.y)
            val r = screen.starRadius(p.radius)
            val onLeft = sx + r + gap + tipWidth > width
            val tipX = if (onLeft) sx - r - gap - tipWidth else sx + r + gap
            Column(
                Modifier
                    .offset { IntOffset(tipX.roundToInt(), (sy - with(density) { 14.dp.toPx() }).roundToInt()) }
                    .width(220.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xE60E0E12))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalAlignment = if (onLeft) Alignment.End else Alignment.Start,
            ) {
                Text(artist.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val genres = topGenres(artist)
                if (genres.isNotEmpty()) {
                    Text(genres.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The smallest a star is drawn: big enough for the portrait inside it to be recognised. */
private val MIN_STAR_RADIUS = 16.dp

/** Air between the farthest star and its halo, in unit-box units. */
private const val HALO_PAD = 0.01

// endregion

// region side panel

/** The selected star: who it is, how it was found, what it sounds like, and the way into the artist room. */
@Composable
private fun ConstellationPanel(
    artist: LibraryArtist,
    clusterLabel: String?,
    neighbours: List<Pair<LibraryArtist, Double>>,
    onSelect: (String) -> Unit,
    onOpen: () -> Unit,
    onLadderFrom: () -> Unit,
    onLadderInto: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val topAlbums = remember(artist.topAlbumKeys, artist.releases) { artistTopReleases(artist, 5) }
    val accent = colors.accent
    val releases = remember(artist.releases) { countReleaseCards(artist.releases) }
    val genres = remember(artist.genres, artist.tags) { topGenres(artist, 5) }
    Column(
        modifier
            .padding(start = 8.dp, end = 12.dp, bottom = 8.dp)
            .musicGlass(RoundedCornerShape(16.dp))
            .pointerInput(Unit) { detectTapGestures { } } // a click on the panel is not a click on the map behind it
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.clip(CircleShape).background(accent).padding(3.dp)) {
                MusicCover(artist.art.thumb, artist.name, size = 88.dp, circle = true)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(artist.name, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull("$releases release${if (releases == 1) "" else "s"}", clusterLabel).joinToString(" · "),
                    fontSize = 22.sp,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Close",
                tint = colors.textMuted,
                modifier = Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClose),
            )
        }
        if (genres.isNotEmpty()) {
            Text(genres.joinToString(" · "), fontSize = 22.sp, color = colors.textSecondary)
        }
        if (topAlbums.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("TOP ALBUMS", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.accent, modifier = Modifier.padding(bottom = 4.dp))
                topAlbums.forEachIndexed { i, release ->
                    Text(
                        "${i + 1}  ${release.title}",
                        fontSize = 22.sp,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpen).padding(horizontal = 6.dp, vertical = 5.dp),
                    )
                }
            }
        }
        artist.foundVia?.let {
            Text("Found via ladder: $it", fontSize = 20.sp, color = colors.textMuted)
        }
        if (neighbours.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("SOUNDS LIKE", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.accent, modifier = Modifier.padding(bottom = 4.dp))
                for ((other, similarity) in neighbours.take(6)) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onSelect(other.id) }.padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(other.name, fontSize = 22.sp, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${(similarity * 100).roundToInt()}%", fontSize = 20.sp, color = colors.textMuted)
                    }
                }
            }
        }
        NuvioPrimaryButton(text = "Ladder from here", modifier = Modifier.fillMaxWidth(), height = 52.dp, onClick = onLadderFrom)
        NuvioPrimaryButton(text = "Climb into ${artist.name}", modifier = Modifier.fillMaxWidth(), height = 52.dp, onClick = onLadderInto)
        NuvioPrimaryButton(text = "Open artist room", modifier = Modifier.fillMaxWidth(), height = 52.dp, onClick = onOpen)
    }
}

// endregion

// region legend and tray

private enum class LegendGlyph { Size, Favourite, Line, Halo, Dim }

/** What the picture encodes. Only what is drawn so far; C5 adds the found-via-ladder ring. */
@Composable
private fun ConstellationLegend(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.nuvio.colors
    Column(
        modifier.musicGlass(RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for ((glyph, text) in listOf(
            LegendGlyph.Size to "Bigger star, more releases",
            LegendGlyph.Favourite to "Gold ring, a favourite",
            LegendGlyph.Line to "Line, sounds alike",
            LegendGlyph.Halo to "Halo, a genre group",
            LegendGlyph.Dim to "Dimmed, not matching the filter",
        )) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LegendMark(glyph)
                Text(text, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun LegendMark(glyph: LegendGlyph) {
    val accent = MaterialTheme.nuvio.colors.accent
    Canvas(Modifier.size(width = 28.dp, height = 16.dp)) {
        val mid = Offset(size.width / 2, size.height / 2)
        when (glyph) {
            LegendGlyph.Size -> {
                drawCircle(accent, 3.dp.toPx(), Offset(size.width * 0.25f, mid.y))
                drawCircle(accent, 6.dp.toPx(), Offset(size.width * 0.7f, mid.y))
            }
            LegendGlyph.Favourite -> {
                drawCircle(accent, 4.dp.toPx(), mid)
                drawCircle(GOLD, 7.dp.toPx(), mid, style = Stroke(1.6.dp.toPx()))
            }
            LegendGlyph.Line -> {
                drawLine(accent.copy(alpha = 0.6f), Offset(size.width * 0.2f, mid.y), Offset(size.width * 0.8f, mid.y), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(accent, 3.dp.toPx(), Offset(size.width * 0.2f, mid.y))
                drawCircle(accent, 3.dp.toPx(), Offset(size.width * 0.8f, mid.y))
            }
            LegendGlyph.Halo -> {
                drawOval(accent.copy(alpha = 0.08f), Offset(0f, 0f), size)
                drawOval(accent.copy(alpha = 0.3f), Offset(0f, 0f), size, style = Stroke(1.dp.toPx()))
            }
            LegendGlyph.Dim -> drawCircle(accent.copy(alpha = 0.16f), 5.dp.toPx(), mid)
        }
    }
}

/**
 * Artists with no genres or tags yet: they have no place on the map until those arrive. Just their
 * portraits, wrapping so a long list still fits; the name shows on hover. Clicking one opens a picker ([GenrePickerPanel]):
 * choose a genre from the map, type one, take a suggestion, or look the artist up again (a spinner shows while it works).
 * An artist whose look-up found nothing is dimmed with a "?" and its retry waits 3, 7, then 14 days ([tagRetryDelayMs]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotPlacedTray(artists: List<LibraryArtist>, library: List<LibraryArtist>, onArtistClick: (String) -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val state by MusicLibraryRepository.state.collectAsStateWithLifecycle()
    var pickerFor by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Click an artist to choose a genre for them, or look them up again",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            artists.forEach { artist ->
                val source = remember { MutableInteractionSource() }
                val hovered by source.collectIsHoveredAsState()
                val focused by source.collectIsKeyboardFocusedAsState()
                val working = state.working[artist.id] != null
                // A completed look-up found no genres: say so on the portrait instead of leaving a button that cannot help.
                val noData = artist.tagMisses > 0 && !artist.hasLookedUpGenres()
                // Where the pointer is over the portrait, so the name can follow it instead of covering the ring.
                var pointer by remember { mutableStateOf<Offset?>(null) }
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(64.dp)
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        pointer = when (event.type) {
                                            PointerEventType.Exit -> null
                                            else -> event.changes.firstOrNull()?.position ?: pointer
                                        }
                                    }
                                }
                            }
                            .clip(CircleShape)
                            .smoothBorder(2.dp, if (hovered || focused) colors.accent else NuvioGlass.EdgeColor, CircleShape)
                            .hoverable(source)
                            .clickable(interactionSource = source, indication = null) {
                                pickerFor = if (pickerFor == artist.id) null else artist.id
                            },
                    ) {
                        MusicCover(artist.art.thumb, artist.name, size = 64.dp, circle = true, modifier = Modifier.alpha(if (noData) 0.5f else 1f))
                        if (working) {
                            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), color = colors.accent, strokeWidth = 2.dp)
                            }
                        } else if (noData) {
                            Text(
                                "?",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.textPrimary,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }
                    if (pickerFor == artist.id) {
                        Popup(
                            popupPositionProvider = AbovePortrait,
                            onDismissRequest = { pickerFor = null },
                            properties = PopupProperties(focusable = true),
                        ) {
                            GenrePickerPanel(
                                artist = artist,
                                library = library,
                                working = working,
                                onApply = { genres ->
                                    MusicLibraryRepository.setManualGenres(artist.id, genres)
                                    pickerFor = null
                                },
                                onLookAgain = { MusicLibraryRepository.refreshTags(artist.id) },
                                onOpen = {
                                    pickerFor = null
                                    onArtistClick(artist.id)
                                },
                            )
                        }
                    }
                    if ((hovered || focused) && pickerFor != artist.id) {
                        val density = LocalDensity.current
                        // Just below and right of the pointer; with keyboard focus (no pointer), under the portrait.
                        val at = pointer?.takeIf { hovered }
                        val offset = with(density) {
                            if (at != null) IntOffset(at.x.roundToInt() + 14.dp.roundToPx(), at.y.roundToInt() + 18.dp.roundToPx())
                            else IntOffset(0, 70.dp.roundToPx())
                        }
                        Popup(offset = offset, properties = PopupProperties(focusable = false)) {
                            Column(
                                Modifier
                                    .background(NuvioGlass.night(colors.background, colors.accent), RoundedCornerShape(8.dp))
                                    .musicGlass(RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text(artist.name, style = MaterialTheme.typography.labelMedium, color = colors.textPrimary, maxLines = 1)
                                if (noData) {
                                    Text("No genre data found online", style = MaterialTheme.typography.labelSmall, color = colors.textMuted, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Puts the picker above the portrait it belongs to (the tray sits at the bottom of the window), kept inside the window. */
private val AbovePortrait = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (anchorBounds.top - popupContentSize.height - 8).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/**
 * What the tray offers for one artist with no genres: why it is not placed, a one-tap suggestion ([suggestGenres], never
 * applied on its own), the genres already on the map, a box to type one, and a retry that is withheld while a previous
 * empty look-up is being waited out. Choosing a genre writes it as the artist's own ([LibraryArtist.manualGenres]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenrePickerPanel(
    artist: LibraryArtist,
    library: List<LibraryArtist>,
    working: Boolean,
    onApply: (List<String>) -> Unit,
    onLookAgain: () -> Unit,
    onOpen: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val now = LibraryClock.nowEpochMs()
    val suggestions = remember(artist, library) { suggestGenres(artist, library) }
    val choices = remember(library) { genreChoices(library) }
    val wait = artist.retryTagsInMs(now)
    var typed by remember { mutableStateOf("") }
    var rejected by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .width(360.dp)
            .background(NuvioGlass.night(colors.background, colors.accent), shape)
            .musicGlass(shape)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(artist.name, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            when {
                working -> "Looking it up…"
                artist.tagMisses > 0 && wait > 0L ->
                    "No genre data found online. The next look-up is in ${retryWaitText(wait)}; until then pick one yourself."
                artist.tagMisses > 0 -> "No genre data found online last time. You can look again, or pick one yourself."
                else -> "No genre data yet."
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
        if (suggestions.isNotEmpty()) {
            Text("Suggested", style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
            suggestions.forEach { s ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    BigChip(displayGenre(s.genre), selected = false) { onApply(withManualGenre(artist.manualGenres, s.genre)) }
                    Text(s.reason, style = MaterialTheme.typography.bodySmall, color = colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (choices.isNotEmpty()) {
            Text("On your map", style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { genre ->
                    BigChip(displayGenre(genre), selected = false) { onApply(withManualGenre(artist.manualGenres, genre)) }
                }
            }
        }
        val apply: () -> Unit = {
            val next = withManualGenres(artist.manualGenres, typed)
            if (next == artist.manualGenres) {
                rejected = true
            } else {
                onApply(next)
            }
        }
        NuvioTextField(
            value = typed,
            onValueChange = {
                typed = it
                rejected = false
            },
            placeholder = "Or type one, like uk garage",
            isError = rejected,
            supportingText = if (rejected) "That is not a genre the map can use" else null,
            onImeAction = apply,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (typed.isNotBlank()) BigChip("Add", selected = true) { apply() }
            if (!working && wait == 0L) BigChip("Look again", selected = false, onClick = onLookAgain)
            BigChip("Open artist room", selected = false, onClick = onOpen)
        }
    }
}

// endregion

// region HTPC phase 5 widgets

/** A chip the d-pad can reach: 22sp text, a focus ring, Enter or a click to use it. */
@Composable
private fun BigChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsKeyboardFocusedAsState()
    val hovered by source.collectIsHoveredAsState()
    val shape = RoundedCornerShape(20.dp)
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (selected) colors.onAccent else colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .musicGlass(shape, raised = hovered, selected = selected, keyFocused = focused)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

// endregion
