package com.nuvio.app.features.input

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioGlass
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.gamepad_button_a
import nuvio.composeapp.generated.resources.gamepad_button_x
import nuvio.composeapp.generated.resources.gamepad_cursor_hint_click
import nuvio.composeapp.generated.resources.gamepad_cursor_hint_dpad
import nuvio.composeapp.generated.resources.gamepad_cursor_hint_lock
import nuvio.composeapp.generated.resources.gamepad_cursor_hint_right_click
import nuvio.composeapp.generated.resources.gamepad_cursor_hint_right_stick
import nuvio.composeapp.generated.resources.gamepad_cursor_hint_scroll
import org.jetbrains.compose.resources.stringResource

/**
 * While the controller cursor is unlocked, a glass pill in the bottom right corner names its buttons, so it is clear
 * the pad is in pointer mode and how to leave it (centred at the foot it sat over the player's controls and the rows,
 * Nov 16). It can be turned off (Controller cursor > Button hints). Hosted beside the panel ring's hint.
 */
@Composable
internal fun GamepadCursorHint() {
    val active by GamepadCursor.active.collectAsState()
    val enabled by GamepadSettingsRepository.cursorHintState.collectAsState()
    // Only while the pad is in hand: with Pointer by default the cursor is always unlocked, and the pill stayed up
    // over a mouse and keyboard session (Nov 16). Fades a few seconds after the pad's last input.
    var padInUse by remember { mutableStateOf(false) }
    LaunchedEffect(active, enabled) {
        if (!active || !enabled) return@LaunchedEffect
        while (true) {
            padInUse = GamepadInput.usedRecently(withinMs = HINT_IDLE_MS)
            delay(250)
        }
    }
    Box(modifier = Modifier.fillMaxSize().padding(end = 24.dp, bottom = 24.dp), contentAlignment = Alignment.BottomEnd) {
        AnimatedVisibility(visible = active && enabled && padInUse, enter = fadeIn(), exit = fadeOut()) {
            val shape = RoundedCornerShape(50)
            Row(
                modifier = Modifier
                    .clip(shape)
                    .background(Color(0xE6101420))
                    .border(BorderStroke(1.dp, NuvioGlass.EdgeColor), shape)
                    .padding(horizontal = 18.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HintPart(stringResource(Res.string.gamepad_button_a), stringResource(Res.string.gamepad_cursor_hint_click))
                HintPart(stringResource(Res.string.gamepad_button_x), stringResource(Res.string.gamepad_cursor_hint_right_click))
                HintPart(stringResource(Res.string.gamepad_cursor_hint_right_stick), stringResource(Res.string.gamepad_cursor_hint_scroll))
                HintPart(stringResource(Res.string.gamepad_cursor_hint_dpad), stringResource(Res.string.gamepad_cursor_hint_lock))
            }
        }
    }
}

@Composable
private fun HintPart(key: String, action: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = key,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.14f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
        Text(text = action, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.86f))
    }
}

/** The pill fades this long after the pad was last used. */
private const val HINT_IDLE_MS = 4_000L
