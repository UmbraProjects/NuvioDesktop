package com.nuvio.app.features.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * fork:phone-remote tonight - the Tonight picker as the phone mirrors it (user, Nov 10: "mirror the TV", for Three
 * Questions and Three Cards; Nov 11: every mode, The Map as a touchpad). The TV publishes what it shows; the phone's taps come back as [act] and do exactly what
 * the TV's keys would.
 */
data class RemoteTonightCard(
    /** "SAFE", "NEW TO YOU", "WILDCARD". */
    val slot: String,
    val name: String,
    val why: String,
    /** "2016 · 1h 56m · ends 23:11". */
    val meta: String,
    val poster: String?,
    /** Mode-specific: on the launcher, whether the mode is built; in Ends By, whether it ends before bed. */
    val flag: Boolean = false,
)

data class RemoteTonightScreen(
    /** [LAUNCHER], [QUESTION], [CARDS], one of the mode views, or [OTHER] (anything else; [mode] names it). */
    val view: String,
    val mode: String? = null,
    val number: Int = 0,
    val total: Int = 0,
    val countFrom: Int = 0,
    val countLeft: Int = 0,
    val prompt: String? = null,
    val leftLabel: String? = null,
    val leftDetail: String? = null,
    val rightLabel: String? = null,
    val rightDetail: String? = null,
    val answers: List<String> = emptyList(),
    val reshuffleLeft: Boolean = false,
    val cards: List<RemoteTonightCard> = emptyList(),
    /** True while the titles are still being gathered. */
    val loading: Boolean = false,
    /** A line under the title (Coin Toss's lean, Channel Flick's last note, The Map's reading). */
    val extra: String? = null,
    /** A countdown (Coin Toss, Knockout's winner); -1 when none runs. */
    val seconds: Int = -1,
    val secondsTotal: Int = 0,
    val paused: Boolean = false,
    /** The row the TV has selected in a list (Ends By, The Pile), from 1; 0 for none. */
    val selected: Int = 0,
) {
    companion object {
        const val LAUNCHER = "launcher"
        const val QUESTION = "question"
        const val CARDS = "cards"
        // Nov 11: every mode is mirrored. [cards] carries each mode's titles (see TonightScreen's MirrorOnPhone callers).
        const val KNOCKOUT = "knockout"
        const val COIN_TOSS = "cointoss"
        const val FLICK = "flick"
        const val ENDS_BY = "endsby"
        const val PILE = "pile"
        const val MAP = "map"
        const val OTHER = "other"
    }
}

/** What the phone can ask for. [RemoteTonightAction.wire] names are lower-case and stable. */
enum class RemoteTonightAction(val wire: String) {
    /** Opens the Tonight picker from anywhere (the phone's "What to watch?" button); handled by the PC, not the host. */
    Open("open"),
    OpenQuestions("questions"), OpenCards("cards"),
    Left("left"), Right("right"), Either("either"), Back("back"),
    /** The TV's own keys, for the modes that mirror them (Knockout, Coin Toss, Channel Flick, Ends By, The Pile, The Map). */
    Up("up"), Down("down"), Ok("ok"),
    /** Opens mode n (from 1, the launcher's tile order). */
    Mode("mode"),
    /** The Pile: lets title n go for good. */
    LetGo("letgo"),
    /** Plays card n (from 1); in a list mode, row n. */
    Play("play"),
    /** Opens card n's page. */
    Details("details"),
    Reshuffle("reshuffle");

    companion object {
        fun fromWire(wire: String): RemoteTonightAction? = entries.firstOrNull { it.wire == wire.lowercase() }
    }
}

object RemoteTonight {
    private val _current = MutableStateFlow<RemoteTonightScreen?>(null)

    /** What the Tonight picker shows; null while it is not on screen. */
    val current: StateFlow<RemoteTonightScreen?> = _current.asStateFlow()

    private var owner: Any? = null
    private var handler: ((RemoteTonightAction, Int) -> Unit)? = null

    fun publish(owner: Any, screen: RemoteTonightScreen, onAction: (RemoteTonightAction, Int) -> Unit) {
        this.owner = owner
        handler = onAction
        _current.value = screen
    }

    fun clear(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        handler = null
        _current.value = null
    }

    /** Runs a phone's tap; call on the UI thread. False when Tonight is not on screen. */
    fun act(action: RemoteTonightAction, number: Int): Boolean {
        val run = handler ?: return false
        run(action, number)
        return true
    }
}
