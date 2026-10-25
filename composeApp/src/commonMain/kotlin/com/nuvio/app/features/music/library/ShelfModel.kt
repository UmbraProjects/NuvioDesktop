package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.identity.ArtworkPalette
import com.nuvio.app.features.music.identity.ArtworkPaletteMath
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Phase 4b: the Library shelf, as pure data. A shelf is one artist; a spine is one folded release.
 * Spine art comes from TheAudioDB when it has some; otherwise the UI draws a spine from [ShelfSpine.bg],
 * [ShelfSpine.fg], the cover thumb and the vertical title.
 */

internal const val SPINE_GAP_DP = 4
internal const val SPINE_DEFAULT_WIDTH_DP = 38

/** The mock's rule: 26 + 1.5 per track, capped at 26 tracks. Unknown track count gets a fixed width. */
internal fun spineWidthDp(trackCount: Int?): Int =
    if (trackCount == null || trackCount <= 0) SPINE_DEFAULT_WIDTH_DP else (26 + 1.5 * min(trackCount, 26)).roundToInt()

internal data class ShelfSpine(
    val release: LibraryRelease,
    val widthDp: Int,
    /** TheAudioDB spine picture, when there is one: draw it instead of the generated spine. */
    val spineArtUrl: String?,
    /** Square thumbnail drawn at the top of a generated spine. */
    val thumbUrl: String?,
    /** `0xAARRGGBB`. */
    val bg: Long,
    /** Readable text colour on [bg], `0xAARRGGBB`. */
    val fg: Long,
)

internal data class ShelfRow(
    val artist: LibraryArtist,
    val spines: List<ShelfSpine>,
    /** Releases not shown as spines (the "+N more" pill), demos included. */
    val hidden: Int,
    /** Same count as the artist page: [countReleaseCards]. */
    val totalReleases: Int,
)

private fun kindPriority(kind: ReleaseKind): Int = when (kind) {
    ReleaseKind.Album -> 0
    ReleaseKind.EP -> 1
    ReleaseKind.Single -> 2
    ReleaseKind.Live -> 3
    ReleaseKind.Compilation -> 4
    ReleaseKind.Other -> 5
}

private fun LibraryRelease.dateKey(): String = releaseDate ?: year ?: ""

/**
 * Colours for a generated spine: the release's palette (else the artist's) gives a dark wash of the
 * cover's hue. With no palette at all the hue comes from the title, so a spine is stable between runs
 * and neighbours differ.
 */
internal fun spineColors(release: LibraryRelease, artistPalette: ArtworkPalette?): Pair<Long, Long> {
    val palette = release.palette ?: artistPalette ?: run {
        val hue = (release.title.fold(7) { h, c -> (h * 31 + c.code) and 0x7FFFFFFF } % 360) / 360f
        ArtworkPaletteMath.derive(ArtworkPaletteMath.hslToRgb(hue, 0.5f, 0.5f))
    }
    val bg = palette.tint
    val bgRgb = (bg and 0xFFFFFFL).toInt()
    val fgRgb = if (ArtworkPaletteMath.contrast(bgRgb, 0xFFFFFF) >= ArtworkPaletteMath.contrast(bgRgb, 0x0B0B0B)) 0xFFFFFF else 0x0B0B0B
    return bg to (0xFF000000L or fgRgb.toLong())
}

/**
 * Chooses which releases of [artist] become spines in [availableWidthDp]. Albums, then EPs, then the
 * rest are taken first (newest first within a kind) until the shelf is full; the chosen ones are then
 * laid out oldest to newest. When some are left out, [moreWidthDp] is kept free for the "+N more" pill.
 * Demos are never spines.
 */
internal fun buildShelfRow(
    artist: LibraryArtist,
    availableWidthDp: Int,
    moreWidthDp: Int = 96,
): ShelfRow {
    val total = countReleaseCards(artist.releases)
    val groups = buildDiscography(artist.releases, playableOnly = false, filter = ReleaseFilter.All)
        .eras.flatMap { it.full + it.singles }
        .map { it.primary }

    fun fits(list: List<LibraryRelease>, width: Int): List<LibraryRelease> {
        var used = 0
        val chosen = ArrayList<LibraryRelease>()
        for (r in list) {
            val w = spineWidthDp(r.trackCount) + SPINE_GAP_DP
            if (used + w > width) continue
            used += w
            chosen += r
        }
        return chosen
    }

    val ranked = groups.sortedWith(
        compareBy<LibraryRelease> { kindPriority(it.kind) }.thenByDescending { it.dateKey() },
    )
    var chosen = fits(ranked, availableWidthDp)
    if (chosen.size < groups.size || total > groups.size) {
        chosen = fits(ranked, availableWidthDp - moreWidthDp)
    }
    val spines = chosen.sortedBy { it.dateKey() }.map { r ->
        val (bg, fg) = spineColors(r, artist.palette)
        ShelfSpine(
            release = r,
            widthDp = spineWidthDp(r.trackCount),
            spineArtUrl = r.art.spine,
            thumbUrl = r.art.cover,
            bg = bg,
            fg = fg,
        )
    }
    return ShelfRow(artist, spines, hidden = (total - spines.size).coerceAtLeast(0), totalReleases = total)
}

// region a year ago this week

internal data class YearAgoPick(val artist: LibraryArtist, val release: LibraryRelease)

private val cumulativeDays = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)

private fun ordinal(month: Int, day: Int): Int = cumulativeDays[month - 1] + day

/**
 * The release whose full date falls within [windowDays] of today's date one year earlier. A playable
 * release beats an unplayable one, then the nearest date, then a full release over a single. Releases
 * with only a year or month are ignored (no day to compare). A window crossing New Year is not handled.
 */
internal fun pickYearAgo(
    artists: List<LibraryArtist>,
    todayYear: Int,
    todayMonth: Int,
    todayDay: Int,
    windowDays: Int = 3,
): YearAgoPick? {
    val target = ordinal(todayMonth, todayDay)
    return artists.asSequence()
        .flatMap { a -> a.releases.asSequence().filterNot { it.isDemo() }.map { a to it } }
        .mapNotNull { (a, r) ->
            val p = r.releaseDate?.takeIf { it.length == 10 }?.split("-") ?: return@mapNotNull null
            val y = p[0].toIntOrNull() ?: return@mapNotNull null
            val m = p[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return@mapNotNull null
            val d = p[2].toIntOrNull()?.takeIf { it in 1..31 } ?: return@mapNotNull null
            if (y != todayYear - 1) return@mapNotNull null
            val diff = abs(ordinal(m, d) - target)
            if (diff > windowDays) null else Triple(YearAgoPick(a, r), diff, r)
        }
        .sortedWith(
            compareBy<Triple<YearAgoPick, Int, LibraryRelease>>(
                { !it.third.isPlayable },
                { it.second },
                { it.third.kind == ReleaseKind.Single },
            ),
        )
        .firstOrNull()?.first
}

// endregion
