package com.nuvio.app.features.mdblist

import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.metadata.MediaIdResolver
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingMutationResult
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingRatingWriter
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Writes title-level 1-10 ratings to the user's native MDBList rating history. */
internal object MdbListRatingWriter : TrackingRatingWriter {
    override val providerId: TrackingProviderId = TrackingProviderId.MDBLIST

    private val json = Json { encodeDefaults = false; explicitNulls = false }

    override suspend fun setRating(
        profileId: Int,
        media: TrackingMediaReference,
        rating: Int?,
    ): TrackingMutationResult {
        if (profileId != ProfileRepository.activeProfileId) {
            return TrackingMutationResult(attemptedCount = 1, notFoundCount = 1)
        }
        require(rating == null || rating in 1..10) { "MDBList ratings must be between 1 and 10" }
        val catalog = media.catalog ?: return TrackingMutationResult(1, notFoundCount = 1)
        val apiKey = MdbListSettingsRepository.trackingApiKey()
            ?: error("MDBList tracking is not connected")
        val resolved = MediaIdResolver.resolve(
            contentType = catalog.contentType,
            parentMetaId = catalog.contentId,
            videoId = null,
            title = media.title,
            isAnimeHint = media.kind == TrackingMediaKind.ANIME,
        )
        val ids = MdbListScrobbleRepository.MdbListIds(
            imdb = resolved.imdb?.takeIf(String::isNotBlank),
            tmdb = resolved.tmdb,
            tvdb = resolved.tvdb,
            trakt = resolved.trakt,
        )
        if (!ids.hasAny()) return TrackingMutationResult(1, notFoundCount = 1)

        val body = buildRatingBody(
            ids = ids,
            mediaType = ratingMediaType(
                kind = media.kind,
                preferredTmdbMediaType = resolved.preferredTmdbMediaType,
                contentType = catalog.contentType,
            ),
            rating = rating,
        )
        val endpoint = if (rating == null) "/sync/ratings/remove" else "/sync/ratings"
        val response = httpRequestRaw(
            method = "POST",
            url = "$MDBLIST_BASE_URL$endpoint?apikey=${apiKey.encodeURLParameter()}",
            headers = mapOf("Accept" to "application/json", "Content-Type" to "application/json"),
            body = body,
        )
        if (response.status !in 200..299) {
            error("MDBList rating update failed (${response.status}): ${response.body.take(200)}")
        }
        return TrackingMutationResult(attemptedCount = 1)
    }

    internal fun buildRatingBody(
        ids: MdbListScrobbleRepository.MdbListIds,
        mediaType: String,
        rating: Int?,
    ): String {
        val entry = RatingEntry(ids = ids, rating = rating)
        val request = if (mediaType == "movie") {
            RatingRequest(movies = listOf(entry))
        } else {
            RatingRequest(shows = listOf(entry))
        }
        return json.encodeToString(request)
    }

    internal fun ratingMediaType(
        kind: TrackingMediaKind,
        preferredTmdbMediaType: String?,
        contentType: String,
    ): String = when {
        kind == TrackingMediaKind.MOVIE -> "movie"
        preferredTmdbMediaType.equals("movie", ignoreCase = true) -> "movie"
        contentType.trim().lowercase() in setOf("movie", "film") -> "movie"
        else -> "show"
    }

    private fun MdbListScrobbleRepository.MdbListIds.hasAny(): Boolean =
        !imdb.isNullOrBlank() || tmdb != null || tvdb != null || trakt != null || !mdblist.isNullOrBlank()

    @Serializable
    private data class RatingRequest(
        val movies: List<RatingEntry> = emptyList(),
        val shows: List<RatingEntry> = emptyList(),
    )

    @Serializable
    private data class RatingEntry(
        val ids: MdbListScrobbleRepository.MdbListIds,
        val rating: Int? = null,
    )
}
