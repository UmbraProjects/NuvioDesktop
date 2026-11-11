package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's artist page redesign (the Story lens), not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

/*
 * fork:artist-story - the artist page's Story lens: a short career timeline worked out from the library's own release dates,
 * with no AI and no network. Each point names a moment (the first record, the first full album, the one they are best known
 * for, the return after a long gap, the latest) and the record from then, which the page links to. Pure functions, so they
 * are tested without Compose.
 *
 * Nov 10 (Nick: "the years do not make sense"): reissues, deluxe editions, compilations and live records used to count as
 * albums (Jamiroquai read 16 albums, the right panel 17; they have 8 studio albums), the comeback named a gap whose start was
 * not on the line, and the latest point could be a release with no cover that plays nowhere. Now every edition of a record
 * folds into one at its first year, counts are studio albums only (shared with the right panel), the comeback says what it
 * came back from, the latest must have a cover or play, and [artistStoryRuler] gives the page a to-scale year line.
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

/**
 * The year line drawn to scale above the points: [from] to [to], a tick for every studio album ([albumYears]), the story's
 * points ([pointYears]) and the longest break between albums or EPs ([gapFrom] to [gapTo], null when no break of
 * [MIN_COMEBACK_GAP_YEARS] or more).
 */
internal data class StoryRuler(
    val from: Int,
    val to: Int,
    val albumYears: List<Int>,
    val pointYears: List<Int>,
    val gapFrom: Int?,
    val gapTo: Int?,
)

/** Secondary types (MusicBrainz) that make a release something other than a record of the artist's own new songs. */
private val NOT_STUDIO = setOf("live", "compilation", "remix", "demo", "dj-mix", "soundtrack", "spokenword", "mixtape/street", "interview", "audiobook")

/**
 * Titles that say a release is not new studio songs, for the many releases YouTube Music lists with no MusicBrainz types:
 * live sets, best-ofs, singles collections, remix albums, sessions. Word-bounded, so "Alive" or "Hitsville" pass.
 */
private val NOT_STUDIO_TITLE = Regex(
    """\b(live|in concert|unplugged|greatest hits|the hits|hits collection|best of|the best|the very best|collection|anthology|essentials?|singles|b-sides|rarities|remix(es|ed)?|the remixes|sessions?|demos?|karaoke|instrumentals?)\b""",
    RegexOption.IGNORE_CASE,
)

/** The year a release came out, from its year or full date; null when neither holds four digits. */
internal fun releaseYearOf(release: LibraryRelease): Int? =
    (release.year ?: release.releaseDate)?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }?.toInt()

private fun notStudioByType(release: LibraryRelease): Boolean = release.secondaryTypes.any { it.lowercase() in NOT_STUDIO }

private fun notStudioByTitle(release: LibraryRelease): Boolean = NOT_STUDIO_TITLE.containsMatchIn(release.title)

/** An album or EP of the artist's own new songs: not live, a compilation, a remix or a soundtrack (by type or by title). */
internal fun isStudioRelease(release: LibraryRelease): Boolean =
    (release.kind == ReleaseKind.Album || release.kind == ReleaseKind.EP) && !notStudioByType(release) && !notStudioByTitle(release)

/** A record for the timeline: a studio album or EP, or a single. */
internal fun isRecordRelease(release: LibraryRelease): Boolean =
    isStudioRelease(release) || (release.kind == ReleaseKind.Single && !notStudioByType(release) && !notStudioByTitle(release))

/**
 * The record a release is an edition of: kind plus the title without edition words ("Emergency On Planet Earth (Remastered
 * 2013)" and "... Deluxe Edition" are the album of 1993).
 */
internal fun storyRecordKey(release: LibraryRelease): String =
    "${release.kind.name}|" + albumSearchTitle(release.title).lowercase().filter { it.isLetterOrDigit() }

/** One record on the story: the year it first came out and the edition to show and open (one that plays, with a cover). */
internal data class StoryRecord(val year: Int, val release: LibraryRelease)

/** Oldest first: by year, then full date, then key so the order never changes between runs. */
private val OLDEST_RECORD_FIRST = compareBy<StoryRecord>({ it.year }, { it.release.releaseDate.orEmpty() }, { it.release.key })

/**
 * Every record of [artist] once: studio albums, EPs and singles with a year, demos left out, each edition folded into the
 * record at its first year. The edition shown is the one that plays ([addonKeys] count as playing), then has a cover, then
 * is oldest. Oldest first.
 */
internal fun storyRecords(artist: LibraryArtist, addonKeys: Set<String> = emptySet()): List<StoryRecord> =
    artist.releases
        .filter { isRecordRelease(it) && !it.isDemo() && releaseYearOf(it) != null }
        .groupBy(::storyRecordKey)
        .values
        .map { editions ->
            val year = editions.minOf { releaseYearOf(it)!! }
            val shown = editions.sortedWith(
                compareByDescending<LibraryRelease> { it.isPlayable || it.key in addonKeys }
                    .thenByDescending { it.art.cover != null }
                    .thenBy { releaseYearOf(it) ?: Int.MAX_VALUE }
                    .thenBy { it.key },
            ).first()
            StoryRecord(year, shown)
        }
        .sortedWith(OLDEST_RECORD_FIRST)

