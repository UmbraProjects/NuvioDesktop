package com.nuvio.app.features.games

import kotlinx.serialization.Serializable

/**
 * One game in the library. Ported from the standalone Umbra launcher, and the on-disk shape is
 * kept byte-compatible with it so an existing `library.json` carries straight over.
 */
@Serializable
data class GameEntry(
    val id: String,
    val igdbId: Long? = null,
    val title: String,
    val executablePath: String? = null,
    val arguments: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val coverUrl: String? = null,
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
    val summary: String? = null,
    val releaseDateEpochSeconds: Long? = null,
    val genres: List<String> = emptyList(),
    val platforms: List<String> = emptyList(),
    val rating: Double? = null,
    val logoLookupCompleted: Boolean = false,
    val logoLookupVersion: Int = 0,
    /**
     * The logo was picked by hand in the editor, so the background artwork top-up must leave it
     * alone. Version arithmetic alone cannot express this: the automatic path also reaches the
     * highest lookup version, so without an explicit flag a hand-picked logo is indistinguishable
     * from one SteamGridDB chose and gets replaced the next time the top-up runs.
     */
    val logoManuallyChosen: Boolean = false,
    val addedAtEpochMillis: Long = System.currentTimeMillis(),
)

/**
 * A game is "installed" purely because it points at something runnable. Tracked-but-unreleased
 * entries are the whole reason the library has a second row, so do not widen this to mean
 * anything else.
 */
val GameEntry.isInstalled: Boolean
    get() = !executablePath.isNullOrBlank()

/**
 * The `settings` block of `library.json`.
 *
 * The credentials and presentation choice moved into Nuvio's own settings storage when game mode
 * was merged in; they are still read here so a pre-merge Umbra library can be migrated once. Only
 * [lastExecutableDirectory] is still written — it is per-library state (where the user last picked
 * an executable from), not something a settings page should show.
 */
@Serializable
data class GameLibraryFileSettings(
    val igdbClientId: String = "",
    val igdbClientSecret: String = "",
    val steamGridDbApiKey: String = "",
    val lastExecutableDirectory: String = "",
    val backdropShelfStyle: String = "",
)

@Serializable
data class GameLibraryData(
    val games: List<GameEntry> = emptyList(),
    val settings: GameLibraryFileSettings = GameLibraryFileSettings(),
)

data class IgdbGame(
    val id: Long,
    val title: String,
    val coverUrl: String?,
    val backdropUrl: String?,
    val logoUrl: String?,
    val summary: String?,
    val releaseDateEpochSeconds: Long?,
    val genres: List<String>,
    val platforms: List<String>,
    val rating: Double?,
    val backdrops: List<ArtworkCandidate> = emptyList(),
)

data class ArtworkCandidate(
    val url: String,
    val width: Int,
    val height: Int,
    val source: ArtworkSource,
) {
    val resolutionLabel: String
        get() = if (width > 0 && height > 0) "$width × $height" else "Resolution unknown"
}

data class LogoCandidate(
    val url: String,
    val width: Int,
    val height: Int,
    val score: Int,
    val language: String? = null,
    val style: String? = null,
) {
    val resolutionLabel: String
        get() = if (width > 0 && height > 0) "$width × $height" else "Resolution unknown"

    val languageLabel: String?
        get() = language
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { code -> if (code.length <= 3) code.uppercase() else code }
}

/**
 * Whether a hand-picked logo survives this save.
 *
 * It does when the user just picked one, and when a previously hand-picked logo is still the one
 * being saved. Re-matching the game against a different IGDB entry replaces the logo URL, which
 * ends the manual choice and lets the top-up fetch artwork for the newly matched game.
 */
internal fun logoRemainsManuallyChosen(
    pickedNow: Boolean,
    previouslyManual: Boolean,
    previousLogoUrl: String?,
    logoUrlToSave: String?,
): Boolean = pickedNow ||
    (previouslyManual && !previousLogoUrl.isNullOrBlank() && previousLogoUrl == logoUrlToSave)

/** The logo the artwork top-up should store, given whatever it just fetched. */
internal fun GameEntry.refreshedLogoUrl(fetched: String?): String? =
    if (logoManuallyChosen) logoUrl else fetched ?: logoUrl

enum class ArtworkSource(val label: String) {
    ARTWORK("Artwork"),
    SCREENSHOT("Screenshot"),
    STEAMGRIDDB("SteamGridDB"),
}
