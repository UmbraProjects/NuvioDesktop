package com.nuvio.app.features.games

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * Steam's own CDN as an artwork source, for games that have a Steam app id.
 *
 * Steam publishes the library artwork its client draws itself with at fixed URLs under
 * `steam/apps/<appid>/` — `library_hero.jpg` is the same 1920x620 wide art SteamGridDB's `heroes`
 * category mirrors, and `logo.png` the same transparent clear logo. It needs **no API key
 * at all**, which is the point: it is the one artwork source that works before the user has been
 * to Settings.
 *
 * It is offered in the pickers alongside IGDB and SteamGridDB and is never auto-picked, for the
 * same reason those are not — see [SteamGridDbClient]. The automatic artwork top-up in
 * [GameLibraryController] is deliberately left alone: it runs unattended across the whole library,
 * where a source that can fall back to a title search would silently attach another game's art.
 *
 * The fixed URLs are no longer the whole story. Since 2025 the store files new and re-uploaded
 * assets under a per-asset content hash (`steam/apps/<appid>/<sha1>/library_capsule.jpg`), and a
 * game whose art only ever existed in that era — Mortal Shell II, at the time of writing — has
 * nothing at the bare path at all: its cover is a 404 there, and the file is not even called
 * `library_600x900.jpg` any more. The hash cannot be guessed, so the cover and hero are read from
 * the store's own asset manifest ([storeAssetsFor]) first, and only probed the old way when that
 * manifest cannot be reached. The clear logo is not in the manifest and is still probed.
 *
 * Nothing is assumed to exist. Every probed URL is checked before it is offered, because coverage
 * varies by title and era — Portal 2 has `library_hero.jpg` and `logo.png` but neither the 2x
 * hero nor the store-page background that every game after it carries.
 */
