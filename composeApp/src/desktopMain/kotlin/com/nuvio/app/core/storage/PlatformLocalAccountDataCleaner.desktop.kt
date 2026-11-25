package com.nuvio.app.core.storage

internal actual object PlatformLocalAccountDataCleaner {
    // Sign-out on desktop must not nuke machine-local preferences that have nothing to do
    // with the signed-in Nuvio account — this fork's desktop-only settings (playback tuning,
    // Adaptive Hero/TV Mode, hero badges, games, local library, updater, screensaver) and
    // third-party integrations (TVDB, SIMKL) never sync to the account in the first place, so
    // wiping them just forces the user to redo them for no benefit.
    //
    // So this names what to delete rather than what to keep. It used to be a keep-list over a walk
    // of the whole data directory, which also took the game library, Badges/, the open log file,
    // instance.lock and every machine-local store nobody had thought to add to the list.
    //
    // The list mirrors LocalAccountDataCleaner.wipe(): a store belongs here when the repository
    // that owns it is reset in memory there (or reads the store lazily, like the Continue Watching
    // enrichment cache), so memory and disk agree after sign-out. Stores whose repository keeps
    // its state in memory (the synchronization permissions, player and home-row settings) stay on
    // disk for the same reason.
    internal val accountStoreNames = setOf(
        "nuvio_auth",
        "nuvio_profiles",
        "nuvio_profile_pin_cache",
        "nuvio_addons",
        "nuvio_plugins",
        "nuvio_meta_screen_settings",
        "nuvio_library",
        "nuvio_watch_progress",
        "nuvio_watched",
        "nuvio_continue_watching_preferences",
        "nuvio_continue_watching_enrichment",
        "nuvio_resume_prompt",
        "nuvio_episode_release_notifications",
        "nuvio_collection_mobile_settings",
        "nuvio_collections",
        "nuvio_playlists",
        "nuvio_theme_settings",
        "nuvio_poster_card_style",
        "nuvio_trakt_auth",
        "nuvio_trakt_settings",
        "nuvio_trakt_library",
        "nuvio_trakt_comments",
        "nuvio_stream_badge_settings",
        "nuvio_search_history",
        "torrent_settings",
    )

    actual fun wipe() {
        DesktopStorage.wipe(accountStoreNames)
        com.nuvio.app.features.watched.WatchedStorage.clearAll()
    }
}
