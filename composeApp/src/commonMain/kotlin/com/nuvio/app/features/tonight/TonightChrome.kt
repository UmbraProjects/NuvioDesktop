package com.nuvio.app.features.tonight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.nuvio

/*
 * fork:tonight - the pieces every Tonight screen shares since the Nov 12 redesign: one controls strip (the key hint
 * between two fading rules, a separator, then the buttons, the launcher's layout), a Back button now that the ✕ is
 * gone, and mouse-wheel stepping for the lists.
 */

/** Height of [TonightControls], for screens that size their content from what is left. */
internal val TonightControlsHeight = 149.dp

/**
 * The strip under a screen's content: [hint] centred between two fading rules, a separator, then [buttons] centred
 * beneath it. Fixed height whatever it holds.
 */
@Composable
internal fun TonightControls(
    hint: String,
    modifier: Modifier = Modifier,
    hintColor: Color = TextFaint,
    buttons: @Composable RowScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth().height(TonightControlsHeight)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 28.dp).height(22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.width(140.dp).height(1.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, GlassEdge))))
            Text(
                hint, color = hintColor, fontSize = 15.sp, textAlign = TextAlign.Center, maxLines = 1,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Box(Modifier.width(140.dp).height(1.dp).background(Brush.horizontalGradient(listOf(GlassEdge, Color.Transparent))))
        }
        Box(modifier = Modifier.fillMaxWidth().padding(top = 20.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.fillMaxWidth(0.5f).height(1.dp).background(
                    Brush.horizontalGradient(listOf(Color.Transparent, GlassEdge, Color.Transparent)),
                ),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp).height(54.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            content = buttons,
        )
    }
}

/**
 * The way back to the launcher on a mode screen, for the mouse (Back on the remote does the same). It names its key:
 * the old "← Back" read as the Left arrow, which on several modes does something else (Channel Flick's "never show
 * me this"), and on those the pill cannot be reached with the arrows at all.
 */
@Composable
internal fun TonightBackPill(onBack: () -> Unit, selected: Boolean = false, text: String = "Esc  Back") {
    GlassPill(text = text, selected = selected, accent = MaterialTheme.nuvio.colors.accent, onClick = onBack, pill = true)
}

/** Steps [onStep] by +1 (wheel down) or -1 (wheel up) for each wheel notch over this element. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.wheelSteps(onStep: (Int) -> Unit): Modifier = onPointerEvent(PointerEventType.Scroll) { event ->
    val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: return@onPointerEvent
    if (dy > 0f) onStep(1) else if (dy < 0f) onStep(-1)
}
