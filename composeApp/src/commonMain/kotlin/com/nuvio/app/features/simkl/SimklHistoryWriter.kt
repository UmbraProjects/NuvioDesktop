package com.nuvio.app.features.simkl

import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingHistoryItem
import com.nuvio.app.features.tracking.TrackingHistoryWriter
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingMutationResult
import com.nuvio.app.features.tracking.TrackingProviderId
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object SimklHistoryWriter : TrackingHistoryWriter {
    override val providerId: TrackingProviderId = TrackingProviderId.SIMKL

    private const val BASE_URL = "https://api.simkl.com"
    private val json = Json { encodeDefaults = false; explicitNulls = false }

    override suspend fun addToHistory(
        profileId: Int,
        items: Collection<TrackingHistoryItem>,
    ): TrackingMutationResult = mutate(
        profileId = profileId,
        media = items.map(TrackingHistoryItem::media),
        endpoint = "/sync/history",
    )

    override suspend fun removeFromHistory(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
    ): TrackingMutationResult = mutate(
        profileId = profileId,
        media = items,
        endpoint = "/sync/history/remove",
    )

    private suspend fun mutate(
        profileId: Int,
        media: Collection<TrackingMediaReference>,
        endpoint: String,
    ): TrackingMutationResult {
        if (profileId != ProfileRepository.activeProfileId) {
            return TrackingMutationResult(media.size, notFoundCount = media.size)
        }
        val headers = SimklAuthRepository.authorizedHeaders()
            ?: error("SIMKL is not connected")
        val resolved = media.mapNotNull { reference -> reference.toHistoryEntry() }
        if (resolved.isEmpty()) {
            return TrackingMutationResult(media.size, notFoundCount = media.size)
        }
        val response = httpRequestRaw(
            method = "POST",
            url = SimklAuthRepository.appendParams("$BASE_URL$endpoint"),
            headers = headers,
            body = json.encodeToString(resolved.toRequest()),
        )
        if (response.status !in 200..299) {
            error("SIMKL watched-history update failed (${response.status}): ${response.body.take(200)}")
        }
        SimklSettingsRepository.setLastLibraryActivitiesAt("")
        // The watching-list seed cache is gated on its own activities stamp, so without this a
        // history change is invisible to Continue Watching until SIMKL happens to bump that stamp
        // — the "watching-list activities unchanged, skipping re-fetch" path keeps serving the
        // episode this request just added or removed.
        SimklSettingsRepository.setLastCwActivitiesAt("")
        SimklProgressRepository.refreshAsync()
        return TrackingMutationResult(
            attemptedCount = media.size,
            notFoundCount = media.size - resolved.size,
        )
    }

    private suspend fun TrackingMediaReference.toHistoryEntry(): HistoryEntry? {
        val catalog = catalog ?: return null
        val anime = kind == TrackingMediaKind.ANIME
        return when (val item = SimklScrobbleRepository.buildItem(
            contentType = catalog.contentType,
            parentMetaId = catalog.contentId,
            videoId = catalog.videoId,
            title = title,
            seasonNumber = episode?.season,
            episodeNumber = episode?.number,
            isAnime = anime,
        )) {
            is SimklScrobbleItem.Movie -> HistoryEntry.Title(item.title, item.ids, kind)
            is SimklScrobbleItem.Episode -> HistoryEntry.Episode(
                title = item.showTitle,
                ids = item.ids,
                season = item.season,
                episode = item.number,
                anime = item.isAnime,
            )
            null -> null
        }
    }

    private fun Collection<HistoryEntry>.toRequest(): HistoryRequest {
        fun titleEntries(kind: TrackingMediaKind) = filterIsInstance<HistoryEntry.Title>()
            .filter { it.kind == kind }
            .map { HistoryMedia(it.title, it.ids) }

        val showEpisodes = filterIsInstance<HistoryEntry.Episode>().filterNot(HistoryEntry.Episode::anime)
        val shows = showEpisodes.groupBy { it.ids.stableKey() }.values.map { entries ->
            HistoryMedia(
                title = entries.first().title,
                ids = entries.first().ids,
                seasons = entries.groupBy(HistoryEntry.Episode::season).map { (season, episodes) ->
                    HistorySeason(season, episodes.map(HistoryEntry.Episode::episode).distinct().map(::HistoryEpisode))
                },
            )
        } + titleEntries(TrackingMediaKind.SHOW)

        val animeEpisodes = filterIsInstance<HistoryEntry.Episode>().filter(HistoryEntry.Episode::anime)
        val anime = animeEpisodes.groupBy { it.ids.stableKey() }.values.map { entries ->
            HistoryMedia(
                title = entries.first().title,
                ids = entries.first().ids,
                episodes = entries.map(HistoryEntry.Episode::episode).distinct().map(::HistoryEpisode),
            )
        } + titleEntries(TrackingMediaKind.ANIME)

        val movies = titleEntries(TrackingMediaKind.MOVIE)
        return HistoryRequest(movies = movies, shows = shows, anime = anime)
    }

    private fun SimklScrobbleRepository.SimklIds.stableKey(): String =
        simkl?.let { "simkl:$it" } ?: imdb ?: tmdb?.let { "tmdb:$it" }
            ?: tvdb?.let { "tvdb:$it" } ?: mal?.let { "mal:$it" } ?: toString()

    private sealed interface HistoryEntry {
        data class Title(
            val title: String?,
            val ids: SimklScrobbleRepository.SimklIds,
            val kind: TrackingMediaKind,
        ) : HistoryEntry
        data class Episode(
            val title: String?,
            val ids: SimklScrobbleRepository.SimklIds,
            val season: Int,
            val episode: Int,
            val anime: Boolean,
        ) : HistoryEntry
    }

    @Serializable private data class HistoryRequest(
        val movies: List<HistoryMedia> = emptyList(),
        val shows: List<HistoryMedia> = emptyList(),
        val anime: List<HistoryMedia> = emptyList(),
    )
    @Serializable private data class HistoryMedia(
        val title: String? = null,
        val ids: SimklScrobbleRepository.SimklIds,
        val seasons: List<HistorySeason> = emptyList(),
        val episodes: List<HistoryEpisode> = emptyList(),
    )
    @Serializable private data class HistorySeason(val number: Int, val episodes: List<HistoryEpisode>)
    @Serializable private data class HistoryEpisode(val number: Int)
}
