package com.nuvio.app.features.trailer

data class TrailerPlaybackSource(
    val videoUrl: String,
    val audioUrl: String? = null,
    /**
     * Another way to play the same trailer, tried when this one fails to open (e.g. YouTube answers
     * 403 for the adaptive URLs). Chains: adaptive -> HLS manifest -> progressive.
     */
    val fallback: TrailerPlaybackSource? = null,
    /**
     * Sharpest rendition this trailer offers, measured on its short side (0 when unknown), used to
     * compare uploads of the same trailer. It is the best the upload has, not necessarily the one
     * chosen for playback.
     */
    val heightPx: Int = 0,
    /** Taller than it is wide (a Short or a phone upload); never picked over a landscape upload. */
    val portrait: Boolean = false,
    /**
     * False when the streams came from a client whose media URLs are cut off (HTTP 403 on the
     * open-ended range request, or nothing past about a minute) rather than the unrestricted one.
     * That happens when the preferred client's request fails (typically throttling during a burst
     * of lookups). Such a source reports a height it cannot actually deliver, so it must not win a
     * comparison against one that can play.
     */
    val reliable: Boolean = true,
    /**
     * fork:music - false for a source from a restricted client ([reliable] false when it was resolved) that a probe
     * then showed plays from the start. Its URLs still refuse a range request from the middle, so it cannot be opened
     * part way in: mpv waits on that seek for good. Kept apart from [reliable], which the probe sets.
     */
    val openableMidway: Boolean = true,
    /**
     * How long the upload is, when the caller knows it (the theme-song search does), else 0. Lets a
     * looping player tell a stream that was cut off early from one that played to its real end.
     */
    val durationMs: Long = 0L,
    /**
     * fork:innertubex — request headers the media fetch must carry, for a stream whose resolver
     * says it is only served to a particular client identity. Empty for everything the in-app
     * extractor finds.
     */
    val headers: Map<String, String> = emptyMap(),
    /**
     * fork:alternate-upload — true for an ordinary upload standing in for a track's album audio. Uploads are
     * not mastered to the album's level and play noticeably quieter, so the player evens the level out.
     */
    val evenOutLoudness: Boolean = false,
    /**
     * fork:loudness - a stable identity for the track behind this stream ("yt:<video id>", "addon:<track id>"), since the
     * URL itself expires. Music keeps the loudness it measured under it, so the next play starts at the right level.
     */
    val loudnessKey: String? = null,
    /**
     * fork:youtube-languages - the video's other language tracks (dubs) beside [audioUrl], for a caller
     * that asked for them ([com.nuvio.app.features.music.YouTubeCaller.allAudioTracks]); empty otherwise.
     */
    val extraAudioTracks: List<TrailerAudioTrack> = emptyList(),
)

/** One more language of a video's audio: [language] a code ("es"), [title] as YouTube names it. */
data class TrailerAudioTrack(val url: String, val language: String, val title: String)

/**
 * fork:youtube-languages - [audioUrl] with [extra] tracks after it, as the player's audio source
 * string carries them: one per line, "language<TAB>title<TAB>url" (the desktop player bridge opens
 * them as further audio tracks). Just [audioUrl] when there are none.
 */
fun encodePlayerAudioSources(audioUrl: String?, extra: List<TrailerAudioTrack>): String? {
    if (audioUrl == null || extra.isEmpty()) return audioUrl
    return buildString {
        append(audioUrl)
        extra.forEach { track ->
            append('\n').append(track.language.filter { it.isLetterOrDigit() || it == '-' })
            append('\t').append(track.title.replace('\t', ' ').replace('\n', ' '))
            append('\t').append(track.url)
        }
    }
}

/** The first audio URL of a source string [encodePlayerAudioSources] built (the original track). */
fun primaryPlayerAudioUrl(sources: String?): String? = sources?.substringBefore('\n')
