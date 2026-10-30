package com.nuvio.app.features.preroll

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The BBFC's content advisory for a film ("moderate threat, occasional bloody moments, action
 * violence"), which the details record does not carry.
 *
 * The BBFC publishes it on each release's own page, in an element whose class starts with
 * `Release_ShortFormInsight`. The rest of that class name is a build hash that changes between
 * site releases, so only the stable prefix is matched. The page is found through the BBFC's
 * search, and only the film's title leaves the device.
 *
 * Every step is best effort: a slow or changed site, no match, or a different film with the same
 * name all end in null, and the card simply has no advisory line.
 */
internal object BbfcAdvisory {
    private val log = Logger.withTag("BbfcAdvisory")

    private const val SITE = "https://www.bbfc.co.uk"
    private const val FETCH_TIMEOUT_MS = 6_000L
    private val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml",
        "User-Agent" to "Mozilla/5.0 NuvioDesktop",
    )

    private fun searchUrl(title: String) = "$SITE/search?q=${title.encodeURLParameter()}"

    private val lock = Any()
    private val cache = HashMap<String, String?>()

    /** The formatted advisory line ("Contains moderate threat, ..."), or null when there is none. */
    suspend fun advisoryFor(title: String, year: String?): String? {
        val key = "${title.trim().lowercase()}|${year.orEmpty()}"
        synchronized(lock) { if (cache.containsKey(key)) return cache[key] }
        val insight = withTimeoutOrNull(FETCH_TIMEOUT_MS * 2) { lookUp(title, year) }
        if (insight == null) {
            log.i { "no advisory for \"$title\"" }
            return null
        }
        val line = formatBbfcAdvisory(insight)
        synchronized(lock) { cache[key] = line }
        return line
    }

    private suspend fun lookUp(title: String, year: String?): String? {
        val searchHtml = get(searchUrl(title))
        if (searchHtml == null) {
            log.i { "step 1 search: no response for \"$title\"" }
            return null
        }
        val links = RELEASE_LINK.findAll(searchHtml).map { it.groupValues[1] }.distinct().toList()
        log.i { "step 1 search: ${searchHtml.length} chars, ${links.size} release links" }

        // The search result card prints the same advisory as the release page, so it is enough on
        // its own when present; the release page is the fallback.
        val card = findBbfcSearchCard(searchHtml, title, year)
        if (card?.insight != null) {
            log.i { "step 2 card: advisory read from search result ${card.path}" }
            return card.insight
        }
        val path = card?.path ?: findBbfcReleasePath(searchHtml, title, year)
        if (path == null) {
            log.i {
                "step 2 match: no release for \"$title\" year=$year among $links; " +
                    "page starts: ${searchHtml.take(200).replace(Regex("\\s+"), " ")}"
            }
            return null
        }
        val releaseHtml = get("$SITE$path")
        if (releaseHtml == null) {
            log.i { "step 3 release: no response for $path" }
            return null
        }
        val insight = parseBbfcShortFormInsight(releaseHtml)
        if (insight == null) {
            log.i { "step 3 release: ${releaseHtml.length} chars from $path but no ShortFormInsight element" }
        }
        return insight
    }

    private suspend fun get(url: String): String? {
        val body = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            runCatching { httpGetTextWithHeaders(url, headers) }
                .onFailure { log.w { "fetch failed for $url: ${it.message}" } }
                .getOrNull()
        }
        if (body == null) log.w { "no body for $url (failed or timed out after ${FETCH_TIMEOUT_MS}ms)" }
        return body
    }
}

