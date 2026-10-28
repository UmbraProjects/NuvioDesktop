package com.nuvio.app.features.games

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.app.DesktopApplicationExit
import com.nuvio.app.core.ui.NuvioPosterCard
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.navigationKey
import com.nuvio.app.core.ui.rememberMouseActivityState
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeTvRowDotsAnchor
import com.nuvio.app.features.home.HomeTvRowTransition
import com.nuvio.app.features.home.components.HomeHeroLayout
import com.nuvio.app.features.home.components.HomeTvRowDot
import com.nuvio.app.features.home.components.HomeTvRowDotStrip
import com.nuvio.app.features.home.components.IMMERSIVE_HERO_LOGO_SLOT_MIN_HEIGHT
import com.nuvio.app.features.home.components.ImmersiveRowDirection
import com.nuvio.app.features.home.components.PAGE_ITEM_STEP
import com.nuvio.app.features.home.components.desktopHeroLogoSlotHeight
import com.nuvio.app.features.home.components.desktopHeroLogoWidthFraction
import com.nuvio.app.features.home.components.homeHeroLayout
import com.nuvio.app.features.home.components.homeSectionHorizontalPaddingForWidth
import com.nuvio.app.features.home.components.immersiveHeroBackdropHeight
import com.nuvio.app.features.home.components.immersiveHeroBottomFadeStops
import com.nuvio.app.features.home.components.immersiveHeroContentHeight
import com.nuvio.app.features.home.components.immersiveHeroContentOffsetY
import com.nuvio.app.features.home.components.immersiveHeroSideScrimStops
import com.nuvio.app.features.home.components.immersiveRowBodyEnter
import com.nuvio.app.features.home.components.immersiveRowBodyExit
import com.nuvio.app.features.home.components.immersiveRowFadeBounds
import com.nuvio.app.features.home.components.immersiveRowTransition
import com.nuvio.app.features.home.components.immersiveShelfScrimStops
import com.nuvio.app.features.home.immersiveCatalogPosterBaseWidthDp
import com.nuvio.app.features.home.immersiveShelfHeightDp
import com.nuvio.app.features.home.IMMERSIVE_SHELF_TOP_PADDING_DP
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.nuvio.app.core.i18n.localizedString

/**
 * Where the user was in the library the last time game mode was open.
 *
 * Game mode is an overlay that is torn down on every toggle, so anything remembered inside the
 * screen is gone by the time it comes back; TV Mode's `HomeScrollMemory` does the same job for
 * the media rows. Session-scoped on purpose: a restart starts at the first row.
 */
internal object GameModeMemory {
    var rowIndex: Int = 0
    var itemIndex: Int = 0
    val rowStates = mutableMapOf<String, LazyListState>()
    val dotsState = LazyListState()
}

