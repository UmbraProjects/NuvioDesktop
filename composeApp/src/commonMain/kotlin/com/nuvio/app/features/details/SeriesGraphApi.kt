package com.nuvio.app.features.details

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object SeriesGraphApi {
    suspend fun getSeasonRatings(tmdbId: Int): List<SeriesGraphSeasonRatingsDto> =
        requestSeasonRatings(
            baseUrl = ImdbEpisodeRatingsConfig.IMDB_RATINGS_API_BASE_URL,
            showId = tmdbId.toString(),
        )
}

internal object ImdbTapframeApi {
    suspend fun getSeasonRatings(imdbId: String): List<SeriesGraphSeasonRatingsDto> =
        requestSeasonRatings(
            baseUrl = ImdbEpisodeRatingsConfig.IMDB_TAPFRAME_API_BASE_URL,
            showId = imdbId,
        )
}

/**
 * Direct IMDb fallback for builds that do not have upstream's private ratings proxy configured.
 *
 * IMDb silently clamps the episode connection to [IMDB_GRAPHQL_PAGE_SIZE] edges per request — a
 * larger `first:` returns 200 OK with a truncated list rather than an error, which used to drop
 * every episode past roughly season 17 on long-running shows. Walk the cursor instead.
 */
internal object ImdbGraphQlApi {
    suspend fun getEpisodeRatings(imdbId: String): List<SeriesGraphEpisodeRatingDto> {
        // Outside runCatching so a failure part-way through the walk still returns the seasons that
        // did come back, rather than throwing away the whole show.
        val collected = mutableListOf<SeriesGraphEpisodeRatingDto>()
        runCatching {
            var cursor: String? = null
            var page = 0
            while (page < IMDB_GRAPHQL_MAX_PAGES) {
                val response = httpRequestRaw(
                    method = "POST",
                    url = IMDB_GRAPHQL_URL,
                    headers = mapOf(
                        "Accept" to "application/json",
                        "Content-Type" to "application/json",
                    ),
                    body = "{\"query\":${seriesGraphJson.encodeToString(episodeRatingsQuery(imdbId, cursor))}}",
                )
                if (response.status !in 200..299 || response.body.isBlank()) {
                    seriesGraphLog.w { "IMDb GraphQL ratings request failed for $imdbId (${response.status})" }
                    break
                }
                val pageResult = parseImdbGraphQlEpisodeRatingsPage(response.body)
                collected += pageResult.ratings
                page++
                cursor = pageResult.nextCursor ?: break
            }
        }.onFailure { error ->
            seriesGraphLog.w(error) { "IMDb GraphQL ratings request failed for $imdbId" }
        }
        return collected
    }

