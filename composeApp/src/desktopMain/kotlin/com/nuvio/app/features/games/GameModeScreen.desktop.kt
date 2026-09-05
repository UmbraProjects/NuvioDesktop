package com.nuvio.app.features.games

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.navigationKey
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

@Composable
actual fun GameModeScreen(
    modifier: Modifier,
    onExit: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val controller = remember { GameLibraryController.shared }
    val games by controller.games.collectAsState()
    val loading by controller.loading.collectAsState()
    val lastExecutableDirectory by controller.lastExecutableDirectory.collectAsState()
    val settings by remember {
        GameLibrarySettingsRepository.ensureLoaded()
        GameLibrarySettingsRepository.uiState
    }.collectAsState()

    val focusRequester = remember { FocusRequester() }
    var selectedRow by remember { mutableIntStateOf(0) }
    var installedIndex by remember { mutableIntStateOf(0) }
    var uninstalledIndex by remember { mutableIntStateOf(0) }
    var lastWheelSwitchNanos by remember { mutableLongStateOf(0L) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingGame by remember { mutableStateOf<GameEntry?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val installedGames = games.filter(GameEntry::isInstalled)
    val uninstalledGames = games.filterNot(GameEntry::isInstalled)
    val selectedGame = if (selectedRow == 0) {
        installedGames.getOrNull(installedIndex)
    } else {
        uninstalledGames.getOrNull(uninstalledIndex)
    }
    val dialogOpen = showAddDialog || editingGame != null

    LaunchedEffect(installedGames.size, uninstalledGames.size) {
        installedIndex = installedIndex.coerceIn(0, (installedGames.size - 1).coerceAtLeast(0))
        uninstalledIndex = uninstalledIndex.coerceIn(0, (uninstalledGames.size - 1).coerceAtLeast(0))
        if (selectedRow == 0 && installedGames.isEmpty() && uninstalledGames.isNotEmpty()) selectedRow = 1
        if (selectedRow == 1 && uninstalledGames.isEmpty() && installedGames.isNotEmpty()) selectedRow = 0
    }
    LaunchedEffect(dialogOpen) {
        if (!dialogOpen) {
            runCatching { focusRequester.requestFocus() }
        }
    }
    LaunchedEffect(message) {
        if (message != null) {
            delay(4_500)
            message = null
        }
    }

    fun launchGame(game: GameEntry) {
        controller.launch(game).onSuccess { pid ->
            message = "Started ${game.title}  •  PID $pid"
        }.onFailure { error ->
            message = error.message ?: "Could not start ${game.title}"
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .focusRequester(focusRequester)
            .focusable()
            .onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
                if (dialogOpen) return@onPointerEvent
                val scroll = event.changes.firstOrNull()?.scrollDelta ?: return@onPointerEvent
                if (scroll.y == 0f || abs(scroll.y) <= abs(scroll.x)) return@onPointerEvent
                val now = System.nanoTime()
                if (now - lastWheelSwitchNanos < 220_000_000L) return@onPointerEvent
                val nextRow = adjacentLibraryRow(
                    currentRow = selectedRow,
                    direction = if (scroll.y > 0f) 1 else -1,
                    installedAvailable = installedGames.isNotEmpty(),
                    uninstalledAvailable = uninstalledGames.isNotEmpty(),
                )
                if (nextRow != selectedRow) {
                    selectedRow = nextRow
                    lastWheelSwitchNanos = now
                }
            }
            // Preview phase, so the library's own single-key shortcuts win over the app-wide ones
            // (S is Search outside game mode) rather than racing them. Anything not handled here
            // still bubbles out to the app dispatcher, which is how G gets back to the media UI
            // and how Backspace leaves it.
            //
            // navigationKey() rather than the raw key, for the same reason every other grid in the
            // app uses it: it honours TKL mode (W/A/S/D as arrows, which then legitimately takes
            // A and S away from add/settings here) and any rebound select/dismiss key.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || dialogOpen) return@onPreviewKeyEvent false
                when (event.navigationKey()) {
                    Key.DirectionLeft -> {
                        if (selectedRow == 0) installedIndex = (installedIndex - 1).coerceAtLeast(0)
                        else uninstalledIndex = (uninstalledIndex - 1).coerceAtLeast(0)
                        true
                    }
                    Key.DirectionRight -> {
                        if (selectedRow == 0) {
                            installedIndex = (installedIndex + 1).coerceAtMost((installedGames.size - 1).coerceAtLeast(0))
                        } else {
                            uninstalledIndex = (uninstalledIndex + 1).coerceAtMost((uninstalledGames.size - 1).coerceAtLeast(0))
                        }
                        true
                    }
                    Key.DirectionUp -> {
                        if (installedGames.isNotEmpty()) selectedRow = 0
                        true
                    }
                    Key.DirectionDown -> {
                        if (uninstalledGames.isNotEmpty()) selectedRow = 1
                        true
                    }
                    Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
                        if (selectedGame?.isInstalled == true) launchGame(selectedGame)
                        else if (selectedGame != null) editingGame = selectedGame
                        true
                    }
                    Key.A -> {
                        showAddDialog = true
                        true
                    }
                    Key.E -> {
                        if (selectedGame != null) editingGame = selectedGame
                        true
                    }
                    Key.S -> {
                        onOpenSettings()
                        true
                    }
                    Key.Escape -> {
                        onExit()
                        true
                    }
                    else -> false
                }
            },
    ) {
        when {
            loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            games.isEmpty() -> EmptyGameLibrary(
                onAdd = { showAddDialog = true },
                onSettings = onOpenSettings,
            )
            selectedGame != null -> GameLibraryContent(
                installedGames = installedGames,
                uninstalledGames = uninstalledGames,
                selectedRow = selectedRow,
                installedIndex = installedIndex,
                uninstalledIndex = uninstalledIndex,
                backdropStyle = settings.backdropStyle,
                onInstalledSelected = {
                    selectedRow = 0
                    installedIndex = it
                },
                onUninstalledSelected = {
                    selectedRow = 1
                    uninstalledIndex = it
                },
                onLaunchGame = ::launchGame,
                onEditGame = { editingGame = it },
                onAdd = { showAddDialog = true },
                onSettings = onOpenSettings,
            )
        }

        message?.let {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 76.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                // The alpha copy no longer matches colorScheme.surface, so contentColorFor() gives
                // up and Surface falls back to LocalContentColor - black text on a black toast.
                contentColor = Color.White,
                shape = RoundedCornerShape(14.dp),
                shadowElevation = 12.dp,
            ) {
                Text(it, Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }
    }

    if (showAddDialog) {
        GameEditorDialog(
            title = "Add game",
            initial = null,
            lastExecutableDirectory = lastExecutableDirectory,
            igdbConfigured = settings.igdbConfigured,
            steamGridDbConfigured = settings.steamGridDbConfigured,
            onSearch = controller::searchIgdb,
            onLoadMetadata = controller::loadIgdbGame,
            onLoadLogos = controller::loadLogoCandidates,
            onLoadHeroes = controller::loadHeroCandidates,
            onExecutableChosen = controller::rememberExecutableDirectory,
            onOpenSettings = {
                showAddDialog = false
                onOpenSettings()
            },
            onSave = {
                controller.add(it)
                if (it.isInstalled) {
                    selectedRow = 0
                    installedIndex = installedGames.size
                } else {
                    selectedRow = 1
                    uninstalledIndex = uninstalledGames.size
                }
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    editingGame?.let { game ->
        GameEditorDialog(
            title = "Edit game",
            initial = game,
            lastExecutableDirectory = lastExecutableDirectory,
            igdbConfigured = settings.igdbConfigured,
            steamGridDbConfigured = settings.steamGridDbConfigured,
            onSearch = controller::searchIgdb,
            onLoadMetadata = controller::loadIgdbGame,
            onLoadLogos = controller::loadLogoCandidates,
            onLoadHeroes = controller::loadHeroCandidates,
            onExecutableChosen = controller::rememberExecutableDirectory,
            onOpenSettings = {
                editingGame = null
                onOpenSettings()
            },
            onSave = {
                controller.update(it)
                editingGame = null
            },
            onDelete = {
                controller.delete(game.id)
                editingGame = null
            },
            onDismiss = { editingGame = null },
        )
    }
}

@Composable
private fun GameLibraryContent(
    installedGames: List<GameEntry>,
    uninstalledGames: List<GameEntry>,
    selectedRow: Int,
    installedIndex: Int,
    uninstalledIndex: Int,
    backdropStyle: GameBackdropStyle,
    onInstalledSelected: (Int) -> Unit,
    onUninstalledSelected: (Int) -> Unit,
    onLaunchGame: (GameEntry) -> Unit,
    onEditGame: (GameEntry) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
) {
    val selected = if (selectedRow == 0) installedGames[installedIndex] else uninstalledGames[uninstalledIndex]
    val activeGames = if (selectedRow == 0) installedGames else uninstalledGames
    val activeIndex = if (selectedRow == 0) installedIndex else uninstalledIndex
    val activeTitle = if (selectedRow == 0) "INSTALLED" else "UNINSTALLED"
    val shelfBackground = if (backdropStyle == GameBackdropStyle.BlackShelf) {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.30f to Color.Black.copy(alpha = 0.28f),
                0.62f to Color.Black.copy(alpha = 0.88f),
                1f to Color.Black,
            ),
        )
    } else {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.18f to Color.Black.copy(alpha = 0.08f),
                0.46f to Color.Black.copy(alpha = 0.28f),
                0.72f to Color.Black.copy(alpha = 0.58f),
                1f to Color.Black.copy(alpha = 0.82f),
            ),
        )
    }
    var logoLoadFailed by remember(selected.id, selected.logoUrl) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        GameBackdrop(selected, backdropStyle)
        GameTopBar(onAdd = onAdd, onSettings = onSettings)

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 64.dp, end = 48.dp)
                .widthIn(max = 650.dp),
        ) {
            // Plain white title text when there is no dedicated logo, deliberately: ordinary game
            // artwork is not a logo, and pressing it into service as one looks worse than type.
            if (!selected.logoUrl.isNullOrBlank() && !logoLoadFailed) {
                AsyncImage(
                    model = selected.logoUrl,
                    contentDescription = selected.title,
                    modifier = Modifier.height(82.dp).widthIn(max = 440.dp),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    onError = { logoLoadFailed = true },
                )
                Spacer(Modifier.height(14.dp))
            } else {
                Text(
                    text = selected.title,
                    fontSize = 38.sp,
                    lineHeight = 42.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
            }

            GameHeroMetadata(selected)
            if (!selected.summary.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = selected.summary,
                    color = Color.White.copy(alpha = 0.84f),
                    fontSize = 16.sp,
                    lineHeight = 23.sp,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(GameShelfHeight)
                .background(shelfBackground)
                .padding(top = 68.dp, bottom = 26.dp),
        ) {
            GameShelf(
                title = activeTitle,
                games = activeGames,
                selectedIndex = activeIndex,
                emptyText = if (selectedRow == 0) "No installed games" else "No tracked games",
                onSelected = if (selectedRow == 0) onInstalledSelected else onUninstalledSelected,
                onPrimaryActivate = if (selectedRow == 0) onLaunchGame else onEditGame,
                onEdit = onEditGame,
                installedActive = selectedRow == 0,
                installedAvailable = installedGames.isNotEmpty(),
                uninstalledAvailable = uninstalledGames.isNotEmpty(),
                onInstalledRow = { onInstalledSelected(installedIndex) },
                onUninstalledRow = { onUninstalledSelected(uninstalledIndex) },
            )
        }
    }
}

