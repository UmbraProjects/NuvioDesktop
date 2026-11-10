package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's artist page redesign (the Story lens), not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

/*
 * fork:artist-story - the artist page's Story lens: a short career timeline worked out from the library's own release dates,
 * with no AI and no network. Each point names a moment (the first record, the first full album, the one they are best known
 * for, the return after a long gap, the latest) and the record from then, which the page links to. Pure functions, so they
 * are tested without Compose.
 */

/** The kinds of moment on the timeline; [label] is the small heading over the point. */
internal enum class StoryKind(val label: String) {
    Debut("DEBUT"),
    FirstAlbum("FIRST ALBUM"),
    BestKnown("BEST KNOWN"),
    Comeback("COMEBACK"),
    Latest("LATEST"),
}

/** One moment of the story: when, what kind, the record from then and a line saying why it is on the list. */
internal data class StoryPoint(val kind: StoryKind, val year: Int, val release: LibraryRelease, val line: String)

/** Secondary types (MusicBrainz) that make a release something other than a record of the artist's own new songs. */
private val NOT_STUDIO = setOf("live", "compilation", "remix", "demo", "dj-mix", "soundtrack", "spokenword", "mixtape/street", "interview", "audiobook")

/** The year a release came out, from its year or full date; null when neither holds four digits. */
internal fun releaseYearOf(release: LibraryRelease): Int? =
    (release.year ?: release.releaseDate)?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }?.toInt()

/** An album or EP of the artist's own new songs: not live, a compilation, a remix or a soundtrack. */
internal fun isStudioRelease(release: LibraryRelease): Boolean =
    (release.kind == ReleaseKind.Album || release.kind == ReleaseKind.EP) && release.secondaryTypes.none { it.lowercase() in NOT_STUDIO }

/** A record for the timeline: a studio album or EP, or a single. */
internal fun isRecordRelease(release: LibraryRelease): Boolean =
    isStudioRelease(release) || (release.kind == ReleaseKind.Single && release.secondaryTypes.none { it.lowercase() in NOT_STUDIO })

/** Oldest first: by year, then full date, then key so the order never changes between runs. */
private val OLDEST_FIRST = compareBy<LibraryRelease>({ releaseYearOf(it) ?: Int.MAX_VALUE }, { it.releaseDate.orEmpty() }, { it.key })

/** A band, orchestra or choir has a formation year; a person's begin year is a birth year and says nothing about a debut. */
private fun isGroupType(type: String?): Boolean = type?.lowercase() in setOf("group", "orchestra", "choir")

/**
 * The story of [artist] in time order: at most five points, each with a different record. Empty when the library knows no
 * dated releases. Nothing here reads the clock, so the same library always tells the same story.
 */
internal fun artistStoryPoints(artist: LibraryArtist): List<StoryPoint> {
    val records = artist.releases.filter { isRecordRelease(it) && releaseYearOf(it) != null }.sortedWith(OLDEST_FIRST)
    val debut = records.firstOrNull() ?: return emptyList()
    val used = HashSet<String>()
    val points = ArrayList<StoryPoint>()
    val best = artistTopReleases(artist, limit = 1).firstOrNull()?.takeIf { releaseYearOf(it) != null }

    val debutYear = releaseYearOf(debut) ?: return emptyList()
    val formed = artist.beginYear?.toIntOrNull()
    val debutLine = buildString {
        if (formed != null && isGroupType(artist.type) && debutYear - formed >= 2) {
            append("First record, ${debutYear - formed} years after they formed in $formed")
        } else {
            append("Where the story starts")
        }
        if (best != null && best.key == debut.key) append(", and still the one they are best known for")
    }
    points += StoryPoint(StoryKind.Debut, debutYear, debut, debutLine)
    used += debut.key

    val firstAlbum = records.firstOrNull { it.kind == ReleaseKind.Album && isStudioRelease(it) }
    if (firstAlbum != null && used.add(firstAlbum.key)) {
        points += StoryPoint(StoryKind.FirstAlbum, releaseYearOf(firstAlbum) ?: debutYear, firstAlbum, "Their first full album")
    }

    if (best != null && used.add(best.key)) {
        points += StoryPoint(StoryKind.BestKnown, releaseYearOf(best) ?: debutYear, best, "The record they are best known for")
    }

    val studio = records.filter { isStudioRelease(it) }
    var gap = 0
    var returned: LibraryRelease? = null
    for (i in 1 until studio.size) {
        val years = (releaseYearOf(studio[i]) ?: continue) - (releaseYearOf(studio[i - 1]) ?: continue)
        if (years > gap) {
            gap = years
            returned = studio[i]
        }
    }
    if (returned != null && gap >= MIN_COMEBACK_GAP_YEARS && used.add(returned.key)) {
        points += StoryPoint(StoryKind.Comeback, releaseYearOf(returned) ?: debutYear, returned, "Back after $gap years without a new album or EP")
    }

    val latest = records.last()
    if (used.add(latest.key)) {
        points += StoryPoint(
            StoryKind.Latest,
            releaseYearOf(latest) ?: debutYear,
            latest,
            if (artist.ended) "Their last record" else "Their most recent record",
        )
    }
    return points.sortedWith(compareBy({ it.year }, { it.kind.ordinal }))
}

/** A gap between albums or EPs this long (in years) is called out as a comeback. */
internal const val MIN_COMEBACK_GAP_YEARS = 4

/** "8 albums · 3 EPs · 1989–2022": what the timeline is made of, for its heading; null with no dated record. */
internal fun artistStorySummary(artist: LibraryArtist): String? {
    val records = artist.releases.filter { isRecordRelease(it) && releaseYearOf(it) != null }
    if (records.isEmpty()) return null
    val albums = records.count { it.kind == ReleaseKind.Album && isStudioRelease(it) }
    val eps = records.count { it.kind == ReleaseKind.EP && isStudioRelease(it) }
    val first = records.minOf { releaseYearOf(it) ?: Int.MAX_VALUE }
    val last = records.maxOf { releaseYearOf(it) ?: Int.MIN_VALUE }
    return listOfNotNull(
        albums.takeIf { it > 0 }?.let { if (it == 1) "1 album" else "$it albums" },
        eps.takeIf { it > 0 }?.let { if (it == 1) "1 EP" else "$it EPs" },
        if (first == last) "$first" else "$first–$last",
    ).joinToString(" · ")
}