    private fun episodeRatingsQuery(imdbId: String, after: String?): String {
        val afterArg = after?.let { ", after: ${seriesGraphJson.encodeToString(it)}" }.orEmpty()
        return """
            query {
              title(id: "$imdbId") {
                episodes {
                  episodes(first: $IMDB_GRAPHQL_PAGE_SIZE$afterArg) {
                    pageInfo { hasNextPage endCursor }
                    edges {
                      node {
                        series { episodeNumber { seasonNumber episodeNumber } }
                        ratingsSummary { aggregateRating }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()
    }
}

@Serializable
internal data class SeriesGraphEpisodeRatingDto(
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val name: String? = null,
    val tconst: String? = null,
)

@Serializable
internal data class SeriesGraphSeasonRatingsDto(
    val episodes: List<SeriesGraphEpisodeRatingDto>? = null,
)

@Serializable
private data class ImdbGraphQlResponseDto(
    val data: ImdbGraphQlDataDto? = null,
)

@Serializable
private data class ImdbGraphQlDataDto(
    val title: ImdbGraphQlTitleDto? = null,
)

@Serializable
private data class ImdbGraphQlTitleDto(
    val episodes: ImdbGraphQlEpisodeConnectionContainerDto? = null,
)

@Serializable
private data class ImdbGraphQlEpisodeConnectionContainerDto(
    val episodes: ImdbGraphQlEpisodeConnectionDto? = null,
)

@Serializable
private data class ImdbGraphQlEpisodeConnectionDto(
    val edges: List<ImdbGraphQlEpisodeEdgeDto> = emptyList(),
    val pageInfo: ImdbGraphQlPageInfoDto? = null,
)

@Serializable
private data class ImdbGraphQlPageInfoDto(
    val hasNextPage: Boolean = false,
    val endCursor: String? = null,
)

@Serializable
private data class ImdbGraphQlEpisodeEdgeDto(
    val node: ImdbGraphQlEpisodeNodeDto? = null,
)

@Serializable
private data class ImdbGraphQlEpisodeNodeDto(
    val series: ImdbGraphQlSeriesDto? = null,
    val ratingsSummary: ImdbGraphQlRatingsSummaryDto? = null,
)

@Serializable
private data class ImdbGraphQlSeriesDto(
    val episodeNumber: ImdbGraphQlEpisodeNumberDto? = null,
)

@Serializable
private data class ImdbGraphQlEpisodeNumberDto(
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
)

@Serializable
private data class ImdbGraphQlRatingsSummaryDto(
    val aggregateRating: Double? = null,
)

private val seriesGraphLog = Logger.withTag("SeriesGraphApi")
private val seriesGraphJson = Json { ignoreUnknownKeys = true }

internal data class ImdbGraphQlEpisodeRatingsPage(
    val ratings: List<SeriesGraphEpisodeRatingDto>,
    val nextCursor: String?,
)

internal fun parseImdbGraphQlEpisodeRatings(body: String): List<SeriesGraphEpisodeRatingDto> =
    parseImdbGraphQlEpisodeRatingsPage(body).ratings

internal fun parseImdbGraphQlEpisodeRatingsPage(body: String): ImdbGraphQlEpisodeRatingsPage {
    val connection = seriesGraphJson.decodeFromString<ImdbGraphQlResponseDto>(body)
        .data
        ?.title
        ?.episodes
        ?.episodes
    return ImdbGraphQlEpisodeRatingsPage(
        ratings = connection?.edges.orEmpty().mapNotNull { edge ->
            val episodeNumber = edge.node?.series?.episodeNumber ?: return@mapNotNull null
            val rating = edge.node.ratingsSummary?.aggregateRating?.takeIf { it > 0.0 }
                ?: return@mapNotNull null
            SeriesGraphEpisodeRatingDto(
                seasonNumber = episodeNumber.seasonNumber ?: return@mapNotNull null,
                episodeNumber = episodeNumber.episodeNumber ?: return@mapNotNull null,
                voteAverage = rating,
            )
        },
        nextCursor = connection?.pageInfo?.takeIf { it.hasNextPage }
            ?.endCursor
            ?.takeIf(String::isNotBlank),
    )
}

private suspend fun requestSeasonRatings(
    baseUrl: String,
    showId: String,
): List<SeriesGraphSeasonRatingsDto> {
    val resolvedBaseUrl = baseUrl.trim().trimEnd('/')
    if (resolvedBaseUrl.isBlank()) return emptyList()

    return runCatching {
        val response = httpRequestRaw(
            method = "GET",
            url = "$resolvedBaseUrl/api/shows/$showId/season-ratings",
            headers = mapOf("Accept" to "application/json"),
            body = "",
        )
        if (response.status !in 200..299 || response.body.isBlank()) {
            seriesGraphLog.w { "Season ratings request failed for $showId (${response.status})" }
            return emptyList()
        }
        seriesGraphJson.decodeFromString<List<SeriesGraphSeasonRatingsDto>>(response.body)
    }.onFailure { error ->
        seriesGraphLog.w(error) { "Season ratings request failed for $showId" }
    }.getOrDefault(emptyList())
}

private const val IMDB_GRAPHQL_URL = "https://api.graphql.imdb.com/"

/** IMDb's hard server-side cap for the episode connection; asking for more is silently clamped. */
private const val IMDB_GRAPHQL_PAGE_SIZE = 250

/** 250 * 12 = 3000 episodes, comfortably past the longest-running series. */
private const val IMDB_GRAPHQL_MAX_PAGES = 12
