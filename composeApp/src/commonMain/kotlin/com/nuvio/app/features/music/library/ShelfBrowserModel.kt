package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's Shelf redesign, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

/*
 * fork:shelf - the redesigned Shelf (ShelfBrowser.kt) as pure data, so it is unit-tested. The Shelf browses the collection in
 * three panels that fit one screen: the artists (or how to sort and narrow the album wall) on the left, a fixed page of
 * covers in the middle, and the album in focus on the right. Pages turn rather than scroll, so nothing moves or resizes.
 */

/** Browse by artist (the artist's albums in the middle) or every album as one wall. */
internal enum class ShelfMode { ByArtist, AllAlbums }

/** Which artists the left panel lists. */
internal enum class ShelfArtistFilter(val label: String) {
    All("All"),
    Starred("Starred"),
    AddedRecently("Added recently"),
}

/** How the album wall is ordered. */
internal enum class ShelfAlbumSort(val label: String, val hint: String) {
    Album("Album A to Z", ""),
    Artist("Artist A to Z", ""),
    Year("Year", "newest first"),
    Added("Added", "most recent first"),
}

/** Covers on one page of an artist's albums (four by two) and of the album wall (five by three). */
internal const val SHELF_ARTIST_COLUMNS = 4
internal const val SHELF_ARTIST_PAGE = 8
internal const val SHELF_WALL_COLUMNS = 5
internal const val SHELF_WALL_PAGE = 15

/** "Added recently" lists the artists added in the last this many days, or at least this many of the newest. */
private const val RECENT_DAYS = 60L
private const val RECENT_AT_LEAST = 12
private const val DAY_MS = 24L * 60 * 60 * 1000

/** One album on the Shelf: a playable release, its artist, and its place in the artist's top five (1..5) when it has one. */
internal data class ShelfAlbum(
    val artist: LibraryArtist,
    val release: LibraryRelease,
    val rank: Int?,
    val pinned: Boolean,
) {
    val key: String get() = "${artist.id}|${release.key}"
}

/** The key an artist is sorted and lettered by: the sort name if MusicBrainz gave one, else the name without a leading "The". */
internal fun LibraryArtist.shelfSortKey(): String {
    val base = sortName?.takeIf { it.isNotBlank() } ?: name.removePrefixIgnoringCase("the ")
    return base.trim().lowercase()
}