/**
 * The game library, laid out exactly as TV Mode lays out the media rows: a full-viewport hero
 * whose metadata column sits just above a fixed-height shelf, one row shown at a time, posters
 * sized to fill the shelf, and the row-jump dots beside the row title. The geometry comes from the
 * same helpers TV Mode uses rather than being copied, so a change there carries over here.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun GameModeScreen(
    modifier: Modifier,
    onExit: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val controller = remember { GameLibraryController.shared }
    val games by controller.games.collectAsState()
    val rows by controller.rows.collectAsState()
    val loading by controller.loading.collectAsState()
    val lastExecutableDirectory by controller.lastExecutableDirectory.collectAsState()
    val settings by remember {
        GameLibrarySettingsRepository.ensureLoaded()
        GameLibrarySettingsRepository.uiState
    }.collectAsState()
    // TV Mode's own row transition and dot anchor: the point of this screen is to look like the
    // media rows, so it follows the same knobs rather than growing a second set.
    val homeSettings by remember {
        HomeCatalogSettingsRepository.snapshot()
        HomeCatalogSettingsRepository.uiState
    }.collectAsState()

    val focusRequester = remember { FocusRequester() }
    val shelfRows = remember(games, rows) { gameShelfRows(games, rows) }
    var rowIndex by remember { mutableIntStateOf(GameModeMemory.rowIndex) }
    var itemIndex by remember { mutableIntStateOf(GameModeMemory.itemIndex) }
    // Clamped on read rather than only in an effect: the library can shrink under a remembered
    // position, and an effect would leave one frame with nothing selected.
    val effectiveRowIndex = rowIndex.coerceIn(0, (shelfRows.size - 1).coerceAtLeast(0))
    val activeRow = shelfRows.getOrNull(effectiveRowIndex)
    val effectiveItemIndex = itemIndex.coerceIn(0, ((activeRow?.games?.size ?: 0) - 1).coerceAtLeast(0))
    val selectedGame = activeRow?.games?.getOrNull(effectiveItemIndex)
    var wheelLocked by remember { mutableStateOf(false) }
    val mouseActivity = rememberMouseActivityState()
    // A game just added or imported: land on it once the rows have been rebuilt around it.
    var pendingFocusGameId by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingGame by remember { mutableStateOf<GameEntry?>(null) }
    var folderImport by remember { mutableStateOf<GameFolderImport?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val dialogOpen = showAddDialog || editingGame != null || folderImport != null

    LaunchedEffect(effectiveRowIndex, effectiveItemIndex) {
        rowIndex = effectiveRowIndex
        itemIndex = effectiveItemIndex
        GameModeMemory.rowIndex = effectiveRowIndex
        GameModeMemory.itemIndex = effectiveItemIndex
    }
    LaunchedEffect(shelfRows, pendingFocusGameId) {
        val target = pendingFocusGameId ?: return@LaunchedEffect
        shelfRows.forEachIndexed { row, shelfRow ->
            val item = shelfRow.games.indexOfFirst { it.id == target }
            if (item >= 0) {
                rowIndex = row
                itemIndex = item
                pendingFocusGameId = null
                return@LaunchedEffect
            }
        }
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

    // Row changes carry the column across, the way TV Mode's Up/Down and wheel do; the clamp on
    // read above handles a shorter destination row.
    fun moveRow(delta: Int) {
        if (shelfRows.isEmpty()) return
        rowIndex = (effectiveRowIndex + delta).coerceIn(0, shelfRows.lastIndex)
    }

    // A dot jump does not carry the column: it skips arbitrarily far, and the previous row's
    // column means nothing there. Mirrors HomeScreen.jumpToImmersiveRow.
    fun jumpToRow(index: Int) {
        if (shelfRows.isEmpty()) return
        val target = index.coerceIn(0, shelfRows.lastIndex)
        if (target == effectiveRowIndex) return
        rowIndex = target
        itemIndex = 0
    }

    fun moveItem(delta: Int) {
        val count = activeRow?.games?.size ?: return
        itemIndex = (effectiveItemIndex + delta).coerceIn(0, (count - 1).coerceAtLeast(0))
    }

    // Picks a folder, then scans it off the UI thread while the dialog shows its spinner. The scan
    // walks every sub-folder three levels deep, which on a spinning disk is not instant.
    fun importFolder() {
        if (dialogOpen) return
        scope.launch {
            val chosen = GameLauncher.chooseGamesFolder(lastExecutableDirectory) ?: return@launch
            val folder = File(chosen)
            folderImport = GameFolderImport(folder)
            val result = withContext(Dispatchers.IO) { GameFolderScanner.scan(folder, games) }
            // Cancelled while scanning: leave it closed rather than reopening on a stale scan.
            if (folderImport?.folder == folder) folderImport = GameFolderImport(folder, result)
        }
    }

    fun launchGame(game: GameEntry) {
        controller.launch(game).onSuccess { pid ->
            message = if (pid != null) localizedString(Res.string.games_mode_started_pid, game.title, pid) else localizedString(Res.string.games_mode_started, game.title)
            if (settings.closeAfterLaunch) {
                // The process (or the shell hand-off for a protocol/shortcut launch) is already
                // under way; the pause is only so the status line is readable before the window
                // goes, and so a launcher that re-execs itself is not racing our exit.
                message = localizedString(Res.string.games_mode_started_closing, game.title)
                scope.launch {
                    delay(CloseAfterLaunchDelayMs)
                    withContext(Dispatchers.Main) { DesktopApplicationExit.request() }
                }
            }
        }.onFailure { error ->
            message = error.message ?: localizedString(Res.string.games_mode_start_failed, game.title)
        }
    }

    fun activate(game: GameEntry) {
        if (game.isInstalled) launchGame(game) else editingGame = game
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .focusRequester(focusRequester)
            .focusable()
            // Same mouse-activity tracking as the home rows: keyboard navigation scrolls posters
            // under a stationary cursor, and only a real mouse move may take the selection back.
            .onPointerEvent(PointerEventType.Move, PointerEventPass.Initial) { event ->
                mouseActivity.onMouseMoved(event.changes.first().position)
            }
            .onPointerEvent(PointerEventType.Enter, PointerEventPass.Initial) { event ->
                mouseActivity.onMouseMoved(event.changes.first().position)
            }
            // TV Mode's wheel: one notch is one row, then a short lock so a free-spinning wheel or
            // a trackpad flick steps once rather than racing through the library.
            .onPointerEvent(PointerEventType.Scroll) { event ->
                if (dialogOpen) return@onPointerEvent
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                val direction = change.scrollDelta.y.compareTo(0f)
                if (direction == 0) return@onPointerEvent
                change.consume()
                if (!wheelLocked && shelfRows.isNotEmpty()) {
                    wheelLocked = true
                    moveRow(direction)
                    scope.launch {
                        delay(ImmersiveWheelLockMs)
                        wheelLocked = false
                    }
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
                        mouseActivity.onKeyboardNavigation()
                        moveItem(-1)
                        true
                    }
                    Key.DirectionRight -> {
                        mouseActivity.onKeyboardNavigation()
                        moveItem(1)
                        true
                    }
                    Key.DirectionUp -> {
                        mouseActivity.onKeyboardNavigation()
                        moveRow(-1)
                        true
                    }
                    Key.DirectionDown -> {
                        mouseActivity.onKeyboardNavigation()
                        moveRow(1)
                        true
                    }
                    // One row at a time, so a page is a run of posters along it — as in TV Mode.
                    Key.PageUp -> {
                        mouseActivity.onKeyboardNavigation()
                        moveItem(-PAGE_ITEM_STEP)
                        true
                    }
                    Key.PageDown -> {
                        mouseActivity.onKeyboardNavigation()
                        moveItem(PAGE_ITEM_STEP)
                        true
                    }
                    Key.MoveHome -> {
                        mouseActivity.onKeyboardNavigation()
                        jumpToRow(0)
                        true
                    }
                    Key.MoveEnd -> {
                        mouseActivity.onKeyboardNavigation()
                        jumpToRow(shelfRows.lastIndex)
                        true
                    }
                    Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
                        selectedGame?.let(::activate)
                        true
                    }
                    Key.A -> {
                        showAddDialog = true
                        true
                    }
                    Key.I -> {
                        importFolder()
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
                    // Both, because a controller has one B button for the keyboard's two keys, and
                    // game mode is an overlay a pad user can open (right stick down).
                    Key.Escape, Key.Backspace -> {
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
                onImportFolder = ::importFolder,
                onSettings = onOpenSettings,
            )
            selectedGame != null -> GameLibraryContent(
                shelfRows = shelfRows,
                rowIndex = effectiveRowIndex,
                itemIndex = effectiveItemIndex,
                selectedGame = selectedGame,
                backdropStyle = settings.backdropStyle,
                rowTransition = homeSettings.tvRowTransition,
                rowDotsAnchor = homeSettings.tvRowDotsAnchor,
                showHeaderAccent = !homeSettings.hideCatalogUnderline,
                isKeyboardNavigation = !mouseActivity.isMouseActive,
                onHoverItem = { index ->
                    // Keyboard paging scrolls posters beneath a stationary cursor, which emits
                    // Enter events for the posters passing under it. Only a real mouse move may
                    // take the selection back — the same rule as the home rows.
                    if (mouseActivity.isMouseActive) itemIndex = index
                },
                onRowDotClick = ::jumpToRow,
                onActivateGame = ::activate,
                onEditGame = { editingGame = it },
                onAdd = { showAddDialog = true },
                onImportFolder = ::importFolder,
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
            title = stringResource(Res.string.games_mode_add_game),
            initial = null,
            rows = rows,
            lastExecutableDirectory = lastExecutableDirectory,
            igdbConfigured = settings.igdbConfigured,
            steamGridDbConfigured = settings.steamGridDbConfigured,
            metadataSource = settings.metadataSource,
            onSearch = controller::searchMetadata,
            onLoadMetadata = controller::loadMetadata,
            onLoadLogos = controller::loadLogoCandidates,
            onLoadHeroes = controller::loadHeroCandidates,
            onLoadSteamHeroes = controller::loadSteamHeroCandidates,
            onLoadSteamLogos = controller::loadSteamLogoCandidates,
            onExecutableChosen = controller::rememberExecutableDirectory,
            onOpenSettings = {
                showAddDialog = false
                onOpenSettings()
            },
            onSave = {
                controller.add(it)
                pendingFocusGameId = it.id
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    folderImport?.let { import ->
        GameFolderImportDialog(
            import = import,
            onImport = { candidates ->
                val added = controller.importScanned(candidates)
                folderImport = null
                if (added.isNotEmpty()) {
                    // Land on the first new game, in the row it went to.
                    pendingFocusGameId = added.first().id
                    message = if (added.size == 1) {
                        localizedString(Res.string.games_mode_imported_one, added.first().title)
                    } else {
                        localizedString(Res.string.games_mode_imported_many, added.size)
                    }
                }
            },
            onDismiss = { folderImport = null },
        )
    }

    editingGame?.let { game ->
        GameEditorDialog(
            title = stringResource(Res.string.games_mode_edit_game),
            initial = game,
            rows = rows,
            lastExecutableDirectory = lastExecutableDirectory,
            igdbConfigured = settings.igdbConfigured,
            steamGridDbConfigured = settings.steamGridDbConfigured,
            metadataSource = settings.metadataSource,
            onSearch = controller::searchMetadata,
            onLoadMetadata = controller::loadMetadata,
            onLoadLogos = controller::loadLogoCandidates,
            onLoadHeroes = controller::loadHeroCandidates,
            onLoadSteamHeroes = controller::loadSteamHeroCandidates,
            onLoadSteamLogos = controller::loadSteamLogoCandidates,
            onExecutableChosen = controller::rememberExecutableDirectory,
            onOpenSettings = {
                editingGame = null
                onOpenSettings()
            },
            onSave = {
                controller.update(it)
                // Follow the game if the edit moved it to another row.
                if (it.rowId != game.rowId || it.isInstalled != game.isInstalled) pendingFocusGameId = it.id
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

/** How long the wheel is ignored after a row step; the same lock TV Mode uses. */
private const val ImmersiveWheelLockMs = 220L

