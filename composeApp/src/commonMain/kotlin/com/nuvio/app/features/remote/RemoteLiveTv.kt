package com.nuvio.app.features.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * fork:phone-remote live-tv - Live TV's stages as the phone mirrors them (Nov 14). The TV publishes the stage on
 * screen: its heading, the strip of stages, the rows it offers and its buttons. A tap on the phone comes back as the
 * row's group and index and does exactly what OK on that row does on the TV.
 */

/** One row (or card) of a stage. [group] and [index] are what the phone sends back; "-" rows cannot be tapped. */
data class RemoteLiveTvItem(
    val group: String,
    val index: Int,
    /** A small label: "SAFE", "ON NOW", "21:00", "7". */
    val slot: String,
    val title: String,
    /** "BBC One · 12 min in · ends 21:00". */
    val sub: String,
    val picture: String?,
    val live: Boolean = false,
    /** How far in, 0..1000; -1 when not on. */
    val progress: Int = -1,
    /** What a tap does: "Watch", "Tune in at 21:00", "Play from the start". */
    val action: String? = null,
)

data class RemoteLiveTvButton(val group: String, val index: Int, val label: String, val accent: Boolean = false)

data class RemoteLiveTvScreen(
    /** The stage's name ([com.nuvio.app.features.iptv.IptvStage]). */
    val stage: String,
    val heading: String,
    val sub: String,
    /** The strip: (name, label), in order. */
    val stages: List<Pair<String, String>>,
    val items: List<RemoteLiveTvItem>,
    val buttons: List<RemoteLiveTvButton> = emptyList(),
    /** What to say when there are no rows. */
    val empty: String? = null,
)

/** A phone's tap: a stage on the strip, a row or button, or All channels. */
sealed interface RemoteLiveTvDo {
    data class Stage(val name: String) : RemoteLiveTvDo
    data class Item(val group: String, val index: Int) : RemoteLiveTvDo
    data object All : RemoteLiveTvDo

    companion object {
        /** `stage <name>`, `item <group> <index>` or `all` (the words after LIVETV_DO). */
        fun parse(words: List<String>): RemoteLiveTvDo? = when (words.getOrNull(0)?.lowercase()) {
            "stage" -> words.getOrNull(1)?.takeIf { it.isNotBlank() && it.length <= 40 }?.let(::Stage)
            "item" -> {
                val group = words.getOrNull(1)?.takeIf { it.isNotBlank() && it != "-" && it.length <= 40 }
                val index = words.getOrNull(2)?.toIntOrNull()?.takeIf { it in 0..999 }
                if (group != null && index != null) Item(group, index) else null
            }
            "all" -> All
            else -> null
        }
    }
}

object RemoteLiveTv {
    private val _current = MutableStateFlow<RemoteLiveTvScreen?>(null)

    /** The Live TV stage on screen; null while there is none. */
    val current: StateFlow<RemoteLiveTvScreen?> = _current.asStateFlow()

    private var owner: Any? = null
    private var handler: ((RemoteLiveTvDo) -> Unit)? = null

    fun publish(owner: Any, screen: RemoteLiveTvScreen, onAction: (RemoteLiveTvDo) -> Unit) {
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

    /** Runs a phone's tap; call on the UI thread. False when no stage is on screen. */
    fun act(action: RemoteLiveTvDo): Boolean {
        val run = handler ?: return false
        run(action)
        return true
    }
}
