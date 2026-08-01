package com.nuvio.app.features.streams

enum class StreamAutoPlayMode {
    MANUAL,
    FIRST_STREAM,
    REGEX_MATCH,

    /**
     * Play the highest-scoring stream, per the user's [StreamScoreProfile].
     *
     * Supersedes [REGEX_MATCH] for anyone who turns scoring on — same intent ("pick the release I
     * actually want"), without authoring a pattern. Falls back to [FIRST_STREAM] behaviour when the
     * profile is disabled or not applied to first-stream selection, because ranking with an inactive
     * profile leaves the candidate order untouched.
     */
    SCORED,
}

enum class StreamAutoPlaySource {
    ALL_SOURCES,
    INSTALLED_ADDONS_ONLY,
    ENABLED_PLUGINS_ONLY,
}
