package com.nuvio.app.features.music.library

import co.touchlab.kermit.Logger
import com.nuvio.app.features.library.LibraryClock
import com.nuvio.app.features.music.MusicCatalog
import com.nuvio.app.features.music.defaultMusicCatalog
import com.nuvio.app.features.music.identity.ArtworkPalette
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
    /** Stage and album motion off: no drift, pulse, cycling or spinning disc. Applies to the whole Music section. */
    val reducedMotion: Boolean = false,
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
    /**
     * Artists opened from Search (or from an album) that are not in the library: enriched in the
     * background so their page looks like a saved artist's, but never written to disk. Bounded by
     * [boundedPreview]; Add promotes one into [artists].
     */
    val preview: Map<String, LibraryArtist> = emptyMap(),
    /** Previewed artists whose first look-up failed outright, so the page can offer a retry. */
    val previewFailed: Set<String> = emptySet(),
) {
    /** True only for a saved artist; a previewed one is not "in the library". */
    fun contains(artistId: String): Boolean = artists.any { it.id == artistId }
    fun artist(id: String): LibraryArtist? = artists.firstOrNull { it.id == id }

    /** The saved artist, else the previewed copy. What an artist page reads, so both doors show the same thing. */
    fun artistOrPreview(id: String): LibraryArtist? = artist(id) ?: preview[id]

    /** Library id of the artist (saved first, then previewed) that owns the YouTube Music album [browseId], if one does. */
    fun artistOfAlbum(browseId: String): LibraryArtist? =
        (artists.asSequence() + preview.values.asSequence())
            .firstOrNull { artist -> artist.releases.any { it.ytBrowseId == browseId } }
}

/** How many unsaved artists are kept for this session before the least recently touched is dropped. */
internal const val MAX_PREVIEWS = 12

/**
 * [previews] with [artist] stored as the most recently touched entry, the oldest dropped past
 * [max]. Pure, so the eviction rule is tested without the repository.
 */