@Composable
private fun GameBackdrop(game: GameEntry, backdropStyle: GameBackdropStyle) {
    val imageModel = game.backdropUrl ?: game.coverUrl
    if (backdropStyle == GameBackdropStyle.BlackShelf) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val backdropHeight = maxOf(
                maxHeight - GameShelfHeight + ShelfOverlap,
                maxHeight * PortraitBackdropMinimum,
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .height(backdropHeight)
                    .fillMaxWidth(BackdropWidthFraction)
                    .clipToBounds()
                    .backdropMask(Color.Black),
            ) {
                AsyncImage(
                    model = imageModel,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    alignment = BackdropAlignment,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(maxHeight * BottomFadeHeightFraction)
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Color.Transparent,
                                0.38f to Color.Black.copy(alpha = 0.18f),
                                0.68f to Color.Black.copy(alpha = 0.72f),
                                1f to Color.Black,
                            ),
                        ),
                    ),
            )
        }
    } else {
        Box(Modifier.fillMaxSize().background(Color(0xFF0A080D))) {
            AsyncImage(
                model = imageModel,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        // Keep the metadata readable without laying a uniform tint over the
                        // artwork. Low-contrast IGDB images otherwise look globally washed out;
                        // the right-hand 30% now remains exactly as supplied by the source.
                        0f to Color(0xF509080C),
                        0.22f to Color(0xBD09080C),
                        0.42f to Color(0x7009080C),
                        0.60f to Color(0x2009080C),
                        0.70f to Color.Transparent,
                        1f to Color.Transparent,
                    ),
                ),
            )
        }
    }
}

