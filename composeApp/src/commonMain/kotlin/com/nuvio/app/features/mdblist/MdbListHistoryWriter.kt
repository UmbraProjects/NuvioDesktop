package com.nuvio.app.features.mdblist

import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingHistoryItem
import com.nuvio.app.features.tracking.TrackingHistoryWriter
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMutationResult
import com.nuvio.app.features.tracking.TrackingProviderId
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object MdbListHistoryWriter : TrackingHistoryWriter {
    override val providerId: TrackingProviderId = TrackingProviderId.MDBLIST

    private val json = Json { encodeDefaults = false; explicitNulls = false }

    override suspend fun addToHistory(
        profileId: Int,
        items: Collection<TrackingHistoryItem>,
    ): TrackingMutationResult = mutate(
        profileId = profileId,
        media = items.map(TrackingHistoryItem::media),
        endpoint = "/sync/watched",
    )

    override suspend fun removeFromHistory(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
    ): TrackingMutationResult = mutate(
        profileId = profileId,
        media = items,
        endpoint = "/sync/watched/remove",
    )

    private suspend fun mutate(
        profileId: Int,
        media: Collection<TrackingMediaReference>,
        endpoint: String,
    ): TrackingMutationResult {
        if (profileId != ProfileRepository.activeProfileId) {
            return TrackingMutationResult(media.size, notFoundCount = media.size)
        }
        val apiKey = MdbListSettingsRepository.trackingApiKey()
            ?: error("MDBList tracking is not connected")
        val resolved = media.mapNotNull { reference -> reference.toHistoryEntry() }
        if (resolved.isEmpty()) {
            return TrackingMutationResult(media.size, notFoundCount = media.size)
        }
        val response = httpRequestRaw(
            method = "POST",
            url = "$MDBLIST_BASE_URL$endpoint?apikey=${apiKey.encodeURLParameter()}",
            headers = mapOf("Accept" to "application/json", "Content-Type" to "application/json"),
            body = json.encodeToString(resolved.toRequest()),
        )
        if (response.status !in 200..299) {
            error("MDBList watched-history update failed (${response.status}): ${response.body.take(200)}")
        }
        // The watched cache is keyed on `/sync/last_activities`; drop it so the next read reflects
        // this write immediately instead of waiting for the remote stamp to move.
        MdbListSyncRepository.clearLocalState()
        return TrackingMutationResult(
            attemptedCount = media.size,
            notFoundCount = media.size - resolved.size,
        )
    }

    private suspend fun TrackingMediaReference.toHistoryEntry(): HistoryEntry? {
        val catalog = catalog ?: return null
        return when (val item = MdbListScrobbleRepository.buildItem(
            contentType = catalog.contentType,
            parentMetaId = catalog.contentId,
            videoId = catalog.videoId,
            title = title,
            seasonNumber = episode?.season,
            episodeNumber = episode?.number,
            isAnime = kind.name == "ANIME",
        )) {
            is MdbListScrobbleItem.Movie -> HistoryEntry.Title(item.ids, kind)
            is MdbListScrobbleItem.Episode -> HistoryEntry.Episode(item.ids, item.season, item.episode)
            null -> null
        }
    }

    private fun Collection<HistoryEntry>.toRequest(): HistoryRequest {
        val titles = filterIsInstance<HistoryEntry.Title>()
        val movies = titles.filter { it.kind == TrackingMediaKind.MOVIE }.map { HistoryMovie(it.ids) }
        val shows = titles.filter { it.kind != TrackingMediaKind.MOVIE }
            .mapTo(mutableListOf()) { HistoryShow(ids = it.ids) }
        filterIsInstance<HistoryEntry.Episode>()
            .groupBy { it.ids.stableKey }
            .values
            .forEach { entries ->
                val seasons = entries.groupBy(HistoryEntry.Episode::season).map { (season, episodes) ->
                    HistorySeason(season, episodes.map(HistoryEntry.Episode::episode).distinct().map(::HistoryEpisode))
                }
                shows += HistoryShow(ids = entries.first().ids, seasons = seasons)
            }
        return HistoryRequest(movies = movies, shows = shows)
    }

    private sealed interface HistoryEntry {
        data class Title(
            val ids: MdbListScrobbleRepository.MdbListIds,
            val kind: TrackingMediaKind,
        ) : HistoryEntry
        data class Episode(
            val ids: MdbListScrobbleRepository.MdbListIds,
            val season: Int,
            val episode: Int,
        ) : HistoryEntry
    }

    @Serializable private data class HistoryRequest(
        val movies: List<HistoryMovie> = emptyList(),
        val shows: List<HistoryShow> = emptyList(),
    )
    @Serializable private data class HistoryMovie(val ids: MdbListScrobbleRepository.MdbListIds)
    @Serializable private data class HistoryShow(
        val ids: MdbListScrobbleRepository.MdbListIds,
        val seasons: List<HistorySeason> = emptyList(),
    )
    @Serializable private data class HistorySeason(val number: Int, val episodes: List<HistoryEpisode>)
    @Serializable private data class HistoryEpisode(val number: Int)
}
