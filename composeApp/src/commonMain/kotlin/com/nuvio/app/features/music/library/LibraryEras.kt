package com.nuvio.app.features.music.library

/**
 * Library views, phase E0: the whole library on one time axis, as pure data (no Compose, so it is
 * unit-tested). The artist room's [buildEraTimeline] does this for one artist; this does it for every
 * saved artist, in bands of years whose width follows the library's span.
 *
 * Releases are folded and demos left out exactly as [buildDiscography] does. Each remaining release is
 * placed by a year taken from the chosen [ErasAxis]: when it came out, or when its artist was added.
 */

/** Which date places a release on the ruler. */
internal enum class ErasAxis {
    /** The release's own year: `releaseDate`, else `year`. */
    Released,

    /** The year its artist joined the library (`addedAtEpochMs`): a history of taste, not of the music. */
    Added,
}

/** One folded release in the library, with the artist it belongs to and the year it is placed at. */
internal data class LibraryEraEntry(
    val artist: LibraryArtist,
    val group: ReleaseGroup,
    /** The year on the ruler, or null for the undated block. */
    val year: Int?,
    /** Fractional year for ordering inside a band (2023.5 is mid-2023). Equal to [year] + 0.5 on the Added axis. */
    val position: Double,
) {
    val release: LibraryRelease get() = group.primary
    val isPlayable: Boolean get() = group.isPlayable
}

/** One band on the ruler. [entries] are oldest first. A band with nothing in it is kept, drawn thin. */
internal data class LibraryEra(
    val index: Int,
    val fromYear: Int,
    val toYear: Int,
    /** The release that names the band and lends it its colour. Null when the band is empty. */
    val lead: LibraryEraEntry?,
    val entries: List<LibraryEraEntry>,
) {
    /** "2014" or "1990 – 1994". */
    val years: String get() = if (fromYear == toYear) "$fromYear" else "$fromYear – $toYear"
    val releaseCount: Int get() = entries.size
    val artistCount: Int get() = entries.mapTo(HashSet()) { it.artist.id }.size
    val isEmpty: Boolean get() = entries.isEmpty()

    /** True when at least one release in the band can be played here; Play era has nothing to do without it. */
    val hasPlayable: Boolean get() = entries.any { it.isPlayable }

    /** Artists with a release in the band, in the order they first appear (oldest release first). */
    val artists: List<LibraryArtist> get() = entries.map { it.artist }.distinctBy { it.id }
}

internal data class LibraryEras(
    val axis: ErasAxis,
    /** Years per band: 1, 5 or 10. */
    val bandWidth: Int,
    /** Oldest band first, with no holes: an empty band stands for a gap in the library. */
    val eras: List<LibraryEra>,
    /** Releases with no usable date: their own block at the end, not on the ruler. */
    val undated: List<LibraryEraEntry>,
) {
    val isEmpty: Boolean get() = eras.isEmpty() && undated.isEmpty()

    /** The tallest band's release count, so a ruler can scale its bars: `era.releaseCount / maxReleaseCount`. */
    val maxReleaseCount: Int get() = eras.maxOfOrNull { it.releaseCount } ?: 0

    /** First and last year the ruler covers, or null with no dated releases. */
    val startYear: Int? get() = eras.firstOrNull()?.fromYear
    val endYear: Int? get() = eras.lastOrNull()?.toYear

    /** The newest band that has anything in it, else -1: where a screen can start. */
    fun defaultEraIndex(): Int = eras.lastOrNull { !it.isEmpty }?.index ?: -1
}

/** Span (newest year minus oldest) up to which every year is its own band. */
private const val ONE_YEAR_BANDS_MAX_SPAN = 12

/** Span up to which bands are five years wide; beyond it they are ten. */
private const val FIVE_YEAR_BANDS_MAX_SPAN = 40

/**
 * Band width for a library whose dated releases run over [spanYears] years (newest minus oldest):
 * 12 or fewer is 1 year, 13 to 40 is 5, over 40 is 10. A ten-year library is not one block and a
 * sixty-year one is not sixty.
 */
