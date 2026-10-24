package com.nuvio.app.features.mdblist

data class MdbListSettings(
    val enabled: Boolean = false,
    /**
     * Whether the same API key may also be used for playback tracking (scrobble + watched sync).
     *
     * Separate from [enabled] on purpose: [enabled] is consent to spend the key's daily request
     * budget on ratings lookups, this is consent to write playback history to the user's MDBList
     * account. One should never imply the other.
     */
    val trackingEnabled: Boolean = false,
    /** Whether MDBList's paused playback sessions drive Continue Watching. Requires tracking. */
    val asContinueWatchingSource: Boolean = false,
    /** Whether MDBList supplies the application's release calendar. Requires tracking. */
    val asCalendarSource: Boolean = false,
    val apiKey: String = "",
    val useImdb: Boolean = true,
    val useTmdb: Boolean = true,
    val useTomatoes: Boolean = true,
    val useMetacritic: Boolean = true,
    val useTrakt: Boolean = true,
    val useLetterboxd: Boolean = true,
    val useAudience: Boolean = true,
    val useMal: Boolean = true,
) {
    val hasApiKey: Boolean
        get() = apiKey.isNotBlank()

    val isTrackingActive: Boolean
        get() = trackingEnabled && hasApiKey

    val isContinueWatchingSource: Boolean
        get() = isTrackingActive && asContinueWatchingSource

    val isCalendarSource: Boolean
        get() = isTrackingActive && asCalendarSource

    fun isProviderEnabled(providerId: String): Boolean =
        when (providerId) {
            MdbListMetadataService.PROVIDER_IMDB -> useImdb
            MdbListMetadataService.PROVIDER_TMDB -> useTmdb
            MdbListMetadataService.PROVIDER_TOMATOES -> useTomatoes
            MdbListMetadataService.PROVIDER_METACRITIC -> useMetacritic
            MdbListMetadataService.PROVIDER_TRAKT -> useTrakt
            MdbListMetadataService.PROVIDER_LETTERBOXD -> useLetterboxd
            MdbListMetadataService.PROVIDER_AUDIENCE -> useAudience
            MdbListMetadataService.PROVIDER_MAL -> useMal
            else -> false
        }

    fun enabledProvidersInPriorityOrder(): List<String> =
        MdbListMetadataService.PROVIDER_PRIORITY_ORDER.filter(::isProviderEnabled)
}
