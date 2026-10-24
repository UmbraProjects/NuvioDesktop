package com.nuvio.app.features.games

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Steam's store as a metadata provider — the keyless alternative to IGDB.
 *
 * Two undocumented store endpoints do the work: `storesearch` for the result list (one request for
 * a whole search, carrying the name, app id and capsule of each hit) and `appdetails` for the
 * matched game. Artwork comes from [SteamArtworkClient], so a game matched here gets Steam's own
 * cover, hero and clear logo without a SteamGridDB key either.
 *
 * **It only knows games Steam sells.** An Epic or GOG exclusive, a console or emulated title, or
 * anything from itch.io cannot be matched at all, and an entry that fails to match keeps whatever
 * it already had rather than being blanked. That is the whole trade against IGDB, and the settings
 * row says so before the user picks it.
 *
 * Two further gaps worth knowing, both from the endpoint rather than from this code:
 * - **Rating** is Metacritic's or nothing. IGDB scores almost everything; Metacritic covers
 *   reviewed releases, so most of a library shows no rating at all.
 * - **Release date** arrives as the store's display string ("17 Sep, 2020"), not a timestamp, and
 *   can legitimately be "Coming soon" or "Q4 2026" — see [parseSteamReleaseDate].
 */
class SteamMetadataClient(
    private val artwork: SteamArtworkClient = SteamArtworkClient(),
) : AutoCloseable {
    private val http = HttpClient(CIO) { expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Search results, from two requests.
     *
     * Deliberately shallow: `storesearch` returns a name, an app id and a capsule image, which is
     * all the result row draws, and fetching full details for twenty hits to fill a list the user
     * will click once would spend the endpoint's rate limit on nothing. [game] fills the rest in
     * when one is picked. The one addition is a single batched asset-manifest lookup for the whole
     * page, because the portrait cover a row draws cannot be guessed for a game filed under the
     * hashed layout — see [SteamArtworkClient] — and the manifest is cached for [game] anyway.
     */
    suspend fun search(query: String): List<GameMetadata> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        val response = http.get("https://store.steampowered.com/api/storesearch/") {
            url {
                parameters.append("term", term)
                parameters.append("cc", "us")
                parameters.append("l", "english")
            }
        }
        if (!response.status.isSuccess()) {
            throw SteamStoreException("Steam returned ${response.status.value} for that search.")
        }
        val body = runCatching { json.parseToJsonElement(response.bodyAsText()).jsonObject }
            .getOrElse { throw SteamStoreException("Steam returned something that was not a search result.") }
        val items = body["items"]?.jsonArray ?: return emptyList()
        val hits = items.mapNotNull { item ->
            val fields = item.jsonObject
            val appId = fields["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
            val name = fields["name"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            Triple(appId, name, fields)
        }
        val manifests = artwork.storeAssetsFor(hits.map { it.first })
        return hits.map { (appId, name, fields) ->
            GameMetadata(
                id = appId,
                source = GameMetadataSource.Steam,
                title = name,
                // The portrait cover from the manifest when the store lists it, else by convention
                // rather than by probe: this is a list the user scrolls past, and a missing one
                // costs a blank tile, not a wrong match.
                coverUrl = manifests[appId]?.url(STEAM_COVER_ASSETS.first().first().manifestKey)
                    ?: steamAssetUrls(appId, STEAM_COVER_ASSETS.first().first().fileName).first(),
                backdropUrl = null,
                logoUrl = null,
                summary = null,
                releaseDateEpochSeconds = null,
                genres = emptyList(),
                platforms = emptyList(),
                rating = fields["metascore"]?.jsonPrimitive?.content?.toDoubleOrNull(),
            )
        }
    }

    /** Everything known about one app: the store record, and the artwork probed alongside it. */
    suspend fun game(appId: Long): GameMetadata? = coroutineScope {
        val details = async { appDetails(appId) }
        val cover = async { artwork.coverFor(appId) }
        val heroes = async { artwork.heroesFor(appId) }
        val logo = async { artwork.logosFor(appId).firstOrNull()?.url }
        val record = details.await() ?: return@coroutineScope null
        val backdrops = heroes.await()
        record.copy(
            coverUrl = cover.await() ?: record.coverUrl,
            backdropUrl = backdrops.firstOrNull()?.url,
            logoUrl = logo.await(),
            backdrops = backdrops,
        )
    }

    private suspend fun appDetails(appId: Long): GameMetadata? {
        val response = http.get("https://store.steampowered.com/api/appdetails") {
            url {
                parameters.append("appids", appId.toString())
                parameters.append("l", "english")
            }
        }
        if (!response.status.isSuccess()) {
            throw SteamStoreException("Steam returned ${response.status.value} for app $appId.")
        }
        val root = runCatching { json.parseToJsonElement(response.bodyAsText()).jsonObject }
            .getOrElse { throw SteamStoreException("Steam returned something that was not app details.") }
        val entry = root[appId.toString()]?.jsonObject ?: return null
        // The endpoint answers 200 with success:false for an app it will not describe — a delisted
        // game, or one region-locked away from this machine.
        if (entry["success"]?.jsonPrimitive?.booleanOrNull != true) return null
        val data = entry["data"]?.jsonObject ?: return null
        return data.toGameMetadata(appId)
    }

    /** Closes only what this owns: [artwork] is shared with the pickers and outlives this client. */
    override fun close() = http.close()
}

class SteamStoreException(message: String) : Exception(message)

private fun JsonObject.toGameMetadata(appId: Long): GameMetadata = GameMetadata(
    id = appId,
    source = GameMetadataSource.Steam,
    title = get("name")?.jsonPrimitive?.content.orEmpty(),
    coverUrl = null,
    backdropUrl = null,
    logoUrl = null,
    // The short description, not the detailed one: `detailed_description` is store HTML, complete
    // with embedded video and layout markup, and the hero draws plain text.
    summary = get("short_description")?.jsonPrimitive?.content?.takeIf(String::isNotBlank),
    releaseDateEpochSeconds = parseSteamReleaseDate(
        get("release_date")?.jsonObject?.get("date")?.jsonPrimitive?.content,
    ),
    genres = get("genres")?.jsonArray
        ?.mapNotNull { it.jsonObject["description"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank) }
        ?.distinct()
        .orEmpty(),
    platforms = steamPlatformNames(get("platforms")?.jsonObject),
    rating = get("metacritic")?.jsonObject?.get("score")?.jsonPrimitive?.content?.toDoubleOrNull(),
)

private fun steamPlatformNames(platforms: JsonObject?): List<String> {
    if (platforms == null) return emptyList()
    fun supported(key: String) = platforms[key]?.jsonPrimitive?.booleanOrNull == true
    return buildList {
        if (supported("windows")) add("Windows")
        if (supported("mac")) add("macOS")
        if (supported("linux")) add("Linux")
    }
}

/**
 * The store's display date as an epoch second, or null when it is not a date.
 *
 * Steam has no timestamp field — `release_date.date` is the string the store page prints, which is
 * "17 Sep, 2020" in English, sometimes only a month or a year for older titles, and legitimately
 * "Coming soon", "TBA" or "Q4 2026" for unreleased ones. Anything that is not one of the known
 * shapes returns null rather than a guess, which the library already handles: a tracked game with
 * no date simply shows none.
 *
 * Parsed at UTC midnight to match IGDB's `first_release_date`, so the year the editor prints does
 * not depend on which side of midnight the machine's timezone falls.
 */
internal fun parseSteamReleaseDate(value: String?): Long? {
    val text = value?.trim()?.takeIf(String::isNotBlank) ?: return null
    STEAM_DATE_FORMATS.forEach { pattern ->
        val parsed = runCatching {
            LocalDate.parse(text, DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
        }.getOrNull()
        if (parsed != null) return parsed.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
    }
    // A month and year, or a bare year, is still worth the date it names. "Fall 2026" and
    // "Q4 2026" name no month, so they fall through to null rather than to January.
    STEAM_MONTH_YEAR.matchEntire(text)?.let { match ->
        val month = STEAM_MONTHS.indexOfFirst { it.equals(match.groupValues[1].take(3), ignoreCase = true) }
        val year = match.groupValues[2].toIntOrNull()
        if (month >= 0 && year != null) {
            return LocalDate.of(year, month + 1, 1).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        }
    }
    text.toIntOrNull()
        ?.takeIf { it in 1950..2100 }
        ?.let { return LocalDate.of(it, 1, 1).atStartOfDay(ZoneOffset.UTC).toEpochSecond() }
    return null
}

private val STEAM_DATE_FORMATS = listOf(
    "d MMM, yyyy",
    "d MMMM, yyyy",
    "MMM d, yyyy",
    "MMMM d, yyyy",
    "d MMM yyyy",
    "MMM d yyyy",
)

private val STEAM_MONTH_YEAR = Regex("""([A-Za-z]{3,9})\s+(\d{4})""")

private val STEAM_MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)