private val INSIGHT_ELEMENT = Regex(
    """<([a-z][a-z0-9]*)\b[^>]*\bclass\s*=\s*["'][^"']*\bRelease_ShortFormInsight[^"']*["'][^>]*>(.*?)</\1\s*>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

/** The text of the page's short-form advisory element, or null when the page has none. */
internal fun parseBbfcShortFormInsight(html: String): String? {
    val inner = INSIGHT_ELEMENT.find(html)?.groupValues?.get(2) ?: return null
    return decodeHtmlText(inner.replace(Regex("<[^>]*>"), " "))
        .replace(Regex("\\s+"), " ")
        .trim()
        .takeIf { it.isNotEmpty() }
}

private val RELEASE_LINK = Regex("""href\s*=\s*["'](?:https?://(?:www\.)?bbfc\.co\.uk)?(/release/[^"'#?\s]+)""", RegexOption.IGNORE_CASE)

private fun slugify(text: String): String =
    text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

/**
 * The release page for [title] in the BBFC's search results: the first result whose address starts
 * with the title's slug. With a [year], only a result that mentions it nearby counts, so a remake
 * or the original is never mistaken for the film asked about. Null when nothing fits.
 */
internal fun findBbfcReleasePath(searchHtml: String, title: String, year: String?): String? {
    val wanted = slugify(title).takeIf { it.isNotEmpty() } ?: return null
    val links = RELEASE_LINK.findAll(searchHtml).toList()
    val candidates = links.mapIndexed { i, m ->
        // The window for a link ends where the next link begins, so a neighbour's year never counts.
        val windowEnd = minOf(
            searchHtml.length,
            m.range.last + 600,
            links.getOrNull(i + 1)?.range?.first ?: searchHtml.length,
        )
        Triple(m.groupValues[1], m.range.last, windowEnd)
    }
        .distinctBy { it.first }
        .filter { (path, _, _) ->
            val slug = path.removePrefix("/release/").substringBefore('/')
            slug == wanted || slug.startsWith("$wanted-")
        }
    if (year == null) return candidates.firstOrNull()?.first
    return candidates.firstOrNull { (_, end, windowEnd) ->
        searchHtml.substring(end, windowEnd).contains(year)
    }?.first
}

internal data class BbfcSearchCard(val path: String, val insight: String?)

private val RESULT_CARD = Regex(
    """<a\b[^>]*\bhref\s*=\s*["'](?:https?://(?:www\.)?bbfc\.co\.uk)?(/release/[^"'#?\s]+)["'][^>]*>(.*?)</a\s*>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

/** The advisory line inside a search result card; only the stable class prefix is matched. */
private val CARD_ADVISORY = Regex(
    """<p\b[^>]*\bclass\s*=\s*["'][^"']*\bSearchResultItem_Description[^"']*["'][^>]*>(.*?)</p\s*>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

/**
 * The search result card for [title], with the advisory it prints when it has one. Cards are
 * matched like [findBbfcReleasePath]: address starts with the title's slug, and with a [year] the
 * card's own text (its heading reads "Title (2022)") has to mention it. Null when no card fits.
 */
internal fun findBbfcSearchCard(searchHtml: String, title: String, year: String?): BbfcSearchCard? {
    val wanted = slugify(title).takeIf { it.isNotEmpty() } ?: return null
    return RESULT_CARD.findAll(searchHtml)
        .filter { m ->
            val slug = m.groupValues[1].removePrefix("/release/").substringBefore('/')
            slug == wanted || slug.startsWith("$wanted-")
        }
        .filter { m -> year == null || m.groupValues[2].contains(year) }
        .map { m ->
            val insight = CARD_ADVISORY.find(m.groupValues[2])?.groupValues?.get(1)?.let {
                decodeHtmlText(it.replace(Regex("<[^>]*>"), " ")).replace(Regex("\\s+"), " ").trim()
            }?.takeIf { it.isNotEmpty() }
            BbfcSearchCard(m.groupValues[1], insight)
        }
        .firstOrNull()
}

private val LOWERCASE_WORDS =setOf("and", "or", "of", "the", "in", "to", "a", "an", "for", "with")

/**
 * The advisory as the BBFC's own cards print it: "Contains Strong Bloody Violence and Gore".
 * The site gives it in lower case without the lead-in.
 */
internal fun formatBbfcAdvisory(insight: String): String {
    val titled = insight.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { raw ->
        val word = raw.lowercase()
        if (word in LOWERCASE_WORDS) word
        else Regex("(^|[/-])([a-z])").replace(word) { it.groupValues[1] + it.groupValues[2].uppercase() }
    }
    val sentenceStart = titled.replaceFirstChar { it.uppercase() }
    return if (sentenceStart.startsWith("Contains ", ignoreCase = true)) sentenceStart else "Contains $titled"
}

private fun decodeHtmlText(text: String): String {
    var out = text
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
    out = Regex("&#x([0-9a-fA-F]+);").replace(out) { m ->
        m.groupValues[1].toIntOrNull(16)?.let { it.toChar().toString() } ?: m.value
    }
    out = Regex("&#(\\d+);").replace(out) { m ->
        m.groupValues[1].toIntOrNull()?.let { it.toChar().toString() } ?: m.value
    }
    return out
}
