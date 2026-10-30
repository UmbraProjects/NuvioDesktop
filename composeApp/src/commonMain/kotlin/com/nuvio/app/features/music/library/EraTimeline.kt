package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.identity.ArtworkPalette
import kotlin.math.roundToInt

/**
 * Phase 4a: the artist room's timeline, as pure data (no Compose, so it is unit-tested).
 *
 * Releases are folded and demos left out exactly as [buildDiscography] does, then clustered into
 * multi-year eras. An era starts a new block when there is a gap of two or more years since the
 * previous release, or when it would otherwise span more than two calendar years (so 2023, 2024,
 * 2025, 2026 become "2023 – 2024" and "2025 – 2026", as in the mock).
 */

/** One block on the timeline. [groups] are oldest first, so the cards read left to right. */
internal data class TimelineEra(
    val index: Int,
    val fromYear: Int,
    val toYear: Int,
    /** The title of the [lead] release. */
    val name: String,
    /** The release that names the era and lends it its colour. */
    val lead: ReleaseGroup,
    val groups: List<ReleaseGroup>,
) {
    /** "2014" or "2023 – 2024". */
    val years: String get() = if (fromYear == toYear) "$fromYear" else "$fromYear – $toYear"
    val releaseCount: Int get() = groups.size
    val trackTotal: Int get() = groups.sumOf { it.primary.trackCount ?: 0 }
}

/** One release on the ruler. [position] is a fractional year (2023.5 is mid-2023). */
internal data class TimelineDot(
    val eraIndex: Int,
    val release: LibraryRelease,
    val position: Double,
    val trackCount: Int,
)

internal data class EraTimeline(
    val eras: List<TimelineEra>,
    val dots: List<TimelineDot>,
    /** Ruler runs from the start of [startYear] to the end of [endYear] (exclusive of the next year's start). */
    val startYear: Int,
    val endYear: Int,
    /** Releases with no usable year: shown in their own block, not on the ruler. */
    val undated: List<ReleaseGroup>,
) {
    val isEmpty: Boolean get() = eras.isEmpty() && undated.isEmpty()

    /** Ruler length in years, so a UI can map [TimelineDot.position] to pixels: `(position - startYear) / spanYears`. */
    val spanYears: Int get() = endYear - startYear + 1

    /** Era of the release the page leads with, else the newest era. -1 when there are no dated eras. */
    fun defaultEraIndex(latest: LibraryRelease?): Int {
        if (eras.isEmpty()) return -1
        val key = latest?.dedupeKey()
        return eras.firstOrNull { era -> era.groups.any { it.primary.dedupeKey() == key } }?.index ?: eras.last().index
    }
}

private const val MAX_ERA_SPAN_YEARS = 2
private const val MIN_GAP_YEARS = 2

private fun ReleaseGroup.yearInt(): Int? = releaseYearOrNull(primary.year)

/** The release that names an era: the fullest album, else the fullest release of any kind. */
internal fun leadOf(groups: List<ReleaseGroup>): ReleaseGroup =
    groups.maxWithOrNull(
        compareBy<ReleaseGroup> { it.kind == ReleaseKind.Album }
            .thenBy { it.kind == ReleaseKind.EP }
            .thenBy { it.primary.trackCount ?: 0 }
            .thenBy { it.isPlayable },
    )!!

/**
 * Where a release sits on the ruler as a fractional year, or null with no year. A full date is placed
 * by month and day; a month by its middle; a bare year at mid-year (indicative, as the brief says).
 */
internal fun releasePosition(release: LibraryRelease): Double? {
    val parts = (release.releaseDate ?: release.year)?.split("-").orEmpty()
    val year = parts.getOrNull(0)?.takeIf { it.length == 4 }?.let(::releaseYearOrNull) ?: return null
    val month = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..12 } ?: return year + 0.5
    val day = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it in 1..31 }
    val within = if (day != null) ((month - 1) + (day - 1) / 31.0) / 12.0 else (month - 0.5) / 12.0
    return year + within
}

/** Dot diameter on the ruler, 10 for a single track up to about 32 for 26 or more (the mock's rule). */
internal fun timelineDotSize(trackCount: Int?): Int = (10 + (trackCount ?: 1).coerceIn(1, 26) * 0.85).roundToInt()

/**
 * What the artist room's side timeline draws: every dated release, or with the Playable toggle on only
 * the ones that can be played. Only that toggle narrows it; the kind chips (Albums, EPs...) do not.
 */
internal fun timelineEntries(groups: List<ReleaseGroup>, playableOnly: Boolean): List<ReleaseGroup> =
    if (playableOnly) groups.filter { it.isPlayable } else groups

/** Colour source for an era: its lead release's palette, else the artist's. Null means keep the app accent. */
internal fun TimelineEra.palette(artistPalette: ArtworkPalette?): ArtworkPalette? = lead.primary.palette ?: artistPalette

internal fun buildEraTimeline(releases: List<LibraryRelease>): EraTimeline {
    val all = buildDiscography(releases, playableOnly = false, filter = ReleaseFilter.All)
        .eras.flatMap { it.full + it.singles }
    val (dated, undated) = all.partition { it.yearInt() != null }
    if (dated.isEmpty()) return EraTimeline(emptyList(), emptyList(), 0, 0, undated)

    val sorted = dated.sortedWith(compareBy<ReleaseGroup>({ it.yearInt() }, { releasePosition(it.primary) ?: 0.0 }))
    val clusters = mutableListOf<MutableList<ReleaseGroup>>()
    var clusterStart = 0
    var previous = 0
    for (group in sorted) {
        val year = group.yearInt()!!
        val startsNew = clusters.isEmpty() || year - previous >= MIN_GAP_YEARS || year - clusterStart >= MAX_ERA_SPAN_YEARS
        if (startsNew) {
            clusters += mutableListOf<ReleaseGroup>()
            clusterStart = year
        }
        clusters.last() += group
        previous = year
    }

    val eras = clusters.mapIndexed { i, groups ->
        val lead = leadOf(groups)
        TimelineEra(
            index = i,
            fromYear = groups.first().yearInt()!!,
            toYear = groups.last().yearInt()!!,
            name = lead.primary.title,
            lead = lead,
            groups = groups,
        )
    }
    val dots = eras.flatMap { era ->
        era.groups.mapNotNull { g ->
            releasePosition(g.primary)?.let { TimelineDot(era.index, g.primary, it, g.primary.trackCount ?: 1) }
        }
    }
    return EraTimeline(
        eras = eras,
        dots = dots,
        startYear = eras.first().fromYear,
        endYear = eras.last().toYear,
        undated = undated,
    )
}
