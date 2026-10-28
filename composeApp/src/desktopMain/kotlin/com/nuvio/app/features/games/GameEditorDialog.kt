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
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import com.nuvio.app.core.ui.accentFill
import androidx.compose.ui.graphics.SolidColor
import com.nuvio.app.core.ui.accentBrush
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.getString

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
    /** Shelf rows the game can be filed under; the defaults are always present. */
    rows: List<GameRow>,
    lastExecutableDirectory: String,
    igdbConfigured: Boolean,
    steamGridDbConfigured: Boolean,
    metadataSource: GameMetadataSource,
    onSearch: suspend (String) -> List<GameMetadata>,
    onLoadMetadata: suspend (Long) -> GameMetadata?,
    onLoadLogos: suspend (String) -> List<LogoCandidate>,
    onLoadHeroes: suspend (String) -> List<ArtworkCandidate>,
    onLoadSteamHeroes: suspend (SteamGameReference) -> List<ArtworkCandidate>,
    onLoadSteamLogos: suspend (SteamGameReference) -> List<LogoCandidate>,
    onExecutableChosen: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onSave: (GameEntry) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.nuvio.colors
    val providerName = metadataSource.providerName
    var executablePath by remember(initial) { mutableStateOf(initial?.executablePath.orEmpty()) }
    var gameTitle by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
    var arguments by remember(initial) {
        mutableStateOf(initial?.arguments?.joinToString(" ") { quoteArgument(it) }.orEmpty())
    }
    var workingDirectory by remember(initial) { mutableStateOf(initial?.workingDirectory.orEmpty()) }
    // null = automatic (Installed / Uninstalled by whether it has an executable). A stored id whose
    // row has since been removed reads back as automatic, which is also what it means on the shelf.
    var selectedRowId by remember(initial, rows) {
        mutableStateOf(initial?.rowId?.takeIf { id -> rows.any { it.id == id } })
    }
    var showRowMenu by remember { mutableStateOf(false) }
    var searchText by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
    var results by remember { mutableStateOf<List<GameMetadata>>(emptyList()) }
    var selectedMetadata by remember { mutableStateOf<GameMetadata?>(null) }
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
    var steamHeroes by remember(initial) { mutableStateOf<List<ArtworkCandidate>>(emptyList()) }
    var loadingSteamHeroes by remember { mutableStateOf(false) }

    // What the Steam lookup resolves an app id from: the executable's place in a Steam library, the
    // launch arguments, or failing both the title. Read from the live fields, not from `initial`,
    // so browsing to a game's exe and picking artwork in the same visit works.
    fun steamReference() = steamGameReference(
        title = gameTitle.trim(),
        executablePath = executablePath.trim().takeIf(String::isNotBlank),
        arguments = parseArguments(arguments),
    )

    fun runSearch() {
        if (searchText.isBlank() || searching) return
        scope.launch {
            searching = true
            error = null
            results = runCatching { onSearch(searchText) }
                .onFailure { error = it.message ?: "$providerName search failed" }
                .getOrDefault(emptyList())
            searching = false
        }
    }

    /**
     * Both logo sources, SteamGridDB first so an existing library never reorders under the user.
     *
     * Steam is queried whether or not a SteamGridDB key exists — it needs none — which is what
     * makes the LOGO block worth pressing on a fresh install.
     */
    fun loadLogos() {
        if (gameTitle.isBlank() || loadingLogos) return
        scope.launch {
            loadingLogos = true
            error = null
            val steamGridLogos = if (steamGridDbConfigured) {
                runCatching { onLoadLogos(gameTitle) }
                    .onFailure { error = it.message ?: getString(Res.string.games_editor_logo_search_failed) }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val steamLogos = runCatching { onLoadSteamLogos(steamReference()) }.getOrDefault(emptyList())
            logoCandidates = (steamGridLogos + steamLogos).distinctBy(LogoCandidate::url)
            loadingLogos = false
            if (logoCandidates.isEmpty()) {
                if (error == null) error = getString(Res.string.games_editor_no_logos, gameTitle)
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
            .onFailure { error = it.message ?: getString(Res.string.games_editor_backdrop_search_failed) }
            .getOrDefault(emptyList())
        loadingHeroes = false
    }

    // Separate from the SteamGridDB effect on purpose: the two sources are independent, so a
    // missing key or a failing search on one must not hold up or empty the other. Keyed on the
    // executable as well, because that is what turns a title guess into an exact app id.
    LaunchedEffect(showBackdropPicker, gameTitle, executablePath) {
        if (!showBackdropPicker || gameTitle.isBlank()) return@LaunchedEffect
        loadingSteamHeroes = true
        steamHeroes = runCatching { onLoadSteamHeroes(steamReference()) }.getOrDefault(emptyList())
        loadingSteamHeroes = false
    }

    // Reloads the stored match so the dialog opens showing what the game is matched to. Keyed on
    // the source because each provider stores its id in its own field, and the id of the one the
    // user has switched away from means nothing to the one now answering.
    LaunchedEffect(initial?.id, metadataSource, igdbConfigured) {
        val matchId = when (metadataSource) {
            GameMetadataSource.Igdb -> initial?.igdbId.takeIf { igdbConfigured }
            GameMetadataSource.Steam -> initial?.steamAppId
        } ?: return@LaunchedEffect
        if (selectedMetadata != null) return@LaunchedEffect
        runCatching { onLoadMetadata(matchId) }.getOrNull()?.let { metadata ->
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
                        text = stringResource(Res.string.games_editor_delete_this_game),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.danger,
                    )
                    TextButton(onClick = onDelete) { Text(stringResource(Res.string.games_editor_confirm), color = colors.danger) }
                    TextButton(onClick = { confirmDelete = false }) { Text(stringResource(Res.string.games_editor_keep)) }
                } else {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text(stringResource(Res.string.action_delete), color = colors.danger)
                    }
                }
            }
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
            Button(
                enabled = gameTitle.isNotBlank(),
                onClick = {
                    val metadata = selectedMetadata
                    executablePath.trim().takeIf(String::isNotBlank)?.let(onExecutableChosen)
                    onSave(
                        GameEntry(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            igdbId = metadata?.takeIf { it.source == GameMetadataSource.Igdb }?.id
                                ?: initial?.igdbId,
                            steamAppId = metadata?.takeIf { it.source == GameMetadataSource.Steam }?.id
                                ?: initial?.steamAppId,
                            metadataSource = metadata?.source ?: initial?.metadataSource,
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
                            rowId = selectedRowId,
                        ),
                    )
                },
            ) {
                Text(if (initial == null) stringResource(Res.string.games_mode_add_game) else stringResource(Res.string.games_editor_save_changes))
            }
        },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            FieldColumn(stringResource(Res.string.games_editor_executable_shortcut_or_launch_url_optional), Modifier.weight(1f)) {
                NuvioTextField(
                    value = executablePath,
                    onValueChange = {
                        executablePath = it
                        if (workingDirectory.isBlank()) {
                            workingDirectory = defaultWorkingDirectory(it).orEmpty()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = stringResource(Res.string.games_editor_c_games_example_game_exe_a),
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
                            workingDirectory = defaultWorkingDirectory(selected).orEmpty()
                        }
                    }
                },
            ) {
                Icon(Icons.Rounded.FolderOpen, null)
                Spacer(Modifier.width(7.dp))
                Text(stringResource(Res.string.games_editor_browse))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FieldColumn(stringResource(Res.string.games_editor_display_title), Modifier.weight(1f)) {
                NuvioTextField(
                    value = gameTitle,
                    onValueChange = { gameTitle = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = stringResource(Res.string.games_editor_shown_in_the_library),
                )
            }
            FieldColumn(stringResource(Res.string.games_editor_launch_arguments_optional), Modifier.weight(1f)) {
                NuvioTextField(
                    value = arguments,
                    onValueChange = { arguments = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "--windowed",
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FieldColumn(stringResource(Res.string.games_editor_working_directory_optional), Modifier.weight(1f)) {
                NuvioTextField(
                    value = workingDirectory,
                    onValueChange = { workingDirectory = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = stringResource(Res.string.games_editor_defaults_to_the_folder_holding_the),
                )
            }
            FieldColumn(stringResource(Res.string.games_editor_row), Modifier.weight(1f)) {
                GameRowPicker(
                    rows = rows,
                    selectedRowId = selectedRowId,
                    expanded = showRowMenu,
                    onExpandedChange = { showRowMenu = it },
                    onSelected = { selectedRowId = it },
                )
            }
        }

        SectionLabel("$providerName METADATA".uppercase())
        // Steam needs no credentials, so the only provider that can be unconfigured is IGDB.
        if (metadataSource == GameMetadataSource.Igdb && !igdbConfigured) {
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
                        text = stringResource(Res.string.games_editor_add_your_twitch_application_credentials_before),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onOpenSettings) { Text(stringResource(Res.string.games_editor_open_settings)) }
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
                    placeholder = stringResource(Res.string.games_editor_search_provider, providerName),
                    onImeAction = ::runSearch,
                )
                Button(onClick = ::runSearch, enabled = searchText.isNotBlank() && !searching) {
                    if (searching) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Search, null)
                    }
                    Spacer(Modifier.width(7.dp))
                    Text(stringResource(Res.string.games_editor_search))
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
                    text = selectedMetadata?.let { stringResource(Res.string.games_editor_metadata_selected, it.title) }
                        ?: stringResource(Res.string.games_editor_search_results_hint),
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                )
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(results, key = GameMetadata::id) { item ->
                        MetadataResultRow(
                            game = item,
                            selected = selectedMetadata?.id == item.id,
                            onClick = {
                                selectedMetadata = item
                                gameTitle = item.title
                                selectedBackdropUrl = item.backdrops
                                    .firstOrNull { it.url == selectedBackdropUrl }
                                    ?.url
                                    ?: item.backdropUrl
                                selectedLogoUrl = if (initial?.matchesMetadata(item) == true) initial.logoUrl else item.logoUrl
                                logoCandidates = emptyList()
                                logoManuallySelected = false
                            },
                        )
                    }
                }
            }
        }

        val backdrops = selectedMetadata?.backdrops.orEmpty()
        val selectedBackdrop = (backdrops + steamGridHeroes + steamHeroes)
            .firstOrNull { it.url == selectedBackdropUrl }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ArtworkChoice(
                modifier = Modifier.weight(1f),
                label = stringResource(Res.string.games_editor_backdrop),
                count = (backdrops.size + steamGridHeroes.size + steamHeroes.size).takeIf { it > 0 },
                detail = when {
                    selectedBackdrop != null -> "${selectedBackdrop.source.label}  •  ${selectedBackdrop.resolutionLabel}"
                    !selectedBackdropUrl.isNullOrBlank() -> stringResource(Res.string.games_editor_current_backdrop)
                    else -> stringResource(Res.string.games_editor_artwork_from_igdb_steam_and_steamgriddb)
                },
                // No longer gated on an IGDB match: Steam art is found from the executable or the
                // title alone, so a game with no match at all can still have a backdrop.
                enabled = gameTitle.isNotBlank(),
                onClick = { showBackdropPicker = true },
            )
            ArtworkChoice(
                modifier = Modifier.weight(1f),
                label = stringResource(Res.string.games_editor_logo),
                count = logoCandidates.size.takeIf { it > 0 },
                detail = when {
                    logoManuallySelected -> stringResource(Res.string.games_editor_chosen_artwork)
                    !selectedLogoUrl.isNullOrBlank() -> stringResource(Res.string.games_editor_current_logo_choose_another_if_needed)
                    !steamGridDbConfigured -> stringResource(Res.string.games_editor_steam_logos_add_a_steamgriddb_key)
                    else -> stringResource(Res.string.games_editor_automatic_english_first_selection_on_save)
                },
                enabled = gameTitle.isNotBlank() && !loadingLogos,
                busy = loadingLogos,
                onClick = ::loadLogos,
            )
        }
    }

    if (showBackdropPicker) {
        BackdropPickerDialog(
            gameTitle = selectedMetadata?.title ?: gameTitle,
            igdbCandidates = selectedMetadata?.backdrops.orEmpty(),
            steamGridCandidates = steamGridHeroes,
            loadingSteamGrid = loadingHeroes,
            steamCandidates = steamHeroes,
            loadingSteam = loadingSteamHeroes,
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

/**
 * Which shelf the game is filed under. "Automatic" is the default and what every game starts
 * with: Installed or Uninstalled by whether it has something to launch. Picking a row pins it
 * there; the rows themselves are managed in Settings > Games.
 */
@Composable
private fun GameRowPicker(
    rows: List<GameRow>,
    selectedRowId: String?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelected: (String?) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val automaticLabel = stringResource(Res.string.games_editor_row_automatic)
    val selectedLabel = rows.firstOrNull { it.id == selectedRowId }?.name ?: automaticLabel
    Box(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.colors.surface, tokens.shapes.compactCard)
                .clickable { onExpandedChange(true) }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val dotColor = rows.firstOrNull { it.id == selectedRowId }?.color
            if (dotColor != null) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(Color(dotColor), RoundedCornerShape(50)),
                )
            }
            Text(
                text = selectedLabel,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = tokens.colors.textMuted,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            DropdownMenuItem(
                text = { Text(automaticLabel) },
                trailingIcon = {
                    if (selectedRowId == null) {
                        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                },
                onClick = {
                    onSelected(null)
                    onExpandedChange(false)
                },
            )
            rows.forEach { row ->
                DropdownMenuItem(
                    text = { Text(row.name) },
                    leadingIcon = {
                        Box(
                            Modifier
                                .size(10.dp)
                                .background(
                                    row.color?.let { Color(it) } ?: tokens.colors.textMuted,
                                    RoundedCornerShape(50),
                                ),
                        )
                    },
                    trailingIcon = {
                        if (selectedRowId == row.id) {
                            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    },
                    onClick = {
                        onSelected(row.id)
                        onExpandedChange(false)
                    },
                )
            }
        }
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
 * Backdrop options from every source in one grid, each tile labelled with where it came from.
 *
 * No source wins automatically: IGDB artworks and screenshots keep the order they arrived in so an
 * existing library never shifts under the user, and SteamGridDB heroes then Steam's own library
 * art are appended. The subtitle breaks the count down by source so it is obvious which is which
 * before scrolling.
 */
@Composable
private fun BackdropPickerDialog(
    gameTitle: String,
    igdbCandidates: List<ArtworkCandidate>,
    steamGridCandidates: List<ArtworkCandidate>,
    loadingSteamGrid: Boolean,
    steamCandidates: List<ArtworkCandidate>,
    loadingSteam: Boolean,
    selectedUrl: String?,
    onSelect: (ArtworkCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    // SteamGridDB mirrors Steam's own library art, and occasionally an image IGDB also has; the
    // first occurrence wins so the grid never shows the same picture twice under two labels.
    val candidates = remember(igdbCandidates, steamGridCandidates, steamCandidates) {
        (igdbCandidates + steamGridCandidates + steamCandidates).distinctBy(ArtworkCandidate::url)
    }
    val loadingAnySource = loadingSteamGrid || loadingSteam
    val breakdown = buildList {
        add("${igdbCandidates.size} from IGDB")
        when {
            loadingSteamGrid -> add("searching SteamGridDB…")
            steamGridCandidates.isNotEmpty() -> add("${steamGridCandidates.size} from SteamGridDB")
        }
        when {
            loadingSteam -> add("searching Steam…")
            steamCandidates.isNotEmpty() -> add("${steamCandidates.size} from Steam")
        }
    }.joinToString("  •  ")

    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.games_editor_choose_backdrop),
        subtitle = "$gameTitle  •  $breakdown",
        maxWidth = 1040.dp,
        actions = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_close)) } },
    ) {
        if (candidates.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(520.dp), contentAlignment = Alignment.Center) {
                if (loadingAnySource) {
                    CircularProgressIndicator()
                } else {
                    Text(
                        text = stringResource(Res.string.games_editor_no_artwork, gameTitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMuted,
                    )
                }
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
            if (loadingAnySource) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = if (loadingSteamGrid) {
                                stringResource(Res.string.games_editor_looking_steamgriddb)
                            } else {
                                stringResource(Res.string.games_editor_looking_steam)
                            },
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
    val breakdown = candidates
        .groupingBy { it.source.label }
        .eachCount()
        .entries
        .joinToString("  •  ") { (source, count) -> "$count from $source" }

    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.games_editor_choose_logo),
        subtitle = "$gameTitle  •  $breakdown",
        maxWidth = 1040.dp,
        actions = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_close)) } },
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
                    // Steam's own logos carry neither a language nor a style, so they fall back to
                    // naming their source rather than to a bare "Logo" that says nothing.
                    leadingLabel = listOfNotNull(logo.languageLabel, logo.style?.takeIf(String::isNotBlank))
                        .joinToString("  •  ")
                        .ifBlank { logo.source.label },
                    trailingLabel = logo.resolutionLabel,
                ) {
                    // Logos are transparent, so they need a dark plate and padding to read at all.
                    Box(Modifier.fillMaxSize().background(Color(0xFF0A080D))) {
                        AsyncImage(
                            model = logo.url,
                            contentDescription = stringResource(Res.string.games_editor_logo_cd, logo.resolutionLabel),
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
                            contentDescription = stringResource(Res.string.games_editor_selected),
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
private fun MetadataResultRow(game: GameMetadata, selected: Boolean, onClick: () -> Unit) {
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
                    }.joinToString("  •  ").ifBlank { stringResource(Res.string.games_editor_no_release_info) },
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