/** Pause between a successful game start and Nuvio exiting when "close after launch" is on. */
private const val CloseAfterLaunchDelayMs = 1_500L

@Composable
private fun GameLibraryContent(
    shelfRows: List<GameShelfRow>,
    rowIndex: Int,
    itemIndex: Int,
    selectedGame: GameEntry,
    backdropStyle: GameBackdropStyle,
    rowTransition: HomeTvRowTransition,
    rowDotsAnchor: HomeTvRowDotsAnchor,
    showHeaderAccent: Boolean,
    isKeyboardNavigation: Boolean,
    onHoverItem: (Int) -> Unit,
    onRowDotClick: (Int) -> Unit,
    onActivateGame: (GameEntry) -> Unit,
    onEditGame: (GameEntry) -> Unit,
    onAdd: () -> Unit,
    onImportFolder: () -> Unit,
    onSettings: () -> Unit,
) {
    val fullBackdrop = backdropStyle == GameBackdropStyle.FullBackdrop
    val backgroundColor = MaterialTheme.colorScheme.background
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The same three numbers TV Mode derives for the media rows: shelf height from the
        // viewport, section padding from the width, and a poster width that fills the shelf.
        val shelfHeight = immersiveShelfHeightDp(
            viewportHeightDp = maxHeight.value,
            landscapeMode = false,
        ).dp
        val sectionPadding = homeSectionHorizontalPaddingForWidth(maxWidth.value)
        val posterBaseWidthDp = remember(maxWidth, shelfHeight, sectionPadding) {
            immersiveCatalogPosterBaseWidthDp(
                maxWidthDp = maxWidth.value,
                shelfHeightDp = shelfHeight.value,
                sectionPaddingDp = sectionPadding.value,
                hideLabels = true,
                landscapeMode = false,
            )
        }
        val heroLayout = remember(maxWidth, maxHeight) {
            homeHeroLayout(
                maxWidthDp = maxWidth.value,
                viewportHeightDp = maxHeight.value,
                preferDesktopLayout = true,
            )
        }

        GameHero(
            game = selectedGame,
            heroHeight = maxHeight,
            shelfHeight = shelfHeight,
            fullBackdrop = fullBackdrop,
            layout = heroLayout,
            backgroundColor = backgroundColor,
        )
        GameTopBar(onAdd = onAdd, onImportFolder = onImportFolder, onSettings = onSettings)

        // The row-jump dots, one per non-empty row, coloured by the row's own marker. Always on
        // here: the library has had a row indicator since it was written, and with more than two
        // rows the dots are the way to reach one without stepping through the others.
        val dots = remember(shelfRows) {
            shelfRows.map { shelfRow ->
                HomeTvRowDot(
                    rowKey = shelfRow.row.id,
                    label = shelfRow.row.name,
                    markerColor = shelfRow.row.color?.let { Color(it) },
                )
            }
        }
        val dotsContent: (@Composable () -> Unit)? = if (dots.size > 1) {
            {
                HomeTvRowDotStrip(
                    dots = dots,
                    activeIndex = rowIndex,
                    onDotClick = onRowDotClick,
                    listState = GameModeMemory.dotsState,
                    anchor = rowDotsAnchor,
                )
            }
        } else {
            null
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(shelfHeight)
                .align(Alignment.BottomStart)
                // The shelf's own scrim — what makes the bottom band black under Black shelf, and
                // what stops short so the artwork shows through under Full backdrop.
                .background(
                    Brush.verticalGradient(
                        colorStops = immersiveShelfScrimStops(
                            backgroundColor = backgroundColor,
                            fullBackdrop = fullBackdrop,
                        ),
                    ),
                )
                .padding(
                    top = IMMERSIVE_SHELF_TOP_PADDING_DP.dp,
                    // Applied inside each row instead; see immersiveRowFadeBounds.
                    bottom = 0.dp,
                ),
            contentAlignment = Alignment.TopStart,
        ) {
            val rowDirection = remember { ImmersiveRowDirection() }
            rowDirection.observe(rowIndex)
            AnimatedContent(
                targetState = rowIndex,
                transitionSpec = { immersiveRowTransition(rowTransition) },
                contentAlignment = Alignment.TopStart,
                label = "game_row",
            ) { index ->
                val shelfRow = shelfRows.getOrNull(index) ?: return@AnimatedContent
                // The nudge moves only the posters: the header (title, dots) just fades with the
                // shelf, so it never shifts under the reader's eye.
                val rowBodyModifier = if (rowTransition == HomeTvRowTransition.FadeNudge) {
                    Modifier.animateEnterExit(
                        enter = immersiveRowBodyEnter(rowDirection.forward),
                        exit = immersiveRowBodyExit(rowDirection.forward),
                        label = "game_row_body",
                    )
                } else {
                    Modifier
                }
                NuvioShelfSection(
                    title = shelfRow.row.name,
                    entries = shelfRow.games,
                    modifier = Modifier.immersiveRowFadeBounds(),
                    headerHorizontalPadding = sectionPadding,
                    rowContentPadding = PaddingValues(horizontal = sectionPadding),
                    showHeaderAccent = showHeaderAccent,
                    // Only the row on screen owns the selection; the outgoing row of a transition
                    // keeps drawing its posters unhighlighted.
                    focusedItemIndex = if (index == rowIndex) itemIndex else null,
                    onHoverItem = if (index == rowIndex) onHoverItem else null,
                    isKeyboardNavigation = isKeyboardNavigation,
                    headerTrailingContent = dotsContent,
                    bodyModifier = rowBodyModifier,
                    key = { game -> game.id },
                    rowState = remember(shelfRow.row.id) {
                        GameModeMemory.rowStates.getOrPut(shelfRow.row.id) { LazyListState() }
                    },
                ) { game ->
                    NuvioPosterCard(
                        title = game.title,
                        imageUrl = highResolutionIgdbCoverUrl(game.coverUrl),
                        basePosterWidthDpOverride = posterBaseWidthDp,
                        // TV Mode hides the labels under its posters; the hero carries the title.
                        showTitleBelow = false,
                        onClick = { onActivateGame(game) },
                        // Right-click (and long press) edits, as before.
                        onLongClick = { onEditGame(game) },
                    )
                }
            }
        }
    }
}

