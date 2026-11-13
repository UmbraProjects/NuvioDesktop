@file:OptIn(ExperimentalMaterial3Api::class)

package com.spydermahn.nuvioremote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.BookmarkRemove
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Watch tab's long-press sheets: the actions for a poster, a Continue Watching card, an episode or a season (the
 * TV's own long-press menus, worded by the PC), the playlist picker one of them opens, and an AI recap.
 *
 * Glass on the same dark sheet as the show and resume sheets. The sheet opens at once with what the phone already knows
 * (title, picture); its rows arrive from the PC a moment later, in fixed-height rows so nothing jumps when they do.
 */

private val SheetColor = Color(0xFF14151A)
private val RowHeight = 52.dp

/** An icon for each code the phone knows; anything newer gets a plain one. */
private fun iconFor(code: String, label: String): ImageVector = when (code) {
    MenuCode.WATCHED, MenuCode.PREVIOUS_WATCHED, MenuCode.SEASON_WATCHED, MenuCode.EARLIER_SEASONS_WATCHED ->
        when {
            label.contains("unwatched", ignoreCase = true) -> Icons.Rounded.RadioButtonUnchecked
            code == MenuCode.WATCHED -> Icons.Rounded.CheckCircle
            else -> Icons.Rounded.DoneAll
        }
    MenuCode.LIBRARY -> if (label.startsWith("Remove", ignoreCase = true)) Icons.Rounded.BookmarkRemove else Icons.Rounded.BookmarkAdd
    MenuCode.ALL_LIBRARIES -> Icons.Rounded.LibraryAdd
    MenuCode.REWATCH -> Icons.Rounded.Replay
    MenuCode.RESUME -> Icons.Rounded.PlayArrow
    MenuCode.FROM_START -> Icons.Rounded.RestartAlt
    MenuCode.CHOOSE_SOURCE -> Icons.Rounded.Tune
    MenuCode.RECAP -> Icons.Rounded.AutoStories
    MenuCode.PLAYLIST, MenuCode.PLAYLIST_SEASON -> Icons.AutoMirrored.Rounded.PlaylistAdd
    MenuCode.NOT_INTERESTED -> Icons.Rounded.ThumbDown
    MenuCode.REMOVE -> Icons.Rounded.Close
    MenuCode.DETAILS -> Icons.Rounded.Info
    else -> Icons.Rounded.MoreHoriz
}

/** Rows that take something away get a warmer colour, so they are not tapped by mistake. */
private fun isLosing(code: String, label: String) =
    code == MenuCode.REMOVE || code == MenuCode.NOT_INTERESTED || (code == MenuCode.LIBRARY && label.startsWith("Remove", ignoreCase = true))

/** The top of every sheet: picture, a small label, the title. */
@Composable
private fun SheetHeader(label: String?, title: String, image: String?, imageFallback: String?, wide: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val pictureSize = if (wide) Modifier.size(width = 112.dp, height = 63.dp) else Modifier.size(width = 56.dp, height = 84.dp)
        RemoteImage(image, imageFallback, title, pictureSize.clip(RoundedCornerShape(10.dp)))
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            label?.takeIf { it.isNotBlank() }?.let { GlassLabel(it) }
            DisplayTitle(title, size = 20, maxLines = 2)
        }
    }
}

/** One glass row: icon, label, and a spinner while its answer is awaited. */
@Composable
private fun SheetRow(icon: ImageVector, label: String, tint: Color, busy: Boolean = false, trailing: String? = null, onClick: () -> Unit) {
    val g = LocalGlass.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(RowHeight)
            .glass(RoundedCornerShape(14.dp))
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(14.dp))
        Text(label, color = g.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        when {
            busy -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            trailing != null -> Text(trailing, color = g.textMuted, fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** The long-press sheet itself. */
@Composable
fun ActionSheet(sheet: MenuSheet, onAction: (MenuAction) -> Unit, onDismiss: () -> Unit) {
    val g = LocalGlass.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetColor,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val loaded = sheet.loaded
            SheetHeader(
                label = loaded?.detail ?: sheet.detail,
                title = loaded?.heading?.takeIf { it.isNotBlank() } ?: sheet.heading,
                image = sheet.image,
                imageFallback = sheet.imageFallback,
                wide = sheet.wide,
            )
            Spacer(Modifier.height(8.dp))
            when {
                loaded == null -> Row(Modifier.height(RowHeight), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                    Text("Asking the TV what you can do…", color = g.textSecondary)
                }
                loaded.actions.isEmpty() -> Text(
                    loaded.detail ?: "The TV couldn't find this.",
                    color = g.textSecondary,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                else -> loaded.actions.forEach { action ->
                    SheetRow(
                        icon = iconFor(action.code, action.label),
                        label = action.label,
                        tint = if (isLosing(action.code, action.label)) Color(0xFFE8A07A) else g.accent,
                        busy = sheet.doing == action.code,
                    ) { onAction(action) }
                }
            }
        }
    }
}

/** Which playlist to add to; a show added from its poster first picks a season (or all of them). */
@Composable
fun PlaylistSheet(pick: ServerLine.Playlists, onPick: (playlistId: String, season: Int) -> Unit, onDismiss: () -> Unit) {
    val g = LocalGlass.current
    var season by remember(pick) { mutableIntStateOf(0) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetColor,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassLabel("Add to a playlist")
            DisplayTitle(pick.label, size = 20, maxLines = 2)
            if (pick.seasons.isNotEmpty()) {
                LazyRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { GlassTag("Every season", accent = season == 0, onClick = { season = 0 }) }
                    items(pick.seasons) { s -> GlassTag("Season $s", accent = season == s, onClick = { season = s }) }
                }
            } else {
                Spacer(Modifier.height(6.dp))
            }
            pick.playlists.forEach { playlist ->
                SheetRow(
                    icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                    label = playlist.name,
                    tint = g.accent,
                    trailing = if (playlist.count == 1) "1 item" else "${playlist.count} items",
                ) { onPick(playlist.id, season) }
            }
            SheetRow(icon = Icons.Rounded.Add, label = "New playlist", tint = g.accent, trailing = "named after it") {
                onPick(MenuCode.NEW_PLAYLIST, season)
            }
            if (pick.playlists.isEmpty()) {
                Text("No playlists on the TV yet: this makes the first.", color = g.textMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** The AI recap of the story so far, as the TV's recap sheet shows it. */
@Composable
fun RecapSheet(recap: ServerLine.Recap, onDismiss: () -> Unit) {
    val g = LocalGlass.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetColor,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            GlassLabel("Recap", icon = Icons.Rounded.AutoStories)
            Spacer(Modifier.height(6.dp))
            DisplayTitle(recap.heading, size = 20, maxLines = 2)
            Spacer(Modifier.height(14.dp))
            when (recap.state) {
                0 -> Row(Modifier.height(RowHeight), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                    Text("Writing the recap… a fresh one takes a few seconds.", color = g.textSecondary)
                }
                else -> Box(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        recap.text ?: "The recap couldn't be written.",
                        color = if (recap.state == 1) g.text else g.textSecondary,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                    )
                }
            }
        }
    }
}