private fun Modifier.backdropMask(backgroundColor: Color): Modifier = drawWithContent {
    drawContent()
    drawRect(
        brush = Brush.horizontalGradient(
            colorStops = arrayOf(
                0f to backgroundColor,
                BackdropHorizontalFadeFraction to Color.Transparent,
                1f to Color.Transparent,
            ),
        ),
    )
    drawRect(
        brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.82f to Color.Transparent,
                1f to backgroundColor,
            ),
        ),
    )
    drawRect(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.68f to Color.Transparent,
                1f to backgroundColor,
            ),
            center = center.copy(x = size.width * 0.78f, y = size.height * 0.35f),
            radius = size.maxDimension * 0.82f,
        ),
    )
}

private val GameShelfHeight = 388.dp
private val ShelfOverlap = 72.dp
private val BackdropAlignment = BiasAlignment(horizontalBias = 1f, verticalBias = -0.6f)
private const val BackdropWidthFraction = 0.85f
private const val BackdropHorizontalFadeFraction = 0.35f
private const val PortraitBackdropMinimum = 0.64f
private const val BottomFadeHeightFraction = 0.52f

@Composable
private fun GameTopBar(onAdd: () -> Unit, onSettings: () -> Unit) {
    val silver = Color(0xFFD3D5D8)
    // No wordmark: game mode is a view of Nuvio, not a separate app, so branding it "Umbra" here
    // would name something the user never launched.
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 54.dp, vertical = 26.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onAdd) { Icon(Icons.Rounded.Add, "Add game", tint = silver) }
        IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Game settings", tint = silver) }
    }
}

