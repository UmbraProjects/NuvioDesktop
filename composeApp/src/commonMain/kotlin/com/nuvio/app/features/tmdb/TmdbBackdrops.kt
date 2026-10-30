package com.nuvio.app.features.tmdb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * fork:preshow
 *
 * Response shapes for TmdbService.fetchBackdropUrls. They sit here, not in TmdbService.kt, so that
 * file only carries the one function.
 */

@Serializable
internal data class TmdbBackdropListResponse(
    val backdrops: List<TmdbBackdropEntry> = emptyList(),
)

@Serializable
internal data class TmdbBackdropEntry(
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("aspect_ratio") val aspectRatio: Double = 0.0,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
)
