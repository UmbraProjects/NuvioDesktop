package com.nuvio.app.features.yamtrack

import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingHistoryItem
import com.nuvio.app.features.tracking.TrackingHistoryWriter
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingMutationResult
import com.nuvio.app.features.tracking.TrackingProviderId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Explicit mark-watched/unwatched actions through Floppy's REST tracking routes. */
internal object YamtrackHistoryWriter : TrackingHistoryWriter {
    override val providerId: TrackingProviderId = TrackingProviderId.YAMTRACK
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * How many times an unwatch will re-delete before giving up.
     *
     * Both endpoints remove one play at a time, so repeats have to be drained in a loop. The cap
     * exists because the loop's exit depends on the server answering the way we expect: a hundred
     * passes over a paged history is a request storm on the user's own instance.
     */
    private const val MAX_HISTORY_DELETE_PASSES = 20

    override suspend fun addToHistory(
        profileId: Int,
        items: Collection<TrackingHistoryItem>,
    ): TrackingMutationResult = mutate(profileId, items.map(TrackingHistoryItem::media), watched = true)

    override suspend fun removeFromHistory(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
    ): TrackingMutationResult = mutate(profileId, items, watched = false)

    private suspend fun mutate(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
        watched: Boolean,
    ): TrackingMutationResult {
        if (profileId != ProfileRepository.activeProfileId) {
            return TrackingMutationResult(items.size, notFoundCount = items.size)
        }
        val (baseUrl, token) = YamtrackSettingsRepository.activeCredentials()
            ?: error("Floppy is not connected")
        var notFound = 0
        items.forEach { media ->
            val target = media.toFloppyHistoryTarget()
            if (target == null) {
                notFound += 1
                return@forEach
            }
            val succeeded = when (target) {
                is FloppyHistoryTarget.Episode -> mutateEpisode(baseUrl, token, target, watched)
                is FloppyHistoryTarget.Movie -> if (watched) {
                    YamtrackScrobbleRepository.scrobble(
                        action = "stop",
                        item = target.item,
                        progressPercent = 100f,
                        positionSeconds = null,
                        durationSeconds = null,
                    ).handled
                } else {
                    deleteMovieHistory(baseUrl, token, target.identity)
                }
            }
            if (!succeeded) notFound += 1
        }
        return TrackingMutationResult(items.size, notFoundCount = notFound)
    }

    private suspend fun TrackingMediaReference.toFloppyHistoryTarget(): FloppyHistoryTarget? {
        val catalog = catalog ?: return null
        val item = YamtrackScrobbleRepository.buildItem(
            contentType = catalog.contentType,
            parentMetaId = catalog.contentId,
            videoId = catalog.videoId,
            title = title,
            episodeTitle = episode?.title,
            seasonNumber = episode?.season,
            episodeNumber = episode?.number,
            isAnime = kind == TrackingMediaKind.ANIME,
        ) ?: return null
        val identity = item.ids.toFloppyIdentity() ?: return null
        return when (item) {
            is YamtrackScrobbleItem.Movie -> FloppyHistoryTarget.Movie(identity, item)
            is YamtrackScrobbleItem.Episode -> FloppyHistoryTarget.Episode(
                identity = identity,
                season = item.season,
                episode = item.episode,
            )
        }
    }

    private suspend fun mutateEpisode(
        baseUrl: String,
        token: String,
        target: FloppyHistoryTarget.Episode,
        watched: Boolean,
    ): Boolean {
        val headers = floppyHeaders(token)
        if (watched) {
            val response = httpRequestRaw("POST", target.watchUrl(baseUrl), headers, "{}")
            if (response.status == 404) return false
            require(response.status in 200..299) {
                "Floppy episode watch failed (${response.status}): ${response.body.take(200)}"
            }
            return true
        }

        // The endpoint removes the most recent play. Repeat so "mark unwatched" clears repeats as
        // well and does not leave the episode watched because it happened to have two play rows.
        var deleted = false
        repeat(MAX_HISTORY_DELETE_PASSES) {
            val response = httpRequestRaw("DELETE", target.watchUrl(baseUrl), headers, "")
            when (response.status) {
                in 200..299 -> deleted = true
                404 -> return deleted
                else -> error("Floppy episode unwatch failed (${response.status}): ${response.body.take(200)}")
            }
        }
        return deleted
    }

    private suspend fun deleteMovieHistory(
        baseUrl: String,
        token: String,
        identity: FloppyIdentity,
    ): Boolean {
        val headers = floppyHeaders(token)
        var deleted = false
        repeat(MAX_HISTORY_DELETE_PASSES) {
            val history = httpRequestRaw(
                "GET",
                "$baseUrl/api/v1/media/movie/${identity.source}/${identity.id}/history?limit=100&offset=0",
                headers,
                "",
            )
            if (history.status == 404) return deleted
            require(history.status in 200..299) {
                "Floppy movie history fetch failed (${history.status}): ${history.body.take(200)}"
            }
            val ids = json.decodeFromString<FloppyHistoryPage>(history.body).results
                .mapNotNull(FloppyHistoryRecord::consumptionId)
            if (ids.isEmpty()) return deleted
            var deletedThisPass = false
            ids.forEach { id ->
                val response = httpRequestRaw(
                    "DELETE",
                    "$baseUrl/api/v1/history/movie/$id",
                    headers,
                    "",
                )
                require(response.status in 200..299 || response.status == 404) {
                    "Floppy movie unwatch failed (${response.status}): ${response.body.take(200)}"
                }
                if (response.status in 200..299) deletedThisPass = true
            }
            // A pass that removed nothing will not remove anything on the next one either — the
            // history page comes back identical. Without this the loop re-fetches and re-deletes
            // the same rows for every remaining pass.
            if (!deletedThisPass) return deleted
            deleted = true
        }
        return deleted
    }

    private fun floppyHeaders(token: String) = mapOf(
        "Accept" to "application/json",
        "Content-Type" to "application/json",
        "Authorization" to "Bearer $token",
    )
}

private data class FloppyIdentity(val source: String, val id: String)

private sealed interface FloppyHistoryTarget {
    data class Movie(
        val identity: FloppyIdentity,
        val item: YamtrackScrobbleItem.Movie,
    ) : FloppyHistoryTarget

    data class Episode(
        val identity: FloppyIdentity,
        val season: Int,
        val episode: Int,
    ) : FloppyHistoryTarget {
        fun watchUrl(baseUrl: String): String =
            "$baseUrl/api/v1/media/tv/${identity.source}/${identity.id}/$season/episodes/$episode/watch"
    }
}

@Serializable
private data class FloppyHistoryPage(val results: List<FloppyHistoryRecord> = emptyList())

@Serializable
private data class FloppyHistoryRecord(
    @SerialName("consumption_id") val consumptionId: Long? = null,
)

private fun YamtrackScrobbleRepository.YamtrackIds.toFloppyIdentity(): FloppyIdentity? =
    tmdb?.takeIf(String::isNotBlank)?.let { FloppyIdentity("tmdb", it) }
        ?: imdb?.takeIf(String::isNotBlank)?.let { FloppyIdentity("imdb", it) }
        ?: tvdb?.takeIf(String::isNotBlank)?.let { FloppyIdentity("tvdb", it) }
