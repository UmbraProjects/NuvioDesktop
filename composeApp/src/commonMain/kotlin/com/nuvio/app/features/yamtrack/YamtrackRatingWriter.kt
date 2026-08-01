package com.nuvio.app.features.yamtrack

import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.metadata.MediaIdResolver
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingMutationResult
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingRatingWriter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Writes title-level scores through Floppy's tracked-media PATCH endpoint. */
internal object YamtrackRatingWriter : TrackingRatingWriter {
    private val json = Json { encodeDefaults = false; explicitNulls = true }
    override val providerId: TrackingProviderId = TrackingProviderId.YAMTRACK

    override suspend fun setRating(
        profileId: Int,
        media: TrackingMediaReference,
        rating: Int?,
    ): TrackingMutationResult {
        if (profileId != ProfileRepository.activeProfileId) {
            return TrackingMutationResult(attemptedCount = 1, notFoundCount = 1)
        }
        require(rating == null || rating in 1..10) { "Floppy ratings must be between 1 and 10" }
        val catalog = media.catalog ?: return TrackingMutationResult(1, notFoundCount = 1)
        val (baseUrl, token) = YamtrackSettingsRepository.activeCredentials()
            ?: error("Floppy is not connected")
        val resolved = MediaIdResolver.resolve(
            contentType = catalog.contentType,
            parentMetaId = catalog.contentId,
            videoId = null,
            title = media.title,
            isAnimeHint = media.kind == TrackingMediaKind.ANIME,
        )
        val target = resolved.tmdb?.let { FloppyRatingTarget("tmdb", it.toString()) }
            ?: resolved.imdb?.takeIf(String::isNotBlank)?.let { FloppyRatingTarget("imdb", it) }
            ?: resolved.tvdb?.let { FloppyRatingTarget("tvdb", it.toString()) }
            ?: return TrackingMutationResult(1, notFoundCount = 1)
        val mediaType = floppyRatingMediaType(
            kind = media.kind,
            preferredTmdbMediaType = resolved.preferredTmdbMediaType,
            contentType = catalog.contentType,
        )
        // Floppy only permits PATCH on media the user already tracks — and watching something is
        // what tracks it, so the ordinary path is a plain PATCH with nothing before it.
        //
        // The add route is used only when the PATCH reports the title is not tracked. It used to
        // run first, unconditionally, on the assumption that adding an already-tracked title was
        // an idempotent no-op answering 409. This build raises instead, so rating anything already
        // in the library failed on the preparation step with a 500 while the PATCH that would have
        // worked was never attempted.
        var response = patchScore(baseUrl, token, mediaType, target, rating)
        if (response.status == HTTP_NOT_FOUND) {
            // Nothing tracked means nothing to clear; adding a title just to remove a score it
            // never had would be a surprising side effect of "clear rating".
            if (rating == null) return TrackingMutationResult(attemptedCount = 1)
            val addResponse = httpRequestRaw(
                method = "POST",
                url = "$baseUrl/api/v1/media/$mediaType",
                headers = headers(token),
                body = json.encodeToString(FloppyTrackRequest(target.source, target.id)),
            )
            if (addResponse.status !in 200..299) {
                error(
                    "Floppy could not track ${target.source}:${target.id} for rating " +
                        "(${addResponse.status}): ${addResponse.body.take(200)}",
                )
            }
            response = patchScore(baseUrl, token, mediaType, target, rating)
        }
        if (response.status !in 200..299) {
            error("Floppy rating update failed (${response.status}): ${response.body.take(200)}")
        }
        return TrackingMutationResult(attemptedCount = 1)
    }

    private suspend fun patchScore(
        baseUrl: String,
        token: String,
        mediaType: String,
        target: FloppyRatingTarget,
        rating: Int?,
    ) = httpRequestRaw(
        method = "PATCH",
        url = "$baseUrl/api/v1/media/$mediaType/${target.source}/${target.id}",
        headers = headers(token),
        body = buildScoreBody(rating),
    )

    private const val HTTP_NOT_FOUND = 404

    internal fun buildScoreBody(rating: Int?): String =
        json.encodeToString(FloppyScoreRequest(rating))

    /**
     * The Floppy media type a title-level rating is addressed to — only ever `movie` or `tv`.
     *
     * Floppy's own `anime` media type is MAL-backed, but the ids resolved for a rating are
     * TMDB/IMDb/TVDB, so addressing `anime` with one asks the server to find an anime by a
     * franchise id. The rest of this integration — scrobble, history, library — already treats
     * anime as an ordinary movie/tv entry for exactly that reason; rating was the one place that
     * did not. [preferredTmdbMediaType] is what separates an anime film from an anime series.
     */
    internal fun floppyRatingMediaType(
        kind: TrackingMediaKind,
        preferredTmdbMediaType: String?,
        contentType: String,
    ): String = when {
        kind == TrackingMediaKind.MOVIE -> "movie"
        preferredTmdbMediaType == "movie" -> "movie"
        contentType.trim().lowercase() in setOf("movie", "film") -> "movie"
        else -> "tv"
    }

    private data class FloppyRatingTarget(val source: String, val id: String)

    @Serializable
    private data class FloppyTrackRequest(
        val source: String,
        @kotlinx.serialization.SerialName("media_id") val mediaId: String,
    )

    @Serializable
    private data class FloppyScoreRequest(val score: Int?)

    private fun headers(token: String) = mapOf(
        "Accept" to "application/json",
        "Content-Type" to "application/json",
        "Authorization" to "Bearer $token",
    )
}