internal fun eraBandWidth(spanYears: Int): Int = when {
    spanYears <= ONE_YEAR_BANDS_MAX_SPAN -> 1
    spanYears <= FIVE_YEAR_BANDS_MAX_SPAN -> 5
    else -> 10
}

/** Year of a release for the Released axis: `releaseDate`, else `year`; placeholders like "0000" count as no year. */
internal fun LibraryRelease.releasedYearOrNull(): Int? = releaseYearOrNull(releaseDate) ?: releaseYearOrNull(year)

/**
 * The calendar year (UTC) of a time in epoch milliseconds, or null for 0 (never set) and anything
 * outside the plausible range. Days-to-civil arithmetic, so it needs no date library.
 */
internal fun yearOfEpochMs(epochMs: Long): Int? {
    if (epochMs <= 0L) return null
    val z = epochMs / 86_400_000L + 719_468L
    val era = z / 146_097L
    val doe = z - era * 146_097L
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
    return year.takeIf { it in MIN_RELEASE_YEAR..MAX_RELEASE_YEAR }
}

private fun LibraryArtist.addedYearOrNull(): Int? = yearOfEpochMs(addedAtEpochMs)

private fun LibraryArtist.orderKey(): String = (sortName ?: name).lowercase()

/**
 * Every saved artist's releases on one time axis.
 *
 * - Releases are folded and demos left out as the artist page does; unplayable ones stay (the view dims them).
 * - [ErasAxis.Released] places a release by its own year; [ErasAxis.Added] places all of an artist's releases at the year the artist was added.
 * - Band width comes from the span of dated releases ([eraBandWidth]); bands start on round years (1990, not 1991) and are kept when empty.
 * - A release with no usable date goes to [LibraryEras.undated].
 * - Order is deterministic: oldest first, then artist, then release key.
 */
internal fun buildLibraryEras(artists: List<LibraryArtist>, axis: ErasAxis = ErasAxis.Released): LibraryEras {
    val all = artists.flatMap { artist ->
        val addedYear = artist.addedYearOrNull()
        buildDiscography(artist.releases, playableOnly = false, filter = ReleaseFilter.All)
            .eras.flatMap { it.full + it.singles }
            .map { group ->
                when (axis) {
                    ErasAxis.Released -> {
                        val year = group.primary.releasedYearOrNull()
                        val position = releasePosition(group.primary)?.takeIf { year != null } ?: year?.let { it + 0.5 } ?: 0.0
                        LibraryEraEntry(artist, group, year, position)
                    }
                    ErasAxis.Added -> LibraryEraEntry(artist, group, addedYear, addedYear?.let { it + 0.5 } ?: 0.0)
                }
            }
    }

    val order = compareBy<LibraryEraEntry>({ it.position }, { it.artist.orderKey() }, { it.artist.id }, { it.release.key })
    val (dated, undated) = all.partition { it.year != null }
    if (dated.isEmpty()) return LibraryEras(axis, bandWidth = 1, eras = emptyList(), undated = undated.sortedWith(order))

    val minYear = dated.minOf { it.year!! }
    val maxYear = dated.maxOf { it.year!! }
    val width = eraBandWidth(maxYear - minYear)
    val firstBand = minYear - (minYear % width)
    val lastBand = maxYear - (maxYear % width)

    val byBand = dated.groupBy { it.year!! - (it.year!! % width) }
    val eras = (firstBand..lastBand step width).mapIndexed { i, from ->
        val entries = byBand[from].orEmpty().sortedWith(order)
        LibraryEra(
            index = i,
            fromYear = from,
            toYear = from + width - 1,
            lead = leadEntry(entries),
            entries = entries,
        )
    }
    return LibraryEras(axis, bandWidth = width, eras = eras, undated = undated.sortedWith(order))
}

/** The entry that names a band: the fullest album, as [leadOf] picks for an artist's own timeline. Null for an empty band. */
private fun leadEntry(entries: List<LibraryEraEntry>): LibraryEraEntry? {
    if (entries.isEmpty()) return null
    val lead = leadOf(entries.map { it.group })
    // leadOf hands back one of the groups it was given; match by identity, since two artists can hold equal groups.
    return entries.first { it.group === lead }
}
