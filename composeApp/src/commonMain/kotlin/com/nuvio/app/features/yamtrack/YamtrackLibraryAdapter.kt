package com.nuvio.app.features.yamtrack

import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibrarySection
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingLibraryProvider
import com.nuvio.app.features.tracking.TrackingLibrarySnapshot
import com.nuvio.app.features.tracking.TrackingLibraryTab
import com.nuvio.app.features.tracking.TrackingLibraryTabKind
import com.nuvio.app.features.tracking.TrackingMembershipResolution
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.trakt.parseTraktIsoDateTimeToEpochMs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object YamtrackLibraryAdapter : TrackingLibraryProvider {
    override val providerId: TrackingProviderId = TrackingProviderId.YAMTRACK
    private const val TAB_KEY = "floppy:library"
    private const val PAGE_SIZE = 500
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val changes: Flow<Unit> = changed
    override val connectionRefreshIntent = TrackingRefreshIntent.AUTOMATIC

    private var state = TrackingLibrarySnapshot()

    override fun ensureLoaded() = YamtrackSettingsRepository.ensureLoaded()
    override fun clearLocalState() { state = TrackingLibrarySnapshot() }
    override fun onProfileChanged() { clearLocalState() }

    override suspend fun refresh(intent: TrackingRefreshIntent) {
        val credentials = YamtrackSettingsRepository.activeCredentials()
        if (credentials == null) {
            state = TrackingLibrarySnapshot(hasLoaded = true)
            changed.tryEmit(Unit)
            return
        }
        state = state.copy(isLoading = true, errorMessage = null)
        changed.tryEmit(Unit)
        val (baseUrl, token) = credentials
        val collected = mutableListOf<LibraryItem>()
        var offset = 0
        while (true) {
            val response = httpRequestRaw(
                "GET",
                "$baseUrl/api/v1/media?limit=$PAGE_SIZE&offset=$offset",
                floppyLibraryHeaders(token),
                "",
            )
            if (response.status !in 200..299) {
                state = state.copy(
                    isLoading = false,
                    hasLoaded = true,
                    errorMessage = "Floppy library returned HTTP ${response.status}",
                )
                changed.tryEmit(Unit)
                return
            }
            val page = json.decodeFromString<FloppyLibraryPage>(response.body)
            collected += page.results.mapNotNull(FloppyTrackedMedia::toLibraryItem)
            if (page.pagination.next.isNullOrBlank() || page.results.size < PAGE_SIZE) break
            offset += PAGE_SIZE
        }
        val sections = collected.groupBy(LibraryItem::type).map { (type, items) ->
            LibrarySection(
                type = type,
                displayTitle = if (type == "movie") "Movies" else "TV Shows",
                items = items.sortedByDescending(LibraryItem::savedAtEpochMs),
            )
        }.sortedBy(LibrarySection::displayTitle)
        state = TrackingLibrarySnapshot(
            items = collected,
            sections = sections,
            tabs = listOf(
                TrackingLibraryTab(
                    key = TAB_KEY,
                    title = "Floppy Library",
                    providerId = providerId,
                    kind = TrackingLibraryTabKind.WATCHLIST,
                ),
            ),
            hasLoaded = true,
        )
        changed.tryEmit(Unit)
    }

    override fun snapshot(): TrackingLibrarySnapshot = state

    override fun contains(contentId: String, contentType: String?): Boolean =
        state.items.any { it.id == contentId && (contentType == null || it.type == contentType) }

    override fun find(contentId: String): LibraryItem? = state.items.firstOrNull { it.id == contentId }

    override suspend fun membership(item: LibraryItem): Map<String, Boolean> =
        mapOf(TAB_KEY to contains(item.id, item.type))

    override fun toggledDefaultMembership(currentMembership: Map<String, Boolean>): Map<String, Boolean> =
        mapOf(TAB_KEY to (currentMembership[TAB_KEY] != true))

    override suspend fun applyMembership(
        profileId: Int,
        item: LibraryItem,
        desiredMembership: Map<String, Boolean>,
        destructiveRemovalConfirmed: Boolean,
    ): TrackingMembershipResolution? {
        if (profileId != ProfileRepository.activeProfileId) return null
        val desired = desiredMembership[TAB_KEY] ?: return null
        val current = contains(item.id, item.type)
        if (desired == current) return TrackingMembershipResolution(providerId, TAB_KEY, TAB_KEY)
        val (baseUrl, token) = YamtrackSettingsRepository.activeCredentials()
            ?: error("Floppy is not connected")
        val target = item.toFloppyLibraryTarget() ?: error("Floppy could not resolve this title")
        val response = if (desired) {
            httpRequestRaw(
                "POST",
                "$baseUrl/api/v1/media/${target.mediaType}",
                floppyLibraryHeaders(token),
                json.encodeToString(FloppyTrackRequest(target.source, target.id)),
            )
        } else {
            httpRequestRaw(
                "DELETE",
                "$baseUrl/api/v1/media/${target.mediaType}/${target.source}/${target.id}",
                floppyLibraryHeaders(token),
                "",
            )
        }
        if (response.status !in 200..299 && !(response.status == 404 && !desired)) {
            error("Floppy library update failed (${response.status}): ${response.body.take(200)}")
        }
        refresh(TrackingRefreshIntent.INVALIDATED)
        return TrackingMembershipResolution(providerId, TAB_KEY, TAB_KEY)
    }
}

