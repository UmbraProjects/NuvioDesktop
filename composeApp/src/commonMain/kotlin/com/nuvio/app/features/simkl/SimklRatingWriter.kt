package com.nuvio.app.features.simkl

import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingMutationResult
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingRatingWriter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import com.nuvio.app.features.tracking.SavedRatingRow
import com.nuvio.app.features.tracking.SavedRatingsCache
import com.nuvio.app.features.tracking.ratingFor
import com.nuvio.app.features.tracking.savedRatingIds
import com.nuvio.app.features.tracking.savedRatingRows

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
        if (!SimklAuthRepository.hasUsableToken()) error("SIMKL is not connected")
        val response = simklRequest(
            method = "POST",
            url = SimklAuthRepository.appendParams("$BASE_URL$endpoint"),
            body = body,
        )
        if (response.status !in 200..299) {
            error("SIMKL rating update failed (${response.status}): ${response.body.take(200)}")
        }
        savedRatings.applyWrite(
            keys = RATING_TYPES.map { "$profileId:$it" },
            target = item.ids.savedRatingTarget(),
            rating = rating,
        )
        return TrackingMutationResult(attemptedCount = 1)
    }

    private val savedRatings = SavedRatingsCache()
    private val RATING_TYPES = listOf("movies", "shows", "anime")

    /**
     * The user's own score from `GET /sync/ratings/{type}/1,...,10` (rated items only). A show is
     * looked for under both `shows` and `anime`, since SIMKL files anime under its own type.
     */
    override suspend fun currentRating(profileId: Int, media: TrackingMediaReference): Int? {
        if (profileId != ProfileRepository.activeProfileId) return null
        if (!SimklAuthRepository.hasUsableToken()) return null
        val catalog = media.catalog ?: return null
        val item = SimklScrobbleRepository.buildItem(
            contentType = catalog.contentType,
            parentMetaId = catalog.contentId,
            videoId = null,
            title = media.title,
            seasonNumber = null,
            episodeNumber = null,
            isAnime = media.kind == TrackingMediaKind.ANIME,
        ) as? SimklScrobbleItem.Movie ?: return null
        val target = item.ids.savedRatingTarget()
        if (target.isEmpty()) return null
        val types = when (media.kind) {
            TrackingMediaKind.MOVIE -> listOf("movies")
            TrackingMediaKind.ANIME -> listOf("anime", "shows")
            TrackingMediaKind.SHOW -> listOf("shows", "anime")
        }
        for (type in types) {
            val rows = savedRatings.rows("$profileId:$type") { fetchRatedRows(type) }
            rows.ratingFor(target)?.let { return it }
        }
        return null
    }

    private suspend fun fetchRatedRows(type: String): List<SavedRatingRow> {
        val response = simklRequest(
            method = "GET",
            url = SimklAuthRepository.appendParams("$BASE_URL/sync/ratings/$type/1,2,3,4,5,6,7,8,9,10"),
            allowLargeResponse = true,
        )
        if (response.status !in 200..299) {
            error("SIMKL ratings lookup failed (${response.status})")
        }
        return parseRatedRows(response.body)
    }

    /** Rows of every type array; the media sits under `movie` or `show` (anime included). */
    internal fun parseRatedRows(body: String): List<SavedRatingRow> {
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return emptyList()
        return RATING_TYPES.flatMap { type ->
            (root[type] as? JsonArray).savedRatingRows(
                ratingKeys = listOf("user_rating"),
                mediaKeys = listOf("movie", "show", "anime"),
            )
        }.map { row ->
            row.copy(
                ids = row.ids
                    .filterNot { (kind, value) -> kind == "imdb" && value == PLACEHOLDER_IMDB_ID }
                    .mapKeys { (kind, _) -> if (kind == "al") "anilist" else kind },
            )
        }
    }

    private fun SimklScrobbleRepository.SimklIds.savedRatingTarget(): Map<String, String> = savedRatingIds(
        "simkl" to simkl,
        "imdb" to imdb?.takeIf { it != PLACEHOLDER_IMDB_ID },
        "tmdb" to tmdb,
        "tvdb" to tvdb,
        "mal" to mal,
        "kitsu" to kitsu,
        "anilist" to anilist,
        "anidb" to anidb,
    )

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
