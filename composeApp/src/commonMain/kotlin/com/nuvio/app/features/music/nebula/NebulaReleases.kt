package com.nuvio.app.features.music.nebula

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import com.nuvio.app.features.music.library.LibraryArtist
import com.nuvio.app.features.music.library.LibraryRelease
import com.nuvio.app.features.music.library.MusicLibraryRepository
import com.nuvio.app.features.music.library.releasesToAskAddon
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/*
 * What the music addon has among an artist's releases that YouTube Music does not, for the artist page: those cards
 * are not dimmed, show "Lossless on your addon", and count under "Playable". The answers come from
 * NebulaMusicSource.availabilityOf, which keeps them on disk, so a revisit asks the addon about nothing it already knows.
 */

/** The most releases one visit to an artist page asks the addon about. The rest wait for the next visit (answers already kept do not count). */
internal const val ADDON_CHECKS_PER_VISIT = 30

/** What [availabilityOf][NebulaMusicSource.availabilityOf] needs to look for [this] release of [artist]. */
internal fun LibraryRelease.addonQuery(artist: LibraryArtist): AddonReleaseQuery = AddonReleaseQuery(
    cacheKey = addonCacheKey(artist.id, key),
    title = title,
    artist = artist.name,
    year = year ?: releaseDate?.take(4),
    trackCount = trackCount,
)

/**
 * The addon's answers for one artist page, by [LibraryRelease.key]. Snapshot state, so the cards update as answers
 * arrive. Empty (and nothing is asked) without an addon link.
 */
@Stable
internal class AddonReleases {
    private val answers = mutableStateMapOf<String, AddonAvailability>()

    /** The keys of the releases the addon has. Read in a composable, it follows the answers as they come in. */
    val keys: Set<String> get() = answers.filterValues { it.has }.keys.toSet()

    /** The addon's album id for a release it has, or null. */
    fun albumIdOf(releaseKey: String): String? = answers[releaseKey]?.albumId

    /**
     * Fills [answers] for [releases]: answers already kept first (no request, so a revisit shows its badges at once),
     * then the addon is asked about the rest, newest first, at most [ADDON_CHECKS_PER_VISIT] of them. Leaving the page
     * cancels what has not been asked yet; what was asked is kept either way.
     */
    suspend fun load(artist: LibraryArtist, releases: List<LibraryRelease>) {
        for (release in releases) {
            NebulaMusicSource.cachedAvailability(addonCacheKey(artist.id, release.key))?.let { answers[release.key] = it }
        }
        val pending = releases.filter { it.key !in answers }.take(ADDON_CHECKS_PER_VISIT)
        coroutineScope {
            for (release in pending) {
                // The source limits these to two at a time and in this order, and pauses itself when the addon is failing.
                launch { NebulaMusicSource.availabilityOf(release.addonQuery(artist))?.let { answers[release.key] = it } }
            }
        }
    }
}

/** The addon's answers for [artist]'s page. Nothing is asked unless a music addon link is set. */
@Composable
internal fun rememberAddonReleases(artist: LibraryArtist): AddonReleases {
    remember { MusicLibraryRepository.ensureLoaded() }
    val link = MusicLibraryRepository.state.collectAsState().value.settings.nebulaSecretLink
    val holder = remember(artist.id, link) { AddonReleases() }
    val toAsk = remember(artist.releases) { releasesToAskAddon(artist.releases) }
    // Keyed by the release keys, not the releases: their art and palettes fill in while the page loads, and that must not restart the asking.
    val askKeys = remember(toAsk) { toAsk.map { it.key } }
    LaunchedEffect(holder, askKeys) {
        if (nebulaBaseUrl(link) != null) holder.load(artist, toAsk)
    }
    return holder
}