class SteamArtworkClient : AutoCloseable {
    private val http = HttpClient(CIO) { expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val appIdCacheLock = Mutex()
    private val appIdCache = mutableMapOf<SteamGameReference, Long?>()
    private val storeAssetsLock = Mutex()
    private val storeAssetsCache = mutableMapOf<Long, CompletableDeferred<SteamStoreAssets?>>()

    suspend fun heroesFor(reference: SteamGameReference): List<ArtworkCandidate> =
        heroesFor(appIdFor(reference) ?: return emptyList())

    suspend fun heroesFor(appId: Long): List<ArtworkCandidate> {
        val manifest = storeAssetsFor(appId)
        if (manifest != null) {
            return STEAM_HERO_ASSETS.mapNotNull { group ->
                group.firstNotNullOfOrNull { asset ->
                    manifest.url(asset.manifestKey)?.let { url ->
                        ArtworkCandidate(url, asset.width, asset.height, ArtworkSource.STEAM)
                    }
                }
            }
        }
        return firstExistingPerGroup(appId, STEAM_HERO_ASSETS, measure = false).map { probe ->
            ArtworkCandidate(
                url = probe.url,
                width = probe.asset.width,
                height = probe.asset.height,
                source = ArtworkSource.STEAM,
            )
        }
    }

    /**
     * Logo dimensions are read rather than declared: Steam's clear logos have no fixed size, and
     * the picker labels every other candidate with one. The measurement is free — the same request
     * that proves the file is there returns the PNG header that states its size.
     */
    suspend fun logosFor(reference: SteamGameReference): List<LogoCandidate> =
        logosFor(appIdFor(reference) ?: return emptyList())

    suspend fun logosFor(appId: Long): List<LogoCandidate> {
        return firstExistingPerGroup(appId, STEAM_LOGO_ASSETS, measure = true).map { probe ->
            LogoCandidate(
                url = probe.url,
                width = probe.width,
                height = probe.height,
                score = 0,
                source = ArtworkSource.STEAM,
            )
        }
    }

    /** The portrait cover for the poster rail, largest first, or null when Steam has none. */
    suspend fun coverFor(appId: Long): String? {
        val manifest = storeAssetsFor(appId)
        if (manifest != null) {
            return STEAM_COVER_ASSETS.first().firstNotNullOfOrNull { manifest.url(it.manifestKey) }
        }
        return firstExistingPerGroup(appId, STEAM_COVER_ASSETS, measure = false).firstOrNull()?.url
    }

    /**
     * The store's asset manifest for [appId], or null when it could not be fetched or the store
     * does not list the app (delisted, or region-locked away from this machine).
     *
     * `IStoreBrowseService/GetItems` is the endpoint the store pages themselves draw from and needs
     * no key. It names every capsule, hero and background file with its hash segment when it has
     * one, which is the only way to reach art filed under the hashed layout. Answers are kept for
     * the life of the client, and a lookup in flight is shared — the cover and hero of one game are
     * asked for at the same time from the editor — so each app costs one request. A failed fetch
     * is not remembered, so a network blip does not pin a game to the probe fallback for good.
     */
    suspend fun storeAssetsFor(appId: Long): SteamStoreAssets? = storeAssetsFor(listOf(appId))[appId]

    /** The manifests of every app in [appIds] that the store lists, in one request for the misses. */
    suspend fun storeAssetsFor(appIds: Collection<Long>): Map<Long, SteamStoreAssets> {
        val ids = appIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        val waiting = mutableMapOf<Long, CompletableDeferred<SteamStoreAssets?>>()
        val owned = mutableMapOf<Long, CompletableDeferred<SteamStoreAssets?>>()
        storeAssetsLock.withLock {
            ids.forEach { id ->
                val cached = storeAssetsCache[id]
                if (cached != null) {
                    waiting[id] = cached
                } else {
                    owned[id] = CompletableDeferred<SteamStoreAssets?>().also { storeAssetsCache[id] = it }
                }
            }
        }
        if (owned.isNotEmpty()) {
            val fetched = try {
                fetchStoreAssets(owned.keys)
            } catch (error: Throwable) {
                // Every waiter sees the failure, and the next caller gets to try again.
                storeAssetsLock.withLock { owned.keys.forEach { storeAssetsCache.remove(it) } }
                owned.values.forEach { it.completeExceptionally(error) }
                if (error is CancellationException) throw error
                null
            }
            owned.forEach { (id, deferred) -> deferred.complete(fetched?.get(id)) }
        }
        return buildMap {
            (owned + waiting).forEach { (id, deferred) ->
                val assets = runCatching { deferred.await() }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
                if (assets != null) put(id, assets)
            }
        }
    }

    private suspend fun fetchStoreAssets(appIds: Collection<Long>): Map<Long, SteamStoreAssets> {
        val request = buildString {
            append("{\"ids\":[")
            append(appIds.joinToString(",") { "{\"appid\":$it}" })
            append("],\"context\":{\"language\":\"english\",\"country_code\":\"US\"},")
            append("\"data_request\":{\"include_assets\":true}}")
        }
        val response = http.get("https://api.steampowered.com/IStoreBrowseService/GetItems/v1") {
            url { parameters.append("input_json", request) }
        }
        if (!response.status.isSuccess()) {
            throw SteamStoreException("Steam returned ${response.status.value} for the asset manifest.")
        }
        val items = json.parseToJsonElement(response.bodyAsText())
            .jsonObject["response"]?.jsonObject?.get("store_items")?.jsonArray
            ?: return emptyMap()
        return items.mapNotNull { item -> parseSteamStoreAssets(item.jsonObject) }.associateBy { it.appId }
    }

    /**
     * The app id, from the cheapest source that can produce one.
     *
     * The launch target and the install location are exact and offline — a game launched through
     * Steam, or installed under a `steamapps` library, identifies itself. The store search is the
     * fallback for everything else (an Epic or GOG copy of a game Steam also sells, or a tracked
     * title with no executable yet) and is the only one that can be wrong, so it demands an exact
     * normalised-title match rather than taking the first result.
     */
    suspend fun appIdFor(reference: SteamGameReference): Long? {
        appIdCacheLock.withLock { if (appIdCache.containsKey(reference)) return appIdCache[reference] }
        val resolved = steamAppIdFromLaunchTarget(reference.executablePath, reference.arguments)
            ?: steamAppIdFromInstallLocation(reference.executablePath)
            ?: searchStoreForAppId(reference.title)
        appIdCacheLock.withLock { appIdCache[reference] = resolved }
        return resolved
    }

    private suspend fun searchStoreForAppId(title: String): Long? {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return null
        val response = runCatching {
            http.get("https://steamcommunity.com/actions/SearchApps/${cleanTitle.encodeURLPathPart()}")
        }.getOrNull() ?: return null
        if (!response.status.isSuccess()) return null
        val apps = runCatching {
            json.decodeFromString<List<SteamSearchApp>>(response.bodyAsText())
        }.getOrNull() ?: return null
        return selectSteamApp(apps, cleanTitle)?.appid?.toLongOrNull()
    }

    private suspend fun firstExistingPerGroup(
        appId: Long,
        groups: List<List<SteamAsset>>,
        measure: Boolean,
    ): List<SteamAssetProbe> = coroutineScope {
        groups
            .map { group ->
                async {
                    group.firstNotNullOfOrNull { asset ->
                        steamAssetUrls(appId, asset.fileName)
                            .firstNotNullOfOrNull { url -> probe(asset, url, measure) }
                    }
                }
            }
            .awaitAll()
            .filterNotNull()
    }

    /**
     * Whether the asset is there, and how big it is when [measure] is set.
     *
     * Measuring reads the first bytes instead of asking HEAD, because those bytes carry the PNG
     * header — one request answers both questions. Otherwise HEAD is enough, and a status that is
     * neither success nor 404 is retried as a ranged read: a CDN refusing the method is not the
     * same answer as a CDN denying the file.
     */
    private suspend fun probe(asset: SteamAsset, url: String, measure: Boolean): SteamAssetProbe? {
        if (measure) {
            val header = rangedRead(url) ?: return null
            val size = pngDimensions(header)
            return SteamAssetProbe(asset, url, size?.first ?: 0, size?.second ?: 0)
        }
        val found = SteamAssetProbe(asset, url, asset.width, asset.height)
        val head = runCatching { http.head(url) }.getOrNull() ?: return null
        if (head.status.isSuccess()) return found
        if (head.status.value == 404) return null
        return if (rangedRead(url) != null) found else null
    }

    private suspend fun rangedRead(url: String): ByteArray? {
        val response = runCatching {
            http.get(url) { header(HttpHeaders.Range, "bytes=0-$PNG_HEADER_BYTES") }
        }.getOrNull() ?: return null
        if (!response.status.isSuccess()) return null
        return runCatching { response.body<ByteArray>() }.getOrNull()
    }

    override fun close() = http.close()
}

/**
 * What is known about a game while artwork is being looked up.
 *
 * Deliberately not a [GameEntry]: the editor asks while the user is still typing, so the title and
 * path being resolved are the ones in the open dialog rather than the ones last saved.
 */
data class SteamGameReference(
    val title: String,
    val executablePath: String? = null,
    val arguments: List<String> = emptyList(),
)

/**
 * One Steam asset: the file name it has at the bare `steam/apps/<appid>/` path, and the key the
 * store's asset manifest lists it under (which is also where its hashed path, if any, lives).
 */
internal data class SteamAsset(
    val fileName: String,
    val width: Int,
    val height: Int,
    val manifestKey: String? = null,
)

/**
 * The store's asset manifest for one app: every file it lists, by manifest key, as a path relative
 * to the store CDN root — `steam/apps/<appid>/<hash>/library_capsule.jpg?t=…` for art filed under
 * the hashed layout, `steam/apps/<appid>/library_600x900.jpg?t=…` for art that predates it.
 */
data class SteamStoreAssets(
    val appId: Long,
    private val paths: Map<String, String>,
) {
    fun url(manifestKey: String?): String? = manifestKey?.let(paths::get)?.let { STEAM_STORE_ASSET_ROOT + it }
}

/**
 * The manifest out of one `GetItems` store item. An item the store will not show (`visible:false`,
 * `success` other than 1) carries no `assets` and yields nothing, so the caller falls back.
 *
 * The `${'$'}{FILENAME}` placeholder in `asset_url_format` is filled per file: the file values
 * already carry their hash segment when they have one, and the format supplies the app directory
 * and the cache-busting stamp. `community_icon` is a bare hash rather than a file and is skipped.
 */
internal fun parseSteamStoreAssets(item: JsonObject): SteamStoreAssets? {
    val appId = item["appid"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: return null
    val assets = item["assets"]?.jsonObject ?: return null
    val format = assets["asset_url_format"]?.jsonPrimitive?.content
        ?.takeIf { it.contains(STEAM_ASSET_FILENAME_PLACEHOLDER) }
        ?: return null
    val paths = assets.mapNotNull { (key, value) ->
        val file = runCatching { value.jsonPrimitive.content }.getOrNull()
            ?.takeIf { key != "asset_url_format" && it.contains('.') }
            ?: return@mapNotNull null
        key to format.replace(STEAM_ASSET_FILENAME_PLACEHOLDER, file)
    }.toMap()
    return SteamStoreAssets(appId, paths)
}

private const val STEAM_ASSET_FILENAME_PLACEHOLDER = "${'$'}{FILENAME}"

/** The CDN root `asset_url_format` paths hang off; the akamai and fastly hosts mirror it. */
internal const val STEAM_STORE_ASSET_ROOT = "https://shared.steamstatic.com/store_item_assets/"

internal data class SteamAssetProbe(
    val asset: SteamAsset,
    val url: String,
    val width: Int,
    val height: Int,
)

/**
 * Width and height out of a PNG's IHDR chunk, which is always the first one: an 8-byte signature,
 * then the chunk length and type, then the two 32-bit dimensions. Anything that does not start
 * with the signature is not a PNG and is not measured.
 */
internal fun pngDimensions(header: ByteArray): Pair<Int, Int>? {
    if (header.size < 24) return null
    if (!PNG_SIGNATURE.indices.all { header[it] == PNG_SIGNATURE[it] }) return null
    fun intAt(offset: Int): Int = (0 until 4).fold(0) { value, index ->
        (value shl 8) or (header[offset + index].toInt() and 0xFF)
    }
    val width = intAt(16)
    val height = intAt(20)
    return if (width > 0 && height > 0) width to height else null
}

private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
)

/** Enough of the file to hold a PNG signature plus its IHDR chunk. */
private const val PNG_HEADER_BYTES = 33

/**
 * Backdrop assets, as preference chains — one candidate per chain.
 *
 * `library_hero_2x` and `library_hero` are the same picture at two resolutions, so offering both
 * would be two identical tiles: the larger wins and the chain falls through when it is missing.
 * `page_bg_raw` is a genuinely different image (the old store-page background), so it is its own
 * chain and appears as a second option.
 */
internal val STEAM_HERO_ASSETS: List<List<SteamAsset>> = listOf(
    listOf(
        SteamAsset("library_hero_2x.jpg", 3840, 1240, manifestKey = "library_hero_2x"),
        SteamAsset("library_hero.jpg", 1920, 620, manifestKey = "library_hero"),
    ),
    listOf(SteamAsset("page_bg_raw.jpg", 0, 0, manifestKey = "raw_page_background")),
)

/**
 * Logo assets, one chain because they are all the same logo when more than one exists.
 *
 * `logo.png` leads because it is the one that is actually there: probed across six titles old and
 * new (Portal 2, Dota 2, Cyberpunk 2077, Valheim, Hades, Grounded) it was present every time and
 * `library_logo*.png` was present none. It is a transparent RGBA clear logo, 640x360 for the ones
 * measured. The `library_logo` names stay behind it as a safety net rather than ahead of it, so
 * the usual case costs one request.
 */
internal val STEAM_LOGO_ASSETS: List<List<SteamAsset>> = listOf(
    listOf(
        SteamAsset("logo.png", 0, 0),
        SteamAsset("library_logo_2x.png", 0, 0),
        SteamAsset("library_logo.png", 0, 0),
    ),
)

/**
 * Portrait cover art, as one chain: the 2x is the same picture at twice the size, and `header.jpg`
 * is landscape, so it is not an acceptable substitute for a poster and is not in the chain.
 *
 * The manifest calls the same picture `library_capsule`, under which a hashed-era game files it as
 * `<hash>/library_capsule.jpg` and an older one as plain `library_600x900.jpg`.
 */
internal val STEAM_COVER_ASSETS: List<List<SteamAsset>> = listOf(
    listOf(
        SteamAsset("library_600x900_2x.jpg", 1200, 1800, manifestKey = "library_capsule_2x"),
        SteamAsset("library_600x900.jpg", 600, 900, manifestKey = "library_capsule"),
    ),
)

/**
 * Both hosts the same bare-path asset can live behind. Newer titles are served from the
 * `store_item_assets` path, older ones from the bare `steam/apps` path, and most from both.
 * `shared.steamstatic.com` rather than `shared.cloudflare.steamstatic.com`, which only 301s to it.
 * Hashed-era art is on the `store_item_assets` host only, and is reached via [SteamStoreAssets].
 */
internal fun steamAssetUrls(appId: Long, fileName: String): List<String> = listOf(
    "https://cdn.cloudflare.steamstatic.com/steam/apps/$appId/$fileName",
    "https://shared.steamstatic.com/store_item_assets/steam/apps/$appId/$fileName",
)

/**
 * The app id a launch target names outright.
 *
 * Covers the two ways a Steam game is launched without pointing at its own executable: the
 * `steam://rungameid/<id>` protocol URL, and `steam.exe -applaunch <id>`.
 */
internal fun steamAppIdFromLaunchTarget(executablePath: String?, arguments: List<String>): Long? {
    val joined = (listOfNotNull(executablePath) + arguments).joinToString(" ")
    STEAM_PROTOCOL_APP_ID.find(joined)?.groupValues?.get(1)?.toLongOrNull()?.let { return it }
    val appLaunchIndex = arguments.indexOfFirst { it.equals("-applaunch", ignoreCase = true) }
    if (appLaunchIndex >= 0) return arguments.getOrNull(appLaunchIndex + 1)?.toLongOrNull()
    return null
}

/**
 * The app id of the game installed at [executablePath], read from Steam's own install manifests.
 *
 * A Steam install always looks like `<library>/steamapps/common/<installdir>/…`, and the
 * `appmanifest_<id>.acf` next to `common` records that `installdir`. Matching on it rather than on
 * the executable name is what makes this exact: two games can ship a `game.exe`, but no two
 * entries in one library share an install directory.
 */
internal fun steamAppIdFromInstallLocation(executablePath: String?): Long? {
    val installDirectory = steamInstallDirectoryName(executablePath) ?: return null
    val steamAppsDirectory = steamAppsDirectoryFor(executablePath) ?: return null
    val manifests = steamAppsDirectory
        .listFiles { file: File -> file.isFile && file.name.startsWith("appmanifest_") && file.extension == "acf" }
        ?: return null
    return manifests
        .asSequence()
        .mapNotNull { manifest -> runCatching { parseSteamAppManifest(manifest.readText()) }.getOrNull() }
        .firstOrNull { it.installDirectory.equals(installDirectory, ignoreCase = true) }
        ?.appId
}

internal data class SteamAppManifest(
    val appId: Long,
    val installDirectory: String,
    /** The store title, which the folder scan prefers to the install directory's name. */
    val name: String? = null,
)

/** Pulls the fields that matter out of a Valve KeyValues manifest, without a VDF parser. */
internal fun parseSteamAppManifest(text: String): SteamAppManifest? {
    val appId = ACF_APP_ID.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
    val installDirectory = ACF_INSTALL_DIR.find(text)?.groupValues?.get(1) ?: return null
    val name = ACF_NAME.find(text)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotBlank)
    return SteamAppManifest(appId, installDirectory, name)
}

