package com.nuvio.app.features.preroll

/*
 * One loudness for everything that is not the film.
 *
 * The film is the reference: it plays untouched, at whatever level the viewer has set the volume to. Trailers,
 * the Home hero, the pre-show, theme songs and music are all brought to the same integrated loudness a film's
 * dialogue sits at, so nobody has to reach for the remote when one of them starts.
 *
 * Before this, trailers went through `dynaudnorm`, which pulls peaks up towards full scale (consistent with each
 * other, but far louder than a film), and music played as mastered (about -9 to -14 LUFS).
 *
 * Tune [LOUDNESS_TARGET_LUFS] here if the trailers still sound a touch loud (lower it) or quiet (raise it) next to a
 * film; every user of it follows.
 */

/** Integrated loudness in LUFS. EBU R128 broadcast is -23; film dialogue downmixed to stereo sits around -24. */
internal const val LOUDNESS_TARGET_LUFS = -24

/** Loudness range in LU for trailers and pre-show: tight, so a jump scare or a trailer's finale cannot spike. */
internal const val TRAILER_LOUDNESS_RANGE_LU = 7

/** Music keeps more of its own dynamics. */
internal const val MUSIC_LOUDNESS_RANGE_LU = 11

/** True-peak ceiling in dBTP, under full scale so the limiter inside `loudnorm` has room to work. */
internal const val LOUDNESS_TRUE_PEAK_DBTP = -2

/** The bare libavfilter expression (no `lavfi=[...]` wrapper), so it can sit in a chain with other filters. */
internal fun loudnessFilterExpression(rangeLu: Int = TRAILER_LOUDNESS_RANGE_LU): String =
    "loudnorm=I=$LOUDNESS_TARGET_LUFS:LRA=$rangeLu:TP=$LOUDNESS_TRUE_PEAK_DBTP"

/** The value for mpv's `af` option or property: [expressions] as one libavfilter chain. */
internal fun mpvAudioFilterValue(vararg expressions: String): String =
    "lavfi=[${expressions.joinToString(",")}]"
