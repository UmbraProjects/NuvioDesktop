package com.nuvio.app.features.playlist

import kotlinx.serialization.Serializable
import nuvio.composeapp.generated.resources.*
import com.nuvio.app.core.i18n.localizedString

/**
 * A hand-picked, ordered list of things to watch that plays through in order.
 *
 * Entries are always concrete — a movie or one specific episode — never "the next unwatched episode
 * of a show", so running the same playlist twice plays the same things. [resumeIndex] is where Play
 * picks up: it moves past an entry once that entry is finished, and is left alone when playback is
 * abandoned part-way through one.
 *
 * Local-only by design. It is not part of any sync payload: those schemas are shared with the
 * official TV and mobile clients, which have no idea what a playlist is.
 */
@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val entries: List<PlaylistEntry> = emptyList(),
    val resumeIndex: Int = 0,
    val loop: Boolean = false,
    /** Entries are dropped from the list once watched through, instead of the pointer moving past them. */
    val removeWatched: Boolean = false,
    /**
     * On: an entry with a local copy (local library file or completed download) plays that copy,
     * whatever the app-wide local-library setting says. Off: entries always search streams.
     */
    val preferLocalLibrary: Boolean = true,
    /**
     * The entry order from before shuffle was switched on (entry ids), or null when not shuffled.
     * Switching shuffle off puts the entries back in this order, like a music player's shuffle;
     * entries added while shuffled go after them in the order they are in now.
     */
    val shuffleOriginalOrder: List<String>? = null,
    val createdAtEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
) {
    /** The entry Play starts from, or null for an empty playlist. */
    val resumeEntry: PlaylistEntry?
        get() = entries.getOrNull(resumeIndex.coerceIn(0, (entries.size - 1).coerceAtLeast(0)))

    val shuffled: Boolean
        get() = shuffleOriginalOrder != null
}

/**
 * One playable item. Identifies content, never a stream: every entry runs a normal source search
 * when it is reached, the same way pressing Play on its details page would.
 *
 * Display metadata is captured at add time and carried with the entry, so the playlist renders
 * without a metadata round trip per row and survives an addon being removed.
 */
@Serializable
data class PlaylistEntry(
    /** Unique within its playlist, so the same episode can be queued twice. */
    val entryId: String,
    /** The content type the details page would have launched with ("movie", "series", ...). */
    val type: String,
    /** The video id streams are requested for: the movie id, or the episode's stream video id. */
    val videoId: String,
    val parentMetaId: String,
    val parentMetaType: String,
    /** Movie or show name. */
    val title: String,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val addedAtEpochMs: Long = 0L,
    /**
     * A "TV channel" slot: a random aired episode of the show is picked each time the slot is
     * reached (see PlaylistRandomEpisodes). Stored without season/episode; the resolved copy handed
     * to the player carries them. Played like the details page's Random episode: from the start and
     * with progress tracking and scrobbling off.
     */
    val randomEpisode: Boolean = false,
    /** For a random slot: the seasons it picks from, or null for all main seasons. */
    val randomSeasons: List<Int>? = null,
) {
    val isEpisode: Boolean
        get() = seasonNumber != null && episodeNumber != null

    /** A random slot not yet turned into a concrete episode. */
    val isUnresolvedRandom: Boolean
        get() = randomEpisode && !isEpisode
}

/** "Show · S2E3 · Episode title" for an episode, the title alone for a movie. */
fun PlaylistEntry.displayTitle(): String {
    if (isUnresolvedRandom) return "$title · ${randomSlotLabel()}"
    if (!isEpisode) return title
    val code = "S${seasonNumber}E$episodeNumber"
    return listOfNotNull(title, code, episodeTitle?.takeIf { it.isNotBlank() }).joinToString(" · ")
}

/** "Random episode", or "Random episode · S2–S4" for a slot narrowed to some seasons. */
fun PlaylistEntry.randomSlotLabel(): String {
    val seasons = randomSeasons?.sorted().orEmpty()
    if (seasons.isEmpty()) return localizedString(Res.string.action_random_episode)
    val runs = mutableListOf<String>()
    var start = seasons.first()
    var prev = start
    fun close() { runs += if (start == prev) seasonLabel(start) else "${seasonLabel(start)}–${seasonLabel(prev)}" }
    seasons.drop(1).forEach { season ->
        if (season != prev + 1) { close(); start = season }
        prev = season
    }
    close()
    return localizedString(Res.string.playlist_random_slot_seasons, runs.joinToString(", "))
}

private fun seasonLabel(season: Int): String = if (season == 0) localizedString(Res.string.playlist_specials) else "S$season"