private fun String.removePrefixIgnoringCase(prefix: String): String =
    if (length > prefix.length && startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this

/** The letter an artist is filed under on the A to Z rail: A to Z, or '#' for anything else (digits, symbols, other scripts). */
internal fun LibraryArtist.shelfLetter(): Char {
    val c = shelfSortKey().firstOrNull()?.uppercaseChar() ?: return '#'
    return if (c in 'A'..'Z') c else '#'
}

/** The left panel's artists for [filter] and the header's [query]: A to Z, except Added recently, which is newest first. */
internal fun shelfArtists(artists: List<LibraryArtist>, filter: ShelfArtistFilter, query: String, nowEpochMs: Long): List<LibraryArtist> {
    val matching = artists.filter { it.matchesLibraryFilter(query) }
    return when (filter) {
        ShelfArtistFilter.All -> matching.sortedBy { it.shelfSortKey() }
        ShelfArtistFilter.Starred -> matching.filter { it.favourite }.sortedBy { it.shelfSortKey() }
        ShelfArtistFilter.AddedRecently -> {
            val newest = matching.filter { it.addedAtEpochMs > 0L }.sortedByDescending { it.addedAtEpochMs }
            val since = nowEpochMs - RECENT_DAYS * DAY_MS
            val recent = newest.takeWhile { it.addedAtEpochMs >= since }
            if (recent.size >= RECENT_AT_LEAST) recent else newest.take(RECENT_AT_LEAST)
        }
    }
}

/** Where each letter's first artist is in [artists] (A to Z order); a letter with no artist is absent. */
internal fun shelfLetterIndex(artists: List<LibraryArtist>): Map<Char, Int> {
    val out = LinkedHashMap<Char, Int>()
    artists.forEachIndexed { i, a -> out.getOrPut(a.shelfLetter()) { i } }
    return out
}

/** The first artist at or after [letter] (the next letter that has one when [letter] has none), or null past the end. */
internal fun shelfJumpTo(letterIndex: Map<Char, Int>, letter: Char): Int? {
    letterIndex[letter]?.let { return it }
    return letterIndex.entries.filter { it.key > letter }.minByOrNull { it.key }?.value
}

/** [artist]'s playable albums as the Shelf shows them: the top five first, then the rest oldest to newest. */
internal fun shelfAlbumsOf(artist: LibraryArtist): List<ShelfAlbum> =
    buildShelfRow(artist).tiles.map { ShelfAlbum(artist, it.release, it.rank, it.pinned) }

/** A decade on the album wall's Show list ("1980s"), from a release's year. Null without a year. */
internal fun decadeOf(release: LibraryRelease): Int? {
    val year = (release.year ?: release.releaseDate)?.take(4)?.toIntOrNull() ?: return null
    return year / 10 * 10
}

/** What the album wall can show besides everything: starred albums, and each decade the collection has (oldest first). */
internal sealed interface ShelfAlbumShow {
    val label: String
    data object All : ShelfAlbumShow { override val label = "All" }
    data object Starred : ShelfAlbumShow { override val label = "Starred" }
    data class Decade(val start: Int) : ShelfAlbumShow { override val label = "${start}s" }
}

/** The Show choices for [albums]: All, Starred, then the decades that have albums, oldest first. */
internal fun shelfShowChoices(albums: List<ShelfAlbum>): List<ShelfAlbumShow> =
    listOf(ShelfAlbumShow.All, ShelfAlbumShow.Starred) +
        albums.mapNotNull { decadeOf(it.release) }.distinct().sorted().map { ShelfAlbumShow.Decade(it) }

/** Every playable album of [artists], narrowed by the header's [query] (artist name, genre or album title). */
internal fun shelfAllAlbums(artists: List<LibraryArtist>, query: String): List<ShelfAlbum> {
    val q = query.trim()
    return artists.flatMap { artist ->
        val albums = shelfAlbumsOf(artist)
        if (q.isEmpty() || artist.matchesLibraryFilter(q)) albums else albums.filter { it.release.title.contains(q, ignoreCase = true) }
    }
}

/** The album wall: [albums] narrowed to [show] and in [sort] order. */
internal fun shelfWall(albums: List<ShelfAlbum>, sort: ShelfAlbumSort, show: ShelfAlbumShow): List<ShelfAlbum> {
    val shown = when (show) {
        ShelfAlbumShow.All -> albums
        ShelfAlbumShow.Starred -> albums.filter { it.pinned }
        is ShelfAlbumShow.Decade -> albums.filter { decadeOf(it.release) == show.start }
    }
    fun date(a: ShelfAlbum) = a.release.releaseDate ?: a.release.year ?: ""
    return when (sort) {
        ShelfAlbumSort.Album -> shown.sortedWith(compareBy<ShelfAlbum> { it.release.title.lowercase().removePrefixIgnoringCase("the ") }.thenBy { it.artist.shelfSortKey() })
        ShelfAlbumSort.Artist -> shown.sortedWith(compareBy<ShelfAlbum> { it.artist.shelfSortKey() }.thenBy { date(it) })
        ShelfAlbumSort.Year -> shown.sortedWith(compareByDescending<ShelfAlbum> { date(it) }.thenBy { it.artist.shelfSortKey() })
        ShelfAlbumSort.Added -> shown.sortedWith(compareByDescending<ShelfAlbum> { it.artist.addedAtEpochMs }.thenBy { it.artist.shelfSortKey() }.thenBy { date(it) })
    }
}

/** The wall's heading for [show]: "Every album", "Starred albums" or "The 1980s". */
internal fun shelfWallTitle(show: ShelfAlbumShow): String = when (show) {
    ShelfAlbumShow.All -> "Every album"
    ShelfAlbumShow.Starred -> "Starred albums"
    is ShelfAlbumShow.Decade -> "The ${show.start}s"
}

/** Pages of [perPage] needed for [count] items (at least one, so an empty page still shows). */
internal fun shelfPageCount(count: Int, perPage: Int): Int = maxOf(1, (count + perPage - 1) / perPage)

/** The items on [page] of [items], [perPage] a page; an empty list past the end. */
internal fun <T> List<T>.shelfPage(page: Int, perPage: Int): List<T> {
    val from = page * perPage
    if (from !in indices) return emptyList()
    return subList(from, minOf(size, from + perPage))
}

/** "1982 · 9 songs · 42 min", leaving out what is not known. */
internal fun shelfAlbumLine(release: LibraryRelease, artistName: String? = null): String = listOfNotNull(
    artistName,
    release.year ?: release.releaseDate?.take(4),
    release.trackCount?.let { "$it song${if (it == 1) "" else "s"}" },
    release.durationSeconds?.takeIf { it > 0 }?.let { "${(it + 30) / 60} min" },
).joinToString(" · ")

/** The line under an artist's name in the middle panel: "11 albums · 9 play here", with "· 4 lossless on your addon" when known. */
internal fun shelfArtistLine(total: Int, playable: Int, lossless: Int): String = buildString {
    append("$total release${if (total == 1) "" else "s"}")
    if (playable != total) append(" · $playable play here")
    if (lossless > 0) append(" · $lossless lossless on your addon")
}
