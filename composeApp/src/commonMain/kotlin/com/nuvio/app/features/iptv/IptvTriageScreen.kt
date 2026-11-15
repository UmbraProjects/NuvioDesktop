package com.nuvio.app.features.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioGlass
import com.nuvio.app.core.ui.navigationKey
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.smoothBorder
import com.nuvio.app.features.settings.settingsSkyBackground
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.delay

/*
 * fork:live-tv-triage - the questions (see IptvTriage.kt for what they do): countries, kinds of channel, then
 * "your first ten". Drawn in the fork's symmetric three-panel layout: what has been answered on the left, the
 * question in the middle, what is left on the right, the side panels the same width. One highlight, moved by the
 * arrows, as on the rest of the Live TV tab.
 */

/** How many channels "your first ten" may star. */
private const val FIRST_TEN = 10

private val TriageSidePanel = 296.dp
private val TriageBackKeys = setOf(Key.Escape, Key.Back, Key.Backspace)
private val TriageKeys = setOf(
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.Enter, Key.NumPadEnter, Key.PageUp, Key.PageDown,
)

private fun count(number: Int): String = NumberFormat.getIntegerInstance(Locale.UK).format(number)

/** "United Kingdom" for GB; the code itself when Java has no name for it. */
internal fun iptvRegionName(region: String): String {
    if (region == IptvTriageRules.NO_REGION) return "No country marked"
    val name = runCatching { Locale.Builder().setRegion(region).build().getDisplayCountry(Locale.ENGLISH) }.getOrNull()
    return name?.takeIf { it.isNotBlank() } ?: region
}

/** "A, B, C and 4 more". */
private fun listed(names: List<String>, shown: Int = 3): String = when {
    names.isEmpty() -> "None yet"
    names.size <= shown -> names.joinToString(", ")
    else -> names.take(shown).joinToString(", ") + " and ${names.size - shown} more"
}

/** One choice in the middle panel. [region] draws a flag. */
private data class TriageTile(val id: String, val label: String, val note: String?, val region: String?, val on: Boolean)

/** Not a data class: two buttons with the same label are not the same button when their actions close over different answers. */
private class TriageButton(val label: String, val enabled: Boolean = true, val accent: Boolean = false, val action: () -> Unit)

/**
 * The triage for [playlist]. Saves on Done and calls [onClose]; Cancel and Back on the first question close without
 * saving. Opens on the saved answers when there are some.
 */
