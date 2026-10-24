package com.nuvio.app.features.posterservice

import co.touchlab.kermit.Logger
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.metadata.isAnimeNativeId
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository

private val log = Logger.withTag("CustomPosterTemplate")

/**
 * The credentials a template may ask to have forwarded. Neither belongs to the poster service —
 * they are the user's TMDB and MDBList keys — so they are read from those integrations at the
 * call site rather than stored twice.
 */
internal data class CustomPosterKeys(
    val tmdbApiKey: String = "",
    val mdbListApiKey: String = "",
) {
    companion object {
        fun snapshot(): CustomPosterKeys = CustomPosterKeys(
            tmdbApiKey = TmdbSettingsRepository.snapshot().apiKey,
            mdbListApiKey = MdbListSettingsRepository.snapshot().apiKey,
        )
    }
}

/**
 * Builds a poster URL from the user's custom poster-service template (PostersPlus, RPDB, a
 * self-hosted service, …) for the given card [shape].
 *
 * Placeholders follow upstream's custom poster URL patterns, plus two credential placeholders of
 * this fork's own:
 * - `{id}` — the raw Stremio meta id (`tt0137523`, `tmdb:1396`, `kitsu:395`); `{id_type}` — its
 *   namespace (`imdb`, `tmdb`, `kitsu`, …); `{typed_id}` — RPDB's form (`tt…` for IMDb,
 *   `movie-1396`/`series-1396` for TMDB/TVDB, the raw id otherwise).
 * - `{imdb_id}`, `{tmdb_id}`, `{tvdb_id}`, `{kitsu_id}`, `{anilist_id}`, `{mal_id}`, `{anidb_id}`.
 * - `{type}` — `movie` or `series`; `{shape}` — `poster` or `landscape`.
 * - `{tmdb_key}`, `{mdblist_key}` — the user's own keys, forwarded (fork-only).
 *
 * Any placeholder may end in `?` to make it optional (substituted empty when unknown), and
 * `{imdb_id|kitsu_id}` names the ids a service accepts, taking the first one known. Placeholder
 * names are matched case-insensitively. Templates pointing at the RPDB family retry with the
 * other of imdb/tmdb/tvdb when the named id is unknown, as AIOMetadata and upstream do.
 *
 * **Ids and keys behave oppositely, because they answer different questions.**
 *
 * A strict id is the subject of the request, so a placeholder the caller cannot fill normally means
 * **no URL at all**: blanking it produces a request that is malformed rather than partial. The one
 * exception is a populated `{id}`: it already identifies the subject, so split provider ids in the
 * same template are supplemental and may be blank. A pipe list with no known member is never
 * waived — the service said which ids it can take, and it can take none of these.
 *
 * A key is a credential the *service* may already hold, and an empty one reads as "not supplied":
 * an instance configured with its own `TMDB_API_KEY` serves `tmdb_key=` exactly as it serves the
 * parameter being absent. So an unknown key is substituted empty and the request still goes out —
 * suppressing it would break every user whose instance is self-hosted with its own keys.
 *
 * Naming a key placeholder is how the user asks for their key to be forwarded; it is sent to
 * whatever host the template points at, which is the established metadata-addon pattern
 * (AIOMetadata et al.) and the reason the template is a per-user setting rather than a default.
 *
 * Ids the caller did not pass are read from [stremioId]'s own namespace, so a `kitsu:395` row fills
 * `{kitsu_id}` without every call site parsing it.
 *
 * Returns null when the service is off, the template for [shape] is blank, a strict id it names is
 * unknown, or no id is known at all — callers then keep whatever art they already had (a plain TMDB
 * image, or the addon's own).
 */