private data class FloppyLibraryTarget(val mediaType: String, val source: String, val id: String)

private fun LibraryItem.toFloppyLibraryTarget(): FloppyLibraryTarget? {
    val mediaType = if (type.equals("movie", true)) "movie" else "tv"
    tmdbId?.let { return FloppyLibraryTarget(mediaType, "tmdb", it.toString()) }
    imdbId?.takeIf(String::isNotBlank)?.let { return FloppyLibraryTarget(mediaType, "imdb", it) }
    if (id.startsWith("tmdb:", true)) return FloppyLibraryTarget(mediaType, "tmdb", id.substringAfter(':'))
    if (id.startsWith("tt", true)) return FloppyLibraryTarget(mediaType, "imdb", id)
    if (id.startsWith("tvdb:", true)) return FloppyLibraryTarget(mediaType, "tvdb", id.substringAfter(':'))
    return null
}

@Serializable private data class FloppyTrackRequest(val source: String, @SerialName("media_id") val mediaId: String)
@Serializable private data class FloppyLibraryPage(
    val pagination: FloppyLibraryPagination = FloppyLibraryPagination(),
    val results: List<FloppyTrackedMedia> = emptyList(),
)
@Serializable private data class FloppyLibraryPagination(val next: String? = null)
@Serializable private data class FloppyTrackedMedia(
    val item: FloppyLibraryItem? = null,
    @SerialName("created_at") val createdAt: String? = null,
)
@Serializable private data class FloppyLibraryItem(
    @SerialName("media_id") val mediaId: String? = null,
    val source: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val image: String? = null,
)

private fun FloppyTrackedMedia.toLibraryItem(): LibraryItem? {
    val item = item ?: return null
    val rawId = item.mediaId?.takeIf(String::isNotBlank) ?: return null
    val type = when (item.mediaType) {
        "movie" -> "movie"
        "tv", "anime" -> "series"
        else -> return null
    }
    val id = when (item.source?.lowercase()) {
        "imdb" -> rawId
        "tmdb" -> "tmdb:$rawId"
        "tvdb" -> "tvdb:$rawId"
        else -> return null
    }
    return LibraryItem(
        id = id,
        type = type,
        name = item.title ?: id,
        poster = item.image,
        imdbId = id.takeIf { it.startsWith("tt") },
        tmdbId = if (id.startsWith("tmdb:")) id.substringAfter(':').toIntOrNull() else null,
        savedAtEpochMs = createdAt?.let(::parseTraktIsoDateTimeToEpochMs) ?: 0L,
    )
}

private fun floppyLibraryHeaders(token: String) = mapOf(
    "Accept" to "application/json",
    "Content-Type" to "application/json",
    "Authorization" to "Bearer $token",
)
