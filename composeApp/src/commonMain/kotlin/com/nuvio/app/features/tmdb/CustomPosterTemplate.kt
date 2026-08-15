package com.nuvio.app.features.tmdb

import com.nuvio.app.features.home.MetaPreview

/**
 * Builds a poster URL from the user's custom poster-service template (PostersPlus, RPDB, a
 * self-hosted service, …). Placeholders: `{id}`, `{imdb_id}`, `{tmdb_id}`, `{anilist_id}`,
 * `{kitsu_id}`, `{mal_id}`, `{type}`, `{tmdb_key}`, `{mdblist_key}`. `{id}` is the raw Stremio meta
 * id (for example `kitsu:395`); id placeholders may end in `?` when the service accepts alternative
 * ids and a missing value should be substituted as empty.
 *
 * **Ids and keys behave oppositely, because they answer different questions.**
 *
 * A strict id is the subject of the request, so a placeholder the caller cannot fill normally means
 * **no URL at all**: blanking it produces a request that is malformed rather than partial. The one
 * exception is a populated `{id}`: it already identifies the subject, so split provider ids in the
 * same template are supplemental and may be blank. Optional id placeholders remain available for
 * services that accept several alternatives without a raw Stremio id.
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
 * Returns null when the template is off/blank, a strict id it names is unknown, or no id is known at
 * all — callers then keep whatever poster they already had (a plain TMDB image, or the addon's own).
 */
internal fun customPosterUrl(
    settings: TmdbSettings,
    imdbId: String?,
    tmdbId: String?,
    type: String,
    stremioId: String? = null,
    anilistId: String? = null,
    kitsuId: String? = null,
    malId: String? = null,
    mdbListApiKey: String? = null,
): String? {
    if (!settings.libraryPosterEnabled) return null
    val template = settings.libraryPosterUrlTemplate
    if (template.isBlank()) return null

    val ids = linkedMapOf(
        "id" to stremioId?.trim().orEmpty(),
        "imdb_id" to imdbId?.trim().orEmpty(),
        "tmdb_id" to tmdbId?.trim().orEmpty(),
        "anilist_id" to anilistId?.trim().orEmpty(),
        "kitsu_id" to kitsuId?.trim().orEmpty(),
        "mal_id" to malId?.trim().orEmpty(),
    )
    if (ids.values.all { it.isBlank() }) return null
    val hasRawId = ids.getValue("id").isNotBlank() &&
        Regex("\\{id\\??}", RegexOption.IGNORE_CASE).containsMatchIn(template)

    var result = template
    ids.forEach { (name, value) ->
        // Be forgiving about pasted placeholder casing (`{kitsu_ID}` is unambiguous). A trailing
        // question mark makes an id optional, so a service accepting several alternative ids can
        // receive the known ones without suppressing the whole poster URL.
        val placeholder = Regex("\\{${Regex.escape(name)}(\\?)?}", RegexOption.IGNORE_CASE)
        val matches = placeholder.findAll(result).toList()
        val hasMissingStrictPlaceholder = value.isBlank() && matches.any { it.groupValues[1].isEmpty() }
        if (hasMissingStrictPlaceholder && (name == "id" || !hasRawId)) return null
        result = placeholder.replace(result, value)
    }

    return result
        .replace("{type}", type)
        .replace("{tmdb_key}", settings.apiKey.trim())
        .replace("{mdblist_key}", mdbListApiKey?.trim().orEmpty())
}

/** Applies the Library poster-service policy to a TMDB-backed catalog item. */
internal fun MetaPreview.withCustomLibraryPoster(
    settings: TmdbSettings,
    imdbId: String?,
    tmdbId: Int,
    mdbListApiKey: String? = null,
): MetaPreview {
    val custom = customPosterUrl(
        settings = settings,
        imdbId = imdbId,
        tmdbId = tmdbId.toString(),
        type = type,
        stremioId = id,
        mdbListApiKey = mdbListApiKey,
    ) ?: return this
    if (custom == poster) return this
    return copy(
        poster = custom,
        posterFallback = poster ?: posterFallback,
    )
}

/** True when the configured template can only be filled in with an IMDb id. */
internal fun TmdbSettings.customPosterTemplateNeedsImdbId(): Boolean =
    libraryPosterEnabled && Regex("\\{imdb_id}", RegexOption.IGNORE_CASE)
        .containsMatchIn(libraryPosterUrlTemplate)

/** True when the configured template can only be filled in with a TMDB id. */
internal fun TmdbSettings.customPosterTemplateNeedsTmdbId(): Boolean =
    libraryPosterEnabled && Regex("\\{tmdb_id}", RegexOption.IGNORE_CASE)
        .containsMatchIn(libraryPosterUrlTemplate)

/** True when a raw Stremio id or a split native anime id can contribute to the template. */
internal fun TmdbSettings.customPosterTemplateUsesNativeAnimeId(): Boolean {
    if (!libraryPosterEnabled) return false
    return Regex("\\{(?:id|(?:anilist|kitsu|mal)_id)\\??}", RegexOption.IGNORE_CASE)
        .containsMatchIn(libraryPosterUrlTemplate)
}

internal data class CustomPosterIds(val imdbId: String?, val tmdbId: Int?)

/**
 * Derives whichever id the configured template names but the caller does not have.
 *
 * Library items arrive knowing one id: a SIMKL entry is addressed by its IMDb id, a locally matched
 * file by the TMDB id it was matched on, a title fixed by hand by whichever id was typed. A template
 * naming both (PostersPlus's query form) therefore can't be filled for most of a library — not
 * because the id is unknowable, but because nobody asked TMDB for the other half.
 *
 * Both lookups are TMDB `/find` calls that `TmdbService` caches and single-flights, so the cost is
 * one request per title ever. Still not free, so it is only paid when the template actually names
 * the missing id, and never on the composition path — callers run this from a background pass and
 * persist the result.
 */
internal suspend fun resolveCustomPosterIds(
    settings: TmdbSettings,
    imdbId: String?,
    tmdbId: Int?,
    type: String,
): CustomPosterIds {
    var imdb = imdbId?.trim()?.takeIf { it.isNotBlank() }
    var tmdb = tmdbId
    if (!settings.libraryPosterEnabled) return CustomPosterIds(imdb, tmdb)

    if (tmdb == null && imdb != null && settings.customPosterTemplateNeedsTmdbId()) {
        tmdb = runCatching { TmdbService.ensureTmdbId(imdb, type) }.getOrNull()?.toIntOrNull()
    }
    if (imdb == null && tmdb != null && settings.customPosterTemplateNeedsImdbId()) {
        imdb = runCatching { TmdbService.tmdbToImdb(tmdb, type) }.getOrNull()?.takeIf { it.isNotBlank() }
    }
    return CustomPosterIds(imdb, tmdb)
}
