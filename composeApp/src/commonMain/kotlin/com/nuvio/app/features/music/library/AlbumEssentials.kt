package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's album page redesign, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/*
 * fork:essentials - what the album page's right-hand Essentials column and the track list's popularity bars are built from.
 * Deezer's top songs for the artist (already used to rank the top albums) say which songs are played most; the artist's
 * single releases say which songs were singles. Pure functions here, so they are tested without Compose.
 */

/** Songs listed under Best songs. */
internal const val ESSENTIAL_SONG_COUNT = 6

/** Albums listed under Best albums. */
internal const val ESSENTIAL_ALBUM_COUNT = 4

/**
 * A title reduced to what stays the same between editions: lower case, bracketed parts ("(feat. X)", "[Remastered]") and
 * a trailing " - Radio Edit" dropped, only letters and digits kept. A title that is all brackets keeps its letters.
 */
internal fun songKey(title: String): String {
    val lower = title.lowercase()
    val plain = lower.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), " ").substringBefore(" - ")
    val key = plain.filter { it.isLetterOrDigit() }
    return key.ifEmpty { lower.filter { it.isLetterOrDigit() } }
}

/** The most played songs, best first, one per song however many editions carry it. */
internal fun bestSongs(top: List<DeezerTopSong>, limit: Int = ESSENTIAL_SONG_COUNT): List<DeezerTopSong> =
    top.sortedByDescending { it.rank }.distinctBy { songKey(it.title) }.take(limit)

/**
 * How played each of [titles] is next to the artist's other songs: 0..1 (the artist's most played song is 1), or null when
 * the song is not among Deezer's top 100 (so the bar stays empty rather than claiming it is unpopular). Deezer's own
 * numbers when every song has one, else the position in its list.
 */
internal fun trackPopularity(titles: List<String>, top: List<DeezerTopSong>): List<Float?> {
    if (top.isEmpty()) return titles.map { null }
    val useRank = top.all { it.rank > 0 }
    val score = HashMap<String, Double>()
    top.forEachIndexed { index, song ->
        val value = if (useRank) song.rank.toDouble() else (top.size - index).toDouble()
        val key = songKey(song.title)
        if (value > (score[key] ?: 0.0)) score[key] = value
    }
    val best = score.values.maxOrNull()?.takeIf { it > 0.0 } ?: return titles.map { null }
    return titles.map { title -> score[songKey(title)]?.let { (it / best).toFloat().coerceIn(MIN_BAR, 1f) } }
}

/** Where [title] stands among the artist's most played songs: 1 for the most played, null when it is not in Deezer's top 100. */
internal fun songRank(title: String, top: List<DeezerTopSong>): Int? {
    val key = songKey(title)
    val index = bestSongs(top, limit = top.size.coerceAtLeast(1)).indexOfFirst { songKey(it.title) == key }
    return if (index >= 0) index + 1 else null
}

/**
 * The starter path: which of [titles] the recommendation [text] names, as indices into [titles] in the order the text
 * mentions them (at most [limit]). A title counts only as a whole word, so "Bad" is not found in "badly".
 */
internal fun starterPath(text: String, titles: List<String>, limit: Int = 3): List<Int> {
    val lower = text.lowercase()
    fun positionOf(title: String): Int {
        val wanted = title.lowercase().trim()
        if (wanted.isEmpty()) return -1
        var from = 0
        while (true) {
            val at = lower.indexOf(wanted, from)
            if (at < 0) return -1
            val before = lower.getOrNull(at - 1)
            val after = lower.getOrNull(at + wanted.length)
            if ((before == null || !before.isLetterOrDigit()) && (after == null || !after.isLetterOrDigit())) return at
            from = at + 1
        }
    }
    return titles.indices.mapNotNull { i -> positionOf(titles[i]).takeIf { it >= 0 }?.let { i to it } }
        .sortedBy { it.second }.take(limit).map { it.first }
}

/** The shortest bar drawn for a song that is in the list at all. */
private const val MIN_BAR = 0.08f

/** The songs the artist released as singles: the title keys of its Single releases. */
internal fun singleKeys(releases: List<LibraryRelease>): Set<String> =
    releases.filter { it.kind == ReleaseKind.Single }.map { songKey(it.title) }.filter { it.isNotEmpty() }.toSet()

/** A best song with the release it lives on, when the library has that release (so the row can open it). */
internal data class EssentialSong(val title: String, val albumTitle: String, val release: LibraryRelease?)

/** The release of [song]: Deezer's own album when YouTube Music can play it, else a playable one with the same title. */
internal fun releaseForSong(song: DeezerTopSong, releases: List<LibraryRelease>): LibraryRelease? =
    releases.firstOrNull { it.deezerId == song.albumId && it.ytBrowseId != null }
        ?: releases.firstOrNull { it.ytBrowseId != null && song.albumTitle.isNotBlank() && songKey(it.title) == songKey(song.albumTitle) }
        ?: releases.firstOrNull { it.deezerId == song.albumId }

internal fun essentialSongs(top: List<DeezerTopSong>, releases: List<LibraryRelease>, limit: Int = ESSENTIAL_SONG_COUNT): List<EssentialSong> =
    bestSongs(top, limit).map { EssentialSong(it.title, it.albumTitle, releaseForSong(it, releases)) }

/**
 * The prompt that asks the AI where a newcomer should start. It is given only the artist's albums (best first) and songs,
 * so it has nothing to name that is not on the page.
 */
internal fun startHerePrompt(artist: String, albums: List<String>, songs: List<String>): String = buildString {
    appendLine("Artist: $artist")
    appendLine("Their albums, most listened to first:")
    albums.forEachIndexed { i, a -> appendLine("${i + 1}. $a") }
    if (songs.isNotEmpty()) appendLine("Their most played songs: ${songs.joinToString(", ")}")
    appendLine()
    append("Tell someone new to $artist where to start: two plain sentences that name up to three of the albums above, in the order to ")
    append("play them, and say in a few words what each is for. Use only the albums listed. No markdown, no quotation marks, no lists.")
}

internal const val START_HERE_SYSTEM_PROMPT =
    "You are a music guide writing one short recommendation for a listening app. You only name albums that are in the list you are given."

/** [reply] as the card's text: markdown marks and quotes removed, cut at a sentence at 320 characters, null when it is empty. */
internal fun cleanStartHere(reply: String): String? {
    val plain = reply.replace(Regex("""[*_#`>]"""), "").replace("\"", "").replace(Regex("""\s+"""), " ").trim()
    if (plain.length < 20) return null
    if (plain.length <= 320) return plain
    val head = plain.take(320)
    val stop = head.lastIndexOfAny(charArrayOf('.', '!', '?'))
    return if (stop >= 160) head.take(stop + 1) else head.substringBeforeLast(' ') + "…"
}

/** The top songs per Deezer artist, asked once per session and shared by every album page of that artist. */
internal object ArtistTopSongs {
    private val _state = MutableStateFlow<Map<Long, List<DeezerTopSong>>>(emptyMap())
    val state: StateFlow<Map<Long, List<DeezerTopSong>>> = _state
    private val lock = Mutex()

    suspend fun ensure(deezerId: Long) {
        lock.withLock {
            if (_state.value.containsKey(deezerId)) return
            // Deezer unreachable: nothing is stored, so the next album page of this artist asks again.
            val songs = runCatching { DeezerClient.topSongs(deezerId) }.getOrNull() ?: return
            _state.update { it + (deezerId to songs) }
        }
    }
}
