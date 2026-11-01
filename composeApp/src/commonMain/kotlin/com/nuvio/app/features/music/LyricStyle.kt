package com.nuvio.app.features.music

import kotlinx.serialization.Serializable

/** How the Now Playing stage sets its lyrics. Saved in the Music settings. */
@Serializable
enum class LyricStyle(val label: String) {
    /** The list that scrolls, the sung line lit word by word. */
    Scroll("scroll"),

    /** One line at a time in a marker face, words landing as they are sung. */
    Calm("calm"),

    /** One line at a time as giant stacked chunks, a new face, colour and entrance every line. */
    Loud("loud");

    /** The next style in the order the top-bar chip cycles through. */
    fun next(): LyricStyle = entries[(ordinal + 1) % entries.size]
}
