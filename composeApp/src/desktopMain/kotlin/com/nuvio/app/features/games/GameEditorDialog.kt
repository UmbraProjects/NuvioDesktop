package com.nuvio.app.features.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioModalDialog
import com.nuvio.app.core.ui.NuvioTextField
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import com.nuvio.app.core.ui.accentFill
import androidx.compose.ui.graphics.SolidColor
import com.nuvio.app.core.ui.accentBrush

/**
 * Add / edit a game.
 *
 * Built on the app's own dialog shell and text fields rather than the standalone launcher's, so it
 * matches every other popup in Nuvio — and so its inputs register with the app-wide text-input
 * focus tracker, which is what stops a typed "G" from dropping out of game mode mid-search.
 */
@Composable
internal fun GameEditorDialog(
    title: String,
    initial: GameEntry?,
    lastExecutableDirectory: String,
    igdbConfigured: Boolean,
    steamGridDbConfigured: Boolean,
    onSearch: suspend (String) -> List<IgdbGame>,
    onLoadMetadata: suspend (Long) -> IgdbGame?,
    onLoadLogos: suspend (String) -> List<LogoCandidate>,
    onLoadHeroes: suspend (String) -> List<ArtworkCandidate>,
    onExecutableChosen: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onSave: (GameEntry) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.nuvio.colors
    var executablePath by remember(initial) { mutableStateOf(initial?.executablePath.orEmpty()) }
    var gameTitle by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
    var arguments by remember(initial) {
        mutableStateOf(initial?.arguments?.joinToString(" ") { quoteArgument(it) }.orEmpty())
    }
    var workingDirectory by remember(initial) { mutableStateOf(initial?.workingDirectory.orEmpty()) }
    var searchText by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
    var results by remember { mutableStateOf<List<IgdbGame>>(emptyList()) }
    var selectedMetadata by remember { mutableStateOf<IgdbGame?>(null) }
    var selectedBackdropUrl by remember(initial) { mutableStateOf(initial?.backdropUrl) }
    var showBackdropPicker by remember { mutableStateOf(false) }
    var selectedLogoUrl by remember(initial) { mutableStateOf(initial?.logoUrl) }
    var logoCandidates by remember { mutableStateOf<List<LogoCandidate>>(emptyList()) }
    var showLogoPicker by remember { mutableStateOf(false) }
    var loadingLogos by remember { mutableStateOf(false) }
    var logoManuallySelected by remember(initial) { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var steamGridHeroes by remember(initial) { mutableStateOf<List<ArtworkCandidate>>(emptyList()) }
    var loadingHeroes by remember { mutableStateOf(false) }

    fun runSearch() {
        if (searchText.isBlank() || searching) return
        scope.launch {
            searching = true
            error = null
            results = runCatching { onSearch(searchText) }
                .onFailure { error = it.message ?: "IGDB search failed" }
                .getOrDefault(emptyList())
            searching = false
        }
    }

    fun loadLogos() {
        if (gameTitle.isBlank() || loadingLogos || !steamGridDbConfigured) return
        scope.launch {
            loadingLogos = true
            error = null
            logoCandidates = runCatching { onLoadLogos(gameTitle) }
                .onFailure { error = it.message ?: "SteamGridDB logo search failed" }
                .getOrDefault(emptyList())
            loadingLogos = false
            if (logoCandidates.isEmpty()) {
                if (error == null) error = "SteamGridDB has no logos for $gameTitle."
            } else {
                showLogoPicker = true
            }
        }
    }

    // Fetched when the picker opens rather than with the dialog: it is a second round trip per
    // game and most edits never touch the backdrop. Keyed on the title too, so re-opening after an
    // IGDB match searches SteamGridDB for the matched name rather than whatever was typed first.
    LaunchedEffect(showBackdropPicker, gameTitle, steamGridDbConfigured) {
        if (!showBackdropPicker || !steamGridDbConfigured || gameTitle.isBlank()) return@LaunchedEffect
        loadingHeroes = true
        steamGridHeroes = runCatching { onLoadHeroes(gameTitle) }
            .onFailure { error = it.message ?: "SteamGridDB backdrop search failed" }
            .getOrDefault(emptyList())
        loadingHeroes = false
    }

    LaunchedEffect(initial?.igdbId, igdbConfigured) {
        val igdbId = initial?.igdbId ?: return@LaunchedEffect
        if (!igdbConfigured || selectedMetadata != null) return@LaunchedEffect
        runCatching { onLoadMetadata(igdbId) }.getOrNull()?.let { metadata ->
            selectedMetadata = metadata
            if (selectedBackdropUrl.isNullOrBlank()) selectedBackdropUrl = metadata.backdropUrl
        }
    }

    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = title,
        subtitle = "A game does not need an executable — tracked titles appear under Uninstalled " +
            "until you add one.",
        maxWidth = 920.dp,
        actions = {
            if (onDelete != null) {
                if (confirmDelete) {
                    Text(
                        text = "Delete this game?",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.danger,
                    )
                    TextButton(onClick = onDelete) { Text("Confirm", color = colors.danger) }
                    TextButton(onClick = { confirmDelete = false }) { Text("Keep") }
                } else {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Delete", color = colors.danger)
                    }
                }
            }
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Button(
                enabled = gameTitle.isNotBlank(),
                onClick = {
                    val metadata = selectedMetadata
                    executablePath.trim().takeIf(String::isNotBlank)?.let(onExecutableChosen)
                    onSave(
                        GameEntry(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            igdbId = metadata?.id ?: initial?.igdbId,
                            title = gameTitle.trim(),
                            executablePath = executablePath.trim().takeIf(String::isNotBlank),
                            arguments = parseArguments(arguments),
                            workingDirectory = workingDirectory.trim().takeIf(String::isNotBlank),
                            coverUrl = metadata?.coverUrl ?: initial?.coverUrl,
                            backdropUrl = selectedBackdropUrl ?: metadata?.backdropUrl ?: initial?.backdropUrl,
                            logoUrl = selectedLogoUrl ?: metadata?.logoUrl ?: initial?.logoUrl,
                            summary = metadata?.summary ?: initial?.summary,
                            releaseDateEpochSeconds = metadata?.releaseDateEpochSeconds
                                ?: initial?.releaseDateEpochSeconds,
                            genres = metadata?.genres ?: initial?.genres.orEmpty(),
                            platforms = metadata?.platforms ?: initial?.platforms.orEmpty(),
                            rating = metadata?.rating ?: initial?.rating,
                            logoLookupCompleted = logoManuallySelected ||
                                metadata != null ||
                                initial?.logoLookupCompleted == true,
                            // Deliberately NOT reset just because metadata is present: opening the
                            // editor auto-loads the existing IGDB match, so "metadata != null" is
                            // true for a plain no-op save too. Resetting here re-armed the artwork
                            // top-up on every save and it overwrote hand-picked logos. Re-matching
                            // is detected by the controller, which compares the stored IGDB id.
                            logoLookupVersion = if (logoManuallySelected) 3 else initial?.logoLookupVersion ?: 0,
                            logoManuallyChosen = logoRemainsManuallyChosen(
                                pickedNow = logoManuallySelected,
                                previouslyManual = initial?.logoManuallyChosen == true,
                                previousLogoUrl = initial?.logoUrl,
                                logoUrlToSave = selectedLogoUrl ?: metadata?.logoUrl ?: initial?.logoUrl,
                            ),
                            addedAtEpochMillis = initial?.addedAtEpochMillis ?: System.currentTimeMillis(),
                        ),
                    )
                },
            ) {
                Text(if (initial == null) "Add game" else "Save changes")
            }
        },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            FieldColumn("Executable (optional)", Modifier.weight(1f)) {
                NuvioTextField(
                    value = executablePath,
                    onValueChange = {
                        executablePath = it
                        if (workingDirectory.isBlank()) workingDirectory = File(it).parent.orEmpty()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "C:\\Games\\Example\\game.exe",
                )
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val selected = GameLauncher.chooseExecutable(
                            executablePath.ifBlank { lastExecutableDirectory },
                        )
                        if (selected != null) {
                            executablePath = selected
                            onExecutableChosen(selected)
                            if (gameTitle.isBlank()) gameTitle = executableDisplayName(selected)
                            if (searchText.isBlank()) searchText = executableDisplayName(selected)
                            workingDirectory = File(selected).parent.orEmpty()
                        }
                    }
                },
            ) {
                Icon(Icons.Rounded.FolderOpen, null)
                Spacer(Modifier.width(7.dp))
                Text("Browse")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FieldColumn("Display title", Modifier.weight(1f)) {
                NuvioTextField(
                    value = gameTitle,
                    onValueChange = { gameTitle = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Shown in the library",
                )
            }
            FieldColumn("Launch arguments (optional)", Modifier.weight(1f)) {
                NuvioTextField(
                    value = arguments,
                    onValueChange = { arguments = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "--windowed",
                )
            }
        }

        FieldColumn("Working directory (optional)", Modifier.fillMaxWidth()) {
            NuvioTextField(
                value = workingDirectory,
                onValueChange = { workingDirectory = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "Defaults to the folder holding the executable",
            )
        }

        SectionLabel("IGDB METADATA")
        if (!igdbConfigured) {
            Surface(
                modifier = Modifier.background(
                    colors.accentFill(MaterialTheme.nuvio.opacity.pressed),
                    MaterialTheme.nuvio.shapes.compactCard,
                ),
                color = Color.Transparent,
                shape = MaterialTheme.nuvio.shapes.compactCard,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Add your Twitch application credentials before searching IGDB.",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onOpenSettings) { Text("Open settings") }
                }
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NuvioTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = "Search IGDB",
                    onImeAction = ::runSearch,
                )
                Button(onClick = ::runSearch, enabled = searchText.isNotBlank() && !searching) {
                    if (searching) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Search, null)
                    }
                    Spacer(Modifier.width(7.dp))
                    Text("Search")
                }
            }
        }

        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = colors.danger,
            )
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                searching -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                results.isEmpty() -> Text(
                    text = selectedMetadata?.let { "Metadata selected: ${it.title}" }
                        ?: "Search results will appear here. You can also save a game without IGDB metadata.",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                )
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(results, key = IgdbGame::id) { item ->
                        IgdbResultRow(
                            game = item,
                            selected = selectedMetadata?.id == item.id,
                            onClick = {
                                selectedMetadata = item
                                gameTitle = item.title
                                selectedBackdropUrl = item.backdrops
                                    .firstOrNull { it.url == selectedBackdropUrl }
                                    ?.url
                                    ?: item.backdropUrl
                                selectedLogoUrl = if (initial?.igdbId == item.id) initial.logoUrl else item.logoUrl
                                logoCandidates = emptyList()
                                logoManuallySelected = false
                            },
                        )
                    }
                }
            }
        }

        val backdrops = selectedMetadata?.backdrops.orEmpty()
        val selectedBackdrop = backdrops.firstOrNull { it.url == selectedBackdropUrl }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ArtworkChoice(
                modifier = Modifier.weight(1f),
                label = "BACKDROP",
                count = (backdrops.size + steamGridHeroes.size).takeIf { it > 0 },
                detail = when {
                    selectedBackdrop != null -> "${selectedBackdrop.source.label}  •  ${selectedBackdrop.resolutionLabel}"
                    !selectedBackdropUrl.isNullOrBlank() -> "Current backdrop"
                    selectedMetadata == null -> "Match with IGDB to choose artwork"
                    else -> "No backdrop available"
                },
                enabled = backdrops.isNotEmpty(),
                onClick = { showBackdropPicker = true },
            )
            ArtworkChoice(
                modifier = Modifier.weight(1f),
                label = "LOGO",
                count = logoCandidates.size.takeIf { it > 0 },
                detail = when {
                    !steamGridDbConfigured -> "Add a SteamGridDB API key in Settings"
                    logoManuallySelected -> "Chosen SteamGridDB artwork"
                    !selectedLogoUrl.isNullOrBlank() -> "Current logo • choose another if needed"
                    else -> "Automatic English-first selection on save"
                },
                enabled = steamGridDbConfigured && gameTitle.isNotBlank() && !loadingLogos,
                busy = loadingLogos,
                onClick = ::loadLogos,
            )
        }
    }

    selectedMetadata?.takeIf { showBackdropPicker }?.let { metadata ->
        BackdropPickerDialog(
            gameTitle = metadata.title,
            igdbCandidates = metadata.backdrops,
            steamGridCandidates = steamGridHeroes,
            loadingSteamGrid = loadingHeroes,
            selectedUrl = selectedBackdropUrl,
            onSelect = { artwork ->
                selectedBackdropUrl = artwork.url
                showBackdropPicker = false
            },
            onDismiss = { showBackdropPicker = false },
        )
    }

    if (showLogoPicker && logoCandidates.isNotEmpty()) {
        LogoPickerDialog(
            gameTitle = gameTitle,
            candidates = logoCandidates,
            selectedUrl = selectedLogoUrl,
            onSelect = { logo ->
                selectedLogoUrl = logo.url
                logoManuallySelected = true
                showLogoPicker = false
            },
            onDismiss = { showLogoPicker = false },
        )
    }
}