internal fun customPosterUrl(
    settings: CustomPosterSettings,
    imdbId: String?,
    tmdbId: String?,
    type: String,
    stremioId: String? = null,
    anilistId: String? = null,
    kitsuId: String? = null,
    malId: String? = null,
    tvdbId: String? = null,
    anidbId: String? = null,
    shape: CustomPosterShape = CustomPosterShape.Portrait,
    keys: CustomPosterKeys = CustomPosterKeys(),
): String? {
    if (!settings.enabled) return null
    val template = settings.templateFor(shape)
    if (template.isBlank()) return null

    val rawId = stremioId?.trim().orEmpty()
    fun fromRawId(namespace: String): String =
        rawId.takeIf { it.startsWith("$namespace:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.substringBefore(':')
            .orEmpty()
    fun String?.orRaw(namespace: String): String = this?.trim()?.takeIf { it.isNotBlank() } ?: fromRawId(namespace)

    val ids = CustomPosterIdValues(
        raw = rawId,
        imdb = imdbId?.trim()?.takeIf { it.isNotBlank() }
            ?: rawId.takeIf { it.startsWith("tt") }?.substringBefore(':').orEmpty(),
        tmdb = tmdbId.orRaw("tmdb"),
        tvdb = tvdbId.orRaw("tvdb"),
        kitsu = kitsuId.orRaw("kitsu"),
        anilist = anilistId.orRaw("anilist"),
        mal = malId.orRaw("mal"),
        anidb = anidbId.orRaw("anidb"),
    )
    if (!ids.hasAny) return null

    val contentType = if (type.trim().equals("movie", ignoreCase = true)) "movie" else "series"
    val values = ids.placeholderValues(contentType) + mapOf(
        "type" to contentType,
        "shape" to shape.placeholderValue,
        "tmdb_key" to keys.tmdbApiKey.trim(),
        "mdblist_key" to keys.mdbListApiKey.trim(),
    )

    fillCustomPosterTemplate(template, values)?.let { return it }
    if (!isRpdbFamilyTemplate(template)) return null
    return rpdbFallbackTemplates(template, ids, contentType)
        .firstNotNullOfOrNull { fallback -> fillCustomPosterTemplate(fallback, values) }
}

private val CUSTOM_POSTER_PLACEHOLDER = Regex("""\{([A-Za-z_]+(?:\|[A-Za-z_]+)*)(\?)?}""")

/** Placeholders a populated `{id}` makes supplemental rather than required. */
private val CUSTOM_POSTER_PROVIDER_ID_NAMES = setOf(
    "imdb_id", "tmdb_id", "tvdb_id", "kitsu_id", "anilist_id", "mal_id", "anidb_id",
)

private val CUSTOM_POSTER_KEY_NAMES = setOf("tmdb_key", "mdblist_key")

/** Everything the template language understands; any other `{…}` is left as the user wrote it. */
private val CUSTOM_POSTER_PLACEHOLDER_NAMES = CUSTOM_POSTER_PROVIDER_ID_NAMES + CUSTOM_POSTER_KEY_NAMES +
    setOf("id", "id_type", "typed_id", "type", "shape")

private class CustomPosterIdValues(
    val raw: String,
    val imdb: String,
    val tmdb: String,
    val tvdb: String,
    val kitsu: String,
    val anilist: String,
    val mal: String,
    val anidb: String,
) {
    val hasAny: Boolean
        get() = listOf(raw, imdb, tmdb, tvdb, kitsu, anilist, mal, anidb).any { it.isNotBlank() }

    fun placeholderValues(contentType: String): Map<String, String> {
        val (idType, bareId) = when {
            raw.startsWith("tt") -> "imdb" to raw
            raw.contains(':') -> raw.substringBefore(':').lowercase() to raw.substringAfter(':')
            raw.isNotBlank() -> "unknown" to raw
            else -> "" to ""
        }
        val typedId = when (idType) {
            "tmdb", "tvdb" -> "$contentType-${bareId.substringBefore(':')}"
            else -> bareId
        }
        return mapOf(
            "id" to raw,
            "id_type" to idType,
            "typed_id" to typedId,
            "imdb_id" to imdb,
            "tmdb_id" to tmdb,
            "tvdb_id" to tvdb,
            "kitsu_id" to kitsu,
            "anilist_id" to anilist,
            "mal_id" to mal,
            "anidb_id" to anidb,
        )
    }
}

/** One pass over [template]; null when a strict placeholder has no value (see [customPosterUrl]). */
private fun fillCustomPosterTemplate(template: String, values: Map<String, String>): String? {
    val matches = CUSTOM_POSTER_PLACEHOLDER.findAll(template).toList()
    val hasRawId = values["id"].orEmpty().isNotBlank() &&
        matches.any { it.groupValues[1].equals("id", ignoreCase = true) }
    val out = StringBuilder(template.length + 32)
    var last = 0
    for (match in matches) {
        val names = match.groupValues[1].lowercase().split('|')
        if (names.any { it !in CUSTOM_POSTER_PLACEHOLDER_NAMES }) continue
        val optional = match.groupValues[2].isNotEmpty()
        val value = names.firstNotNullOfOrNull { name -> values[name]?.takeIf { it.isNotBlank() } }
        if (value == null && !optional) {
            val single = names.singleOrNull()
            val waived = single in CUSTOM_POSTER_KEY_NAMES ||
                (single in CUSTOM_POSTER_PROVIDER_ID_NAMES && hasRawId)
            if (!waived) return null
        }
        out.append(template, last, match.range.first).append(value.orEmpty())
        last = match.range.last + 1
    }
    return out.append(template, last, template.length).toString()
}

private val RPDB_FAMILY_HOSTS = listOf("ratingposterdb.com", "aioratings.com", "top-posters.com", "btttr.cc")

private fun isRpdbFamilyTemplate(template: String): Boolean =
    RPDB_FAMILY_HOSTS.any { template.contains(it, ignoreCase = true) }

/**
 * RPDB-family services address a title by imdb, tmdb, or tvdb in the path; when the one the user
 * named is unknown, try the others (upstream's `resolveRpdbWithFallback`).
 */
private fun rpdbFallbackTemplates(template: String, ids: CustomPosterIdValues, contentType: String): List<String> {
    val fallbacks = mutableListOf<String>()
    when {
        "{imdb_id}" in template -> {
            if (ids.tmdb.isNotBlank()) {
                fallbacks += template.replace("/imdb/", "/tmdb/").replace("{imdb_id}", "$contentType-${ids.tmdb}")
            }
            if (ids.tvdb.isNotBlank()) {
                fallbacks += template.replace("/imdb/", "/tvdb/").replace("{imdb_id}", "$contentType-${ids.tvdb}")
            }
        }
        "{tmdb_id}" in template -> {
            if (ids.imdb.isNotBlank()) {
                fallbacks += template.replace("/tmdb/", "/imdb/")
                    .replace("$contentType-{tmdb_id}", ids.imdb)
                    .replace("{tmdb_id}", ids.imdb)
            }
            if (ids.tvdb.isNotBlank()) {
                fallbacks += template.replace("/tmdb/", "/tvdb/").replace("{tmdb_id}", ids.tvdb)
            }
        }
        "{tvdb_id}" in template -> {
            if (ids.imdb.isNotBlank()) {
                fallbacks += template.replace("/tvdb/", "/imdb/")
                    .replace("$contentType-{tvdb_id}", ids.imdb)
                    .replace("{tvdb_id}", ids.imdb)
            }
            if (ids.tmdb.isNotBlank()) {
                fallbacks += template.replace("/tvdb/", "/tmdb/").replace("{tvdb_id}", ids.tmdb)
            }
        }
    }
    return fallbacks
}

/**
 * Placeholder names in [template], lowercased and with pipe lists split, each paired with whether
 * it is soft — optional, or one of several alternatives, so no single member is required.
 */
private fun placeholderNames(template: String): List<Pair<String, Boolean>> =
    CUSTOM_POSTER_PLACEHOLDER.findAll(template).flatMap { match ->
        val names = match.groupValues[1].lowercase().split('|')
        val soft = match.groupValues[2].isNotEmpty() || names.size > 1
        names.map { it to soft }
    }.toList()

/**
 * False when [template] uses a placeholder upstream's clients do not understand, so syncing it to
 * them would send a literal `{tmdb_key}` to the service.
 */
internal fun customPosterTemplateIsPortable(template: String): Boolean =
    placeholderNames(template).none { (name, _) -> name in CUSTOM_POSTER_KEY_NAMES }

/**
 * Routes a catalog item's art through the poster service: the portrait template replaces
 * [MetaPreview.poster] (keeping the original as [MetaPreview.posterFallback], because the service
 * does not have art for everything), and the landscape template fills [MetaPreview.landscapePoster],
 * which every landscape card prefers over the cropped backdrop.
 *
 * [tmdbId] is nullable because not every row is TMDB-addressed: a title taken from local watch
 * progress carries whatever id its addon used (`tt…`, `kitsu:…`). The raw `{id}` placeholder can
 * still identify it, and [customPosterUrl] already treats the other ids as supplemental once `{id}`
 * is filled — so a missing TMDB id is a reason to send less, not a reason to send nothing.
 */
internal fun MetaPreview.withCustomPosters(
    settings: CustomPosterSettings,
    imdbId: String?,
    tmdbId: Int?,
    keys: CustomPosterKeys = CustomPosterKeys(),
): MetaPreview {
    if (!settings.isActive) return this
    fun urlFor(shape: CustomPosterShape): String? = customPosterUrl(
        settings = settings,
        imdbId = imdbId,
        tmdbId = (tmdbId ?: posterServiceAddonTmdbId())?.toString(),
        tvdbId = posterServiceAddonTvdbId(),
        type = metaLookupType?.takeIf { it.isNotBlank() } ?: type,
        // A filename-resolved cloud row's own id names a file; its resolved title's id names the art.
        stremioId = metadataId,
        shape = shape,
        keys = keys,
    )
    var result = this
    // A row the addon declared landscape or square shows `poster` in that shape, so 2:3 art would
    // be cropped to a sliver; such rows only take the landscape template (as upstream).
    val portrait = if (posterShape == PosterShape.Poster) urlFor(CustomPosterShape.Portrait) else null
    portrait?.takeIf { it != poster }?.let { custom ->
        result = result.copy(
            poster = custom,
            posterFallback = poster ?: posterFallback,
        )
    }
    urlFor(CustomPosterShape.Landscape)?.takeIf { it != landscapePoster }?.let { custom ->
        result = result.copy(landscapePoster = custom)
    }
    return result
}

/**
 * [withCustomPosters] for a row that knows only its content id, filling in whichever id the
 * templates name first.
 *
 * Rows arrive knowing one id: a TMDB recommendation its TMDB id, a watch-progress entry whatever
 * its addon used, a Trakt related title one of the two. [resolveCustomPosterIds] fills in only what
 * the templates actually name, so a template wanting just `{id}` costs no lookups at all; the rest
 * are cached, single-flighted TMDB `/find` calls — one request per title ever.
 *
 * Anime addressed by a native id is left alone unless a template asks for those ids: kitsu/MAL ids
 * belong to the franchise, so a poster service hands back season one's art for every season.
 */
internal suspend fun MetaPreview.withResolvedCustomPosters(
    settings: CustomPosterSettings,
    keys: CustomPosterKeys,
): MetaPreview {
    if (!settings.isActive) return this
    if (metadataId.isAnimeNativeId() && !settings.customPosterTemplateUsesNativeAnimeId()) return this
    val resolved = runCatching {
        resolveCustomPosterIds(
            settings = settings,
            imdbId = metadataId.takeIf { it.startsWith("tt") },
            tmdbId = metadataId.takeIf { it.startsWith("tmdb:") }?.removePrefix("tmdb:")?.substringBefore(":")?.toIntOrNull()
                ?: posterServiceAddonTmdbId(),
            type = metaLookupType?.takeIf { it.isNotBlank() } ?: type,
        )
    }.getOrNull() ?: return this
    // A template naming {tmdb_id} that gets a blank one is not merely a poorer request — it is a
    // rejected one. PostersPlus answers 400 for `tmdb_id=&imdb_id=tt…` while serving both-blank
    // happily, so an unresolved id here would replace working art with a broken image. Keeping
    // what we have is the better failure.
    if (resolved.tmdbId == null && settings.customPosterTemplateNeedsTmdbId()) {
        log.d { "Poster service skipped for $id: template needs a TMDB id and none resolved" }
        return this
    }
    return withCustomPosters(
        settings = settings,
        imdbId = resolved.imdbId,
        tmdbId = resolved.tmdbId,
        keys = keys,
    )
}

/**
 * The addon's own TMDB/TVDB ids for this row, when the row is addressed by its own id. A
 * filename-resolved cloud row's addon ids describe the file entry, not the title it resolved to.
 */
internal fun MetaPreview.posterServiceAddonTmdbId(): Int? = addonTmdbId.takeIf { metaLookupId.isNullOrBlank() }

internal fun MetaPreview.posterServiceAddonTvdbId(): String? = addonTvdbId.takeIf { metaLookupId.isNullOrBlank() }

/** True when an active template can only be filled in with an IMDb id. */
internal fun CustomPosterSettings.customPosterTemplateNeedsImdbId(): Boolean =
    activeTemplates().any { template -> placeholderNames(template).any { (name, soft) -> name == "imdb_id" && !soft } }

/** True when an active template can only be filled in with a TMDB id. */
internal fun CustomPosterSettings.customPosterTemplateNeedsTmdbId(): Boolean =
    activeTemplates().any { template -> placeholderNames(template).any { (name, soft) -> name == "tmdb_id" && !soft } }

private val NATIVE_ANIME_CAPABLE_PLACEHOLDERS = setOf(
    "id", "typed_id", "anilist_id", "kitsu_id", "mal_id", "anidb_id",
)

/** True when a raw Stremio id or a split native anime id can contribute to an active template. */
internal fun CustomPosterSettings.customPosterTemplateUsesNativeAnimeId(): Boolean =
    activeTemplates().any { template ->
        placeholderNames(template).any { (name, _) -> name in NATIVE_ANIME_CAPABLE_PLACEHOLDERS }
    }

/** A stable token for caches whose entries embed poster-service URLs. */
internal fun CustomPosterSettings.cacheToken(): String =
    if (isActive) "poster:${templateFor(CustomPosterShape.Portrait).hashCode()}:${templateFor(CustomPosterShape.Landscape).hashCode()}" else "poster:off"

internal data class CustomPosterIds(val imdbId: String?, val tmdbId: Int?)

/**
 * Derives whichever id the configured templates name but the caller does not have.
 *
 * Library items arrive knowing one id: a SIMKL entry is addressed by its IMDb id, a locally matched
 * file by the TMDB id it was matched on, a title fixed by hand by whichever id was typed. A template
 * naming both (PostersPlus's query form) therefore can't be filled for most of a library — not
 * because the id is unknowable, but because nobody asked TMDB for the other half.
 *
 * Both lookups are TMDB `/find` calls that `TmdbService` caches and single-flights, so the cost is
 * one request per title ever. Still not free, so it is only paid when a template actually names
 * the missing id, and never on the composition path — callers run this from a background pass and
 * persist the result.
 */
internal suspend fun resolveCustomPosterIds(
    settings: CustomPosterSettings,
    imdbId: String?,
    tmdbId: Int?,
    type: String,
): CustomPosterIds {
    var imdb = imdbId?.trim()?.takeIf { it.isNotBlank() }
    var tmdb = tmdbId
    if (!settings.isActive) return CustomPosterIds(imdb, tmdb)

    if (tmdb == null && imdb != null && settings.customPosterTemplateNeedsTmdbId()) {
        tmdb = runCatching { TmdbService.ensureTmdbId(imdb, type) }.getOrNull()?.toIntOrNull()
    }
    if (imdb == null && tmdb != null && settings.customPosterTemplateNeedsImdbId()) {
        imdb = runCatching { TmdbService.tmdbToImdb(tmdb, type) }.getOrNull()?.takeIf { it.isNotBlank() }
    }
    return CustomPosterIds(imdb, tmdb)
}
