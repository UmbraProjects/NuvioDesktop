package com.nuvio.app.features.panelring

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioGlass
import com.nuvio.app.features.input.GamepadInput
import com.nuvio.app.features.input.GamepadSettingsRepository
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * Tells a controller user how to reach the panel ring (and the music player), which nothing on screen did: the
 * first time the pad is used in a launch, a glass pill at the top of the window names the button for a few seconds,
 * and Y for the music player while music plays (2026-11-15). Once a launch. Hosted beside [PanelRingOverlay].
 */
@Composable
internal fun PanelRingHint() {
    val ringButton by GamepadSettingsRepository.ringButtonState.collectAsState()
    var visible by remember { mutableStateOf(false) }
    var withMusic by remember { mutableStateOf(false) }
    LaunchedEffect(ringButton) {
        if (ringButton == null || PanelRingHintState.done) return@LaunchedEffect
        // Waits for the pad's first press this launch; a mouse and keyboard user never sees it.
        while (!GamepadInput.usedRecently(withinMs = 2_000L)) delay(500)
        if (PanelRingHintState.done || PanelRingController.isOpen) return@LaunchedEffect
        PanelRingHintState.done = true
        withMusic = com.nuvio.app.features.music.MusicKeyboard.playerShowing()
        visible = true
        // Gone early if the ring opens meanwhile: the hint did its job.
        var waited = 0
        while (waited < HINT_SHOWN_MS && !PanelRingController.isOpen) {
            delay(250)
            waited += 250
        }
        visible = false
    }
    val button = ringButton ?: return
    Box(modifier = Modifier.fillMaxSize().padding(top = 84.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
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
                HintPart(key = "Hold ${stringResource(button.displayNameRes)}", action = "Switch panels")
                if (withMusic) HintPart(key = "Y", action = "Music player")
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

/** Shown once a launch. */
internal object PanelRingHintState {
    @Volatile
    var done = false
}

private const val HINT_SHOWN_MS = 8_000