@Composable
private fun GameHeroMetadata(game: GameEntry) {
    val year = game.releaseDateEpochSeconds?.let {
        runCatching { Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).year.toString() }.getOrNull()
    }
    val parts = buildList {
        year?.let(::add)
        game.rating?.let { add("${it.toInt().coerceIn(0, 100)}%") }
        addAll(game.genres.take(3))
    }
    if (parts.isNotEmpty()) {
        Text(
            text = parts.joinToString("   •   "),
            color = Color.White.copy(alpha = 0.75f),
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun GameShelf(
    title: String,
    games: List<GameEntry>,
    selectedIndex: Int,
    emptyText: String,
    onSelected: (Int) -> Unit,
    onPrimaryActivate: (GameEntry) -> Unit,
    onEdit: (GameEntry) -> Unit,
    installedActive: Boolean,
    installedAvailable: Boolean,
    uninstalledAvailable: Boolean,
    onInstalledRow: () -> Unit,
    onUninstalledRow: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(title, selectedIndex) {
        if (games.isEmpty()) return@LaunchedEffect
        listState.animateScrollToItem((selectedIndex - 1).coerceAtLeast(0))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(294.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 7.dp, bottom = 7.dp),
        ) {
            Text(
                "$title  ${games.size}",
                modifier = Modifier.align(Alignment.CenterStart),
                fontSize = 13.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE0E2E5),
            )
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GameRowIndicator(
                    active = installedActive,
                    enabled = installedAvailable,
                    onClick = onInstalledRow,
                )
                GameRowIndicator(
                    active = !installedActive,
                    enabled = uninstalledAvailable,
                    onClick = onUninstalledRow,
                )
            }
        }
        if (games.isEmpty()) {
            Surface(
                modifier = Modifier.padding(start = 48.dp).width(174.dp).height(218.dp),
                color = Color.White.copy(alpha = 0.035f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                shape = RoundedCornerShape(12.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(emptyText, color = Color.White.copy(alpha = 0.38f), fontSize = 12.sp)
                }
            }
        } else {
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = PaddingValues(horizontal = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemsIndexed(games, key = { _, game -> game.id }) { index, game ->
                    GamePoster(
                        game = game,
                        selected = index == selectedIndex,
                        onSelected = { onSelected(index) },
                        onPrimaryClick = {
                            onSelected(index)
                            onPrimaryActivate(game)
                        },
                        onSecondaryClick = {
                            onSelected(index)
                            onEdit(game)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GamePoster(
    game: GameEntry,
    selected: Boolean,
    onSelected: () -> Unit,
    onPrimaryClick: () -> Unit,
    onSecondaryClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) onSelected() }
    val width by animateDpAsState(if (selected) 184.dp else 168.dp)
    val height by animateDpAsState(if (selected) 262.dp else 239.dp)
    val borderColor by animateColorAsState(if (selected) Color.White else Color.White.copy(alpha = 0.08f))
    Surface(
        modifier = Modifier
            .size(width, height)
            .graphicsLayer {
                shadowElevation = if (selected) 22f else 4f
                shape = RoundedCornerShape(11.dp)
                clip = true
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onPrimaryClick,
            )
            .pointerInput(onSecondaryClick) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.button == PointerButton.Secondary) {
                            event.changes.forEach { it.consume() }
                            onSecondaryClick()
                        }
                    }
                }
            },
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = highResolutionIgdbCoverUrl(game.coverUrl),
                contentDescription = game.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
            )
            if (game.coverUrl.isNullOrBlank()) {
                Text(
                    game.title,
                    Modifier.align(Alignment.Center).padding(12.dp),
                    fontWeight = FontWeight.Bold,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier.fillMaxWidth().height(62.dp).align(Alignment.BottomCenter).background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.86f))),
                ),
            )
        }
    }
}

