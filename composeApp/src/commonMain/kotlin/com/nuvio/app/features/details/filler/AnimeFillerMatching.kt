package com.nuvio.app.features.details.filler

import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.epochDay
import com.nuvio.app.features.streams.titleSimilarity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** AnimeFillerList's episode categories. Only [Filler] is ever skipped. */
@Serializable
enum class AnimeEpisodeKind {
    @SerialName("manga_canon") MangaCanon,
    @SerialName("anime_canon") AnimeCanon,
    @SerialName("mixed") MixedCanonFiller,
    @SerialName("filler") Filler,
}

/** One row of an AnimeFillerList show page. [kind] is null for a category this parser does not know. */
@Serializable
internal data class AnimeFillerListRow(
    val number: Int,
    val title: String,
    val kind: AnimeEpisodeKind? = null,
    val airDate: String? = null,
)

private val ROW_REGEX = Regex(
    """<tr class="([^"]*?)"\s+id="eps-(\d+)"\s*>(.*?)</tr>""",
    RegexOption.DOT_MATCHES_ALL,
)
private val NUMBER_CELL_REGEX = Regex("""<td class="Number">\s*(\d+)\s*</td>""")
private val TITLE_CELL_REGEX = Regex("""<td class="Title">(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)
private val DATE_CELL_REGEX = Regex("""<td class="Date">\s*([^<]*?)\s*</td>""")
private val TAG_REGEX = Regex("<[^>]+>")
private val ENTITY_REGEX = Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);")

/**
 * The episode table of an AnimeFillerList show page (`/shows/<slug>`): number, title, category and
 * air date per row. The page's "Filler Episodes:" summary is not read; the table carries the same
 * categories plus the title and date the episodes are matched by.
 */
internal fun parseAnimeFillerListPage(html: String): List<AnimeFillerListRow> {
    val tableStart = html.indexOf("class=\"EpisodeList\"").takeIf { it >= 0 } ?: return emptyList()
    return ROW_REGEX.findAll(html, tableStart).mapNotNull { match ->
        val (classes, idNumber, body) = match.destructured
        val number = NUMBER_CELL_REGEX.find(body)?.groupValues?.get(1)?.toIntOrNull()
            ?: idNumber.toIntOrNull()
            ?: return@mapNotNull null
        val title = TITLE_CELL_REGEX.find(body)?.groupValues?.get(1)
            ?.replace(TAG_REGEX, "")
            ?.decodeHtmlEntities()
            ?.trim()
            .orEmpty()
        val airDate = DATE_CELL_REGEX.find(body)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        AnimeFillerListRow(
            number = number,
            title = title,
            kind = animeEpisodeKindForRowClass(classes),
            airDate = airDate,
        )
    }.toList()
}

/** The row's category from its CSS classes (`filler odd`, `mixed_canon/filler even`, ...). */
internal fun animeEpisodeKindForRowClass(classes: String): AnimeEpisodeKind? {
    val tokens = classes.lowercase().split(' ').filter { it.isNotBlank() }
    return when {
        "mixed_canon/filler" in tokens -> AnimeEpisodeKind.MixedCanonFiller
        "filler" in tokens -> AnimeEpisodeKind.Filler
        "anime_canon" in tokens -> AnimeEpisodeKind.AnimeCanon
        "manga_canon" in tokens -> AnimeEpisodeKind.MangaCanon
        else -> null
    }
}

private fun String.decodeHtmlEntities(): String = ENTITY_REGEX.replace(this) { match ->
    val entity = match.groupValues[1]
    when {
        entity.startsWith("#x") || entity.startsWith("#X") ->
            entity.substring(2).toIntOrNull(16)?.let(::codePointString) ?: match.value
        entity.startsWith("#") -> entity.substring(1).toIntOrNull()?.let(::codePointString) ?: match.value
        else -> when (entity.lowercase()) {
            "amp" -> "&"
            "quot" -> "\""
            "apos" -> "'"
            "lt" -> "<"
            "gt" -> ">"
            "nbsp" -> " "
            else -> match.value
        }
    }
}

private fun codePointString(codePoint: Int): String? {
    if (codePoint !in 1..0x10FFFF) return null
    if (codePoint < 0x10000) return codePoint.toChar().toString()
    val offset = codePoint - 0x10000
    return charArrayOf(
        (0xD800 + (offset shr 10)).toChar(),
        (0xDC00 + (offset and 0x3FF)).toChar(),
    ).concatToString()
}

/** How a video was paired with its AnimeFillerList row; kept for logs and tests. */
internal enum class AnimeFillerMatchBasis { Title, DateAndNumber, Interpolated }

internal data class AnimeFillerMatch(
    val row: AnimeFillerListRow,
    val basis: AnimeFillerMatchBasis,
)

/** Episodes a show page can be trusted for; fewer than this in its date span is not a test. */
private const val MIN_EPISODES_IN_SPAN = 5
/** A page that pairs with less of its own date span than this is the wrong show or a bad page. */
private const val MIN_SPAN_MATCH_RATIO = 0.6
private const val TITLE_MATCH_MIN = 0.5
private const val TITLE_MATCH_MARGIN = 0.15
private const val NUMBER_WINDOW = 2
private const val MAX_INTERPOLATION_GAP = 6

private val GENERIC_TITLE = Regex("""^\s*(episode|ep\.?)\s*\d+\s*$""", RegexOption.IGNORE_CASE)

/**
 * Pairs a meta's regular episodes with an AnimeFillerList show page, keyed by video id. Anything
 * not confidently paired is left out: a missing pair costs a badge, a wrong one marks a canon
 * episode filler and skips it.
 *
 * Neither the episode number nor the air date is trustworthy alone. Numbers drift (IMDb/TVDB
 * seasons drop crossover specials, so One Piece's `tt` list is one behind after 590), and dates are
 * wrong in places on both sides (AnimeFillerList has One Piece 683-689 a fortnight early; Kitsu has
 * Naruto 13 four days off). Measured against Kitsu ground truth, pairing by "the only row aired
 * within a day" got 12 of 2,496 episodes wrong, every one with an unrelated title.
 *
 * So a pair needs agreeing evidence:
 * - **Title**: among rows aired within a day or numbered within two, one title is a clear match
 *   (Dice >= 0.5, 0.15 ahead of the next). The candidate window matters: Cinemeta cuts Bleach 114's
 *   title down to exactly episode 41's, which would pair with 41 if every row were a candidate.
 * - **Date and number**: the row with the video's number aired within a day of it. Two independent
 *   signals; this is what pairs shows whose translations differ (half of Detective Conan).
 * - **Interpolated**: between two pairs at most six apart with the same number offset, the rows in
 *   between, unless their dates disagree.
 *
 * The result is then made one-to-one and monotonic (longest increasing run of row numbers), and a
 * page that pairs with less than 60% of the episodes inside its own date span is dropped whole.
 *
 * The number used is the episode number when the list has a single season (anime-native metas
 * number a whole entry that way), otherwise the position among regular episodes.
 */
internal fun matchAnimeFillerRows(
    videos: List<MetaVideo>,
    rows: List<AnimeFillerListRow>,
): Map<String, AnimeFillerMatch> {
    if (rows.isEmpty()) return emptyMap()
    val regular = videos
        .filter { it.fillerSeason() > 0 && (it.episode ?: 0) > 0 }
        .distinctBy { it.id }
        .sortedWith(compareBy({ it.fillerSeason() }, { it.episode ?: 0 }))
    if (regular.isEmpty()) return emptyMap()
    val singleSeason = regular.map { it.fillerSeason() }.distinct().size == 1
    fun numberOf(index: Int): Int = if (singleSeason) regular[index].episode ?: (index + 1) else index + 1

    val videoDays = regular.map { it.released.epochDay() }
    val rowDays = rows.map { it.airDate.epochDay() }
    val rowByNumber = HashMap<Int, Int>()
    rows.forEachIndexed { index, row -> rowByNumber.putIfAbsent(row.number, index) }
    val rowsByDay = HashMap<Long, MutableList<Int>>()
    rowDays.forEachIndexed { index, day -> if (day != null) rowsByDay.getOrPut(day) { mutableListOf() }.add(index) }

    val pairs = HashMap<Int, Pair<Int, AnimeFillerMatchBasis>>()
    regular.forEachIndexed { index, video ->
        val number = numberOf(index)
        val candidates = LinkedHashSet<Int>()
        videoDays[index]?.let { day ->
            for (delta in -1L..1L) rowsByDay[day + delta]?.let(candidates::addAll)
        }
        for (near in number - NUMBER_WINDOW..number + NUMBER_WINDOW) rowByNumber[near]?.let(candidates::add)
        if (candidates.isEmpty()) return@forEachIndexed

        val genericTitle = video.title.isBlank() || GENERIC_TITLE.matches(video.title)
        if (!genericTitle) {
            val scored = candidates
                .map { rowIndex -> rowIndex to titleSimilarity(video.title, rows[rowIndex].title) }
                .sortedByDescending { it.second }
            val (best, score) = scored.first()
            val runnerUp = scored.getOrNull(1)?.second ?: 0.0
            if (score >= TITLE_MATCH_MIN && score - runnerUp >= TITLE_MATCH_MARGIN) {
                pairs[index] = best to AnimeFillerMatchBasis.Title
                return@forEachIndexed
            }
        }
        val numbered = rowByNumber[number] ?: return@forEachIndexed
        val videoDay = videoDays[index] ?: return@forEachIndexed
        val rowDay = rowDays[numbered] ?: return@forEachIndexed
        if (kotlin.math.abs(videoDay - rowDay) <= 1) {
            pairs[index] = numbered to AnimeFillerMatchBasis.DateAndNumber
        }
    }

    // One-to-one: a row claimed by two videos belongs to neither.
    val claims = pairs.values.groupingBy { it.first }.eachCount()
    val unique = pairs.filterValues { (rowIndex, _) -> claims[rowIndex] == 1 }
    val monotonic = longestIncreasingByRowNumber(unique, rows)

    val result = HashMap(monotonic)
    val anchors = monotonic.entries.sortedBy { it.key }
    anchors.zipWithNext().forEach { (start, end) ->
        val gap = end.key - start.key
        if (gap <= 1 || gap > MAX_INTERPOLATION_GAP) return@forEach
        val startNumber = rows[start.value.first].number
        if (rows[end.value.first].number - startNumber != gap) return@forEach
        for (index in start.key + 1 until end.key) {
            val rowIndex = rowByNumber[startNumber + (index - start.key)] ?: continue
            val videoDay = videoDays[index]
            val rowDay = rowDays[rowIndex]
            if (videoDay != null && rowDay != null && kotlin.math.abs(videoDay - rowDay) > 1) continue
            result[index] = rowIndex to AnimeFillerMatchBasis.Interpolated
        }
    }

    if (!passesSpanCheck(result.keys, videoDays, rowDays, regular.size)) return emptyMap()
    return result.entries.associate { (index, pair) ->
        regular[index].id to AnimeFillerMatch(row = rows[pair.first], basis = pair.second)
    }
}

/** Keeps the largest set of pairs whose row numbers rise with the episode order. */
private fun longestIncreasingByRowNumber(
    pairs: Map<Int, Pair<Int, AnimeFillerMatchBasis>>,
    rows: List<AnimeFillerListRow>,
): Map<Int, Pair<Int, AnimeFillerMatchBasis>> {
    val items = pairs.entries.sortedBy { it.key }
    if (items.isEmpty()) return emptyMap()
    val tailValues = ArrayList<Int>()
    val tailItems = ArrayList<Int>()
    val previous = IntArray(items.size) { -1 }
    items.forEachIndexed { itemIndex, entry ->
        val value = rows[entry.value.first].number
        var low = 0
        var high = tailValues.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (tailValues[mid] < value) low = mid + 1 else high = mid
        }
        if (low == tailValues.size) {
            tailValues.add(value)
            tailItems.add(itemIndex)
        } else {
            tailValues[low] = value
            tailItems[low] = itemIndex
        }
        previous[itemIndex] = if (low > 0) tailItems[low - 1] else -1
    }
    val kept = HashMap<Int, Pair<Int, AnimeFillerMatchBasis>>()
    var cursor = tailItems.last()
    while (cursor != -1) {
        val entry = items[cursor]
        kept[entry.key] = entry.value
        cursor = previous[cursor]
    }
    return kept
}

private fun passesSpanCheck(
    matched: Set<Int>,
    videoDays: List<Long?>,
    rowDays: List<Long?>,
    videoCount: Int,
): Boolean {
    val datedRows = rowDays.filterNotNull()
    if (datedRows.isEmpty() || videoDays.all { it == null }) {
        return videoCount > 0 && matched.size.toDouble() / videoCount >= MIN_SPAN_MATCH_RATIO
    }
    val first = datedRows.min() - 1
    val last = datedRows.max() + 1
    val inSpan = videoDays.indices.filter { index -> videoDays[index]?.let { it in first..last } == true }
    if (inSpan.size < MIN_EPISODES_IN_SPAN) return false
    val matchedInSpan = inSpan.count { it in matched }
    return matchedInSpan.toDouble() / inSpan.size >= MIN_SPAN_MATCH_RATIO
}

/**
 * Whether [videos] has regular episodes aired after every row of [rows]: the show continues past
 * this page (Bleach's page stops at 366, before Thousand-Year Blood War), so a sibling entry's page
 * may cover the rest.
 */
internal fun hasEpisodesAfterPage(
    videos: List<MetaVideo>,
    rows: List<AnimeFillerListRow>,
): Boolean {
    val lastRowDay = rows.mapNotNull { it.airDate.epochDay() }.maxOrNull() ?: return false
    return videos.any { video ->
        video.fillerSeason() > 0 && (video.released.epochDay() ?: Long.MIN_VALUE) > lastRowDay + 1
    }
}

/** Anime metas may number a whole entry without seasons; that is season 1. Season 0 is specials. */
private fun MetaVideo.fillerSeason(): Int = season ?: if (episode != null) 1 else 0
