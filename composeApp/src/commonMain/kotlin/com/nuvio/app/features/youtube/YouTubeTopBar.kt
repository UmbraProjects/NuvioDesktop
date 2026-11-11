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
    /**
     * The tab draws no search box: the app bar's Search searches the open tab (YouTube here) into [text], so the page
     * always starts right under the bar.
     */
    val barHosted: Boolean = true

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
