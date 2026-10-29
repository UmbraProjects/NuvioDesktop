package com.nuvio.app.features.music

import com.nuvio.app.features.music.library.LibraryRelease

/**
 * Pure model behind the album object (board 3): what is printed on the back cover and in the liner
 * notes. Nothing here draws; the composables in AlbumObject.kt read it.
 */

private val monthNames = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

/**
 * `2014-05-20` as "20 May 2014", `2014-05` as "May 2014", `2014` as "2014". Anything that is not a
 * date in one of those shapes is returned as it came (trimmed), and blank or null gives null.
 */
fun formatReleaseDate(raw: String?): String? {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val parts = text.split('-')
    val year = parts[0].takeIf { it.length == 4 && it.all(Char::isDigit) } ?: return text
    val month = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..12 } ?: return year
    val day = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it in 1..31 }
    return if (day != null) "$day ${monthNames[month - 1]} $year" else "${monthNames[month - 1]} $year"
}

data class BackCoverLine(val number: Int, val title: String, val duration: String)

/**
 * The printed back cover: tracklist (capped so it fits the case, with [hiddenCount] saying how many
 * more there are), label and date line, and the barcode bars.
 */
data class BackCover(
    val title: String,
    val artist: String,
    val lines: List<BackCoverLine>,
    val hiddenCount: Int,
    val credit: String?,
    val barcode: List<Int>,
)

const val BACK_COVER_MAX_LINES = 18

fun buildBackCover(album: MusicAlbum, release: LibraryRelease?, maxLines: Int = BACK_COVER_MAX_LINES): BackCover {
    val shown = album.tracks.take(maxLines.coerceAtLeast(1))
    val lines = shown.mapIndexed { i, track ->
        BackCoverLine(
            number = track.trackNumber.takeIf { it > 0 } ?: (i + 1),
            title = track.title,
            duration = if (track.durationSeconds > 0) formatMusicTime(track.durationSeconds * 1000L) else "",
        )
    }
    val credit = listOfNotNull(
        release?.label,
        formatReleaseDate(release?.releaseDate) ?: album.year,
    ).joinToString(" · ").takeIf { it.isNotEmpty() }
    return BackCover(
        title = album.title,
        artist = album.artists.joinToString(", ") { it.name },
        lines = lines,
        hiddenCount = (album.tracks.size - lines.size).coerceAtLeast(0),
        credit = credit,
        barcode = barcodeBars(release?.upc, seed = album.browseId),
    )
}

/**
 * Bar and gap widths (1..3, alternating bar, gap, bar...) for the barcode graphic. A graphic only,
 * not a scannable code: the same UPC (or, without one, the same [seed]) always draws the same bars.
 */
fun barcodeBars(upc: String?, seed: String, count: Int = 41): List<Int> {
    val digits = upc?.filter(Char::isDigit)?.takeIf { it.isNotEmpty() }
    val source = digits ?: seed.ifEmpty { "0" }
    var h = 17
    source.forEach { h = h * 31 + it.code }
    return List(count) { i ->
        h = h * 1103515245 + 12345 + i
        // Guard bars at both ends and in the middle are always thin, as on a real code.
        if (i < 3 || i >= count - 3 || i == count / 2) 1 else ((h ushr 16) and 0x7fff) % 3 + 1
    }
}

/** The liner notes beside the case: who released it, when, and the feel of it. */
data class LinerNotes(
    val label: String?,
    val date: String?,
    val genres: List<String>,
    val mood: String?,
    val description: String?,
    val explicit: Boolean,
) {
    val isEmpty: Boolean
        get() = label == null && date == null && genres.isEmpty() && mood == null && description == null && !explicit
}

/** Liner notes for [release]; the album's own [fallbackYear] stands in when the library has no date. */
fun buildLinerNotes(release: LibraryRelease?, fallbackYear: String?): LinerNotes {
    val genres = (release?.genres.orEmpty() + listOfNotNull(release?.style))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
    val mood = release?.mood?.trim()?.takeIf { it.isNotEmpty() }
    return LinerNotes(
        label = release?.label?.trim()?.takeIf { it.isNotEmpty() },
        date = formatReleaseDate(release?.releaseDate) ?: fallbackYear?.takeIf { it.isNotBlank() },
        // The mood chip is drawn in the accent, so it is not repeated among the genres.
        genres = genres.filter { !it.equals(mood, ignoreCase = true) },
        mood = mood,
        description = release?.description?.trim()?.takeIf { it.isNotEmpty() },
        explicit = release?.explicit == true,
    )
}
