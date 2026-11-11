package com.nuvio.app.features.music.visualiser
// FORK UI (Spyder): Nick's album page lenses, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

/*
 * fork:sonic-map - a song's "sound print": the few numbers the album page's Sonic map places it by, worked out once from its
 * track map (which Nuvio builds while the song plays). Kept apart from the map files, which are dropped after 400 songs.
 */

/** A song's sound print. [loudness], [brightness] and [lowEnd] run 0..1; [hitRate] is hits a second; [bpm] null without a steady beat. */
internal data class SoundPrint(
    val loudness: Float,
    val hitRate: Float,
    val bpm: Float?,
    val brightness: Float,
    val lowEnd: Float,
) {
    val steady: Boolean get() = bpm != null
}

/** The print of a [map]; null when too little of it is analysed (under 20 seconds). */
internal fun soundPrint(map: TrackMap): SoundPrint? {
    val n = map.frames
    if (n < 20_000 / MAP_FRAME_MS) return null
    var level = 0.0
    var hits = 0
    val bands = DoubleArray(MAP_BANDS)
    for (f in 0 until n) {
        level += map.level(f)
        if (map.hits(f, Onset.KICK) || map.hits(f, Onset.SNARE) || map.hits(f, Onset.HATS)) hits++
        for (b in 0 until MAP_BANDS) bands[b] += map.band(f, b)
    }
    val total = bands.sum().takeIf { it > 1e-9 } ?: return null
    var centre = 0.0
    for (b in 0 until MAP_BANDS) centre += b * bands[b]
    return SoundPrint(
        loudness = (level / n).toFloat(),
        hitRate = (hits / (n * MAP_FRAME_MS / 1000.0)).toFloat(),
        bpm = map.beat?.bpm?.toFloat(),
        brightness = (centre / total / (MAP_BANDS - 1)).toFloat(),
        lowEnd = ((bands[0] + bands[1] + bands[2] + bands[3]) / total).toFloat(),
    )
}

/** The stored form: five numbers split by "|", the tempo empty when there is none. */
internal fun encodeSoundPrint(p: SoundPrint): String =
    listOf(p.loudness, p.hitRate, p.bpm, p.brightness, p.lowEnd).joinToString("|") { it?.toString().orEmpty() }

internal fun decodeSoundPrint(raw: String?): SoundPrint? {
    val f = raw?.split('|')?.takeIf { it.size == 5 } ?: return null
    return SoundPrint(
        loudness = f[0].toFloatOrNull() ?: return null,
        hitRate = f[1].toFloatOrNull() ?: return null,
        bpm = f[2].toFloatOrNull(),
        brightness = f[3].toFloatOrNull() ?: return null,
        lowEnd = f[4].toFloatOrNull() ?: return null,
    )
}
