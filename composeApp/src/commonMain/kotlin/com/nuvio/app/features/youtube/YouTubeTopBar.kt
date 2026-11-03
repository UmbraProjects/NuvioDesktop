package com.nuvio.app.features.youtube

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapHoriz
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioGlass
import com.nuvio.app.core.ui.NuvioPosterHoverTooltip
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.trackTextInputFocus
import com.nuvio.app.features.music.library.musicKeyFocusRing
import kotlinx.coroutines.delay

/*
 * fork:youtube-mode - the YouTube search on the app's floating top bar, as the Music tab has its own:
 * while the YouTube tab is open the bar holds a way back to the regular bar and the search box, so
 * the tab needs no header row for it and its page starts right under the bar.
 */

/** What the bar's search box and the tab share; one YouTube tab exists at a time. */
object YouTubeTopBarSearch {
    /** True while the top bar holds the box, so the tab leaves its own out and moves up. */
    var barHosted by mutableStateOf(false)
        internal set

    /** The search box's text, the same in the bar's box and the tab's own. */
    var text by mutableStateOf("")

    /** Enter in the bar's box: the tab runs the search (it owns the page history). */
    internal var submits by mutableIntStateOf(0)
        private set

    /** The box's clear button: the tab leaves the results for where the search was typed. */
    internal var clears by mutableIntStateOf(0)
        private set

    internal var focusRequests by mutableIntStateOf(0)
        private set

    /** The first video of the page under the box: Down and Escape from the box go there. */
    internal val resultsFocus = FocusRequester()

    internal fun submit() {
        if (text.isNotBlank()) submits++
    }

    internal fun clear() {
        text = ""
        clears++
    }

    /** The search key on the YouTube tab: into this box rather than the video search. */
    fun requestFocus() {
        focusRequests++
    }
}

/** What the floating top bar shows while the YouTube tab is open: the swap button back to the tabs and the search box. */
@Composable
fun YouTubeTopBarContent(onLeave: () -> Unit, modifier: Modifier = Modifier) {
    DisposableEffect(Unit) {
        YouTubeTopBarSearch.barHosted = true
        onDispose { YouTubeTopBarSearch.barHosted = false }
    }
    Row(modifier.fillMaxWidth().padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        NuvioPosterHoverTooltip(title = "Back to the main bar") {
            BarIconButton(
                icon = Icons.Rounded.SwapHoriz,
                description = "Back to the main bar",
                onClick = onLeave,
                modifier = Modifier.padding(start = 10.dp, end = 4.dp),
            )
        }
        Box(Modifier.size(width = 1.dp, height = 22.dp).background(NuvioGlass.EdgeColor))
        Spacer(Modifier.width(6.dp))
        YouTubeBarSearchField(Modifier.weight(1f))
    }
}

@Composable
private fun YouTubeBarSearchField(modifier: Modifier) {
    val tokens = MaterialTheme.nuvio
    val state = YouTubeTopBarSearch
    val focusManager = LocalFocusManager.current
    val fieldFocus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    // The search shortcut focuses the box. Requests made before it existed are not replayed, and the
    // key's own letter is kept out of the empty box it just focused.
    var handledFocusRequests by remember { mutableIntStateOf(state.focusRequests) }
    var swallowActivationKey by remember { mutableStateOf(false) }
    LaunchedEffect(state.focusRequests) {
        if (state.focusRequests == handledFocusRequests) return@LaunchedEffect
        handledFocusRequests = state.focusRequests
        swallowActivationKey = true
        runCatching { fieldFocus.requestFocus() }
        delay(150)
        swallowActivationKey = false
    }
    fun toResults(): Boolean = runCatching { state.resultsFocus.requestFocus() }.getOrDefault(false)
    BasicTextField(
        value = state.text,
        onValueChange = { next ->
            if (swallowActivationKey && state.text.isEmpty() && next.length == 1) {
                swallowActivationKey = false
            } else {
                state.text = next
            }
        },
        modifier = modifier
            .focusRequester(fieldFocus)
            .height(44.dp)
            .trackTextInputFocus()
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (!focused) return@onPreviewKeyEvent false
                val down = event.type == KeyEventType.KeyDown
                when (event.key) {
                    // The field would take Down as a cursor move: it goes to the videos instead.
                    Key.DirectionDown -> {
                        if (down && !toResults()) focusManager.moveFocus(FocusDirection.Down)
                        true
                    }
                    // Backspace in an empty box is the app's Back, as everywhere else.
                    Key.Backspace -> if (state.text.isEmpty()) {
                        if (down) com.nuvio.app.core.ui.DesktopNavigationGestureBridge.requestBack()
                        true
                    } else {
                        false
                    }
                    // Escape lets go of the box for the page under it; both halves are eaten so the
                    // screen's own Escape (back) doesn't fire on the same press.
                    Key.Escape -> {
                        if (!down && !toResults()) focusManager.clearFocus()
                        true
                    }
                    else -> false
                }
            },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = tokens.colors.textPrimary),
        cursorBrush = SolidColor(tokens.colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { state.submit() }),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = "Search",
                    tint = tokens.colors.textMuted,
                    modifier = Modifier.size(20.dp).clip(CircleShape).clickable { state.submit() },
                )
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (state.text.isEmpty()) {
                        Text(
                            text = "Search YouTube",
                            color = tokens.colors.textMuted,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
                if (state.text.isNotEmpty()) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Clear search",
                        tint = tokens.colors.textMuted,
                        modifier = Modifier.size(20.dp).clip(CircleShape).musicKeyFocusRing(CircleShape).clickable { state.clear() },
                    )
                }
            }
        },
    )
}

@Composable
private fun BarIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .musicKeyFocusRing(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tokens.colors.textSecondary, modifier = Modifier.size(20.dp))
    }
}
