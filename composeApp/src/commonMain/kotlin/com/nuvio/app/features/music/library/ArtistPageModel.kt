package com.nuvio.app.features.music.library

/**
 * Pure shaping of a [LibraryArtist] for the artist page: duplicate versions folded into one card,
 * demos pulled into an archive, releases clustered into year "eras", links deduped and grouped.
 * No Compose in here so it can be unit-tested.
 */

/** One card on the page: the version to show and play, plus every version folded into it. */
internal data class ReleaseGroup(
    val primary: LibraryRelease,
    val versions: List<LibraryRelease>,
) {
    val isPlayable: Boolean get() = versions.any { it.isPlayable }
    val extraVersions: Int get() = versions.size - 1
    val kind: ReleaseKind get() = primary.kind
}

/** The kinds of release the type chips filter on. */
internal enum class ReleaseFilter(val label: String) {
    All("All"),
    Albums("Albums"),
    EPs("EPs"),
    Singles("Singles"),
    Other("Other"),
    Demos("Demos"),
}

/** All releases from one year. [singles] are kept apart so the page can show them as a compact list. */
internal data class Era(
    val year: String,
    val full: List<ReleaseGroup>,
    val singles: List<ReleaseGroup>,
) {
    val count: Int get() = full.size + singles.size
}

internal data class ArtistDiscography(
    val eras: List<Era>,
    val demos: List<ReleaseGroup>,
    /** Count of cards (after folding versions) that can be played, and all of them, demos included. */
    val playableCount: Int,
    val totalCount: Int,
    /** Chips worth showing, with how many cards each holds. */
    val filterCounts: Map<ReleaseFilter, Int>,
)

private val demoWord = Regex("""\bdemos?\b""", RegexOption.IGNORE_CASE)

/** Reissue noise in brackets that should not make two releases look different. */
private val editionNoise = Regex(
    """\s*[(\[][^)\]]*\b(deluxe|expanded|remaster(ed)?|anniversary|edition|bonus|version)\b[^)\]]*[)\]]""",
    RegexOption.IGNORE_CASE,
)

internal fun LibraryRelease.isDemo(): Boolean =
    secondaryTypes.any { it.equals("Demo", ignoreCase = true) } || demoWord.containsMatchIn(title)

/**
 * Secondary types worth printing beside the kind. MusicBrainz tags a live album as kind Live *and*
 * secondary type "Live"; printing both gave "Live · Live".
 */
internal fun LibraryRelease.extraTypes(): List<String> =
    secondaryTypes.filterNot { it.equals(kind.label, ignoreCase = true) }

/** Two releases with the same key are the same record listed twice by different sources. */
internal fun LibraryRelease.dedupeKey(): String {
    val cleanTitle = editionNoise.replace(title, "").lowercase().filter { it.isLetterOrDigit() }
    return "${kind.name}|$cleanTitle|${year.orEmpty()}"
}

/** Sort key, newest first when compared descending. `2026-03` sorts above `2026`. */
private fun LibraryRelease.sortDate(): String = sanePartialDate(releaseDate) ?: sanePartialDate(year) ?: ""

private fun fold(list: List<LibraryRelease>): List<ReleaseGroup> =
    list.groupBy { it.dedupeKey() }.values.map { versions ->
        // The card stands for the version that plays; among equals, the fuller and better-illustrated one.
        val ordered = versions.sortedWith(
            compareByDescending<LibraryRelease> { it.isPlayable }
                .thenByDescending { it.trackCount ?: 0 }
                .thenByDescending { it.art.cover != null },
        )
        ReleaseGroup(primary = ordered.first(), versions = ordered)
    }

internal fun ReleaseGroup.matches(filter: ReleaseFilter): Boolean = when (filter) {
    ReleaseFilter.All -> true
    ReleaseFilter.Albums -> kind == ReleaseKind.Album
    ReleaseFilter.EPs -> kind == ReleaseKind.EP
    ReleaseFilter.Singles -> kind == ReleaseKind.Single
    ReleaseFilter.Other -> kind == ReleaseKind.Compilation || kind == ReleaseKind.Live || kind == ReleaseKind.Other
    ReleaseFilter.Demos -> false // demos never share a card list with the rest
}

/**
 * How many cards an artist has once versions are folded and demos are counted too. This is the one
 * definition of "releases" for every screen: the library grid used the raw list (66 for an artist
 * whose page said 64).
 */
