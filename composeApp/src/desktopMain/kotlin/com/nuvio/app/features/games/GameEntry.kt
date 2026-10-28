package com.nuvio.app.features.games

import kotlinx.serialization.Serializable
import nuvio.composeapp.generated.resources.*
import com.nuvio.app.core.i18n.localizedString

/**
 * One game in the library. The on-disk shape is kept byte-compatible with the standalone
 * launcher this was ported from, so an existing `library.json` carries straight over.
 */
@Serializable
data class GameEntry(
    val id: String,
    val igdbId: Long? = null,
    /** Set only while Steam is the metadata source; kept afterwards so a switch back is free. */
    val steamAppId: Long? = null,
    /** Which provider the stored metadata came from. Null predates the setting and means IGDB. */
    val metadataSource: GameMetadataSource? = null,
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
     * The logo was picked by hand in the editor (or attached by a folder import, which counts as
     * a choice for the same reason), so the background artwork top-up must leave it alone.
     * Version arithmetic alone cannot express this: the automatic path also reaches the
     * highest lookup version, so without an explicit flag a hand-picked logo is indistinguishable
     * from one SteamGridDB chose and gets replaced the next time the top-up runs.
     */
    val logoManuallyChosen: Boolean = false,
    val addedAtEpochMillis: Long = System.currentTimeMillis(),
    /**
     * The shelf row the user filed this game under, or null for the automatic placement: the
     * Installed row when it has something to launch, Uninstalled otherwise. Only an explicit
     * move sets this, so adding an executable to a tracked game still carries it across rows
     * until the user pins it somewhere. A dangling id (its row was deleted) counts as null.
     */
    val rowId: String? = null,
)

/**
 * One shelf in the game library. The two default rows carry a [defaultBucket] and can be renamed
 * and recoloured but never removed: they are where every game lands until it is moved by hand.
 */
@Serializable
data class GameRow(
    val id: String,
    val name: String,
    /** Packed ARGB, drawn as this row's dot in the row-jump strip. Null draws the neutral dot. */
    val color: Long? = null,
    val defaultBucket: GameRowBucket? = null,
)

@Serializable
enum class GameRowBucket {
    Installed,
    Uninstalled,
}

const val GameInstalledRowId = "installed"
const val GameUninstalledRowId = "uninstalled"

/**
 * The saved rows with both default rows guaranteed present, in order.
 *
 * A library written before rows existed has none, and gets Installed then Uninstalled; one that
 * has both keeps them exactly where the user put them. Keyed on the bucket rather than the name so
 * a renamed default row is still recognised.
 */
fun ensureDefaultGameRows(rows: List<GameRow>): List<GameRow> {
    val missing = GameRowBucket.entries.filter { bucket -> rows.none { it.defaultBucket == bucket } }
    if (missing.isEmpty()) return rows
    return rows + missing.map { bucket ->
        when (bucket) {
            GameRowBucket.Installed -> GameRow(GameInstalledRowId, localizedString(Res.string.games_row_installed), defaultBucket = bucket)
            GameRowBucket.Uninstalled -> GameRow(GameUninstalledRowId, localizedString(Res.string.games_row_uninstalled), defaultBucket = bucket)
        }
    }
}

/** The row this game is shown in: its pinned row when that still exists, else its bucket's row. */
fun GameEntry.effectiveRowId(rows: List<GameRow>): String {
    rowId?.let { pinned -> if (rows.any { it.id == pinned }) return pinned }
    val bucket = if (isInstalled) GameRowBucket.Installed else GameRowBucket.Uninstalled
    return rows.firstOrNull { it.defaultBucket == bucket }?.id
        ?: rows.firstOrNull()?.id
        ?: GameInstalledRowId
}

/** A row and the games filed in it, in library order. */
data class GameShelfRow(
    val row: GameRow,
    val games: List<GameEntry>,
)

/**
 * The shelves the library shows, in row order. Empty rows are left out: the shelf only ever
 * displays one row at a time, and stepping through a blank one to reach the next is a wasted
 * press. They still appear in settings, where a count of zero is the useful information.
 */