/** The artist's studio albums, each once: the one count the Story heading and the right panel's Albums tile share. */
internal fun studioAlbumCount(artist: LibraryArtist): Int =
    storyRecords(artist).count { it.release.kind == ReleaseKind.Album }

/** A band, orchestra or choir has a formation year; a person's begin year is a birth year and says nothing about a debut. */
private fun isGroupType(type: String?): Boolean = type?.lowercase() in setOf("group", "orchestra", "choir")

/** The longest break between studio albums or EPs: the record before it and the one after. Null under the threshold. */
private fun longestBreak(records: List<StoryRecord>): Pair<StoryRecord, StoryRecord>? {
    val studio = records.filter { isStudioRelease(it.release) }
    var best: Pair<StoryRecord, StoryRecord>? = null
    for (i in 1 until studio.size) {
        val gap = studio[i].year - studio[i - 1].year
        if (gap >= MIN_COMEBACK_GAP_YEARS && gap > (best?.let { it.second.year - it.first.year } ?: 0)) best = studio[i - 1] to studio[i]
    }
    return best
}

/** Has a cover or plays here (on YouTube Music or from the addon): good enough to stand as the latest record. */
private fun StoryRecord.shows(addonKeys: Set<String>): Boolean =
    release.isPlayable || release.key in addonKeys || release.art.cover != null

/**
 * The story of [artist] in time order: at most five points, each with a different record. Empty when the library knows no
 * dated releases. Nothing here reads the clock, so the same library always tells the same story.
 */
internal fun artistStoryPoints(artist: LibraryArtist, addonKeys: Set<String> = emptySet()): List<StoryPoint> {
    val records = storyRecords(artist, addonKeys)
    val debut = records.firstOrNull() ?: return emptyList()
    val used = HashSet<String>()
    val points = ArrayList<StoryPoint>()
    val bestRelease = artistTopReleases(artist, limit = 1).firstOrNull()
    val best = bestRelease?.let { top -> records.firstOrNull { storyRecordKey(it.release) == storyRecordKey(top) } }

    val formed = artist.beginYear?.toIntOrNull()
    val debutLine = buildString {
        if (formed != null && isGroupType(artist.type) && debut.year - formed >= 2) {
            append("First record, ${debut.year - formed} years after they formed in $formed")
        } else {
            append("Where the story starts")
        }
        if (best != null && best === debut) append(", and still the one they are best known for")
    }
    points += StoryPoint(StoryKind.Debut, debut.year, debut.release, debutLine)
    used += debut.release.key

    val firstAlbum = records.firstOrNull { it.release.kind == ReleaseKind.Album && isStudioRelease(it.release) }
    if (firstAlbum != null && used.add(firstAlbum.release.key)) {
        points += StoryPoint(StoryKind.FirstAlbum, firstAlbum.year, firstAlbum.release, "Their first full album")
    }

    if (best != null && used.add(best.release.key)) {
        points += StoryPoint(StoryKind.BestKnown, best.year, best.release, "The record they are best known for")
    }

    val gap = longestBreak(records)
    if (gap != null && used.add(gap.second.release.key)) {
        val (before, after) = gap
        points += StoryPoint(
            StoryKind.Comeback,
            after.year,
            after.release,
            "Back ${after.year - before.year} years after ${albumSearchTitle(before.release.title)} (${before.year})",
        )
    }

    val latest = records.lastOrNull { it.shows(addonKeys) }
    if (latest != null && used.add(latest.release.key)) {
        points += StoryPoint(
            StoryKind.Latest,
            latest.year,
            latest.release,
            if (artist.ended) "Their last record" else "Their most recent record",
        )
    }
    return points.sortedWith(compareBy({ it.year }, { it.kind.ordinal }))
}

/** The to-scale year line for the Story lens; null with fewer than two points or a career inside one year. */
internal fun artistStoryRuler(artist: LibraryArtist, points: List<StoryPoint>): StoryRuler? {
    if (points.size < 2) return null
    val records = storyRecords(artist)
    val from = points.first().year
    val to = points.last().year
    if (to <= from) return null
    val gap = longestBreak(records)
    return StoryRuler(
        from = from,
        to = to,
        albumYears = records.filter { it.release.kind == ReleaseKind.Album && isStudioRelease(it.release) && it.year in from..to }.map { it.year },
        pointYears = points.map { it.year },
        gapFrom = gap?.first?.year,
        gapTo = gap?.second?.year,
    )
}

/** A gap between albums or EPs this long (in years) is called out as a comeback. */
internal const val MIN_COMEBACK_GAP_YEARS = 4

/** "8 albums · 3 EPs · 1993–2017": what the timeline is made of, for its heading; null with no dated record. */
internal fun artistStorySummary(artist: LibraryArtist): String? {
    val records = storyRecords(artist)
    if (records.isEmpty()) return null
    val albums = records.count { it.release.kind == ReleaseKind.Album }
    val eps = records.count { it.release.kind == ReleaseKind.EP }
    val first = records.first().year
    val last = records.maxOf { it.year }
    return listOfNotNull(
        albums.takeIf { it > 0 }?.let { if (it == 1) "1 studio album" else "$it studio albums" },
        eps.takeIf { it > 0 }?.let { if (it == 1) "1 EP" else "$it EPs" },
        if (first == last) "$first" else "$first–$last",
    ).joinToString(" · ")
}
