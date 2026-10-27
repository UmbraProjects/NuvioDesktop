package com.nuvio.app.features.music.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
 * Hosted by [MusicLibraryShelf] behind one `// fork:music` switch.
 */
@Composable
internal fun ConstellationView(
    artists: List<LibraryArtist>,
    pending: List<String>,
    onArtistClick: (artistId: String) -> Unit,
    onFocusArtist: (artistId: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val context = LocalPlatformContext.current
    val byId = remember(artists) { artists.associateBy { it.id } }

    // Star colours come from the artist palettes the shelf already caches; derive any that are missing, quietly.
    val missingPalettes = remember(artists) { artists.count { it.identity().needsPalette } }
    LaunchedEffect(missingPalettes, artists.size) {
        artists.forEach { MusicPaletteResolver.ensure(context, it.identity()) }
    }

    var model by remember { mutableStateOf<ConstellationModel?>(null) }
    LaunchedEffect(artists) {
        val current = model?.key
        val next = withContext(Dispatchers.Default) {
            val key = constellationKey(artists)
            if (key == current) null else buildConstellationModel(artists, key)
        }
        if (next != null) model = next
    }

    var query by rememberSaveable { mutableStateOf("") }
    var chipKey by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var hoverId by remember { mutableStateOf<String?>(null) }
    var camera by remember { mutableStateOf(Camera()) }
    val mapFocus = remember { FocusRequester() }

    // The screen glow follows the star under the pointer, else the selected one.
    LaunchedEffect(hoverId, selectedId) { onFocusArtist(hoverId ?: selectedId) }
    DisposableEffect(Unit) { onDispose { onFocusArtist(null) } }

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
        if (m != null && canDraw) {
            ConstellationControls(
                model = m,
                query = query,
                onQuery = { query = it },
                activeChip = activeChip,
                onChip = { key -> chipKey = if (activeChip == key) null else key },
                litCount = focus?.size,
                onLeaveField = { runCatching { mapFocus.requestFocus() } },
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
                        ConstellationLegend(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 8.dp))
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
                    onClose = { selectedId = null },
                    modifier = Modifier.width(PANEL_WIDTH).fillMaxHeight(),
                )
            }
        }
        val waiting = m?.graph?.untagged?.mapNotNull { byId[it.id] }.orEmpty()
        if (waiting.isNotEmpty()) NotPlacedTray(waiting, onArtistClick)
    }
}

private val PANEL_WIDTH = 300.dp

// region controls

/** The search box and the genre chips along the top: both dim what does not match. */
@Composable
private fun ConstellationControls(
    model: ConstellationModel,
    query: String,
    onQuery: (String) -> Unit,
    activeChip: String?,
    onChip: (String) -> Unit,
    litCount: Int?,
    onLeaveField: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NuvioTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.width(240.dp).onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onQuery("")
                    onLeaveField()
                    true
                } else {
                    false
                }
            },
            placeholder = "Filter by name or genre",
            imeAction = ImeAction.Done,
            onImeAction = onLeaveField,
            leadingContent = { Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.textMuted) },
        )
        if (litCount != null) {
            Text("$litCount of ${model.graph.placedCount}", style = MaterialTheme.typography.labelMedium, color = colors.textMuted, maxLines = 1)
        }
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(end = 8.dp)) {
            items(model.clusters.chips, key = { it.key }) { cluster ->
                ActionPill("${cluster.label} · ${cluster.size}", selected = activeChip == cluster.key, onClick = { onChip(cluster.key) })
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
    var canvasPx by remember { mutableStateOf(IntSize.Zero) }
    val width = canvasPx.width.toFloat()
    val height = canvasPx.height.toFloat()
    val frame = remember(width, height, padPx) { paddedFrame(width, height, padPx) }
    val screen = ScreenMap(frame, camera)
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

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

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

    fun colourOf(id: String): Color = byId[id]?.palette?.let { Color(it.accent) } ?: fallback
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
                    direction != null -> {
                        stepStar(pointsNow, selectedNow, direction, focusNow)?.let { onSelectNow(it) }
                        true
                    }
                    event.key == Key.Tab -> {
                        cycleStar(pointsNow, selectedNow, if (event.isShiftPressed) -1 else 1, focusNow)?.let { onSelectNow(it) }
                        true
                    }
                    event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                        val id = selectedNow
                        if (id != null) onOpenNow(id)
                        id != null
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
                                    onHoverNow(hitStar(pointsNow, ScreenMap(frameNow, next), change.position.x, change.position.y))
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
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val accent = artist.palette?.let { Color(it.accent) } ?: colors.accent
    val releases = remember(artist.releases) { countReleaseCards(artist.releases) }
    val genres = remember(artist.genres, artist.tags) { topGenres(artist, 5) }
    Column(
        modifier
            .padding(start = 8.dp, end = 12.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceCard)
            .pointerInput(Unit) { detectTapGestures { } } // a click on the panel is not a click on the map behind it
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.clip(CircleShape).background(accent).padding(3.dp)) {
                MusicCover(artist.art.thumb, artist.name, size = 64.dp, circle = true)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(artist.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull("$releases release${if (releases == 1) "" else "s"}", clusterLabel).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Close",
                tint = colors.textMuted,
                modifier = Modifier.size(24.dp).clip(CircleShape).clickable(onClick = onClose),
            )
        }
        if (genres.isNotEmpty()) {
            Text(genres.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        }
        artist.foundVia?.let {
            Text("Found via ladder: $it", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        }
        if (neighbours.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("SOUNDS LIKE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = colors.accent, modifier = Modifier.padding(bottom = 4.dp))
                for ((other, similarity) in neighbours.take(6)) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onSelect(other.id) }.padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(other.name, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${(similarity * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
                    }
                }
            }
        }
        NuvioPrimaryButton(text = "Open artist room", modifier = Modifier.fillMaxWidth(), height = 44.dp, onClick = onOpen)
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
        modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.32f)).padding(horizontal = 12.dp, vertical = 10.dp),
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

/** Artists with no genres or tags yet: they have no place on the map until the enricher fills those in. */
@Composable
private fun NotPlacedTray(artists: List<LibraryArtist>, onArtistClick: (String) -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "NOT PLACED YET · ${artists.size}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.accent,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Text(
            "No genres or tags yet. They join the map once their tags arrive.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(artists, key = { it.id }) { artist ->
                Text(
                    artist.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .clickable { onArtistClick(artist.id) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

// endregion