/** The folder directly under `steamapps/common` that holds [executablePath], if it is in one. */
internal fun steamInstallDirectoryName(executablePath: String?): String? {
    val path = executablePath?.takeIf(String::isNotBlank) ?: return null
    var current: File? = File(path).parentFile
    while (current != null) {
        val parent = current.parentFile ?: return null
        if (parent.name.equals("common", ignoreCase = true) &&
            parent.parentFile?.name.equals("steamapps", ignoreCase = true)
        ) {
            return current.name
        }
        current = parent
    }
    return null
}

/** The `steamapps` directory above [executablePath], if it is inside a Steam library. */
internal fun steamAppsDirectoryFor(executablePath: String?): File? {
    val path = executablePath?.takeIf(String::isNotBlank) ?: return null
    var current: File? = File(path).parentFile
    while (current != null) {
        if (current.name.equals("steamapps", ignoreCase = true)) return current
        current = current.parentFile
    }
    return null
}

/**
 * The store result to trust, or none.
 *
 * Same rule as the SteamGridDB lookup: an exact normalised-title match or nothing. Steam's search
 * happily returns soundtracks, demos and sequels for a partial title, and a wrong app id here
 * would hand the user another game's artwork without saying so.
 */
internal fun selectSteamApp(apps: List<SteamSearchApp>, requestedTitle: String): SteamSearchApp? {
    val requested = requestedTitle.normalizedGameTitle()
    return apps.firstOrNull { it.name.normalizedGameTitle() == requested }
}

@Serializable
data class SteamSearchApp(
    val appid: String = "",
    val name: String = "",
)

private val STEAM_PROTOCOL_APP_ID = Regex("""steam://(?:rungameid|run)/(\d+)""", RegexOption.IGNORE_CASE)
private val ACF_APP_ID = Regex(""""appid"\s+"(\d+)"""", RegexOption.IGNORE_CASE)
private val ACF_INSTALL_DIR = Regex(""""installdir"\s+"([^"]+)"""", RegexOption.IGNORE_CASE)
private val ACF_NAME = Regex(""""name"\s+"([^"]+)"""", RegexOption.IGNORE_CASE)
