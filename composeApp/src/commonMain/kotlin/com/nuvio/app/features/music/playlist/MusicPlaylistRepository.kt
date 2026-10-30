package com.nuvio.app.features.music.playlist

import com.nuvio.app.features.library.LibraryClock
import com.nuvio.app.features.music.library.MusicLibraryStorage
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * fork:music-playlists - the profile's music playlists, newest first. Every change is written straight to disk (one
 * JSON payload per profile beside the music library); nothing here talks to the network.
 */
object MusicPlaylistRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _playlists = MutableStateFlow<List<MusicPlaylist>>(emptyList())
    private var loadedProfileId: Int? = null

    val playlists: StateFlow<List<MusicPlaylist>>
        get() {
            ensureLoaded()
            return _playlists.asStateFlow()
        }

    fun get(id: String): MusicPlaylist? {
        ensureLoaded()
        return _playlists.value.firstOrNull { it.id == id }
    }

    /** Makes a playlist (an unused "New playlist" name when [name] is blank) and returns it. */
    fun create(name: String = "", tracks: List<PlaylistTrack> = emptyList(), importedFrom: String? = null): MusicPlaylist {
        ensureLoaded()
        val now = LibraryClock.nowEpochMs()
        val playlist = MusicPlaylist(
            id = "pl$now${(0..999).random()}",
            name = name.trim().ifEmpty { freshPlaylistName(_playlists.value) },
            tracks = tracks.distinctBy { it.videoId },
            createdAtMs = now,
            updatedAtMs = now,
            importedFrom = importedFrom,
        )
        write(listOf(playlist) + _playlists.value)
        return playlist
    }

    fun rename(id: String, name: String) {
        val trimmed = name.trim().ifEmpty { return }
        update(id) { it.copy(name = trimmed, updatedAtMs = LibraryClock.nowEpochMs()) }
    }

    fun delete(id: String) {
        ensureLoaded()
        write(_playlists.value.filterNot { it.id == id })
    }

    /** Adds [tracks] to the end of the playlist, skipping songs it already has; returns how many were added. */
    fun add(id: String, tracks: List<PlaylistTrack>): Int {
        var added = 0
        update(id) { playlist -> playlist.withAdded(tracks, LibraryClock.nowEpochMs()).also { added = it.second }.first }
        return added
    }

    fun remove(id: String, index: Int) = update(id) { it.withoutTrack(index, LibraryClock.nowEpochMs()) }

    fun move(id: String, from: Int, to: Int) = update(id) { it.withMoved(from, to, LibraryClock.nowEpochMs()) }

    private fun update(id: String, change: (MusicPlaylist) -> MusicPlaylist) {
        ensureLoaded()
        val current = _playlists.value
        val next = current.map { if (it.id == id) change(it) else it }
        if (next != current) write(next)
    }

    private fun write(next: List<MusicPlaylist>) {
        _playlists.value = next
        loadedProfileId?.let { profile ->
            runCatching { MusicLibraryStorage.savePlaylists(profile, json.encodeToString(next)) }
        }
    }

    private fun ensureLoaded() {
        val profileId = ProfileRepository.activeProfileId
        if (loadedProfileId == profileId) return
        loadedProfileId = profileId
        _playlists.value = MusicLibraryStorage.loadPlaylists(profileId)
            ?.let { runCatching { json.decodeFromString<List<MusicPlaylist>>(it) }.getOrNull() }
            .orEmpty()
    }
}
