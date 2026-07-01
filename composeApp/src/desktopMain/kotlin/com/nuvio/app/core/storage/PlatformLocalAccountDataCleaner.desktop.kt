package com.nuvio.app.core.storage

internal actual object PlatformLocalAccountDataCleaner {
    // Sign-out on desktop must not nuke machine-local preferences that have nothing to do
    // with the signed-in Nuvio account — this fork's desktop-only settings (playback tuning,
    // Adaptive Hero/TV Mode, hero badges) and third-party integrations (TVDB, SIMKL) never
    // sync to the account in the first place, so wiping them just forces the user to redo
    // them for no benefit. Only genuinely account-bound stores (watch history, library,
    // collections, addons, profiles, synced settings) get cleared here so a different Nuvio
    // account signing in on this machine doesn't inherit the previous account's synced data.
    private val preservedStoreNames = setOf(
        "nuvio_mdblist_ratings_cache",
        "nuvio_player_settings",
        "nuvio_home_catalog_settings",
        "nuvio_tvdb_settings",
        "nuvio_simkl_settings",
        "nuvio_simkl_auth",
        "nuvio_api_keys_onboarding",
    )

    actual fun wipe() {
        DesktopStorage.wipe(preservedStoreNames = preservedStoreNames)
    }
}