fun gameShelfRows(games: List<GameEntry>, rows: List<GameRow>): List<GameShelfRow> {
    val effectiveRows = ensureDefaultGameRows(rows)
    val byRow = games.groupBy { it.effectiveRowId(effectiveRows) }
    return effectiveRows.mapNotNull { row ->
        byRow[row.id]?.takeIf { it.isNotEmpty() }?.let { GameShelfRow(row, it) }
    }
}

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
 * was merged in; they are still read here so a pre-merge library can be migrated once. Only
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
    /** Shelf rows in display order. Empty in a pre-rows file; see [ensureDefaultGameRows]. */
    val rows: List<GameRow> = emptyList(),
)

/**
 * A matched game, from whichever provider is supplying metadata.
 *
 * [id] is that provider's id — an IGDB game id or a Steam app id — and [source] says which, because
 * the two are stored in different fields on [GameEntry] and are not interchangeable.
 */
data class GameMetadata(
    val id: Long,
    val source: GameMetadataSource,
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
        get() = if (width > 0 && height > 0) "$width × $height" else localizedString(Res.string.games_artwork_resolution_unknown)
}

data class LogoCandidate(
    val url: String,
    val width: Int,
    val height: Int,
    val score: Int,
    val language: String? = null,
    val style: String? = null,
    val source: ArtworkSource = ArtworkSource.STEAMGRIDDB,
) {
    val resolutionLabel: String
        get() = if (width > 0 && height > 0) "$width × $height" else localizedString(Res.string.games_artwork_resolution_unknown)

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

/**
 * Which provider an entry's stored metadata came from.
 *
 * Entries written before the setting existed carry no source and can only have come from IGDB,
 * which is what the null stands for — treating it as "unknown" would re-fetch the whole library on
 * the first launch after the upgrade.
 */
internal val GameEntry.effectiveMetadataSource: GameMetadataSource
    get() = metadataSource ?: GameMetadataSource.Igdb

/** Whether this entry is already matched to [metadata], in that provider's own id space. */
internal fun GameEntry.matchesMetadata(metadata: GameMetadata): Boolean = when (metadata.source) {
    GameMetadataSource.Igdb -> igdbId == metadata.id
    GameMetadataSource.Steam -> steamAppId == metadata.id
}

/** How the provider is named in the editor, which talks about it by name throughout. */
internal val GameMetadataSource.providerName: String
    get() = when (this) {
        GameMetadataSource.Igdb -> "IGDB"
        GameMetadataSource.Steam -> "Steam"
    }

/** What the Steam lookups need to identify this entry, when it has no app id stored yet. */
internal fun GameEntry.steamReference(): SteamGameReference =
    steamGameReference(title, executablePath, arguments)

/**
 * The reference with any shortcut followed, so a `.url` to `steam://rungameid/<id>` or a `.lnk`
 * into a `steamapps/common` folder identifies its app id the same way its target would.
 */
internal fun steamGameReference(title: String, executablePath: String?, arguments: List<String>): SteamGameReference =
    when (val target = executablePath?.takeIf(String::isNotBlank)?.let(::resolveGameLaunchTarget)) {
        is GameLaunchTarget.Executable -> SteamGameReference(title, target.path, target.arguments + arguments)
        is GameLaunchTarget.Protocol -> SteamGameReference(title, target.url, arguments)
        is GameLaunchTarget.ShellShortcut, null -> SteamGameReference(title, executablePath, arguments)
    }

/** The logo the artwork top-up should store, given whatever it just fetched. */
internal fun GameEntry.refreshedLogoUrl(fetched: String?): String? =
    if (logoManuallyChosen) logoUrl else fetched ?: logoUrl

enum class ArtworkSource(val label: String) {
    ARTWORK("Artwork"),
    SCREENSHOT("Screenshot"),
    STEAMGRIDDB("SteamGridDB"),
    STEAM("Steam"),
}

/**
 * Titles compared for identity rather than display.
 *
 * Both artwork sources match on an exact title, and both databases punctuate differently from the
 * store they mirror — "Marvel's Spider-Man" against "Marvel s Spider Man". Stripping everything
 * that is not a letter or digit is what makes those the same game.
 */
internal fun String.normalizedGameTitle(): String =
    lowercase().filter(Char::isLetterOrDigit)
