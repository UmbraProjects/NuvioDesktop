package com.nuvio.app.features.player

/**
 * Track kinds a viewer can rule out wholesale, matched from the track's own name rather than from
 * its language.
 *
 * Language preferences answer "which language", and are useless against the second English subtitle
 * track that only captions the on-screen signs, or the second English audio track that is the
 * director talking over the film. Those tracks are named, not flagged, so the name is what this
 * matches — and it matches it as whole words after punctuation is flattened, so "Signs & Songs",
 * "signs/songs" and "SIGNS_SONGS" all read the same.
 *
 * Rejection is a display *and* selection concern: a rejected track is hidden from the player's track
 * lists and is never chosen automatically. It is never applied to a track the viewer picked by hand
 * — the filters below always keep the current selection, so turning an option on cannot yank the
 * track out from under someone mid-episode.
 */
enum class SubtitleRejectKeyword(val storageValue: String, val phrases: List<String>) {
    /** Sign/caption-only tracks — on-screen text translated for an otherwise dubbed viewing. */
    SIGNS("signs", listOf("signs", "sign", "s s")),

    /** Song/lyric-only tracks, typically shipped alongside a signs track on anime releases. */
    SONGS("songs", listOf("songs", "song", "lyrics")),

    /** Timed-lyric karaoke tracks, usually styled effects over the opening and ending. */
    KARAOKE("karaoke", listOf("karaoke", "kfx")),

    /**
     * Forced tracks. The name is only half the signal here — see [inferForcedSubtitleTrack], which
     * also reads the container's own forced flag, and which this reuses.
     */
    FORCED("forced", listOf("forced", "force")),
    ;

    companion object {
        fun fromStorage(value: String): SubtitleRejectKeyword? =
            entries.firstOrNull { it.storageValue.equals(value, ignoreCase = true) }
    }
}

enum class AudioRejectKeyword(val storageValue: String, val phrases: List<String>) {
    /** Director/cast commentary tracks. */
    COMMENTARY("commentary", listOf("commentary", "commentaries")),

    /** Audio description / descriptive audio narration tracks. */
    DESCRIPTIVE_AUDIO(
        "descriptive_audio",
        listOf("descriptive", "description", "descriptions", "described", "narration"),
    ),

    /** The same narration named for its audience rather than for what it is. */
    VISUALLY_IMPAIRED(
        "visually_impaired",
        listOf("visually impaired", "visual impaired", "vision impaired", "sight impaired", "impaired"),
    ),
    ;

    companion object {
        fun fromStorage(value: String): AudioRejectKeyword? =
            entries.firstOrNull { it.storageValue.equals(value, ignoreCase = true) }
    }
}

fun parseSubtitleRejectKeywords(values: Set<String>?): Set<SubtitleRejectKeyword> =
    values.orEmpty().mapNotNull(SubtitleRejectKeyword::fromStorage).toSet()

fun parseAudioRejectKeywords(values: Set<String>?): Set<AudioRejectKeyword> =
    values.orEmpty().mapNotNull(AudioRejectKeyword::fromStorage).toSet()

internal fun Set<SubtitleRejectKeyword>.rejectsSubtitleTrack(track: SubtitleTrack): Boolean {
    if (isEmpty()) return false
    // The container's forced flag is authoritative and needs no name to say so, so it is checked
    // before the text — a forced track with a plain "English" label is still a forced track.
    if (SubtitleRejectKeyword.FORCED in this &&
        inferForcedSubtitleTrack(
            label = track.label,
            language = track.language,
            trackId = track.id,
            hasForcedSelectionFlag = track.isForced,
        )
    ) {
        return true
    }
    return matchesAny(normalizeTrackText(track.label, track.language, track.id))
}

/**
 * The addon's own name is deliberately not part of the matched text: it describes the source, not
 * the track, and an addon that happens to be called "Signs" would otherwise have every one of its
 * subtitles rejected. Its display name already carries the track's description.
 */
internal fun Set<SubtitleRejectKeyword>.rejectsAddonSubtitle(subtitle: AddonSubtitle): Boolean {
    if (isEmpty()) return false
    return matchesAny(normalizeTrackText(subtitle.display, subtitle.language))
}

internal fun Set<AudioRejectKeyword>.rejectsAudioTrack(track: AudioTrack): Boolean {
    if (isEmpty()) return false
    val text = normalizeTrackText(track.label, track.language, track.id)
    return any { keyword -> keyword.phrases.any { phrase -> text.contains(" $phrase ") } }
}

private fun Set<SubtitleRejectKeyword>.matchesAny(normalizedText: String): Boolean =
    any { keyword -> keyword.phrases.any { phrase -> normalizedText.contains(" $phrase ") } }

/**
 * Lower-cases the track's name fields and reduces every run of punctuation to a single space, then
 * pads the result so a phrase can be searched for with its own spaces around it. That padding is
 * what makes the match whole-word: " ad " cannot hit "adaptive", and " sign " cannot hit "design".
 */
private fun normalizeTrackText(vararg values: String?): String {
    val builder = StringBuilder()
    builder.append(' ')
    var lastWasSpace = true
    for (value in values) {
        if (value.isNullOrEmpty()) continue
        for (char in value) {
            if (char.isLetterOrDigit()) {
                builder.append(char.lowercaseChar())
                lastWasSpace = false
            } else if (!lastWasSpace) {
                builder.append(' ')
                lastWasSpace = true
            }
        }
        if (!lastWasSpace) {
            builder.append(' ')
            lastWasSpace = true
        }
    }
    return builder.toString()
}
