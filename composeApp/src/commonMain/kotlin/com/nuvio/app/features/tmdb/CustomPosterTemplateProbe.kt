package com.nuvio.app.features.tmdb

import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.mdblist.MdbListSettingsRepository

/**
 * A title the probe below asks for. Any long-established film works; what matters is that a failure
 * can never be "the service has no art for this one".
 */
private const val PROBE_IMDB_ID = "tt0347149"
private const val PROBE_TMDB_ID = "4935"
private const val PROBE_ANILIST_ID = "431"
private const val PROBE_KITSU_ID = "395"
private const val PROBE_MAL_ID = "431"
private const val PROBE_STREMIO_ID = "kitsu:395"
private const val PROBE_TYPE = "movie"

sealed interface CustomPosterTemplateProbe {
    data object Ok : CustomPosterTemplateProbe
    /** [detail] is the service's own words — server text, deliberately not translated. */
    data class Failed(val detail: String) : CustomPosterTemplateProbe
}

/**
 * Asks the configured poster service for one known title and reports what it said.
 *
 * A poster service that answers with an error is otherwise completely silent: the card falls back to
 * the plain poster and the library just looks like the feature does nothing. The failures are
 * mundane and self-describing once seen — a self-hosted instance with no TMDB key of its own
 * answering `400 No TMDB API key available…`, a bad host, an expired subscription — so the fix is to
 * show the answer rather than to guess at it.
 */
internal suspend fun probeCustomPosterTemplate(settings: TmdbSettings): CustomPosterTemplateProbe {
    val url = customPosterUrl(
        settings = settings.copy(libraryPosterEnabled = true),
        imdbId = PROBE_IMDB_ID,
        tmdbId = PROBE_TMDB_ID,
        type = PROBE_TYPE,
        stremioId = PROBE_STREMIO_ID,
        anilistId = PROBE_ANILIST_ID,
        kitsuId = PROBE_KITSU_ID,
        malId = PROBE_MAL_ID,
        mdbListApiKey = MdbListSettingsRepository.snapshot().apiKey,
    ) ?: return CustomPosterTemplateProbe.Failed(
        "The template has no supported id placeholder to fill in.",
    )

    val response = runCatching {
        httpRequestRaw(method = "GET", url = url, headers = emptyMap(), body = "")
    }.getOrElse { error ->
        return CustomPosterTemplateProbe.Failed(
            error.message?.takeIf { it.isNotBlank() } ?: "The request could not be made.",
        )
    }

    if (response.status in 200..299) return CustomPosterTemplateProbe.Ok
    val detail = response.body
        .trim()
        .take(200)
        .takeIf { it.isNotBlank() }
        ?.let { ": $it" }
        .orEmpty()
    return CustomPosterTemplateProbe.Failed("The service answered HTTP ${response.status}$detail")
}
