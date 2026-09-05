package com.nuvio.app.features.games

/**
 * User-facing configuration for game mode (Umbra), stored alongside every other Nuvio setting
 * rather than inside the game library file.
 *
 * The library itself — the games, their artwork and the last browsed executable folder — stays in
 * its own JSON document, because it is content rather than configuration. These four values are
 * the ones a settings page has any business editing, so they live where the rest of the app's
 * settings live and are edited through the ordinary settings rows.
 */
data class GameLibrarySettings(
    val igdbClientId: String = "",
    val igdbClientSecret: String = "",
    val steamGridDbApiKey: String = "",
    val backdropStyle: GameBackdropStyle = GameBackdropStyle.BlackShelf,
) {
    /** IGDB needs both halves of a Twitch confidential application; one alone buys nothing. */
    val igdbConfigured: Boolean
        get() = igdbClientId.isNotBlank() && igdbClientSecret.isNotBlank()

    val steamGridDbConfigured: Boolean
        get() = steamGridDbApiKey.isNotBlank()
}

/** How far the artwork behind the game library extends before the cover rail takes over. */
enum class GameBackdropStyle {
    /** Artwork is confined above the rail and fades to black — the default, TV-style presentation. */
    BlackShelf,

    /** Artwork fills the window and the rail floats over it. */
    FullBackdrop,
}
