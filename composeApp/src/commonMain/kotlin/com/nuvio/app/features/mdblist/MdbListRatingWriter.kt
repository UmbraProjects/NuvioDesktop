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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import com.nuvio.app.features.tracking.SavedRatingRow
import com.nuvio.app.features.tracking.SavedRatingsCache
import com.nuvio.app.features.tracking.ratingFor
import com.nuvio.app.features.tracking.savedRatingIds
import com.nuvio.app.features.tracking.savedRatingRows

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

        val mediaType = ratingMediaType(
            kind = media.kind,
            preferredTmdbMediaType = resolved.preferredTmdbMediaType,
            contentType = catalog.contentType,
        )
        val body = buildRatingBody(
            ids = ids,
            mediaType = mediaType,
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
        savedRatings.applyWrite(
            keys = listOf("$profileId:$apiKey"),
            target = ids.savedRatingTarget() + (MEDIA_KIND to mediaType),
            rating = rating,
        )
        return TrackingMutationResult(attemptedCount = 1)
    }

    private val savedRatings = SavedRatingsCache()

    /**
     * The user's own score from `GET /sync/ratings` (paged, all titles). Movies and shows are kept
     * apart, as a TMDB id means a different title in each.
     */
    override suspend fun currentRating(profileId: Int, media: TrackingMediaReference): Int? {
        if (profileId != ProfileRepository.activeProfileId) return null
        val catalog = media.catalog ?: return null
        val apiKey = MdbListSettingsRepository.trackingApiKey() ?: return null
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
        val target = ids.savedRatingTarget()
        if (target.isEmpty()) return null
        val movie = ratingMediaType(media.kind, resolved.preferredTmdbMediaType, catalog.contentType) == "movie"
        val rows = savedRatings.rows("$profileId:$apiKey") { fetchRatedRows(apiKey) }
        return rows.ratingFor(target + (MEDIA_KIND to if (movie) "movie" else "show"))
    }

    private suspend fun fetchRatedRows(apiKey: String): List<SavedRatingRow> {
        val collected = mutableListOf<SavedRatingRow>()
        var cursor: String? = null
        var offset = 0
        repeat(MAX_RATING_PAGES) {
            val query = buildString {
                append("apikey=${apiKey.encodeURLParameter()}&limit=$RATING_PAGE_LIMIT")
                val next = cursor
                if (next != null) append("&cursor=${next.encodeURLParameter()}")
                else if (offset > 0) append("&offset=$offset")
            }
            val response = httpRequestRaw(
                method = "GET",
                url = "$MDBLIST_BASE_URL/sync/ratings?$query",
                headers = mapOf("Accept" to "application/json"),
                body = "",
            )
            if (response.status !in 200..299) error("MDBList ratings lookup failed (${response.status})")
            val page = parseRatedPage(response.body)
            collected += page.rows
            cursor = page.nextCursor
            when {
                cursor != null -> Unit
                page.hasMore && page.size > 0 -> offset += page.size
                else -> return collected
            }
        }
        return collected
    }

    internal data class RatedPage(
        val rows: List<SavedRatingRow>,
        val size: Int,
        val nextCursor: String?,
        val hasMore: Boolean,
    )

    /** One page of `/sync/ratings`; each row is tagged movie or show under [MEDIA_KIND]. */
    internal fun parseRatedPage(body: String): RatedPage {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return RatedPage(emptyList(), 0, null, false)
        fun rows(key: String, kind: String): List<SavedRatingRow> {
            val array = root[key] as? JsonArray
            return array.savedRatingRows(
                ratingKeys = listOf("rating_precise", "rating"),
                mediaKeys = listOf(kind),
            ).map { it.copy(ids = it.ids + (MEDIA_KIND to kind)) }
        }
        val movies = rows("movies", "movie")
        val shows = rows("shows", "show")
        val size = listOf("movies", "shows", "seasons", "episodes").sumOf { (root[it] as? JsonArray)?.size ?: 0 }
        val pagination = root["pagination"] as? JsonObject ?: root
        val cursor = (pagination["next_cursor"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        val hasMore = (pagination["has_more"] as? JsonPrimitive)?.booleanOrNull == true
        return RatedPage(movies + shows, size, cursor, hasMore)
    }

    private fun MdbListScrobbleRepository.MdbListIds.savedRatingTarget(): Map<String, String> = savedRatingIds(
        "imdb" to imdb,
        "tmdb" to tmdb,
        "tvdb" to tvdb,
        "trakt" to trakt,
        "mdblist" to mdblist,
    )

    private const val MEDIA_KIND = "_kind"
    private const val RATING_PAGE_LIMIT = 1000
    private const val MAX_RATING_PAGES = 20

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
