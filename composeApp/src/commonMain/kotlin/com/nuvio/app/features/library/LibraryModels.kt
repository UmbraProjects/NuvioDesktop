package com.nuvio.app.features.library

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.locallibrary.LocalMediaItem
import com.nuvio.app.features.metadata.AnimeIdMappingRepository
import com.nuvio.app.features.metadata.isAnimeNativeId
import com.nuvio.app.features.posterservice.CustomPosterKeys
import com.nuvio.app.features.posterservice.CustomPosterScreen
import com.nuvio.app.features.posterservice.CustomPosterSettingsRepository
import com.nuvio.app.features.posterservice.CustomPosterShape
import com.nuvio.app.features.posterservice.customPosterTemplateUsesNativeAnimeId
import com.nuvio.app.features.posterservice.customPosterUrl
import kotlinx.serialization.Serializable

@Serializable
data class LibraryItem(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val banner: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val runtime: String? = null,
    val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
    val posterShape: PosterShape = PosterShape.Poster,
    val addonBaseUrl: String? = null,
    val listKeys: Set<String> = emptySet(),
    val traktRank: Int? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val traktId: Int? = null,
    val anilistId: Int? = null,
    val kitsuId: Int? = null,
    val malId: Int? = null,
    // Bumped when the user asks to refresh this item's poster; appended as a URL fragment so the
    // image loader re-requests even the poster-service (PostersPlus/RPDB) URL, which is keyed by id.
    val posterRefreshToken: Long? = null,
    // Set only for rows whose [id] addresses a file rather than a title (the built-in debrid cloud
    // library, whose ids are provider download keys). See [MetaPreview.metaLookupId].
    val metaLookupId: String? = null,
    val metaLookupType: String? = null,
    val savedAtEpochMs: Long,
)

data class LibrarySection(
    val type: String,
    val displayTitle: String,
    val items: List<LibraryItem>,
)

enum class LibrarySourceMode {
    LOCAL,
    TRAKT,
    SIMKL,
    MDBLIST,
    YAMTRACK,
}

data class LibraryUiState(
    val sourceMode: LibrarySourceMode = LibrarySourceMode.LOCAL,
    val items: List<LibraryItem> = emptyList(),
    val sections: List<LibrarySection> = emptyList(),
    val isLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    // `type:id` of every saved title, so a shelf of posters can ask about each card with a set
    // lookup rather than a scan of [items] or a provider call per card.
    val savedKeys: Set<String> = emptySet(),
) {
    fun contains(id: String, type: String): Boolean = libraryItemKey(id, type) in savedKeys
}

internal fun Collection<LibraryItem>.toSavedKeys(): Set<String> =
    mapTo(HashSet(size * 2)) { libraryItemKey(it.id, it.type) }

fun MetaDetails.toLibraryItem(savedAtEpochMs: Long): LibraryItem =
    LibraryItem(
        id = id,
        type = type,
        name = name,
        poster = poster,
        banner = background,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        runtime = runtime,
        imdbRating = imdbRating,
        genres = genres,
        posterShape = PosterShape.Poster,
        imdbId = imdbId?.takeIf { it.startsWith("tt") } ?: id.takeIf { it.startsWith("tt") },
        // Lets the Library find plain art (and fill a poster template) for a title whose addon
        // poster is a poster-service image; see [basePoster].
        tmdbId = tmdbId,
        savedAtEpochMs = savedAtEpochMs,
    )

fun MetaPreview.toLibraryItem(savedAtEpochMs: Long): LibraryItem =
    LibraryItem(
        id = id,
        type = type,
        name = name,
        // A card's `poster` is already routed through the poster service for the screen it sits on
        // (the original kept as `posterFallback`); the library stores the raw art and applies its
        // own screen's setting when it renders.
        poster = posterFallback ?: poster,
        banner = banner,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        runtime = runtime,
        imdbRating = imdbRating,
        genres = genres,
        posterShape = posterShape,
        imdbId = id.takeIf { it.startsWith("tt") },
        savedAtEpochMs = savedAtEpochMs,
    )

