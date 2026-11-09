package com.nuvio.app.features.tracking

import com.nuvio.app.features.library.LibrarySourceMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where ratings written from this app go: Settings > Integrations > Ratings go to.
 *
 * [LIBRARY] keeps the original behaviour, where a rating follows the selected *library* source
 * (a rating is a personal opinion recorded once, not an event every connected service should hear
 * about). The other choices name one service outright, so someone whose Library is Trakt or local
 * can still rate on SIMKL, MDBList or Floppy.
 */
enum class TrackingRatingSource(val storageId: String) {
    LIBRARY("library"),
    SIMKL("simkl"),
    MDBLIST("mdblist"),
    YAMTRACK("yamtrack"),
    OFF("off"),
    ;

    companion object {
        fun fromStorage(value: String?): TrackingRatingSource? =
            entries.firstOrNull { it.storageId.equals(value?.trim(), ignoreCase = true) }
    }
}

/** The providers that can take a rating, in the order the picker lists them. */
internal val ratingCapableProviders = listOf(
    TrackingProviderId.SIMKL,
    TrackingProviderId.MDBLIST,
    TrackingProviderId.YAMTRACK,
)

/**
 * Which provider a rating goes to for a library source alone (the [TrackingRatingSource.LIBRARY]
 * choice). Providers without a writer are not offered, so the rating affordances stay hidden
 * instead of offering an action that cannot be carried out.
 */
fun trackingRatingProviderFor(sourceMode: LibrarySourceMode): TrackingProviderId? =
    sourceMode.trackingProvider?.takeIf { it in ratingCapableProviders }

/** Which provider a rating goes to for this choice; null means ratings are off. */
fun trackingRatingProviderFor(
    source: TrackingRatingSource,
    librarySource: LibrarySourceMode,
): TrackingProviderId? = when (source) {
    TrackingRatingSource.LIBRARY -> trackingRatingProviderFor(librarySource)
    TrackingRatingSource.SIMKL -> TrackingProviderId.SIMKL
    TrackingRatingSource.MDBLIST -> TrackingProviderId.MDBLIST
    TrackingRatingSource.YAMTRACK -> TrackingProviderId.YAMTRACK
    TrackingRatingSource.OFF -> null
}

/** The provider name shown in rating copy. Not localized — these are brand names. */
fun trackingRatingProviderDisplayName(providerId: TrackingProviderId?): String =
    when (providerId) {
        TrackingProviderId.SIMKL -> "SIMKL"
        TrackingProviderId.MDBLIST -> "MDBList"
        TrackingProviderId.YAMTRACK -> "Floppy (Yamtrack Fork)"
        else -> ""
    }

/** The provider ratings currently go to, from the saved choice and the library source. */
fun selectedTrackingRatingProvider(): TrackingProviderId? = trackingRatingProviderFor(
    TrackingRatingSourceRepository.selectedSource(),
    LibrarySourceRepository.selectedSource(),
)

/** The connected, rating-capable provider ratings go to, or null if there is none. */
fun activeTrackingRatingProvider(): TrackingProviderId? {
    val providerId = selectedTrackingRatingProvider() ?: return null
    if (TrackingProviderRegistry.ratingWriter(providerId) == null) return null
    if (!TrackingProviderRegistry.isAuthenticated(providerId)) return null
    return providerId
}

/** The saved [TrackingRatingSource], per profile. Defaults to following the Library source. */
object TrackingRatingSourceRepository {
    private val _uiState = MutableStateFlow(TrackingRatingSource.LIBRARY)
    val uiState: StateFlow<TrackingRatingSource> = _uiState.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() = loadFromDisk()

    fun selectedSource(): TrackingRatingSource {
        ensureLoaded()
        return _uiState.value
    }

    fun setSource(source: TrackingRatingSource) {
        ensureLoaded()
        if (_uiState.value == source) return
        _uiState.value = source
        ContinueWatchingSourceStorage.saveRatingSource(source.storageId)
    }

    private fun loadFromDisk() {
        hasLoaded = true
        _uiState.value = TrackingRatingSource.fromStorage(ContinueWatchingSourceStorage.loadRatingSource())
            ?: TrackingRatingSource.LIBRARY
    }
}
