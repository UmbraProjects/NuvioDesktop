package com.nuvio.app.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * fork:voice-search
 *
 * Voice search through Windows' own voice typing (Win+H): the search box is focused, then voice typing
 * is opened over it and types what is said into the box. Nothing is bundled — no speech model, no
 * native library — and Windows keeps the microphone and the recognition to itself.
 *
 * While a box is listening, a few spoken words are commands rather than text, read once the words
 * stop changing: ending with "search" or "confirm" runs the search ("The Dark Knight search"), and
 * "delete that" or "clear" empties the box. Recognition is not always right, so nothing runs until
 * it is asked for; Enter still works as well.
 */

/** Opens Windows voice typing over the focused text box. False when it could not be asked for. */
internal expect fun startVoiceTyping(): Boolean

/** Which search box (by a name of its own) voice typing was opened for; null when none is listening. */
object VoiceSearch {
    private val _owner = MutableStateFlow<String?>(null)
    val owner: StateFlow<String?> = _owner.asStateFlow()

    fun start(owner: String) {
        _owner.value = owner
        startVoiceTyping()
    }

    fun end(owner: String) {
        if (_owner.value == owner) _owner.value = null
    }
}

sealed interface VoiceSearchCommand {
    /** Run the search for [query] (the words before the command). */
    data class Submit(val query: String) : VoiceSearchCommand

    /** Empty the box. */
    data object Clear : VoiceSearchCommand

    /** No command: the dictated text as it should stand ([text], its trailing full stop dropped). */
    data class Text(val text: String) : VoiceSearchCommand
}

private val SUBMIT_COMMAND = Regex("""^(.*?)[\s,.;:!?]*\b(search|confirm)$""", RegexOption.IGNORE_CASE)
private val CLEAR_COMMAND = Regex("""(^|\s|[,.;:!?])(delete that|clear that|clear search|clear)$""", RegexOption.IGNORE_CASE)

/**
 * What dictated [text] asks for. Voice typing capitalises and punctuates as if writing a sentence
 * ("The dark knight search."), so trailing punctuation is ignored and the words are matched without case.
 */
fun parseVoiceSearchCommand(text: String): VoiceSearchCommand {
    val cleaned = text.trimEnd { it.isWhitespace() || it in ".,;:!?" }
    if (CLEAR_COMMAND.containsMatchIn(cleaned)) return VoiceSearchCommand.Clear
    SUBMIT_COMMAND.find(cleaned)?.let { match ->
        val query = match.groupValues[1].trimEnd { it.isWhitespace() || it in ".,;:!?" }.trim()
        if (query.isNotEmpty()) return VoiceSearchCommand.Submit(query)
    }
    return VoiceSearchCommand.Text(cleaned.trimStart())
}

/** How long the dictated words must stay the same before a command is read from them. */
private const val VOICE_COMMAND_SETTLE_MS = 700L

/**
 * Reads [VoiceSearch] commands from [text] while voice typing is open for [owner]. [onSubmit] null: a
 * box that searches as it is typed in, where "search" is only taken off the end.
 */
@Composable
fun VoiceSearchCommands(
    owner: String,
    text: String,
    onTextChange: (String) -> Unit,
    onSubmit: ((String) -> Unit)? = null,
) {
    val listening by VoiceSearch.owner.collectAsState()
    val active = listening == owner
    val change by rememberUpdatedState(onTextChange)
    val submit by rememberUpdatedState(onSubmit)
    LaunchedEffect(text, active) {
        if (!active || text.isEmpty()) return@LaunchedEffect
        delay(VOICE_COMMAND_SETTLE_MS)
        when (val command = parseVoiceSearchCommand(text)) {
            is VoiceSearchCommand.Submit -> {
                change(command.query)
                submit?.let {
                    VoiceSearch.end(owner)
                    it(command.query)
                }
            }
            VoiceSearchCommand.Clear -> change("")
            is VoiceSearchCommand.Text -> if (command.text != text) change(command.text)
        }
    }
    DisposableEffect(owner) { onDispose { VoiceSearch.end(owner) } }
}

/**
 * The mic button: focuses the box ([focusField]) and opens voice typing over it. A square of [size]
 * with the icon in its middle, so it lines up with the icon buttons beside it.
 */
@Composable
fun VoiceSearchButton(
    owner: String,
    focusField: () -> Unit,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    iconSize: Dp = 20.dp,
) {
    val listening by VoiceSearch.owner.collectAsState()
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable {
                focusField()
                VoiceSearch.start(owner)
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Mic,
            contentDescription = "Search by voice",
            tint = if (listening == owner) MaterialTheme.colorScheme.primary else tint,
            modifier = Modifier.size(iconSize),
        )
    }
}