fun LocalMediaItem.toLibraryItem(): LibraryItem {
    val animeIds = AnimeIdMappingRepository.entryForNativeIds(kitsu = kitsuId, mal = malId)
    return LibraryItem(
        id = contentId,
        type = contentType,
        name = title,
        poster = poster,
        // Landscape cards and the TV hero read this; without it they stretch the portrait poster.
        banner = background,
        posterShape = PosterShape.Poster,
        imdbId = imdbId,
        tmdbId = tmdbId,
        anilistId = animeIds?.anilistId,
        kitsuId = kitsuId ?: animeIds?.kitsuId,
        malId = malId ?: animeIds?.malId,
        posterRefreshToken = posterRefreshToken,
        savedAtEpochMs = 0L,
    )
}

fun LibraryItem.toMetaPreview(): MetaPreview {
    val resolvedPoster = resolveLibraryPosterUrl()
    val resolvedFallback = basePoster().withPosterRefreshToken(posterRefreshToken)
    return MetaPreview(
        id = id,
        type = type,
        name = name,
        poster = resolvedPoster,
        posterFallback = if (resolvedPoster != resolvedFallback) resolvedFallback else null,
        landscapePoster = resolveLibraryPosterServiceUrl(CustomPosterShape.Landscape)
            .withPosterRefreshToken(posterRefreshToken),
        // Metahub is a guess — a URL built from the IMDb id that may or may not resolve — so art
        // the item actually carries from a metadata provider (the local library's TMDB backdrop)
        // outranks it. Anything else keeps metahub as the id-derived fallback.
        banner = banner.takeIfProviderArt()
            ?: imdbId?.let { "https://images.metahub.space/background/medium/$it/img" }
            ?: banner,
        logo = logo.takeIfProviderArt()
            ?: imdbId?.let { "https://images.metahub.space/logo/medium/$it/img" }
            ?: logo,
        posterShape = posterShape,
        description = description,
        releaseInfo = releaseInfo,
        runtime = runtime,
        imdbRating = imdbRating,
        genres = genres,
        metaLookupId = metaLookupId,
        metaLookupType = metaLookupType,
    )
}

/** Art fetched from TMDB/TVDB (or per-season anime art), as opposed to an id-derived guess. */
private fun String?.takeIfProviderArt(): String? = this?.takeIf { url ->
    url.contains("image.tmdb.org", ignoreCase = true) ||
        url.contains("artworks.thetvdb.com", ignoreCase = true)
}

/**
 * Routes a library item's poster through the user's custom poster service when configured.
 *
 * The library isn't catalog-backed, so its posters are plain TMDB images. When a poster template is
 * set (e.g. PostersPlus / RPDB / a self-hosted service), this substitutes the item's ids and type
 * into it. Placeholders include the raw Stremio {id}, {imdb_id}, {tmdb_id}, {anilist_id},
 * {kitsu_id}, {mal_id}, and {type}. A strict template placeholder naming an id this item does not
 * have yields no URL and the original poster is kept; optional `{id?}` placeholders are replaced
 * with an empty value — see [customPosterUrl].
 *
 * Both ids come from the item's own fields first, and only then from its content id. A content id
 * is *either* a `tt…` or a `tmdb:…`, never both, so parsing it was structurally unable to fill a
 * template that names both — which is every PostersPlus query-form template, and why they answered
 * 400 to every library poster while a single-placeholder path template worked fine.
 *
 * Native anime ids continue to opt out for templates that only use IMDb/TMDB ids because those ids
 * can describe a whole franchise rather than this item. Templates naming the raw Stremio id opt in
 * and receive values such as `kitsu:395`; the older split AniList/Kitsu/MAL placeholders remain
 * supported for other poster services.
 */
private fun LibraryItem.resolveLibraryPosterUrl(): String? =
    (resolveLibraryPosterServiceUrl(CustomPosterShape.Portrait) ?: basePoster()).withPosterRefreshToken(posterRefreshToken)

/**
 * The item's own poster, minus one that is a poster service's image rather than the title's art.
 *
 * Two ways such a URL gets saved: a Home card routed through the user's own poster service (saved
 * before [toLibraryItem] stored the raw art), and a details page whose metadata addon serves its
 * own badge posters (AIOMetadata's `posters.myaio.xyz`, PostersPlus). Either would show in the
 * Library whatever its own poster-service setting says, beside plain TMDB art for everything else
 * (2026-11-13: four of 111 items). The IMDb-derived metahub poster stands in for it.
 */
