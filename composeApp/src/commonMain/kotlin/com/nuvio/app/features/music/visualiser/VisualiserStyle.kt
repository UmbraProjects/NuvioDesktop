package com.nuvio.app.features.music.visualiser

import kotlinx.serialization.Serializable

/** fork:visualiser - what the Stage's right-hand glass box shows. Saved in the Music settings. */
@Serializable
enum class StageBoxView { Notes, Visualiser }

/** fork:visualiser - the visualiser's styles, in the order Left and Right step through them. Saved in the Music settings. */
@Serializable
enum class VisualiserStyle(val title: String) {
    SongMap("Song Map"),
    Halo("Halo"),
    TwinVu("Twin VU"),
    BeatGrid("Beat Grid"),
}
