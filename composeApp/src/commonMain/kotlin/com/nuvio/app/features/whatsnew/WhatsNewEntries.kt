package com.nuvio.app.features.whatsnew

/**
 * Every feature worth a card, oldest first. Adding an entry is part of shipping a feature: give it
 * the next number (one more than the highest here) and a pitch a person would stop for, in one
 * sentence. This list is plain Kotlin rather than string resources on purpose: the resources are
 * shared with upstream in many languages and conflict on every merge, and this file is the fork's own.
 *
 * Numbers only go up and are never reused: the app remembers the highest one it has shown.
 *
 * An action gives the card a button. `TurnOn` takes a key from `whatsNewToggles` (WhatsNewActions.kt); `OpenSettings`
 * takes a `SettingsPage` name. A test checks both against what exists.
 */
internal val whatsNewEntries: List<WhatsNewEntry> = listOf(
    WhatsNewEntry(
        number = 1,
        title = "Previously on",
        pitch = "Before an episode, short cards recap the last one you watched, then a quick quiz.",
        action = WhatsNewAction.TurnOn(WHATS_NEW_KEY_PREVIOUSLY_ON),
    ),
    WhatsNewEntry(
        number = 2,
        title = "Post-show cards",
        pitch = "When a film's credits start, it shrinks into a corner and cards explain the ending.",
        action = WhatsNewAction.TurnOn(WHATS_NEW_KEY_POST_SHOW),
    ),
    WhatsNewEntry(
        number = 3,
        title = "Word-by-word lyrics",
        pitch = "Lyrics sweep word by word, like karaoke, when a song has word timing. More sources can be switched on in Music settings.",
        weight = WhatsNewWeight.Spotlight,
        action = WhatsNewAction.OpenSettings("Music"),
    ),
    WhatsNewEntry(
        number = 4,
        title = "Theme songs",
        pitch = "A film's or show's theme song can play quietly behind its details page.",
        action = WhatsNewAction.TurnOn(WHATS_NEW_KEY_THEME_SONG_DETAILS),
    ),
    WhatsNewEntry(
        number = 5,
        title = "Countdown in plain time",
        pitch = "Upcoming episodes can count down as \"In 5 hours\" or \"In 2 days\" instead of a date.",
        action = WhatsNewAction.TurnOn(WHATS_NEW_KEY_RELATIVE_COUNTDOWN),
    ),
    WhatsNewEntry(
        number = 6,
        title = "Music playlists",
        pitch = "Build playlists from songs on any album, or import one from YouTube Music, Spotify or Apple Music.",
    ),
    WhatsNewEntry(
        number = 7,
        title = "Your music addon",
        pitch = "Paste an addon link in Music settings: albums can play from its lossless copy, and artist pages list releases YouTube Music misses.",
        action = WhatsNewAction.OpenSettings("Music"),
    ),
)
