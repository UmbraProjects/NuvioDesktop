package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.MusicAlbumSummary
import com.nuvio.app.features.music.MusicArtistSummary

/**
 * HTPC search page, grouped results (pure part): one scroll with Ladder, Artists and Albums as labelled sections and a
 * chip row that jumps between them. This file decides what is in the scroll and where each section's header sits, so
 * the chips can scroll to it and mark the section that is on screen. The drawing is in SearchSectionsView.kt.
 */
internal enum class SearchSection(val label: String) {
    Ladder("Ladder"),
    Artists("Artists"),
    Albums("Albums"),
}

/** Artist and album rows the grouped view shows before "See all"; the Artists and Albums tabs show everything. */
internal const val GROUPED_ARTISTS_SHOWN = 6
internal const val GROUPED_ALBUMS_SHOWN = 10

/**
 * One section of the scroll. [total] is how many results it stands for (the chip's number), [rows] how many list items
 * follow its header, and [headerIndex] the list index of the header itself.
 */
internal data class SectionSlot(val section: SearchSection, val total: Int, val rows: Int, val headerIndex: Int)

/** Rows a section draws: at most [cap] results, plus one "See all" row when there are more. */
internal fun cappedRows(total: Int, cap: Int): Int = when {
    total <= 0 -> 0
    total > cap -> cap + 1
    else -> total
}

/**
 * The sections in scroll order (Ladder, Artists, Albums), leaving out empty ones, with each header's list index. A
 * section is one header item then its rows, so a header's index is the sum of the sizes before it.
 */
internal fun sectionSlots(ladderOffers: Int, artists: Int, albums: Int): List<SectionSlot> {
    val sizes = listOf(
        Triple(SearchSection.Ladder, ladderOffers, ladderOffers),
        Triple(SearchSection.Artists, artists, cappedRows(artists, GROUPED_ARTISTS_SHOWN)),
        Triple(SearchSection.Albums, albums, cappedRows(albums, GROUPED_ALBUMS_SHOWN)),
    )
    var next = 0
    val slots = ArrayList<SectionSlot>(3)
    for ((section, total, rows) in sizes) {
        if (total <= 0) continue
        slots += SectionSlot(section, total, rows, headerIndex = next)
        next += 1 + rows
    }
    return slots
}

/** Chips only help when there is somewhere to jump to. */
internal fun showsChips(slots: List<SectionSlot>): Boolean = slots.size >= 2

/**
 * The section the scroll is in, given the list's first visible item: the last section whose header is at or above it.
 * Null with no sections.
 */
internal fun currentSection(slots: List<SectionSlot>, firstVisibleIndex: Int): SearchSection? =
    slots.lastOrNull { it.headerIndex <= firstVisibleIndex }?.section ?: slots.firstOrNull()?.section

/** The chip text: "Artists 12", or just the name for Ladder (its count is the number of offers, which means nothing to read). */
internal fun chipLabel(slot: SectionSlot): String =
    if (slot.section == SearchSection.Ladder) slot.section.label else "${slot.section.label} ${slot.total}"

/**
 * The ladder cards for a grouped search: a genre query gives its one card, otherwise "Climb into / out from" the first
 * artist result, and with no artist result the first album's two. Nothing for a short query.
 */
internal fun groupedLadderOffers(
    query: String,
    artists: List<MusicArtistSummary>,
    albums: List<MusicAlbumSummary>,
): List<LadderOffer> {
    val byArtist = ladderOffers(query, artists.firstOrNull()?.name)
    if (byArtist.isNotEmpty()) return byArtist
    val first = albums.firstOrNull() ?: return emptyList()
    val by = first.artists.firstOrNull()?.name ?: return emptyList()
    return ladderOffers(query, null, LadderAlbumRef(first.title, by, first.year))
}
