package com.nuvio.app.features.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioGlass
import com.nuvio.app.core.ui.NuvioModalDialog
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.library.MusicLibraryRepository
import com.nuvio.app.features.navbar.NavBarEntry
import com.nuvio.app.features.navbar.NavBarLayoutRepository
import com.nuvio.app.features.youtube.YouTubeFeedRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_done
import nuvio.composeapp.generated.resources.settings_playback_hud_button_move_down
import nuvio.composeapp.generated.resources.settings_playback_hud_button_move_up
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * The navigation's order (the sidebar, the top bar and the Settings screen's bar alike), top to
 * bottom as the sidebar shows it, left to right on the bars, each optional entry with its switch.
 * The switches are the same ones each feature's own page has (Discover here under Layout, YouTube
 * under YouTube account, Music under Music, Games under Games); this is another way in to them.
 * Rows move by dragging or with the arrows, as in the pre-show's running order; an entry that is off
 * keeps its place. Profile / Settings always closes the bar and is not listed.
 */
@Composable
internal fun NavigationOrderDialog(onDismiss: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val hapticFeedback = LocalHapticFeedback.current
    val order by remember { NavBarLayoutRepository.order }.collectAsStateWithLifecycle()
    val switches = rememberNavEntrySwitches()
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        // The saved order, not [order]: one drag can move several rows before the dialog redraws.
        val current = NavBarLayoutRepository.order.value
        val entry = current.getOrNull(from.index) ?: return@rememberReorderableLazyListState
        if (to.index !in current.indices) return@rememberReorderableLazyListState
        NavBarLayoutRepository.moveTo(entry, to.index + 1)
        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = "Navigation order",
        subtitle = "Top to bottom in the sidebar, left to right in the top bar; your profile and Settings always come last. " +
            "Drag a row, or use its arrows, to move it; the switch shows or hides it.",
        maxWidth = 640.dp,
        actions = {
            SettingsGlassButton(text = "Reset order", onClick = NavBarLayoutRepository::resetOrder)
            SettingsGlassButton(text = stringResource(Res.string.action_done), onClick = onDismiss, accent = true)
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
            state = listState,
        ) {
            itemsIndexed(order, key = { _, entry -> entry.key }) { index, entry ->
                ReorderableItem(reorderState, key = entry.key) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp)
                    val switch = switches[entry]
                    val shown = switch?.on ?: true
                    Surface(
                        modifier = with(this@ReorderableItem) {
                            Modifier.draggableHandle(
                                onDragStarted = { hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress) },
                            )
                        },
                        shadowElevation = elevation,
                        color = if (isDragging) NuvioGlass.night(colors.background, colors.accent) else Color.Transparent,
                    ) {
                        Column {
                            if (index > 0) SettingsGroupDivider(isTablet = false)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = "${index + 1}.  ${entry.label}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (shown) colors.textPrimary else colors.textMuted,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = entry.navDescription(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textMuted,
                                    )
                                }
                                IconButton(onClick = { NavBarLayoutRepository.moveTo(entry, index) }, enabled = index > 0) {
                                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(Res.string.settings_playback_hud_button_move_up))
                                }
                                IconButton(onClick = { NavBarLayoutRepository.moveTo(entry, index + 2) }, enabled = index < order.lastIndex) {
                                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(Res.string.settings_playback_hud_button_move_down))
                                }
                                if (switch != null) {
                                    SettingsSquareSwitch(
                                        checked = switch.on,
                                        onCheckedChange = switch.set,
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** An entry's show / hide switch: its state and the setter its own settings page uses. */
internal class NavEntrySwitch(val on: Boolean, val set: (Boolean) -> Unit)

/** The switches of the entries that have one. Home, Search and Library always show. */
@Composable
private fun rememberNavEntrySwitches(): Map<NavBarEntry, NavEntrySwitch> {
    val discover by remember { ThemeSettingsRepository.desktopDiscoverTabVisible }.collectAsStateWithLifecycle()
    val youTube by remember { YouTubeFeedRepository.tabEnabled }.collectAsStateWithLifecycle()
    val music by remember {
        MusicLibraryRepository.ensureLoaded()
        MusicLibraryRepository.state
    }.collectAsStateWithLifecycle()
    val games by remember { NavBarLayoutRepository.gamesInNav }.collectAsStateWithLifecycle()
    val iptv by remember {
        com.nuvio.app.features.iptv.IptvRepository.ensureLoaded()
        com.nuvio.app.features.iptv.IptvRepository.settings
    }.collectAsStateWithLifecycle()
    return mapOf(
        NavBarEntry.LiveTv to NavEntrySwitch(iptv.tabShown, com.nuvio.app.features.iptv.IptvRepository::setTabEnabled),
        NavBarEntry.Discover to NavEntrySwitch(discover, ThemeSettingsRepository::setDesktopDiscoverTabVisible),
        NavBarEntry.YouTube to NavEntrySwitch(youTube == true, YouTubeFeedRepository::setTabEnabled),
        NavBarEntry.Music to NavEntrySwitch(music.settings.tabEnabled) { on ->
            MusicLibraryRepository.updateSettings(MusicLibraryRepository.state.value.settings.copy(tabEnabled = on))
        },
        NavBarEntry.Games to NavEntrySwitch(games, NavBarLayoutRepository::setGamesInNav),
    )
}

private fun NavBarEntry.navDescription(): String = when (this) {
    NavBarEntry.Home -> "Your home rows. Always shown."
    NavBarEntry.Search -> "Search across your addons. Always shown."
    NavBarEntry.Discover -> "Browse catalogs by genre, year and more."
    NavBarEntry.Library -> "Your saved titles and collections. Always shown."
    NavBarEntry.LiveTv -> "Shown while a Live TV playlist is added (Settings > Live TV)."
    NavBarEntry.YouTube -> "Your YouTube home, subscriptions and playlists. Needs the account under YouTube account."
    NavBarEntry.Music -> "Your music library and YouTube Music."
    NavBarEntry.Games -> "Opens game mode, your game library, over the app."
}

/** The row under the navigation style selector that opens [NavigationOrderDialog]: the shown entries in order. */
@Composable
internal fun NavigationOrderRow(isTablet: Boolean, onClick: () -> Unit) {
    val shown = com.nuvio.app.features.navbar.rememberNavBarEntries()
    SettingsNavigationRow(
        title = "Navigation order",
        description = shown.joinToString("  ·  ") { it.label },
        isTablet = isTablet,
        trailingContent = {
            Text(
                text = "${shown.size} of ${NavBarEntry.entries.size} shown",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.nuvio.colors.textMuted,
            )
        },
        onClick = onClick,
    )
}