@Composable
private fun GameRowIndicator(
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(width = 18.dp, height = 26.dp)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (active) 9.dp else 7.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    when {
                        active -> Color.White
                        enabled -> Color.White.copy(alpha = 0.34f)
                        else -> Color.White.copy(alpha = 0.14f)
                    },
                ),
        )
    }
}

internal fun adjacentLibraryRow(
    currentRow: Int,
    direction: Int,
    installedAvailable: Boolean,
    uninstalledAvailable: Boolean,
): Int = when {
    direction > 0 && uninstalledAvailable -> 1
    direction < 0 && installedAvailable -> 0
    else -> currentRow
}

@Composable
private fun EmptyGameLibrary(onAdd: () -> Unit, onSettings: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(listOf(Color(0xFF2B1B40), Color(0xFF09080C)), radius = 900f),
        ),
    ) {
        GameTopBar(onAdd, onSettings)
        Column(
            Modifier.align(Alignment.Center).widthIn(max = 560.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Your games, centre stage.",
                color = Color.White,
                fontSize = 38.sp,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Link a local executable, match it with IGDB, and launch it from a clean TV interface.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 17.sp,
                lineHeight = 25.sp,
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = onAdd) {
                Icon(Icons.Rounded.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Add your first game")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "A add   •   S settings   •   G back to Nuvio",
                color = Color.White.copy(alpha = 0.48f),
                fontSize = 12.sp,
            )
        }
    }
}
