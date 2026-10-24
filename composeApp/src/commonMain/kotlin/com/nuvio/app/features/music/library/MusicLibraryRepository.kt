package com.nuvio.app.features.music.library

import co.touchlab.kermit.Logger
import com.nuvio.app.features.library.LibraryClock
import com.nuvio.app.features.music.MusicCatalog
import com.nuvio.app.features.music.defaultMusicCatalog
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Keys for the artwork sources that want one. Both are optional: without them the library still fills from the other sources. */
@kotlinx.serialization.Serializable
data class MusicLibrarySettings(
    /** A free personal key from fanart.tv (Account > Your API Keys). Unlocks HD logos, banners, backgrounds and CD art. */
    val fanartApiKey: String = "",
    /** A TheAudioDB key. Empty uses the public test key "123", which works but is rate limited. */
    val audioDbKey: String = "",
)

/** What is happening to one artist while it is added or refreshed. */
data class MusicLibraryProgress(val status: String)

data class MusicLibraryState(
    val artists: List<LibraryArtist> = emptyList(),
    /** Artists being added or refreshed, by id, with a line saying what step they are on. */
    val working: Map<String, MusicLibraryProgress> = emptyMap(),
    val settings: MusicLibrarySettings = MusicLibrarySettings(),
    /** Set when the last add failed outright (YouTube Music unreachable), cleared by the next try. */
    val errorMessage: String? = null,
) {
    fun contains(artistId: String): Boolean = artists.any { it.id == artistId }
    fun artist(id: String): LibraryArtist? = artists.firstOrNull { it.id == id }

    /** Library id of the artist that owns the YouTube Music album [browseId], if one does. */
    fun artistOfAlbum(browseId: String): LibraryArtist? =
        artists.firstOrNull { artist -> artist.releases.any { it.ytBrowseId == browseId } }
}

/**
 * The music library: artists the person added, each with every album, single and EP it could find,
 * and the metadata and artwork the sources have. Kept on disk per profile.
 *
 * Adding is fast and then fills in: the artist appears at once with the releases YouTube Music
 * lists, and the slower lookups (MusicBrainz is paced at one call a second) replace it as they
 * finish. The work runs in this object's own scope so leaving the screen does not cancel it.
 */
object MusicLibraryRepository {
    private val log = Logger.withTag("MusicLibrary")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = HashMap<String, Job>()

    private val _state = MutableStateFlow(MusicLibraryState())
    val state: StateFlow<MusicLibraryState> = _state.asStateFlow()

    private var loadedProfileId: Int? = null

    /** Loads the library for the active profile when it is not loaded yet or the profile changed. Cheap to call often. */
    fun ensureLoaded() {
        val profileId = ProfileRepository.activeProfileId
        if (loadedProfileId == profileId) return
        loadedProfileId = profileId
        val artists = MusicLibraryStorage.loadLibrary(profileId)
            ?.let { runCatching { json.decodeFromString<StoredMusicLibrary>(it).artists }.getOrNull() }
            .orEmpty()
        val settings = MusicLibraryStorage.loadSettings()
            ?.let { runCatching { json.decodeFromString<MusicLibrarySettings>(it) }.getOrNull() }
            ?: MusicLibrarySettings()
        _state.value = MusicLibraryState(artists = sorted(artists), settings = settings)
    }

    private fun sorted(artists: List<LibraryArtist>) =
        artists.sortedBy { (it.sortName ?: it.name).lowercase() }

    private fun persist() {
        val profileId = loadedProfileId ?: return
        runCatching {
            MusicLibraryStorage.saveLibrary(profileId, json.encodeToString(StoredMusicLibrary(_state.value.artists)))
        }.onFailure { log.e(it) { "Could not save the music library" } }
    }

    fun updateSettings(settings: MusicLibrarySettings) {
        ensureLoaded()
        _state.update { it.copy(settings = settings) }
        runCatching { MusicLibraryStorage.saveSettings(json.encodeToString(settings)) }
    }

    /** Stores [artist]. With [onlyIfPresent] a late result for an artist the person has since removed is dropped. */
    private fun replace(artist: LibraryArtist, onlyIfPresent: Boolean = false) {
        if (onlyIfPresent && !_state.value.contains(artist.id)) return
        _state.update { state ->
            val without = state.artists.filterNot { it.id == artist.id }
            state.copy(artists = sorted(without + artist))
        }
        persist()
    }

    private fun setProgress(id: String, status: String?) {
        _state.update { state ->
            state.copy(working = if (status == null) state.working - id else state.working + (id to MusicLibraryProgress(status)))
        }
    }

