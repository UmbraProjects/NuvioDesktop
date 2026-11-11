package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Predictive text for the on-screen keyboard. The keyboard only types keystrokes into whatever field holds focus and never
 * sees the field's text, so the screen that owns the field offers the predictions: a few whole lines the person may want
 * (YouTube Music's completions on the music search, recent searches and result titles on the media search). The keyboard
 * shows them as a strip above its keys, and picking one hands it back to the screen to put in its field.
 *
 * One offer at a time; a screen's offer is withdrawn when it leaves ([PublishKeyboardSuggestions] does both).
 */
object KeyboardSuggestions {
    class Offer internal constructor(val owner: Any, val words: List<String>, val onPick: (String) -> Unit)

    private val _offer = MutableStateFlow<Offer?>(null)
    val offer: StateFlow<Offer?> = _offer.asStateFlow()

    fun publish(owner: Any, words: List<String>, onPick: (String) -> Unit) {
        _offer.value = if (words.isEmpty()) null else Offer(owner, words, onPick)
    }

    fun withdraw(owner: Any) {
        if (_offer.value?.owner === owner) _offer.value = null
    }

    /** The keyboard picked [word]: hands it to the screen that offered it. */
    fun pick(word: String) {
        _offer.value?.onPick?.invoke(word)
    }
}

/** Offers [words] to the on-screen keyboard while this is composed; [onPick] puts a picked one in the field. */
@Composable
fun PublishKeyboardSuggestions(words: List<String>, onPick: (String) -> Unit) {
    val owner = remember { Any() }
    val pick by rememberUpdatedState(onPick)
    LaunchedEffect(words) { KeyboardSuggestions.publish(owner, words.distinct().take(MAX_KEYBOARD_SUGGESTIONS)) { pick(it) } }
    DisposableEffect(Unit) { onDispose { KeyboardSuggestions.withdraw(owner) } }
}

/** The strip holds this many: enough to be worth a look, few enough to fit one row of the keyboard. */
const val MAX_KEYBOARD_SUGGESTIONS = 5
