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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import com.nuvio.app.features.music.MusicBarSuggestions
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
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.nuvio.app.core.ui.arrowFocusDirection
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

    /** The recent searches the bar hangs under the box while it is empty and focused (drawn by the bar, as Music's). */
    var suggestions by mutableStateOf<MusicBarSuggestions?>(null)
        internal set

    /** The search key on the YouTube tab: into this box rather than the video search. */
    fun requestFocus() {
        focusRequests++
    }

    /**
     * fork:voice-control - a search asked for by voice, run by the tab as soon as it is on screen. Not
     * [submits]: the tab takes the count it first sees as handled, so a submit made while switching
     * to the tab would be lost.
     */
    internal var pendingSearch by mutableStateOf<String?>(null)

    fun searchFor(query: String) {
        if (query.isBlank()) return
        text = query
        pendingSearch = query
    }
}

/** What the floating top bar shows while the YouTube tab is open: the swap button back to the tabs and the search box. */
@Composable
fun YouTubeTopBarContent(onLeave: () -> Unit, modifier: Modifier = Modifier) {
    DisposableEffect(Unit) {
        YouTubeTopBarSearch.barHosted = true
        onDispose { YouTubeTopBarSearch.barHosted = false }
    }
    val swapFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    Row(modifier.fillMaxWidth().padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        NuvioPosterHoverTooltip(title = "Back to the main bar") {
            BarIconButton(
                icon = Icons.Rounded.SwapHoriz,
                description = "Back to the main bar",
                onClick = onLeave,
                modifier = Modifier
                    .padding(start = 10.dp, end = 4.dp)
                    .focusRequester(swapFocus)
                    // The bar's row is two stops: Right goes back to the box, Down to the videos.
                    .onKeyEvent { event ->
                        when (arrowFocusDirection(event)) {
                            FocusDirection.Right -> runCatching { fieldFocus.requestFocus() }.getOrDefault(false)
                            FocusDirection.Down -> runCatching { YouTubeTopBarSearch.resultsFocus.requestFocus() }.getOrDefault(false)
                            FocusDirection.Left -> true
                            else -> false
                        }
                    },
            )
        }
        Box(Modifier.size(width = 1.dp, height = 22.dp).background(NuvioGlass.EdgeColor))
        Spacer(Modifier.width(6.dp))
        YouTubeBarSearchField(fieldFocus = fieldFocus, swapFocus = swapFocus, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun YouTubeBarSearchField(fieldFocus: FocusRequester, swapFocus: FocusRequester, modifier: Modifier) {
    val tokens = MaterialTheme.nuvio
    val state = YouTubeTopBarSearch
    val focusManager = LocalFocusManager.current
    // The cursor is kept here so Left knows when it is at the start; the text itself is shared with the tab,
    // and a change made there (the tab's own box, a clear) puts the cursor at the end.
    var field by remember { mutableStateOf(TextFieldValue(state.text, TextRange(state.text.length))) }
    val shown = if (field.text == state.text) field else TextFieldValue(state.text, TextRange(state.text.length))
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
    // Recent searches, under the empty box while it has focus; Up and Down walk them, Enter picks one.
    val recent by remember { YouTubeSearchHistory.recent }.collectAsState()
    val offered = recent.take(RECENT_SHOWN)
    var picked by remember(offered) { mutableIntStateOf(-1) }
    val showRecent = focused && state.text.isEmpty() && offered.isNotEmpty()
    fun pick(query: String) {
        field = TextFieldValue(query, TextRange(query.length))
        state.text = query
        state.submit()
    }
    val published = if (showRecent) MusicBarSuggestions(offered, picked, ::pick, onClear = YouTubeSearchHistory::clear) else null
    SideEffect { YouTubeTopBarSearch.suggestions = published }
    DisposableEffect(Unit) { onDispose { YouTubeTopBarSearch.suggestions = null } }
    com.nuvio.app.core.ui.VoiceSearchCommands(
        owner = "youtube-bar",
        text = state.text,
        onTextChange = { state.text = it },
        onSubmit = { query ->
            state.text = query
            state.submit()
        },
    )
    BasicTextField(
        value = shown,
        onValueChange = { next ->
            if (swallowActivationKey && state.text.isEmpty() && next.text.length == 1) {
                swallowActivationKey = false
            } else {
                field = next
                state.text = next.text
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
                if (showRecent) {
                    when (event.key) {
                        Key.DirectionDown -> {
                            if (down) picked = (picked + 1).coerceAtMost(offered.lastIndex)
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionUp -> {
                            if (down) picked = (picked - 1).coerceAtLeast(-1)
                            return@onPreviewKeyEvent true
                        }
                        Key.Enter, Key.NumPadEnter -> if (picked >= 0) {
                            if (down) pick(offered[picked])
                            return@onPreviewKeyEvent true
                        }
                        else -> Unit
                    }
                }
                when (event.key) {
                    // Left at the start of the text (nothing to move past) goes on to the swap button.
                    Key.DirectionLeft -> if (shown.selection.collapsed && shown.selection.start == 0) {
                        if (down) runCatching { swapFocus.requestFocus() }
                        true
                    } else {
                        false
                    }
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
                // Voice search: Windows voice typing types what is said into the box. A 32dp square
                // like the swap button at the other end, so both icons sit the same way in the pill's
                // rounded ends (a bare 20dp icon here sat closer to the edge than the swap icon).
                com.nuvio.app.core.ui.VoiceSearchButton(
                    owner = "youtube-bar",
                    voiceScope = com.nuvio.app.features.voice.VoiceSearchScope.YouTube,
                    focusField = { runCatching { fieldFocus.requestFocus() } },
                    tint = tokens.colors.textMuted,
                    modifier = Modifier.musicKeyFocusRing(CircleShape),
                )
            }
        },
    )
}

/** How many recent searches the panel offers. */
private const val RECENT_SHOWN = 6

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
