package com.nuvio.app.features.servers

import com.nuvio.app.features.tracking.TrackingExternalIds
import java.util.concurrent.ConcurrentHashMap

/**
 * The IMDb/TMDB/TVDB ids a media server reported for its own titles, by the title's server id.
 *
 * Filled whenever a server title's details are read (ServerCatalog.details). Tracking providers
 * cannot identify a `srv…` id, so a scrobble for a server title borrows these ids instead
 * (buildTrackingMediaReference): watching from a server's own row still reaches SIMKL, Trakt and
 * the rest. Not added upstream; memory only, refilled each session as details load.
 */
object ServerTrackingIds {
    private val idsByMetaId = ConcurrentHashMap<String, TrackingExternalIds>()

    internal fun remember(metaId: String, ids: TrackingExternalIds) {
        if (ids.imdb.isNullOrBlank() && ids.tmdb == null && ids.tvdb.isNullOrBlank()) return
        idsByMetaId[metaId] = ids
    }

    fun forMetaId(metaId: String?): TrackingExternalIds? = metaId?.trim()?.let(idsByMetaId::get)
}