private fun LibraryItem.basePoster(): String? {
    val saved = poster
    if (saved == null || !saved.isPosterServiceUrl()) return saved
    val type = type
    val imdb = imdbId?.takeIf { it.startsWith("tt") }
        ?: id.takeIf { it.startsWith("tt") }?.substringBefore(':')
        ?: saved.queryParam("imdb_id")?.takeIf { it.startsWith("tt") }
        ?: (tmdbId ?: saved.queryParam("tmdb_id")?.toIntOrNull())
            ?.let { tmdb -> com.nuvio.app.features.tmdb.TmdbService.peekTmdbToImdb(tmdb, type) }
            ?.takeIf { it.startsWith("tt") }
    // No IMDb id to build plain art from: the service's image beats a blank card.
    return imdb?.let { "https://images.metahub.space/poster/medium/$it/img" } ?: saved
}

/**
 * A poster service's image: the host of the user's own poster template, RPDB, or the query form
 * PostersPlus and AIOMetadata's poster service share (`/poster?tmdb_id=…&imdb_id=…&type=…`).
 */
internal fun String?.isPosterServiceUrl(): Boolean {
    val url = this?.takeIf { it.isNotBlank() } ?: return false
    val host = url.urlHost() ?: return false
    val serviceHost = CustomPosterSettingsRepository.snapshot().posterUrlTemplate.urlHost()
    if (serviceHost != null && host.equals(serviceHost, ignoreCase = true)) return true
    if (host.contains("ratingposterdb.com", ignoreCase = true)) return true
    val path = url.substringAfter("://").substringAfter('/', "").substringBefore('#')
    val beforeQuery = path.substringBefore('?')
    return '?' in path &&
        beforeQuery.trimEnd('/').substringAfterLast('/').equals("poster", ignoreCase = true) &&
        (url.queryParam("tmdb_id") != null || url.queryParam("imdb_id") != null)
}

private fun String.queryParam(name: String): String? =
    substringAfter('?', "").substringBefore('#').split('&')
        .firstOrNull { it.substringBefore('=').equals(name, ignoreCase = true) }
        ?.substringAfter('=', "")
        ?.takeIf { it.isNotBlank() }

private fun String?.urlHost(): String? =
    this?.substringAfter("://", "")?.substringBefore('/')?.substringBefore('?')?.substringBefore('#')
        ?.takeIf { it.isNotBlank() && '{' !in it }

/** The poster service's URL for this item in the given shape, or null when it does not apply. */
private fun LibraryItem.resolveLibraryPosterServiceUrl(shape: CustomPosterShape): String? {
    val settings = CustomPosterSettingsRepository.snapshot(CustomPosterScreen.Library)
    if (!settings.isActive(shape)) return null
    if (id.isAnimeNativeId() && !settings.customPosterTemplateUsesNativeAnimeId()) return null
    return customPosterUrl(
        settings = settings,
        imdbId = imdbId?.takeIf { it.isNotBlank() } ?: id.takeIf { it.startsWith("tt") },
        tmdbId = tmdbId?.toString()
            ?: id.takeIf { it.startsWith("tmdb:") }?.removePrefix("tmdb:")?.substringBefore(":"),
        type = type,
        stremioId = id,
        anilistId = anilistId?.toString()
            ?: id.takeIf { it.startsWith("anilist:") }?.removePrefix("anilist:")?.substringBefore(":"),
        kitsuId = kitsuId?.toString()
            ?: id.takeIf { it.startsWith("kitsu:") }?.removePrefix("kitsu:")?.substringBefore(":"),
        malId = malId?.toString()
            ?: id.takeIf { it.startsWith("mal:") }?.removePrefix("mal:")?.substringBefore(":"),
        shape = shape,
        keys = CustomPosterKeys.snapshot(),
    )
}

/**
 * Appends the poster refresh token as a URL fragment. The fragment changes the image-loader cache
 * key (forcing a fresh request) but is stripped by the HTTP client, so the server — including a
 * poster service like PostersPlus — still receives a valid URL.
 */
private fun String?.withPosterRefreshToken(refreshToken: Long?): String? {
    if (this.isNullOrBlank() || refreshToken == null || this.contains("#")) return this
    return "$this#v=$refreshToken"
}
