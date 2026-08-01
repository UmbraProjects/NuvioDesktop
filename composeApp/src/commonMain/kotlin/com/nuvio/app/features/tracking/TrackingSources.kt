package com.nuvio.app.features.tracking

import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.trakt.WatchProgressSource

/**
 * Bridges the existing source preferences onto provider ids.
 *
 * Upstream (NuvioMobile) redeclares `WatchProgressSource` and the library-source helpers inside
 * this package. This fork deliberately does not: `WatchProgressSource` already exists in
 * `features/trakt` and is what the persisted profile settings deserialize into, and SIMKL progress
 * is selected through the separate `WatchProgressSourceSimkl` marker rather than an enum case. A
 * second enum with the same name would be a live import hazard, so the port keeps one source of
 * truth and adds only the provider-id mapping on top.
 *
 * Consolidating the two representations belongs with the settings work, not with this port.
 */

val WatchProgressSource.trackingProviderId: TrackingProviderId?
    get() = when (this) {
        WatchProgressSource.TRAKT -> TrackingProviderId.TRAKT
        WatchProgressSource.NUVIO_SYNC -> null
    }

val LibrarySourceMode.trackingProviderId: TrackingProviderId?
    get() = when (this) {
        LibrarySourceMode.LOCAL -> null
        LibrarySourceMode.TRAKT -> TrackingProviderId.TRAKT
        LibrarySourceMode.SIMKL -> TrackingProviderId.SIMKL
        LibrarySourceMode.MDBLIST -> TrackingProviderId.MDBLIST
        LibrarySourceMode.YAMTRACK -> TrackingProviderId.YAMTRACK
    }

/**
 * Falls back to local state when the source's provider is not connected.
 *
 * Unlike the existing `effectiveLibrarySourceMode(isAuthenticated, source)` in
 * `TraktSettingsRepository`, which takes a single Trakt-shaped boolean, these resolve the
 * connection per provider id so a source can be added without another boolean parameter.
 */
fun effectiveTrackingWatchProgressSource(
    requestedSource: WatchProgressSource,
    isProviderAuthenticated: (TrackingProviderId) -> Boolean,
): WatchProgressSource {
    val providerId = requestedSource.trackingProviderId ?: return WatchProgressSource.NUVIO_SYNC
    return requestedSource.takeIf { isProviderAuthenticated(providerId) }
        ?: WatchProgressSource.NUVIO_SYNC
}

fun effectiveTrackingLibrarySourceMode(
    requestedSource: LibrarySourceMode,
    isProviderAuthenticated: (TrackingProviderId) -> Boolean,
): LibrarySourceMode {
    val providerId = requestedSource.trackingProviderId ?: return LibrarySourceMode.LOCAL
    return requestedSource.takeIf { isProviderAuthenticated(providerId) }
        ?: LibrarySourceMode.LOCAL
}
