package com.nuvio.app.features.music

/** fork:loudness - true when a measurement is kept for [loudnessKey] (`yt:<id>`, `addon:<id>`), so nothing need be looked up. */
internal expect fun hasKeptLoudness(loudnessKey: String): Boolean