internal fun boundedPreview(
    previews: Map<String, LibraryArtist>,
    artist: LibraryArtist,
    max: Int = MAX_PREVIEWS,
): Map<String, LibraryArtist> {
    val next = LinkedHashMap(previews)
    next.remove(artist.id)
    next[artist.id] = artist
    while (next.size > max) next.remove(next.keys.first())
    return next
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
        _state.value = MusicLibraryState(artists = sorted(artists.map { it.withSaneDates() }), settings = settings)
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
            // A fill-in works from an older copy of the artist; it must not wipe a palette derived meanwhile.
            val palette = artist.palette ?: state.artist(artist.id)?.palette
            // Release palettes survive a refresh too (phase 4).
            val kept = artist.copy(palette = palette).keepingPalettesFrom(state.artist(artist.id))
            state.copy(artists = sorted(without + kept))
        }
        persist()
    }

    /**
     * Caches the palette derived for [artistId]: on disk for a saved artist, in memory for a
     * previewed one. Dropped when the artist is neither (removed and evicted since).
     */
    fun setArtistPalette(artistId: String, palette: ArtworkPalette) {
        ensureLoaded()
        val state = _state.value
        when {
            state.contains(artistId) -> {
                _state.update { s ->
                    s.copy(artists = s.artists.map { if (it.id == artistId) it.copy(palette = palette) else it })
                }
                persist()
            }
            state.preview.containsKey(artistId) -> {
                _state.update { s ->
                    val held = s.preview[artistId] ?: return@update s
                    s.copy(preview = s.preview + (artistId to held.copy(palette = palette)))
                }
            }
        }
    }

    /** Caches release palettes (by release key) for [artistId] in one write: on disk if saved, in memory if previewed. */
    fun setReleasePalettes(artistId: String, palettes: Map<String, ArtworkPalette>) {
        if (palettes.isEmpty()) return
        ensureLoaded()
        fun LibraryArtist.withPalettes() =
            copy(releases = releases.map { r -> palettes[r.key]?.let { r.copy(palette = it) } ?: r })
        val state = _state.value
        when {
            state.contains(artistId) -> {
                _state.update { s -> s.copy(artists = s.artists.map { if (it.id == artistId) it.withPalettes() else it }) }
                persist()
            }
            state.preview.containsKey(artistId) -> {
                _state.update { s ->
                    val held = s.preview[artistId] ?: return@update s
                    s.copy(preview = s.preview + (artistId to held.withPalettes()))
                }
            }
        }
    }

    /** Stores a previewed artist, keeping a palette derived meanwhile. Never persisted. */
    private fun putPreview(artist: LibraryArtist) {
        _state.update { state ->
            val palette = artist.palette ?: state.preview[artist.id]?.palette
            val kept = artist.copy(palette = palette).keepingPalettesFrom(state.preview[artist.id])
            state.copy(preview = boundedPreview(state.preview, kept))
        }
    }

    /** Moves a previewed artist into the saved library and writes it to disk. */
    private fun promote(artist: LibraryArtist) {
        _state.update { state ->
            state.copy(
                artists = sorted(state.artists.filterNot { it.id == artist.id } + artist.copy(addedAtEpochMs = LibraryClock.nowEpochMs())),
                preview = state.preview - artist.id,
                previewFailed = state.previewFailed - artist.id,
            )
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
        val state = _state.value
        if (state.contains(artistBrowseId)) return
        val previewed = state.preview[artistBrowseId]
        if (previewed != null) {
            // Already looked up for the page: keep it, do not fetch it twice. A look-up still running
            // carries on and now writes into the library; one cut short (removed mid-way) is resumed.
            promote(previewed)
            if (jobs[artistBrowseId]?.isActive != true && artistBrowseId !in completed) {
                jobs[artistBrowseId] = scope.launch {
                    fillIn(artistBrowseId, catalog, existing = _state.value.artist(artistBrowseId), preview = false)
                }
            }
            return
        }
        // A preview that has not produced its first page yet has no Add button to press; nothing to do.
        if (jobs[artistBrowseId]?.isActive == true) return
        _state.update { it.copy(errorMessage = null) }
        jobs[artistBrowseId] = scope.launch { fillIn(artistBrowseId, catalog, existing = null, preview = false) }
    }

    /**
     * Looks the artist up for an artist page opened from Search (or from an album) without saving
     * it. The page shows what YouTube Music lists at once and fills in as the other sources answer,
     * exactly as a saved artist does. Does nothing for a saved artist, one already previewed or
     * being looked up. Previews are session-only: closing the app forgets them.
     */
    fun openPreview(artistBrowseId: String, catalog: MusicCatalog = defaultMusicCatalog()) {
        ensureLoaded()
        val state = _state.value
        if (state.contains(artistBrowseId) || state.preview.containsKey(artistBrowseId)) return
        if (jobs[artistBrowseId]?.isActive == true) return
        _state.update { it.copy(previewFailed = it.previewFailed - artistBrowseId) }
        jobs[artistBrowseId] = scope.launch { fillIn(artistBrowseId, catalog, existing = null, preview = true) }
    }

    /** Looks the artist up again, keeping the old data where the sources now say nothing. */
    fun refresh(artistId: String, catalog: MusicCatalog = defaultMusicCatalog()) {
        ensureLoaded()
        val existing = _state.value.artist(artistId) ?: return
        if (jobs[artistId]?.isActive == true) return
        jobs[artistId] = scope.launch { fillIn(artistId, catalog, existing, preview = false) }
    }

    fun refreshAll(catalog: MusicCatalog = defaultMusicCatalog()) {
        ensureLoaded()
        _state.value.artists.forEach { refresh(it.id, catalog) }
    }

    /**
     * Takes the artist out of the library. It stays on as a preview for this session, so the page
     * that is open keeps showing it with Add instead of going blank.
     */
    fun remove(artistId: String) {
        ensureLoaded()
        jobs.remove(artistId)?.cancel()
        val removed = _state.value.artist(artistId)
        _state.update { it.copy(artists = it.artists.filterNot { a -> a.id == artistId }, working = it.working - artistId) }
        removed?.let(::putPreview)
        persist()
    }

    /** Ids whose look-up ran to the end this session, so promoting a finished preview does not fetch it again. */
    private val completed = HashSet<String>()

    /**
     * Looks an artist up and writes the result as it grows. A saved artist is written to the library;
     * with [preview] an unsaved one goes to [MusicLibraryState.preview] instead. The destination is
     * decided at every write, so an artist added mid-way continues into the library and one removed
     * mid-way (a library job) stops being written.
     */
    private suspend fun fillIn(artistId: String, catalog: MusicCatalog, existing: LibraryArtist?, preview: Boolean) {
        fun put(artist: LibraryArtist, onlyIfPresent: Boolean) {
            when {
                _state.value.contains(artist.id) -> replace(artist)
                preview -> putPreview(artist)
                else -> replace(artist, onlyIfPresent)
            }
        }
        try {
            setProgress(artistId, "Reading the artist page")
            val page = catalog.artist(artistId)
            if (page == null) {
                if (preview) {
                    if (_state.value.artistOrPreview(artistId) == null) {
                        _state.update { it.copy(previewFailed = it.previewFailed + artistId) }
                    }
                } else if (existing == null) {
                    _state.update { it.copy(errorMessage = "Couldn't reach YouTube Music to add this artist. Try again.") }
                }
                return
            }
            val releases = catalog.artistReleases(artistId).ifEmpty { page.albums }
            val now = LibraryClock.nowEpochMs()
            val addedAt = existing?.addedAtEpochMs ?: now

            // Show something straight away: the artist with the releases YouTube Music lists.
            if (existing == null) {
                put(
                    assembleArtist(
                        id = artistId,
                        name = page.name,
                        youTubeThumbnail = page.thumbnailUrl,
                        sources = ArtistSources(),
                        releases = mergeReleases(ReleaseSources(youTubeMusic = releases)),
                        addedAtEpochMs = addedAt,
                        nowEpochMs = now,
                    ),
                    onlyIfPresent = false,
                )
            }

            val enricher = MusicLibraryEnricher(_state.value.settings)
            val built = enricher.build(page, releases, addedAt, now) { setProgress(artistId, it) }
            // A namesake verdict replaces what an earlier run merged in; only a source outage keeps old data.
            val full = if (existing == null || built.channelMismatch) built else keepWhereEmpty(built, existing)
            put(full, onlyIfPresent = true)

            setProgress(artistId, "Reading album details")
            val detailed = enricher.addReleaseDetails(full.releases) { partial ->
                put(full.copy(releases = partial), onlyIfPresent = true)
            }
            put(full.copy(releases = detailed), onlyIfPresent = true)
            completed.add(artistId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            log.e(error) { "Looking up artist $artistId failed" }
            if (preview) {
                if (_state.value.artistOrPreview(artistId) == null) {
                    _state.update { it.copy(previewFailed = it.previewFailed + artistId) }
                }
            } else if (existing == null && !_state.value.contains(artistId)) {
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