/**
 * The hero, on TV Mode's immersive geometry: artwork pinned top-right above the shelf (or filling
 * the window under Full backdrop), the side and bottom scrims the media hero uses, and the
 * metadata column bottom-anchored just above the shelf.
 */
@Composable
private fun GameHero(
    game: GameEntry,
    heroHeight: Dp,
    shelfHeight: Dp,
    fullBackdrop: Boolean,
    layout: HomeHeroLayout,
    backgroundColor: Color,
) {
    val contentBottomPadding = shelfHeight - 20.dp
    val backdropHeight = immersiveHeroBackdropHeight(
        heroHeight = heroHeight,
        immersiveContentBottomPadding = contentBottomPadding,
    )
    val artworkHeight = if (fullBackdrop) heroHeight else backdropHeight
    val artworkWidthFraction = if (fullBackdrop) 1f else HeroBackdropWidthFraction

    Box(Modifier.fillMaxSize().background(backgroundColor)) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .height(artworkHeight)
                .fillMaxWidth(artworkWidthFraction)
                .clipToBounds()
                .gameBackdropFadeMask(backgroundColor, fullWidth = fullBackdrop)
                .then(if (fullBackdrop) Modifier else Modifier.gameBackdropExtraMask(backgroundColor)),
        ) {
            // Keyed on the artwork, not the game: two games sharing a backdrop (a series) need no
            // fade, and a game whose art changes in the editor does.
            val imageModel = game.backdropUrl ?: game.coverUrl
            Crossfade(
                targetState = imageModel,
                animationSpec = tween(HeroBackdropCrossfadeMs),
                label = "game_backdrop",
            ) { model ->
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    alignment = HeroBackdropAlignment,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight * HeroBottomFadeFraction)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colorStops = immersiveHeroBottomFadeStops(
                            backgroundColor = backgroundColor,
                            fullBackdrop = fullBackdrop,
                        ),
                    ),
                ),
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = layout.contentHorizontalPadding, end = layout.contentHorizontalPadding)
                .padding(bottom = contentBottomPadding)
                .height(immersiveHeroContentHeight(heroHeight))
                .offset(y = immersiveHeroContentOffsetY(heroHeight))
                .fillMaxWidth(HeroContentWidthFraction)
                .widthIn(max = HeroContentMaxWidth),
            contentAlignment = Alignment.TopStart,
        ) {
            GameHeroContent(game = game, layout = layout)
        }
    }
}

