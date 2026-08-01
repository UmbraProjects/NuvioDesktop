package com.nuvio.app.features.simkl

import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingMutationResult
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingRatingWriter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Writes a title-level 1-10 rating through SIMKL's sync ratings endpoint. */
internal object SimklRatingWriter : TrackingRatingWriter {
    private const val BASE_URL = "https://api.simkl.com"
    private val json = Json { encodeDefaults = false; explicitNulls = false }

    override val providerId: TrackingProviderId = TrackingProviderId.SIMKL

    override suspend fun setRating(
        profileId: Int,
        media: TrackingMediaReference,
        rating: Int?,
    ): TrackingMutationResult {
        if (profileId != ProfileRepository.activeProfileId) {
            return TrackingMutationResult(attemptedCount = 1, notFoundCount = 1)
        }
        require(rating == null || rating in 1..10) { "SIMKL ratings must be between 1 and 10" }
        val catalog = media.catalog ?: return TrackingMutationResult(1, notFoundCount = 1)
        val item = SimklScrobbleRepository.buildItem(
            contentType = catalog.contentType,
            parentMetaId = catalog.contentId,
            videoId = null,
            title = media.title,
            seasonNumber = null,
            episodeNumber = null,
            isAnime = media.kind == TrackingMediaKind.ANIME,
        ) as? SimklScrobbleItem.Movie
            ?: return TrackingMutationResult(1, notFoundCount = 1)

        val body = buildRatingBody(item, media.kind, rating)
        val endpoint = if (rating == null) "/sync/ratings/remove" else "/sync/ratings"
        val headers = SimklAuthRepository.authorizedHeaders()
            ?: error("SIMKL is not connected")
        val response = httpRequestRaw(
            method = "POST",
            url = SimklAuthRepository.appendParams("$BASE_URL$endpoint"),
            headers = headers,
            body = body,
        )
        if (response.status !in 200..299) {
            error("SIMKL rating update failed (${response.status}): ${response.body.take(200)}")
        }
        return TrackingMutationResult(attemptedCount = 1)
    }

    internal fun buildRatingBody(
        item: SimklScrobbleItem.Movie,
        kind: TrackingMediaKind,
        rating: Int?,
    ): String {
        val entry = SimklRatingEntry(title = item.title, ids = item.ids, rating = rating)
        val request = when (kind) {
            TrackingMediaKind.MOVIE -> SimklRatingRequest(movies = listOf(entry))
            TrackingMediaKind.ANIME -> SimklRatingRequest(anime = listOf(entry))
            TrackingMediaKind.SHOW -> SimklRatingRequest(shows = listOf(entry))
        }
        return json.encodeToString(request)
    }

    @Serializable
    private data class SimklRatingRequest(
        val movies: List<SimklRatingEntry> = emptyList(),
        val shows: List<SimklRatingEntry> = emptyList(),
        val anime: List<SimklRatingEntry> = emptyList(),
    )

    @Serializable
    private data class SimklRatingEntry(
        val title: String? = null,
        val ids: SimklScrobbleRepository.SimklIds,
        val rating: Int? = null,
    )
}