    /**
     * Adds the artist with YouTube Music channel id [artistBrowseId] and starts filling it in.
     * Does nothing when it is already in the library or already being added.
     */
    fun add(artistBrowseId: String, catalog: MusicCatalog = defaultMusicCatalog()) {
        ensureLoaded()
        if (_state.value.contains(artistBrowseId) || jobs[artistBrowseId]?.isActive == true) return
        _state.update { it.copy(errorMessage = null) }
        jobs[artistBrowseId] = scope.launch { fillIn(artistBrowseId, catalog, existing = null) }
    }

    /** Looks the artist up again, keeping the old data where the sources now say nothing. */
    fun refresh(artistId: String, catalog: MusicCatalog = defaultMusicCatalog()) {
        ensureLoaded()
        val existing = _state.value.artist(artistId) ?: return
        if (jobs[artistId]?.isActive == true) return
        jobs[artistId] = scope.launch { fillIn(artistId, catalog, existing) }
    }

    fun refreshAll(catalog: MusicCatalog = defaultMusicCatalog()) {
        ensureLoaded()
        _state.value.artists.forEach { refresh(it.id, catalog) }
    }

    fun remove(artistId: String) {
        ensureLoaded()
        jobs.remove(artistId)?.cancel()
        _state.update { it.copy(artists = it.artists.filterNot { a -> a.id == artistId }, working = it.working - artistId) }
        persist()
    }

    private suspend fun fillIn(artistId: String, catalog: MusicCatalog, existing: LibraryArtist?) {
        try {
            setProgress(artistId, "Reading the artist page")
            val page = catalog.artist(artistId)
            if (page == null) {
                if (existing == null) {
                    _state.update { it.copy(errorMessage = "Couldn't reach YouTube Music to add this artist. Try again.") }
                }
                return
            }
            val releases = catalog.artistReleases(artistId).ifEmpty { page.albums }
            val now = LibraryClock.nowEpochMs()
            val addedAt = existing?.addedAtEpochMs ?: now

            // Show something straight away: the artist with the releases YouTube Music lists.
            if (existing == null) {
                replace(
                    assembleArtist(
                        id = artistId,
                        name = page.name,
                        youTubeThumbnail = page.thumbnailUrl,
                        sources = ArtistSources(),
                        releases = mergeReleases(ReleaseSources(youTubeMusic = releases)),
                        addedAtEpochMs = addedAt,
                        nowEpochMs = now,
                    ),
                )
            }

            val enricher = MusicLibraryEnricher(_state.value.settings)
            val built = enricher.build(page, releases, addedAt, now) { setProgress(artistId, it) }
            val full = if (existing == null) built else keepWhereEmpty(built, existing)
            replace(full, onlyIfPresent = true)

            setProgress(artistId, "Reading album details")
            val detailed = enricher.addReleaseDetails(full.releases) { partial ->
                replace(full.copy(releases = partial), onlyIfPresent = true)
            }
            replace(full.copy(releases = detailed), onlyIfPresent = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            log.e(error) { "Adding artist $artistId failed" }
            if (existing == null && !_state.value.contains(artistId)) {
                _state.update { it.copy(errorMessage = "Couldn't add this artist. Try again.") }
            }
        } finally {
            setProgress(artistId, null)
        }
    }

    /** A refresh whose sources were down must not wipe what an earlier run found. */
    private fun keepWhereEmpty(fresh: LibraryArtist, old: LibraryArtist): LibraryArtist = fresh.copy(
        musicBrainzId = fresh.musicBrainzId ?: old.musicBrainzId,
        biography = fresh.biography ?: old.biography,
        biographySource = if (fresh.biography != null) fresh.biographySource else old.biographySource,
        type = fresh.type ?: old.type,
        country = fresh.country ?: old.country,
        beginYear = fresh.beginYear ?: old.beginYear,
        genres = fresh.genres.ifEmpty { old.genres },
        links = fresh.links.ifEmpty { old.links },
        art = ArtistArtwork(
            thumb = fresh.art.thumb ?: old.art.thumb,
            logo = fresh.art.logo ?: old.art.logo,
            logoHd = fresh.art.logoHd ?: old.art.logoHd,
            clearArt = fresh.art.clearArt ?: old.art.clearArt,
            cutout = fresh.art.cutout ?: old.art.cutout,
            banner = fresh.art.banner ?: old.art.banner,
            wideThumb = fresh.art.wideThumb ?: old.art.wideThumb,
            backgrounds = fresh.art.backgrounds.ifEmpty { old.art.backgrounds },
        ),
        releases = if (fresh.releases.size >= old.releases.size) fresh.releases else old.releases,
        sources = (fresh.sources + old.sources).distinct(),
    )
}
