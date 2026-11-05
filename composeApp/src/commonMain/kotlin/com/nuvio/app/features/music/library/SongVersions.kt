package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's album page lenses, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

/*
 * fork:lenses - the Song by song lens's right-hand panel: every other copy of one song we can find without a new request.
 * The album's own tracks (a remix or demo at the end of the record), the artist's single releases, and the other records
 * Deezer's top songs place it on. Pure functions, so they are tested without Compose.
 */

/** What kind of copy a version is, from its bracketed or dashed suffix ("(David Morales Mix)", "- Live"). */
internal enum class VersionKind(val label: String) {
    Remix("REMIX"), Demo("DEMO"), Live("LIVE"), Instrumental("INSTRUMENTAL"), Edit("EDIT"), Acoustic("ACOUSTIC"),
    Version("VERSION"), Single("SINGLE"), Album("ALBUM"),
    /** The same song on the album's clean (censored) edition, or its explicit one, or another edition (deluxe, remaster). */
    Clean("CLEAN"), Explicit("EXPLICIT"), Edition("EDITION"),
}

/**
 * fork:lenses - another edition of this album, from its YouTube Music "Other versions" shelf (the censored "2001" beside the
 * explicit one, say): enough to find and play the same song on it.
 */
internal data class AlbumEditionTracks(
    val browseId: String,
    val title: String,
    /** True when YouTube Music marks the edition or any of its tracks explicit. */
    val explicit: Boolean,
    val trackTitles: List<String>,
    val trackDurations: List<Int> = emptyList(),
)

/** What an [edition] is next to this album: clean, explicit or just another edition. */
internal fun editionKind(edition: AlbumEditionTracks, thisExplicit: Boolean): VersionKind {
    val t = edition.title.lowercase()
    return when {
        Regex("""\b(clean|censored|edited)\b""").containsMatchIn(t) -> VersionKind.Clean
        thisExplicit && !edition.explicit -> VersionKind.Clean
        !thisExplicit && edition.explicit -> VersionKind.Explicit
        else -> VersionKind.Edition
    }
}

/** Where a version is: [trackIndex] on this album, or [release] (another record or a single) of the artist. */
internal data class SongVersion(
    val title: String,
    val kind: VersionKind,
    /** Index into this album's tracks, or null when the version is on another record. */
    val trackIndex: Int?,
    /** The record it is on when it is not this one (null when the library has no such release). */
    val release: LibraryRelease?,
    /** The other record's title, as Deezer or the library names it. */
    val recordTitle: String?,
    val durationSeconds: Int = 0,
    /** The YouTube Music edition of this album the copy is on (see [AlbumEditionTracks]), with its track index there. */
    val editionId: String? = null,
    val editionTrack: Int? = null,
) {
    val onEdition: Boolean get() = editionId != null
    val onThisAlbum: Boolean get() = trackIndex != null
    val isRework: Boolean get() = kind in REWORKS
}

private val REWORKS = setOf(VersionKind.Remix, VersionKind.Demo, VersionKind.Live, VersionKind.Instrumental, VersionKind.Edit, VersionKind.Acoustic, VersionKind.Version)

/** The part of [title] that says what kind of copy it is: the text in its brackets and after a " - ". */
private fun suffixOf(title: String): String {
    val brackets = Regex("""[(\[]([^)\]]*)[)\]]""").findAll(title).joinToString(" ") { it.groupValues[1] }
    val dashed = title.substringAfter(" - ", "")
    return (brackets + " " + dashed).lowercase()
}

/** The kind of copy [title] is; [VersionKind.Album] when nothing marks it (the plain song). */
internal fun versionKind(title: String): VersionKind {
    val s = suffixOf(title)
    if (s.isBlank()) return VersionKind.Album
    fun has(vararg words: String) = words.any { Regex("""\b$it\b""").containsMatchIn(s) }
    return when {
        has("remix", "mix", "dub", "rework") -> VersionKind.Remix
        has("demo") -> VersionKind.Demo
        has("live") -> VersionKind.Live
        has("instrumental") -> VersionKind.Instrumental
        has("acoustic", "unplugged") -> VersionKind.Acoustic
        has("edit", "radio") -> VersionKind.Edit
        has("version", "take", "session") -> VersionKind.Version
        else -> VersionKind.Album // "(feat. X)", "[Remastered]": the same recording
    }
}

/**
 * The other copies of the song at [focus] in [trackTitles]: first the album's own (same [songKey], in track order), then a
 * single release of it, then the same song on the album's other [editions] (one per edition; [thisExplicit] says whether
 * this one is the explicit edition), then each other record Deezer's [top] songs place it on (one per record, the album's
 * own record left out by [albumTitle]). At most [limit].
 */
internal fun songVersions(
    focus: Int,
    trackTitles: List<String>,
    trackDurations: List<Int>,
    albumTitle: String,
    top: List<DeezerTopSong>,
    releases: List<LibraryRelease>,
    limit: Int = 6,
    editions: List<AlbumEditionTracks> = emptyList(),
    thisExplicit: Boolean = false,
): List<SongVersion> {
    val title = trackTitles.getOrNull(focus) ?: return emptyList()
    val key = songKey(title)
    if (key.isEmpty()) return emptyList()
    val out = ArrayList<SongVersion>()
    trackTitles.forEachIndexed { i, t ->
        if (i != focus && songKey(t) == key) {
            out += SongVersion(t, versionKind(t).let { if (it == VersionKind.Album) VersionKind.Version else it }, i, null, null, trackDurations.getOrElse(i) { 0 })
        }
    }
    val hereKey = songKey(albumTitle)
    releases.firstOrNull { it.kind == ReleaseKind.Single && songKey(it.title) == key }?.let { single ->
        out += SongVersion(single.title, VersionKind.Single, null, single, single.title)
    }
    editions.distinctBy { it.browseId }.forEach { edition ->
        val at = edition.trackTitles.indexOfFirst { songKey(it) == key }
        if (at < 0) return@forEach
        val kind = editionKind(edition, thisExplicit)
        val label = when (kind) {
            VersionKind.Clean -> "Clean edition"
            VersionKind.Explicit -> "Explicit edition"
            else -> "Other edition"
        }
        out += SongVersion(
            edition.trackTitles[at], kind, null, null, "$label · ${edition.title} · track ${at + 1}",
            edition.trackDurations.getOrElse(at) { 0 }, editionId = edition.browseId, editionTrack = at,
        )
    }
    val seenRecords = HashSet<String>().apply { add(hereKey); out.mapNotNullTo(this) { it.release?.let { r -> songKey(r.title) } } }
    top.sortedByDescending { it.rank }.forEach { song ->
        if (songKey(song.title) != key) return@forEach
        val recordKey = songKey(song.albumTitle)
        if (recordKey.isEmpty() || !seenRecords.add(recordKey)) return@forEach
        val release = releaseForSong(song, releases)
        val kind = versionKind(song.title).takeIf { it != VersionKind.Album } ?: if (release?.kind == ReleaseKind.Single) VersionKind.Single else VersionKind.Album
        out += SongVersion(song.title, kind, null, release, release?.title ?: song.albumTitle)
    }
    return out.take(limit)
}

/** The four counts the panel shows over its list. */
internal data class VersionCounts(val found: Int, val onThisAlbum: Int, val elsewhere: Int, val reworks: Int)

internal fun versionCounts(versions: List<SongVersion>): VersionCounts = VersionCounts(
    found = versions.size,
    onThisAlbum = versions.count { it.onThisAlbum },
    elsewhere = versions.count { !it.onThisAlbum },
    reworks = versions.count { it.isRework },
)
