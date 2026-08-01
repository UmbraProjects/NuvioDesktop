package com.nuvio.app.features.trakt

import com.nuvio.app.features.tracking.TrackingHistoryItem
import com.nuvio.app.features.tracking.TrackingHistoryWriter
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingMutationResult
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watching.sync.TraktWatchedSyncAdapter

internal object TraktHistoryWriter : TrackingHistoryWriter {
    override val providerId: TrackingProviderId = TrackingProviderId.TRAKT

    override suspend fun addToHistory(
        profileId: Int,
        items: Collection<TrackingHistoryItem>,
    ): TrackingMutationResult {
        val watched = items.mapNotNull { item -> item.media.toWatchedItem(item.watchedAtEpochMs ?: 0L) }
        TraktWatchedSyncAdapter.push(profileId, watched)
        return TrackingMutationResult(items.size, items.size - watched.size)
    }

    override suspend fun removeFromHistory(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
    ): TrackingMutationResult {
        val watched = items.mapNotNull { media -> media.toWatchedItem(0L) }
        TraktWatchedSyncAdapter.delete(profileId, watched)
        return TrackingMutationResult(items.size, items.size - watched.size)
    }

    private fun TrackingMediaReference.toWatchedItem(markedAtEpochMs: Long): WatchedItem? {
        val catalog = catalog ?: return null
        return WatchedItem(
            id = catalog.contentId,
            type = catalog.contentType,
            name = title.orEmpty(),
            releaseInfo = year?.toString(),
            season = episode?.season,
            episode = episode?.number,
            markedAtEpochMs = markedAtEpochMs,
        )
    }
}