internal fun countReleaseCards(releases: List<LibraryRelease>): Int {
    val (demoReleases, regularReleases) = releases.partition { it.isDemo() }
    return fold(regularReleases).size + fold(demoReleases).size
}

internal fun buildDiscography(
    releases: List<LibraryRelease>,
    playableOnly: Boolean,
    filter: ReleaseFilter,
): ArtistDiscography {
    val (demoReleases, regularReleases) = releases.partition { it.isDemo() }
    val regular = fold(regularReleases)
    val demos = fold(demoReleases)

    val filterCounts = buildMap {
        put(ReleaseFilter.All, regular.size + demos.size)
        ReleaseFilter.entries.forEach { f ->
            if (f == ReleaseFilter.All) return@forEach
            val n = if (f == ReleaseFilter.Demos) demos.size else regular.count { it.matches(f) }
            if (n > 0) put(f, n)
        }
    }

    fun List<ReleaseGroup>.visible() = if (playableOnly) filter { it.isPlayable } else this

    val shownRegular = when (filter) {
        ReleaseFilter.Demos -> emptyList()
        else -> regular.filter { it.matches(filter) }.visible()
    }
    val shownDemos = if (filter == ReleaseFilter.All || filter == ReleaseFilter.Demos) demos.visible() else emptyList()

    val eras = shownRegular
        .groupBy { releaseYearOrNull(it.primary.year)?.toString() ?: "" }
        .map { (year, groups) ->
            val sorted = groups.sortedByDescending { it.primary.sortDate() }
            val (singles, full) = sorted.partition { it.kind == ReleaseKind.Single }
            Era(year = year, full = full, singles = singles)
        }
        // Newest year first; releases with no year last.
        .sortedWith(compareByDescending<Era> { it.year.isNotEmpty() }.thenByDescending { it.year })

    return ArtistDiscography(
        eras = eras,
        demos = shownDemos.sortedByDescending { it.primary.sortDate() },
        playableCount = (regular + demos).count { it.isPlayable },
        totalCount = regular.size + demos.size, // same rule as countReleaseCards
        filterCounts = filterCounts,
    )
}

/**
 * The release the page leads with: the newest non-demo one that can be played, else the newest
 * non-demo one. Full releases win over singles from the same year.
 */
internal fun latestRelease(releases: List<LibraryRelease>): LibraryRelease? {
    val candidates = releases.filterNot { it.isDemo() }
    fun pick(list: List<LibraryRelease>) = list.sortedWith(
        compareByDescending<LibraryRelease> { it.sortDate().take(4) }
            .thenByDescending { it.kind != ReleaseKind.Single }
            .thenByDescending { it.sortDate() },
    ).firstOrNull()
    return pick(candidates.filter { it.isPlayable }) ?: pick(candidates)
}

// region links

internal enum class LinkGroup(val label: String) { Listen("Listen"), Social("Social"), Info("Info") }

private val listenLabels = setOf(
    "spotify", "apple music", "deezer", "tidal", "youtube", "youtube music", "bandcamp", "soundcloud",
    "amazon music", "qobuz", "napster",
)
private val socialLabels = setOf(
    "x", "twitter", "facebook", "instagram", "tiktok", "twitch", "threads", "bluesky", "mastodon", "patreon",
)

internal fun ArtistLink.group(): LinkGroup {
    val key = label.trim().lowercase()
    return when {
        key in listenLabels -> LinkGroup.Listen
        key in socialLabels -> LinkGroup.Social
        else -> LinkGroup.Info
    }
}

/** One link per label (the first wins: MusicBrainz lists the better-curated one first), grouped. */
internal fun groupLinks(links: List<ArtistLink>): Map<LinkGroup, List<ArtistLink>> {
    val byGroup = links.distinctBy { it.label.trim().lowercase() }.groupBy { it.group() }
    // LinkGroup order, not the order the sources happened to list them in.
    return LinkGroup.entries.mapNotNull { g -> byGroup[g]?.let { g to it } }.toMap()
}

// endregion

// region headline

/** "hip hop / rapcore / Rap · United Kingdom · since 1990": what the old header said, in one line. */
internal fun artistHeadline(artist: LibraryArtist): String = listOfNotNull(
    artist.genres.take(3).joinToString(" / ").takeIf { it.isNotEmpty() },
    artist.area ?: artist.country,
    artist.beginYear?.let { begin ->
        if (artist.ended && artist.endYear != null) "$begin–${artist.endYear}" else "since $begin"
    },
).joinToString(" · ")

// endregion
