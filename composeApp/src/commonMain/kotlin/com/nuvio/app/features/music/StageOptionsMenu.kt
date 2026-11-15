package com.nuvio.app.features.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.library.musicGlass
import com.nuvio.app.features.music.library.musicKeyFocusRing
import kotlinx.coroutines.delay

/*
 * fork:stage - the Now Playing stage's settings in one place: an Options button at the far right of the top bar that opens a
 * glass menu. Every row has its name on the left and its value in a pill of one width on the right, so the rows line up.
 * Up and Down move, Select changes a row, Left, Back or a few quiet seconds close it.
 */

/** How long the open menu waits for input before closing. */
private const val OPTIONS_IDLE_MS = 8_000L

// Wide and short, two columns of two rows: opened from the top bar it ends above the visualiser box instead of covering it.
private val MENU_WIDTH = 600.dp
private val ROW_HEIGHT = 48.dp
private val PILL_WIDTH = 84.dp

@Composable
internal fun StageOptionsMenu(
    lyricsOn: Boolean,
    lyricStyle: LyricStyle,
    fadeQueue: Boolean,
    reduced: Boolean,
    onToggleLyrics: () -> Unit,
    onCycleLyricStyle: () -> Unit,
    onToggleFadeQueue: () -> Unit,
    onToggleMotion: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    var open by remember { mutableStateOf(false) }
    // Bumped by every change so the idle timer starts again.
    var touched by remember { mutableIntStateOf(0) }
    val buttonFocus = remember { FocusRequester() }
    val firstRow = remember { FocusRequester() }
    val offsetY = with(LocalDensity.current) { 46.dp.roundToPx() }

    fun close() {
        open = false
        runCatching { buttonFocus.requestFocus() }
    }

    LaunchedEffect(open, touched) {
        if (!open) return@LaunchedEffect
        delay(OPTIONS_IDLE_MS)
        close()
    }
    // The native video draws over the menu; nothing in it changes the video, so the picture steps aside while it is open.
    DisposableEffect(open) {
        MusicPlayback.stageMenuOpen.value = open
        onDispose { MusicPlayback.stageMenuOpen.value = false }
    }

    Box(modifier) {
        Row(
            Modifier
                .height(40.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = if (open) 0.6f else 0.45f))
                .border(1.dp, if (open) colors.accent else Color.White.copy(alpha = 0.25f), CircleShape)
                .focusRequester(buttonFocus)
                .musicKeyFocusRing(CircleShape)
                .clickable { if (open) close() else open = true }
                .padding(start = 18.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Options", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        if (open) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, offsetY),
                onDismissRequest = { close() },
                properties = PopupProperties(focusable = true),
            ) {
                com.nuvio.app.core.ui.KeyRouteLayer("stage options menu") // upstream focus router (Nov 13): leaves an open menu alone
                LaunchedEffect(Unit) { runCatching { firstRow.requestFocus() } }
                Column(
                    Modifier
                        .width(MENU_WIDTH)
                        .musicGlass(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                                close()
                                true
                            } else {
                                false
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        "STAGE OPTIONS",
                        color = colors.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.5.sp,
                        modifier = Modifier.padding(start = 14.dp, bottom = 6.dp, top = 2.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            OptionRow("Lyrics", if (lyricsOn) "On" else "Off", on = lyricsOn, modifier = Modifier.focusRequester(firstRow)) {
                                touched++
                                onToggleLyrics()
                            }
                            OptionRow("Lyric style", lyricStyle.label.replaceFirstChar { it.uppercase() }, on = null, enabled = lyricsOn) {
                                touched++
                                onCycleLyricStyle()
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            OptionRow("Fade queue", if (fadeQueue) "On" else "Off", on = fadeQueue) {
                                touched++
                                onToggleFadeQueue()
                            }
                            OptionRow("Motion", if (reduced) "Reduced" else "Full", on = !reduced) {
                                touched++
                                onToggleMotion()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One row: [label] on the left, its value in a [PILL_WIDTH] pill on the right (filled with the accent when [on], an outline when off, plain when null). */
@Composable
private fun OptionRow(label: String, value: String, on: Boolean?, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clip(shape)
            .musicKeyFocusRing(shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Color.White.copy(alpha = if (enabled) 0.92f else 0.35f), fontSize = 16.sp)
        val filled = on == true && enabled
        Box(
            Modifier
                .width(PILL_WIDTH)
                .height(30.dp)
                .clip(CircleShape)
                .then(
                    if (filled) Modifier.background(colors.accent)
                    else Modifier.border(1.dp, Color.White.copy(alpha = if (enabled) 0.35f else 0.15f), CircleShape),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                value,
                color = if (filled) colors.onAccent else Color.White.copy(alpha = if (enabled) 0.85f else 0.35f),
                fontSize = 14.sp,
                fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}
