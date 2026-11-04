package com.nuvio.app.features.music
// FORK UI (Spyder): Nick's album page lenses, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.trackTextInputFocus
import com.nuvio.app.features.music.library.musicKeyFocusRing

/*
 * fork:pins - the Edit control on the album page's song panel. The person's text is pinned (see booklet/Pins.kt) and from
 * then on stands in place of what the AI or Wikipedia wrote. Editing happens in the story box itself, which keeps its size,
 * and the Edit / Cancel / Reset / Save pills sit on the section label's line, which has the same height either way, so
 * nothing on the page moves. Typing needs a keyboard; Escape cancels and Ctrl+Enter saves.
 */

/**
 * What the song panel needs to edit a fact: the pin key of the story's "made" part for a song [madeKey], the pin key of a
 * track's note [noteKey] (null when the album's tracks are not known), and [onChanged], called after a pin is saved or
 * cleared so the page reads what is kept again.
 */
internal class LensEditing(
    val madeKey: (songTitle: String) -> String,
    val noteKey: (trackNumber: Int) -> String?,
    val onChanged: () -> Unit,
)

/** What one Edit control writes: the pin's [key] and whether a pin is already there ([pinned], so Reset is offered). */
internal class LensEditTarget(val key: String, val pinned: Boolean)

/** A small outlined pill that does one thing when pressed: the same height whatever it says. */
@Composable
internal fun PinPill(label: String, tint: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(999.dp)
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = tint,
        maxLines = 1,
        modifier = modifier
            .clip(shape)
            .border(1.dp, tint.copy(alpha = 0.5f), shape)
            .musicKeyFocusRing(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/**
 * The text box that stands in the story box while editing: it takes the box's whole size, scrolls when the text is longer,
 * and takes the keyboard (the page's own keys stand aside while a text field is focused). Escape calls [onCancel] and
 * Ctrl+Enter calls [onSave].
 */
@Composable
internal fun PinEditField(
    value: String,
    onValueChange: (String) -> Unit,
    tint: Color,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(modifier.verticalScroll(rememberScrollState())) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(tint),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .trackTextInputFocus()
                .onPreviewKeyEvent { event ->
                    val cancel = event.key == Key.Escape
                    val save = event.key == Key.Enter && event.isCtrlPressed
                    if (!cancel && !save) return@onPreviewKeyEvent false
                    // Both halves of the key are taken, so the page's own Escape (back) never sees the release.
                    if (event.type == KeyEventType.KeyDown) {
                        if (cancel) onCancel() else onSave()
                    }
                    true
                },
        )
    }
}
