package com.nuvio.app.features.music

import com.nuvio.app.features.trailer.TrailerPlaybackSource

/** fork:loudness - true when a measurement is kept for [loudnessKey] (`yt:<id>`, `addon:<id>`), so nothing need be looked up. */
internal expect fun hasKeptLoudness(loudnessKey: String): Boolean

/**
 * fork:loudness - starts measuring [source] (the next track in the queue) in the background unless it is measured
 * already, so it starts at the right level and its gain never has to move while it plays.
 */
internal expect fun measureLoudnessAhead(source: TrailerPlaybackSource)
