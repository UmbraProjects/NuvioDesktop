package com.nuvio.app.features.discover

/**
 * BingeCat Bulk Add — plan §6.2 / §11.
 *
 * BingeCat's Bulk Add field says: "Paste IMDb IDs, TMDB IDs, or comma-separated titles. IDs can be
 * separated with commas, spaces, or periods", with the placeholder
 * `tt0133093 tt0111161, tmdb:550, Heat, Zodiac`.
 *
 * **`tmdb:550` is the form Nuvio already stores**, so this export is the row's own ids joined — no
 * id conversion, no schema, no file and no account. It is the cheapest of the three exports and the
 * only one that takes a frozen list as a first-class input, which is why a generated row (trending,
 * hidden gems, recommendations) can go straight to BingeCat while AIOMetadata and TMDB Discover+
 * have no catalog kind that would hold it.
 */
data class BingeCatBulkList(
    val text: String,
    val included: Int,
    /** Items with no id BingeCat could read. See [bingeCatBulkList] for why they are not titles. */
    val skipped: Int,
)

/**
 * Comma and space both separate ids, per the field's own help text. Using both is the least
 * ambiguous of the three permitted separators — a bare space would still parse, and a period is
 * indistinguishable from one inside a title should this ever carry titles too.
 */
private const val SEPARATOR = ", "

/**
 * Builds the text to paste into Bulk Add from a row's content ids.
 *
 * **Ids only, never titles**, even though the field accepts them and a title would rescue the items
 * dropped here. Both of the separators BingeCat documents occur *inside* real titles — "Crouching
 * Tiger, Hidden Dragon" carries a comma and "Mr. Robot" a period — so emitting a title risks it
 * being split into two entries that each match something else. A missing title is visible in the
 * count; a wrong one that quietly joins the list is not. The count is reported so the user knows
 * the paste is short rather than assuming it is complete.
 */
fun bingeCatBulkList(contentIds: List<String>): BingeCatBulkList {
    val ids = contentIds.mapNotNull(::bingeCatId).distinct()
    return BingeCatBulkList(
        text = ids.joinToString(SEPARATOR),
        included = ids.size,
        // Counted against the input, so duplicates collapsed by `distinct` are not reported as
        // losses — the row held the title twice, the paste holds it once, nothing was dropped.
        skipped = contentIds.count { bingeCatId(it) == null },
    )
}

/**
 * The id as BingeCat reads it, or null when it reads none.
 *
 * `tmdb:` ids pass through unchanged — that is the placeholder's own spelling. Anime addressed by a
 * native id (`kitsu:`, `mal:`) and anything an addon invented have no BingeCat equivalent.
 */
private fun bingeCatId(contentId: String): String? {
    val trimmed = contentId.trim()
    return when {
        trimmed.startsWith("tmdb:", ignoreCase = true) ->
            trimmed.substringAfter(':').toIntOrNull()?.let { "tmdb:$it" }

        trimmed.length > 2 && trimmed.startsWith("tt") && trimmed.drop(2).all { it.isDigit() } ->
            trimmed

        else -> null
    }
}
