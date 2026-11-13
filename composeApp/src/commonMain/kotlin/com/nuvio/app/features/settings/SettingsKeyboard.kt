package com.nuvio.app.features.settings

import com.nuvio.app.core.ui.requestFocusLogged
import androidx.compose.ui.focus.FocusRequester

/*
 * fork:settings-arrows
 *
 * Keyboard and pad navigation for Settings, built like MusicKeyboard. Opening Settings puts focus on the selected
 * category in the sidebar (or, without one, the page's first control), so the arrow keys - and a controller's D-pad
 * and the phone remote, which send them - have something to move from. Before this, focus was often nowhere when
 * Settings opened (the control that opened it leaves the screen), and no arrow press reached the page.
 *
 * When focus still goes astray, the window sees an arrow nobody took, and [onUnhandledArrow] puts focus back on the page.
 */
internal object SettingsKeyboard {
    internal class Root(val requester: FocusRequester) {
        var hasFocus: Boolean = false
    }

    /** The selected sidebar category, where focus goes when Settings opens. */
    val entry = FocusRequester()

    internal var root: Root? = null

    /** Puts focus on the selected category, or else the page's first control. Returns whether it did. */
    fun focusPage(): Boolean {
        val page = root ?: return false
        return entry.requestFocusLogged() ||
            page.requester.requestFocusLogged()
    }

    /** An arrow reached the window with no control taking it: while Settings is open and holds no focus, focus it. */
    fun onUnhandledArrow(): Boolean {
        val page = root ?: return false
        if (page.hasFocus) return false
        return focusPage()
    }
}