@Composable
private fun FieldColumn(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.nuvio.colors.textMuted,
        )
        content()
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.accentBrush(),
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.nuvio.colors.accent,
    )
}

/**
 * One artwork slot — the whole block is the button.
 *
 * The count lives in the heading ("BACKDROP (16)") rather than on a trailing pill, so backdrop and
 * logo can sit side by side and read as a pair. [count] is null while there is nothing to pick
 * from yet: IGDB has not been matched, or the logo candidates have not been fetched.
 */
@Composable
private fun ArtworkChoice(
    label: String,
    count: Int?,
    detail: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
) {
    val colors = MaterialTheme.nuvio.colors
    Surface(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceCard,
        border = BorderStroke(1.dp, colors.borderDefault),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (count != null) "$label ($count)" else label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) colors.textPrimary else colors.textDisabled,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (busy) {
                Spacer(Modifier.width(10.dp))
                CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
            }
        }
    }
}

/**
 * Backdrop options from both sources in one grid, each tile labelled with where it came from.
 *
 * Neither source wins automatically: IGDB artworks and screenshots keep the order they arrived in
 * so an existing library never shifts under the user, and SteamGridDB heroes are appended. The
 * subtitle breaks the count down by source so it is obvious which half is which before scrolling.
 */
@Composable
private fun BackdropPickerDialog(
    gameTitle: String,
    igdbCandidates: List<ArtworkCandidate>,
    steamGridCandidates: List<ArtworkCandidate>,
    loadingSteamGrid: Boolean,
    selectedUrl: String?,
    onSelect: (ArtworkCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    // SteamGridDB occasionally mirrors an image IGDB also has; the first occurrence wins so the
    // grid never shows the same picture twice under two different labels.
    val candidates = remember(igdbCandidates, steamGridCandidates) {
        (igdbCandidates + steamGridCandidates).distinctBy(ArtworkCandidate::url)
    }
    val breakdown = buildList {
        add("${igdbCandidates.size} from IGDB")
        when {
            loadingSteamGrid -> add("searching SteamGridDB…")
            steamGridCandidates.isNotEmpty() -> add("${steamGridCandidates.size} from SteamGridDB")
        }
    }.joinToString("  •  ")

    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = "Choose backdrop",
        subtitle = "$gameTitle  •  $breakdown",
        maxWidth = 1040.dp,
        actions = { TextButton(onClick = onDismiss) { Text("Close") } },
    ) {
        if (candidates.isEmpty() && loadingSteamGrid) {
            Box(Modifier.fillMaxWidth().height(520.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@NuvioModalDialog
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 260.dp),
            modifier = Modifier.fillMaxWidth().height(520.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            gridItems(candidates, key = ArtworkCandidate::url) { artwork ->
                ArtworkTile(
                    selected = artwork.url == selectedUrl,
                    onClick = { onSelect(artwork) },
                    leadingLabel = artwork.source.label,
                    trailingLabel = artwork.resolutionLabel,
                ) {
                    AsyncImage(
                        model = artwork.url,
                        contentDescription = "${artwork.source.label} ${artwork.resolutionLabel}",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            if (loadingSteamGrid) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Looking for SteamGridDB backdrops",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogoPickerDialog(
    gameTitle: String,
    candidates: List<LogoCandidate>,
    selectedUrl: String?,
    onSelect: (LogoCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = "Choose logo",
        subtitle = "$gameTitle  •  ${candidates.size} SteamGridDB options",
        maxWidth = 1040.dp,
        actions = { TextButton(onClick = onDismiss) { Text("Close") } },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 260.dp),
            modifier = Modifier.fillMaxWidth().height(520.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            gridItems(candidates, key = LogoCandidate::url) { logo ->
                ArtworkTile(
                    selected = logo.url == selectedUrl,
                    onClick = { onSelect(logo) },
                    leadingLabel = listOfNotNull(logo.languageLabel, logo.style?.takeIf(String::isNotBlank))
                        .joinToString("  •  ")
                        .ifBlank { "Logo" },
                    trailingLabel = logo.resolutionLabel,
                ) {
                    // Logos are transparent, so they need a dark plate and padding to read at all.
                    Box(Modifier.fillMaxSize().background(Color(0xFF0A080D))) {
                        AsyncImage(
                            model = logo.url,
                            contentDescription = "Logo ${logo.resolutionLabel}",
                            modifier = Modifier.fillMaxSize().padding(14.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtworkTile(
    selected: Boolean,
    onClick: () -> Unit,
    leadingLabel: String,
    trailingLabel: String,
    preview: @Composable () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(13.dp),
        color = colors.surfaceCard,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) colors.accentFill else SolidColor(colors.borderDefault),
        ),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                preview()
                if (selected) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(colors.accentFill, RoundedCornerShape(50)),
                        shape = RoundedCornerShape(50),
                        color = Color.Transparent,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "Selected",
                            modifier = Modifier.padding(5.dp).size(17.dp),
                            tint = colors.onAccent,
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = leadingLabel,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = trailingLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun IgdbResultRow(game: IgdbGame, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val year = game.releaseDateEpochSeconds?.let {
        runCatching { Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).year }.getOrNull()
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(86.dp)
            .background(
                if (selected) {
                    colors.accentFill(MaterialTheme.nuvio.opacity.pressed)
                } else {
                    SolidColor(colors.surfaceCard)
                },
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick),
        color = Color.Transparent,
        border = BorderStroke(1.dp, if (selected) colors.accentFill else SolidColor(colors.borderDefault)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(49.dp, 70.dp), shape = RoundedCornerShape(7.dp), color = colors.surfaceCard) {
                AsyncImage(game.coverUrl, game.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = game.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildList {
                        year?.let { add(it.toString()) }
                        game.platforms.take(2).let(::addAll)
                    }.joinToString("  •  ").ifBlank { "No release information" },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildList {
                        addAll(game.genres.take(3))
                        if (game.backdrops.isNotEmpty()) add("${game.backdrops.size} backdrops")
                    }.joinToString("  •  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted.copy(alpha = 0.72f),
                    maxLines = 1,
                )
            }
        }
    }
}

private fun quoteArgument(argument: String): String =
    if (argument.any(Char::isWhitespace)) "\"${argument.replace("\"", "\\\"")}\"" else argument