/**
 * The metadata column: logo (or title) at the bottom of a flexible slot, then the genre line,
 * synopsis and a footer — the same rows, styles and spacing as the media hero's immersive column.
 */
@Composable
private fun GameHeroContent(game: GameEntry, layout: HomeHeroLayout) {
    var logoLoadFailed by remember(game.id, game.logoUrl) { mutableStateOf(false) }
    val logoUrl = game.logoUrl?.takeIf { it.isNotBlank() && !logoLoadFailed }
    val onBackground = MaterialTheme.colorScheme.onBackground
    Column(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
        horizontalAlignment = Alignment.Start,
    ) {
        // The weight hands the slot whatever the fixed rows leave; heightIn caps that at the
        // natural slot height, and fillMaxHeight takes all of the capped room so the logo sits on
        // the same baseline whether the game has a synopsis or not.
        val logoSlotModifier = Modifier
            .fillMaxWidth()
            .weight(1f, fill = false)
            .heightIn(
                min = IMMERSIVE_HERO_LOGO_SLOT_MIN_HEIGHT,
                max = desktopHeroLogoSlotHeight(layout),
            )
            .fillMaxHeight()
        Box(modifier = logoSlotModifier, contentAlignment = Alignment.BottomStart) {
            if (logoUrl != null) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = game.title,
                    modifier = Modifier
                        .fillMaxWidth(desktopHeroLogoWidthFraction(layout))
                        .fillMaxHeight(),
                    alignment = Alignment.BottomStart,
                    contentScale = ContentScale.Fit,
                    clipToBounds = false,
                    onError = { logoLoadFailed = true },
                )
            } else {
                // Plain type when there is no dedicated logo, deliberately: ordinary game artwork
                // is not a logo, and pressing it into service as one looks worse than text.
                Text(
                    text = game.title,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.displayMedium,
                    color = onBackground,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Start,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val year = game.releaseDateEpochSeconds?.let {
            runCatching { Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).year.toString() }.getOrNull()
        }
        val genreParts = game.genres.take(3) + listOfNotNull(year)
        if (genreParts.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = genreParts.joinToString(" • "),
                style = MaterialTheme.typography.titleMedium,
                color = onBackground.copy(alpha = 0.76f),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        game.summary
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { summary ->
                Spacer(Modifier.height(16.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyLarge,
                    color = onBackground.copy(alpha = 0.82f),
                    maxLines = HeroSynopsisMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }

        // Footer: the rating with its source named — a bare percentage says nothing — and the
        // platforms the provider listed.
        val ratingText = game.rating?.let { rating ->
            val value = rating.toInt().coerceIn(0, 100)
            when (game.effectiveMetadataSource) {
                GameMetadataSource.Igdb -> "IGDB $value%"
                GameMetadataSource.Steam -> "Metacritic $value"
            }
        }
        val footerParts = listOfNotNull(ratingText) + game.platforms.take(3)
        if (footerParts.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = footerParts.joinToString(" • "),
                style = MaterialTheme.typography.labelLarge,
                color = onBackground.copy(alpha = 0.76f),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// The media hero's own backdrop masks (HomeHeroSection.heroBackdropFadeMask and
// immersiveHeroExtraMask are private to it); same stops, so the two heroes blend identically.
private fun Modifier.gameBackdropFadeMask(backgroundColor: Color, fullWidth: Boolean): Modifier =
    drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.horizontalGradient(
                colorStops = if (fullWidth) {
                    immersiveHeroSideScrimStops(backgroundColor)
                } else {
                    arrayOf(
                        0f to backgroundColor,
                        HeroBackdropFadeFraction to Color.Transparent,
                        1f to Color.Transparent,
                    )
                },
            ),
        )
    }

private fun Modifier.gameBackdropExtraMask(backgroundColor: Color): Modifier =
    drawWithContent {
        drawContent()
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

private val HeroBackdropAlignment = BiasAlignment(horizontalBias = 1f, verticalBias = -0.6f)
private const val HeroBackdropWidthFraction = 0.85f
private const val HeroBackdropFadeFraction = 0.35f
private const val HeroBottomFadeFraction = 0.52f
private const val HeroContentWidthFraction = 0.32f
private val HeroContentMaxWidth = 600.dp
private const val HeroSynopsisMaxLines = 5
private const val HeroBackdropCrossfadeMs = 260

@Composable
private fun GameTopBar(onAdd: () -> Unit, onImportFolder: () -> Unit, onSettings: () -> Unit) {
    val silver = Color(0xFFD3D5D8)
    // No wordmark: game mode is a view of Nuvio, not a separate app, so a name of its own here
    // would announce something the user never launched.
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 54.dp, vertical = 26.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onAdd) { Icon(Icons.Rounded.Add, stringResource(Res.string.games_mode_add_game), tint = silver) }
        IconButton(onClick = onImportFolder) {
            Icon(Icons.Rounded.CreateNewFolder, stringResource(Res.string.games_mode_import_games_from_folder), tint = silver)
        }
        IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, stringResource(Res.string.games_mode_game_settings), tint = silver) }
    }
}

@Composable
private fun EmptyGameLibrary(onAdd: () -> Unit, onImportFolder: () -> Unit, onSettings: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(listOf(Color(0xFF2B1B40), Color(0xFF09080C)), radius = 900f),
        ),
    ) {
        GameTopBar(onAdd, onImportFolder, onSettings)
        Column(
            Modifier.align(Alignment.Center).widthIn(max = 560.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(Res.string.games_mode_empty_title),
                color = Color.White,
                fontSize = 38.sp,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(Res.string.games_mode_empty_message),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 17.sp,
                lineHeight = 25.sp,
            )
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onAdd) {
                    Icon(Icons.Rounded.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.games_mode_add_your_first_game))
                }
                OutlinedButton(onClick = onImportFolder) {
                    Icon(Icons.Rounded.CreateNewFolder, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.games_mode_import_a_folder))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(Res.string.games_mode_key_hints),
                color = Color.White.copy(alpha = 0.48f),
                fontSize = 12.sp,
            )
        }
    }
}
