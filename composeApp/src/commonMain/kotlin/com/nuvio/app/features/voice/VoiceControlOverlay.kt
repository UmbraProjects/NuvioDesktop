package com.nuvio.app.features.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio

/**
 * fork:voice-control - the push-to-talk pill at the bottom of the window: a mic that swells with
 * the voice, what has been heard so far, then what it was understood as. Hosted once, over every
 * screen. It draws under the native video surface, so it is not seen while the player is up.
 */
@Composable
fun VoiceControlOverlay(modifier: Modifier = Modifier) {
    val session by VoiceControlRepository.session.collectAsState()
    // Keeps showing the last state while the pill animates away.
    val shown = remember { mutableStateOf<VoiceSessionState>(VoiceSessionState.Idle) }
    if (session !is VoiceSessionState.Idle) shown.value = session

    AnimatedVisibility(
        visible = session !is VoiceSessionState.Idle,
        modifier = modifier,
        enter = fadeIn(tween(160)) + slideInVertically(tween(200)) { it / 2 },
        exit = fadeOut(tween(220)) + slideOutVertically(tween(220)) { it / 2 },
    ) {
        VoicePill(shown.value)
    }
}

@Composable
private fun VoicePill(state: VoiceSessionState) {
    val colors = MaterialTheme.nuvio.colors
    val (title, subtitle) = pillText(state)
    val failed = state is VoiceSessionState.Failed || (state as? VoiceSessionState.Heard)?.intent is VoiceIntent.Unknown
    val level = (state as? VoiceSessionState.Listening)?.level ?: 0f
    val swell by animateFloatAsState(targetValue = 1f + level * 0.45f, animationSpec = tween(90), label = "voice-level")
    val shape = RoundedCornerShape(32.dp)

    Row(
        modifier = Modifier
            .padding(bottom = 40.dp)
            .widthIn(min = 320.dp, max = 720.dp)
            .clip(shape)
            .background(colors.surfacePopover)
            .border(1.dp, colors.borderSubtle, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            val listening = state is VoiceSessionState.Listening
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .scale(if (listening) swell else 1f)
                    .clip(CircleShape)
                    .background(
                        when {
                            failed -> colors.danger.copy(alpha = 0.22f)
                            listening -> colors.accent
                            else -> colors.accent.copy(alpha = 0.35f)
                        },
                    ),
            )
            Icon(
                imageVector = if (state is VoiceSessionState.Failed) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                contentDescription = null,
                tint = if (state is VoiceSessionState.Listening) colors.onAccent else colors.textPrimary,
                modifier = Modifier.size(24.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun quoted(text: String) = "“${text.trim().trimEnd('.')}”"

private fun pillText(state: VoiceSessionState): Pair<String, String> = when (state) {
    is VoiceSessionState.Listening -> when {
        state.transcript.isNotBlank() -> quoted(state.transcript) to "Listening…"
        state.heardSpeech -> "Listening…" to ""
        state.scope != null -> "Listening…" to "Say what to search for"
        else -> "Listening…" to "Say a command, like “search YouTube for cat videos”"
    }
    is VoiceSessionState.Transcribing ->
        (if (state.transcript.isNotBlank()) quoted(state.transcript) else "One moment…") to "Working it out…"
    is VoiceSessionState.Heard -> when (val intent = state.intent) {
        is VoiceIntent.Unknown -> quoted(state.text) to "That isn’t a command I know. Try “open settings” or “search movies for…”"
        else -> describeVoiceIntent(intent) to quoted(state.text)
    }
    is VoiceSessionState.Failed -> state.message to ""
    VoiceSessionState.Idle -> "" to ""
}
