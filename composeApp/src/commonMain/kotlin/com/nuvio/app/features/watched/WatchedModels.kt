package com.nuvio.app.features.watched

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.trakt.TraktPlatformClock
import com.nuvio.app.features.watching.domain.WatchingContentRef
import com.nuvio.app.features.watching.domain.watchedKey
import kotlinx.serialization.Serializable

@Serializable
data class WatchedItem(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val releaseInfo: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val markedAtEpochMs: Long,
    /**
     * Storage id of the tracking provider this row was imported from, or null for the user's own
     * tick — local playback, an explicit mark, or Nuvio Sync.
     *
     * Recorded so an import can be withdrawn again. A provider's history is imported only while it
     * owns Continue Watching, and without provenance the rows it left behind were indistinguishable
     * from local ones, so they kept seeding Up Next long after the source moved elsewhere.
     */
    val importedFrom: String? = null,
)

data class WatchedUiState(
    val items: List<WatchedItem> = emptyList(),
    val watchedKeys: Set<String> = emptySet(),
    val isLoaded: Boolean = false,
)

fun MetaPreview.toWatchedItem(markedAtEpochMs: Long): WatchedItem =
    WatchedItem(
        id = id,
        type = type,
        name = name,
        poster = poster,
        releaseInfo = releaseInfo,
        markedAtEpochMs = markedAtEpochMs,
    )

val WatchedItem.isEpisode: Boolean
    get() = season != null && episode != null

internal fun WatchedItem.normalizedMarkedAt(): WatchedItem {
    val normalized = normalizeWatchedMarkedAtEpochMs(markedAtEpochMs)
    return if (normalized == markedAtEpochMs) this else copy(markedAtEpochMs = normalized)
}

internal fun normalizeWatchedMarkedAtEpochMs(value: Long): Long {
    if (value !in CompactWatchedTimestampMin..CompactWatchedTimestampMax) return value

    val raw = value.toString().padStart(14, '0')
    val year = raw.substring(0, 4).toIntOrNull() ?: return value
    val month = raw.substring(4, 6).toIntOrNull() ?: return value
    val day = raw.substring(6, 8).toIntOrNull() ?: return value
    val hour = raw.substring(8, 10).toIntOrNull() ?: return value
    val minute = raw.substring(10, 12).toIntOrNull() ?: return value
    val second = raw.substring(12, 14).toIntOrNull() ?: return value

    if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) {
        return value
    }

    val iso = buildString {
        append(year.toString().padStart(4, '0'))
        append('-')
        append(month.toString().padStart(2, '0'))
        append('-')
        append(day.toString().padStart(2, '0'))
        append('T')
        append(hour.toString().padStart(2, '0'))
        append(':')
        append(minute.toString().padStart(2, '0'))
        append(':')
        append(second.toString().padStart(2, '0'))
        append('Z')
    }
    return TraktPlatformClock.parseIsoDateTimeToEpochMs(iso) ?: value
}

fun watchedItemKey(
    type: String,
    id: String,
    season: Int? = null,
    episode: Int? = null,
): String = watchedKey(
    content = WatchingContentRef(type = type, id = id),
    seasonNumber = season,
    episodeNumber = episode,
)

private const val CompactWatchedTimestampMin = 19000101000000L
private const val CompactWatchedTimestampMax = 29991231235959L
