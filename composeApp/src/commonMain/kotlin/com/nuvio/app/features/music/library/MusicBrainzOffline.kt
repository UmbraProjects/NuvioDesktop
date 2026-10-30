package com.nuvio.app.features.music.library

import kotlinx.coroutines.flow.StateFlow

/*
 * fork:music
 *
 * An offline copy of MusicBrainz, built on this PC from its public data dumps (data.metabrainz.org, CC0 and CC BY-NC-SA).
 * The web service allows one request a second and often answers 503 to anonymous clients, which held an artist page
 * up for ten seconds and starved "Where next?" ladders. With the copy in place [MusicBrainzClient] answers from disk
 * in a few milliseconds and only goes to the web for an artist newer than the dump.
 *
 * The index answers in the web service's own JSON shapes, so the existing parsers ([parseMbArtist],
 * [parseMbArtistSearch], [parseMbTagSearch], [parseMbReleaseGroups]) read it unchanged.
 */

/** Lookups in a built copy. Blocking (disk reads); call off the main thread. */
internal interface MusicBrainzOfflineIndex {
    /** `{"artists":[…]}` for the artists called [name] (or with it as an alias), best known first; null when there are none. */
    fun searchArtist(name: String, limit: Int): String?

    /** `{"artists":[…]}` with tag votes for the artists tagged [tag], most votes first; empty list when nobody carries it. */
    fun searchByTag(tag: String, limit: Int): String

    /** The `artist/{mbid}?inc=url-rels+tags+genres+ratings` answer; null when the artist is not in the copy. */
    fun artist(mbid: String): String?

    /** `{"release-groups":[…],"release-group-count":n}` for every release group credited to the artist; null when the artist is not in the copy. */
    fun releaseGroups(mbid: String): String?
}

/** Where the offline copy stands, for Settings › Music. */
internal sealed interface MusicBrainzOfflineState {
    /** This platform cannot hold one. */
    data object Unsupported : MusicBrainzOfflineState

    data object Absent : MusicBrainzOfflineState

    data class Downloading(val doneBytes: Long, val totalBytes: Long) : MusicBrainzOfflineState

    /** Reading the dumps into the index; [fraction] runs 0..1 over both files. */
    data class Building(val fraction: Float) : MusicBrainzOfflineState

    /** [dump] is the dump's date stamp ("20261028-001001"); [newer] a later dump on the server, when one was seen. */
    data class Ready(val dump: String, val sizeBytes: Long, val artists: Int, val releaseGroups: Int, val newer: String? = null) :
        MusicBrainzOfflineState

    data class Failed(val message: String) : MusicBrainzOfflineState
}

internal interface MusicBrainzOfflineCopy {
    val state: StateFlow<MusicBrainzOfflineState>

    /** The built copy, while [state] is [MusicBrainzOfflineState.Ready] (and during an update, the old one). */
    val index: MusicBrainzOfflineIndex?

    /** Downloads the latest dump and builds the copy, replacing an older one when it is done. Resumes a download cut short. */
    fun download()

    fun cancel()

    fun delete()

    /** Looks once for a newer dump than the one installed; the answer shows as [MusicBrainzOfflineState.Ready.newer]. */
    fun checkForUpdate()
}

internal expect fun musicBrainzOfflineCopy(): MusicBrainzOfflineCopy