@Composable
internal fun IptvTriageScreen(
    playlist: IptvPlaylist,
    content: IptvPlaylistContent,
    categoryRegions: Map<String, String>,
    settings: IptvSettings,
    modifier: Modifier = Modifier,
    topChromePadding: Dp? = null,
    onClose: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    // fork:live-tv-reminder-banner - no reminder banner over the questions; one that comes up waits until they close.
    androidx.compose.runtime.DisposableEffect(Unit) {
        val release = IptvReminderEngine.hold()
        onDispose { release() }
    }
    val saved = settings.triage[playlist.id]
    var step by remember { mutableIntStateOf(0) }
    var regions by remember { mutableStateOf(saved?.regions ?: emptySet()) }
    var kinds by remember { mutableStateOf(saved?.kinds ?: IptvChannelKind.DEFAULT_ON.mapTo(HashSet()) { it.name }) }
    // fork:live-tv-triage - languages: the chosen countries' own until the question is answered.
    var languages by remember { mutableStateOf(saved?.languages.orEmpty()) }
    var languagesTouched by remember { mutableStateOf(saved?.languages?.isNotEmpty() == true) }
    // Starred in the third question, by the channel's first copy; null until something is changed there.
    var picked by remember { mutableStateOf<Set<Int>?>(null) }

    val regionOptions = remember(content, categoryRegions) { IptvTriageRules.regionOptions(content, categoryRegions) }
    val languageOptions = remember(content, categoryRegions, regions) { IptvTriageRules.languageOptions(content, categoryRegions, regions) }
    val liveLanguages = remember(languages, languagesTouched, regions, languageOptions) {
        (if (languagesTouched) languages else IptvTriageRules.defaultLanguages(regions)).intersect(languageOptions.mapTo(HashSet()) { it.id })
    }
    val kindOptions = remember(content, categoryRegions, regions) { IptvTriageRules.kindOptions(content, categoryRegions, regions) }
    // A kind ticked earlier that the chosen countries do not have counts for nothing.
    val liveKinds = remember(kinds, kindOptions) { kinds.intersect(kindOptions.mapTo(HashSet()) { it.id }) }
    val kept = remember(content, categoryRegions, regions, liveKinds, liveLanguages) {
        IptvTriageRules.keptCategories(content, IptvTriage(regions, liveKinds, liveLanguages), categoryRegions)
    }
    val total = content.channels.size
    val left = when (step) {
        0 -> regionOptions.filter { it.id in regions }.sumOf { it.channels }
        1 -> languageOptions.filter { it.id in liveLanguages }.sumOf { it.channels }
        else -> IptvTriageRules.channelCount(content, kept)
    }

    // Only worked out for the last question: folding thousands of channels on every tick of the others would lag.
    val candidates = remember(step == 3, kept, settings.plays, settings.favourites) {
        if (step != 3) {
            emptyList()
        } else {
            IptvTriageRules.firstTenCandidates(
                channels = IptvChannelMerge.merge(content.channels.filter { kept.keeps(it) }) { categoryRegions[it.categoryId] },
                refOf = { IptvRepository.channelRef(playlist.id, it.key) },
                plays = settings.plays.map { it.ref },
                favourites = settings.favourites,
            )
        }
    }
    val starredBefore = remember(candidates, settings.favourites) {
        candidates.filter { channel -> channel.sources.any { IptvRepository.channelRef(playlist.id, it.key) in settings.favourites } }
            .mapTo(HashSet()) { it.primary.uid }
    }
    val chosen = picked ?: starredBefore

    val tiles = when (step) {
        0 -> regionOptions.map { option ->
            TriageTile(option.id, iptvRegionName(option.id), count(option.channels), option.id.takeIf { it != IptvTriageRules.NO_REGION }, option.id in regions)
        }
        1 -> languageOptions.map { option ->
            TriageTile(option.id, IptvLanguages.label(option.id), count(option.channels), null, option.id in liveLanguages)
        }
        2 -> kindOptions.map { option ->
            TriageTile(option.id, IptvChannelKind.byName(option.id)?.label ?: option.id, count(option.channels), null, option.id in liveKinds)
        }
        else -> candidates.map { channel ->
            val name = channel.primary.name.ifBlank { "Channel ${channel.primary.num}" }
            TriageTile(channel.primary.uid.toString(), name, null, null, channel.primary.uid in chosen)
        }
    }
    // As many columns as fit, so a narrow window gets fewer, wider tiles instead of cut-off names.
    var gridWidth by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val mostColumns = if (step == 3) 5 else 3
    val narrowestTile = if (step == 3) 150.dp else 180.dp
    val columns = if (gridWidth == 0.dp) mostColumns else (gridWidth / narrowestTile).toInt().coerceIn(1, mostColumns)

    fun toggle(tile: TriageTile) {
        when (step) {
            0 -> regions = if (tile.id in regions) regions - tile.id else regions + tile.id
            1 -> {
                languages = if (tile.id in liveLanguages) liveLanguages - tile.id else liveLanguages + tile.id
                languagesTouched = true
            }
            2 -> kinds = if (tile.id in liveKinds) liveKinds - tile.id else liveKinds + tile.id
            else -> {
                val uid = tile.id.toIntOrNull() ?: return
                // Already full: a channel can be taken off, not added.
                picked = if (uid in chosen) chosen - uid else if (chosen.size < FIRST_TEN) chosen + uid else chosen
            }
        }
    }

    // By id, through the latest composition, for the same reason as the buttons below.
    val latestToggle by rememberUpdatedState<(String) -> Unit> { id -> tiles.firstOrNull { it.id == id }?.let { toggle(it) } }

    fun finish() {
        IptvRepository.setTriage(playlist.id, IptvTriage(regions, liveKinds, liveLanguages))
        candidates.forEach { channel ->
            val uid = channel.primary.uid
            if ((uid in starredBefore) != (uid in chosen)) IptvRepository.toggleFavourite(playlist.id, channel)
        }
        onClose()
    }

    fun back() {
        if (step > 0) step-- else onClose()
    }

    val buttons = when (step) {
        0 -> buildList {
            add(TriageButton("Cancel", action = onClose))
            if (saved != null) {
                add(
                    TriageButton("Show everything") {
                        IptvRepository.setTriage(playlist.id, null)
                        onClose()
                    },
                )
            }
            add(TriageButton("Next: languages", enabled = regions.isNotEmpty(), accent = true) { step = 1 })
        }
        1 -> listOf(
            TriageButton("Back") { step = 0 },
            TriageButton("Next: kinds", enabled = liveLanguages.isNotEmpty(), accent = true) { step = 2 },
        )
        2 -> listOf(
            TriageButton("Back") { step = 1 },
            TriageButton("Next: your first ten", enabled = kept.isNotEmpty(), accent = true) { step = 3 },
        )
        else -> listOf(TriageButton("Back") { step = 2 }, TriageButton("Done", accent = true) { finish() })
    }
    // Clicks and Enter go through the buttons of the latest composition. A button view that Compose did not redraw
    // would otherwise keep an action from before the last tick (Done once saved the first ten as they stood when the
    // question opened).
    val latestButtons by rememberUpdatedState(buttons)

    // The highlight: a tile of the question, or one of the buttons under it. Drawn once a key has been pressed.
    var keyboardActive by remember { mutableStateOf(false) }
    var onButtons by remember(step) { mutableStateOf(false) }
    var tileIndex by remember(step) { mutableIntStateOf(0) }
    var buttonIndex by remember(step) { mutableIntStateOf(buttons.lastIndex) }
    tileIndex = tileIndex.coerceIn(0, (tiles.size - 1).coerceAtLeast(0))
    buttonIndex = buttonIndex.coerceIn(0, buttons.lastIndex)
    if (tiles.isEmpty()) onButtons = true

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // The node may not be attached on the first frame.
        repeat(10) {
            if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(48)
        }
    }
    val gridState = rememberLazyGridState()
    LaunchedEffect(keyboardActive, onButtons, tileIndex, step) {
        if (keyboardActive && !onButtons && gridState.layoutInfo.visibleItemsInfo.none { it.index == tileIndex }) {
            gridState.scrollToItem(tileIndex)
        }
    }

    fun handleKey(navKey: Key): Boolean {
        when (navKey) {
            Key.DirectionLeft -> if (onButtons) buttonIndex = (buttonIndex - 1).coerceAtLeast(0) else tileIndex = (tileIndex - 1).coerceAtLeast(0)
            Key.DirectionRight -> if (onButtons) {
                buttonIndex = (buttonIndex + 1).coerceAtMost(buttons.lastIndex)
            } else {
                tileIndex = (tileIndex + 1).coerceAtMost(tiles.lastIndex.coerceAtLeast(0))
            }
            Key.DirectionUp -> if (onButtons) {
                if (tiles.isNotEmpty()) onButtons = false
            } else if (tileIndex - columns >= 0) {
                tileIndex -= columns
            }
            Key.DirectionDown -> if (!onButtons) {
                if (tileIndex + columns <= tiles.lastIndex) tileIndex += columns else onButtons = true
            }
            Key.PageDown -> if (!onButtons) tileIndex = (tileIndex + columns * 4).coerceAtMost(tiles.lastIndex.coerceAtLeast(0))
            Key.PageUp -> if (!onButtons) tileIndex = (tileIndex - columns * 4).coerceAtLeast(0)
            Key.Enter, Key.NumPadEnter -> if (onButtons) {
                latestButtons.getOrNull(buttonIndex)?.takeIf { it.enabled }?.action?.invoke()
            } else {
                tiles.getOrNull(tileIndex)?.let(::toggle)
            }
            else -> return false
        }
        return true
    }

    val question = when (step) {
        0 -> "Which countries do you watch?"
        1 -> "Which languages do you watch in?"
        2 -> "Which of these would you ever watch?"
        else -> "Your first ten"
    }
    val under = when (step) {
        0 -> "Question 1 of 4. Anything left out is still there in search."
        1 -> "Question 2 of 4. Channel by channel: Univision goes even from a US category."
        2 -> "Question 3 of 4. Anything left out is still there in search."
        else -> "Question 4 of 4. Star up to ten to start your favourites, or skip it with Done."
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .settingsSkyBackground(tokens.colors.background, tokens.colors.accent)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.key in TriageBackKeys) {
                    // Back acts as the key comes up, as it does across the app; its key-down is taken so nothing else acts on it.
                    if (event.type == KeyEventType.KeyUp) back()
                    return@onPreviewKeyEvent true
                }
                val navKey = event.navigationKey()
                // A clicked tile keeps Compose focus and would act again on Enter's release.
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent navKey in TriageKeys
                if (!keyboardActive && navKey in TriageKeys) {
                    // The first press only shows where the highlight is.
                    keyboardActive = true
                    return@onPreviewKeyEvent true
                }
                handleKey(navKey)
            }
            .padding(start = 32.dp, end = 32.dp, top = topChromePadding ?: 24.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = question,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = tokens.colors.textPrimary,
            maxLines = 1,
        )
        Text(text = under, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 1)
        Spacer(Modifier.height(12.dp))
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
          // The side panels are always a pair: the same width, narrower on a narrow window.
          val side = (maxWidth * 0.24f).coerceIn(200.dp, TriageSidePanel)
          Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            TriagePanel("So far", null, Modifier.width(side).fillMaxHeight()) {
                val regionNames = regionOptions.filter { it.id in regions }.map { iptvRegionName(it.id) }
                val kindNames = kindOptions.filter { it.id in liveKinds }.map { IptvChannelKind.byName(it.id)?.label ?: it.id }
                val languageNames = languageOptions.filter { it.id in liveLanguages }.map { IptvLanguages.label(it.id) }
                TriageStepLine("Countries", listed(regionNames), current = step == 0, done = step > 0)
                TriageStepLine("Languages", if (step == 0) "Next" else listed(languageNames), current = step == 1, done = step > 1)
                TriageStepLine("Kinds", if (step < 2) "Then" else listed(kindNames), current = step == 2, done = step > 2)
                TriageStepLine("Your first ten", if (step < 3) "Last" else "${chosen.size} starred", current = step == 3, done = false)
            }
            TriagePanel(
                title = when (step) {
                    0 -> "Countries"
                    1 -> "Languages"
                    2 -> "Kinds of channel"
                    else -> "Channels"
                },
                note = when (step) {
                    0 -> "pick any, with the channels in each"
                    1 -> "pick any, counted channel by channel in your countries"
                    2 -> "pick any, counted in your countries"
                    else -> "${chosen.size} of $FIRST_TEN starred · the most watched first"
                },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    modifier = Modifier.weight(1f).fillMaxWidth().onSizeChanged { gridWidth = with(density) { it.width.toDp() } },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(tiles, key = { _, tile -> tile.id }) { index, tile ->
                        TriageTileView(
                            tile = tile,
                            focused = keyboardActive && !onButtons && index == tileIndex,
                            onClick = {
                                keyboardActive = false
                                tileIndex = index
                                onButtons = false
                                latestToggle(tile.id)
                            },
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    buttons.forEachIndexed { index, button ->
                        TriageButtonView(
                            label = button.label,
                            enabled = button.enabled,
                            accent = button.accent && button.enabled,
                            focused = keyboardActive && onButtons && index == buttonIndex,
                            modifier = Modifier.weight(1f),
                            onClick = { latestButtons.getOrNull(index)?.takeIf { it.enabled }?.action?.invoke() },
                        )
                    }
                }
            }
            TriagePanel("What is left", null, Modifier.width(side).fillMaxHeight()) {
                Text(
                    text = count(left),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = tokens.colors.textPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                Text(
                    text = "of ${count(total)} channels",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                    textAlign = TextAlign.Center,
                )
                ProgressLine(if (total == 0) 0f else left.toFloat() / total, height = 4.dp)
                Text(
                    text = when (step) {
                        0 -> "Tick the countries whose channels you watch. \"No country marked\" is everything the playlist files without one."
                        1 -> "Read from each channel's name, then its category's, then its country's language. \"Other or unknown\" is where nothing gives it away."
                        2 -> "Copies of one channel (FHD, HD, SD) are still counted separately here."
                        else -> "These are what Live TV shows from now on: its sidebar, All channels and On now."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Nothing is deleted. The search box still finds every channel, favourites and recents are never " +
                        "hidden, and the Triage button in Live TV asks again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
          }
        }
    }
}

/** A glass section card with a heading, like a settings section. */
@Composable
private fun TriagePanel(title: String, note: String?, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = modifier.iptvGlass(NuvioGlass.Shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = tokens.colors.textPrimary, maxLines = 1)
            if (note != null) {
                Text(text = note, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        content()
    }
}

@Composable
private fun TriageStepLine(title: String, summary: String, current: Boolean, done: Boolean) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (current) tokens.colors.accent.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (current || done) tokens.colors.textPrimary else tokens.colors.textMuted,
                maxLines = 1,
            )
            Text(text = summary, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (done) Icon(Icons.Rounded.Check, contentDescription = "Answered", tint = tokens.colors.textPrimary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun TriageTileView(tile: TriageTile, focused: Boolean, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = NuvioGlass.Shape
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .iptvGlass(shape, raised = focused || hovered, selected = tile.on && !hovered, selectedTint = tokens.colors.accent.copy(alpha = 0.42f))
            .then(if (tile.on) Modifier.border(1.dp, tokens.colors.accent.copy(alpha = 0.85f), shape) else Modifier)
            // Clipped after the edge: a clip is not anti-aliased here and stair-steps an edge drawn inside it.
            .clip(shape)
            .then(if (focused) Modifier.smoothBorder(1.5.dp, tokens.colors.borderFocus, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (tile.region != null) {
            IptvFlag(tile.region, Modifier.size(width = 24.dp, height = 16.dp))
        } else if (tile.on) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = tokens.colors.textPrimary, modifier = Modifier.size(16.dp))
        }
        Text(
            text = tile.label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (tile.on) FontWeight.SemiBold else FontWeight.Normal,
            color = if (tile.on) tokens.colors.textPrimary else tokens.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (tile.note != null) {
            Text(text = tile.note, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 1)
        }
    }
}

@Composable
private fun TriageButtonView(label: String, enabled: Boolean, accent: Boolean, focused: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = NuvioGlass.Shape
    Box(
        modifier = modifier
            .height(44.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .iptvGlass(shape, raised = focused || (hovered && enabled), selected = accent && !hovered, selectedTint = tokens.colors.accent.copy(alpha = 0.42f))
            .then(if (accent) Modifier.border(1.dp, tokens.colors.accent.copy(alpha = 0.85f), shape) else Modifier)
            // Clipped after the edge: a clip is not anti-aliased here and stair-steps an edge drawn inside it.
            .clip(shape)
            .then(if (focused) Modifier.smoothBorder(1.5.dp, tokens.colors.borderFocus, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = tokens.colors.textPrimary,
            maxLines = 1,
        )
    }
}
