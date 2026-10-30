package com.nuvio.app.features.player.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.touchlab.kermit.Logger
import java.io.File

/**
 * P0 of the post-show trivia plan: can the video shrink into a corner box with Compose drawing
 * around it, and come back?
 *
 * Off unless a file named [FLAG_FILE_NAME] exists in the user's home folder, so nobody sees it by
 * accident. With it, the player boxes the video from 45 s to 65 s of playback (see
 * `postShowSpikeActive`), hides the controls overlay while boxed, and draws a placeholder card in
 * the space around it. Escape (from the HUD) dismisses it for the rest of this playback.
 *
 * Everything the spike learns is written to the log under "PostShowSpike".
 */
internal object DesktopPostShowSpike {
    const val FLAG_FILE_NAME = ".nuvio-postshow-spike"

    private val log = Logger.withTag("PostShowSpike")

    /** Whether the flag file is present. Read once; restart the app after creating or removing it. */
    val enabled: Boolean by lazy {
        runCatching { File(System.getProperty("user.home"), FLAG_FILE_NAME).exists() }
            .getOrDefault(false)
            .also { log.i { "post-show spike enabled=$it" } }
    }

    /** True while the video is in the corner box. Written by the player surface, read by the HUD's Escape. */
    val boxed: MutableState<Boolean> = mutableStateOf(false)

    /** The viewer pressed Escape: stay out of the box until the next playback. */
    val dismissed: MutableState<Boolean> = mutableStateOf(false)

    fun dismiss() {
        log.i { "post-show spike dismissed by Escape" }
        dismissed.value = true
    }

    /** Called when a new player surface starts, so one dismissal does not outlive its playback. */
    fun reset() {
        dismissed.value = false
        boxed.value = false
    }

    fun noteBoxed(value: Boolean, positionMs: Long) {
        if (boxed.value == value) return
        boxed.value = value
        log.i { "post-show spike boxed=$value at ${positionMs}ms" }
    }
}

/** The placeholder for the trivia cards: fills the space around the boxed video. */
@Composable
internal fun PostShowSpikeCard(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color(0xFF0B0D12))
            .padding(horizontal = 96.dp, vertical = 72.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "POST-SHOW SPIKE",
            color = Color(0xFF8FA3C8),
            fontSize = 22.sp,
        )
        Text(
            text = "What did the ending mean?",
            color = Color.White,
            fontSize = 56.sp,
        )
        Text(
            text = "The video should be playing in the corner box, with sound, while this fills the rest of the screen.",
            color = Color(0xFFCBD3E4),
            fontSize = 30.sp,
        )
        Text(
            text = "Escape closes this.",
            color = Color(0xFF8FA3C8),
            fontSize = 22.sp,
        )
    }
}
