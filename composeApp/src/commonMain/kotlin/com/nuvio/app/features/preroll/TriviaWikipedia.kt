package com.nuvio.app.features.preroll

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.details.MetaDetails
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Trivia taken from the film's English Wikipedia article: the fallback behind the AI pack.
 *
 * The order is AI provider, then this, then bare metadata ([buildMetadataTrivia]). Wikipedia is
 * free and keyless, so it works when no provider is configured, the key is rejected, or the
 * provider is too slow for the pre-show. Only the film's title and year leave the device.
 *
 * **Spoilers.** Only sections about the making and reception of the film are read; the plot
 * section is never used, and sentences that talk about twists, endings or deaths are dropped even
 * from the allowed sections (reception text loves to mention them).
 *
 * Text is licensed CC BY-SA 4.0, so every pack ends with a slide saying where it came from.
 * English only: section names are matched in English, which is what keeps the plot section out.
 */
internal object TriviaWikipedia {
    private val log = Logger.withTag("Trivia")
    private val json = Json { ignoreUnknownKeys = true }

    private const val API = "https://en.wikipedia.org/w/api.php"
    private const val REQUEST_TIMEOUT_MS = 8_000L
    internal const val ATTRIBUTION = "Facts from Wikipedia, licensed CC BY-SA 4.0."
    private val headers = mapOf(
        "User-Agent" to "NuvioDesktop/1.0 (pre-show trivia; Kotlin Multiplatform)",
        "Accept" to "application/json",
    )

    /**
     * Cached under its own key, shared by every provider. v2: packs gained a quiz and an origin,
     * so v1 entries are left behind rather than shown without them.
     */
    private fun cacheKey(meta: MetaDetails, source: TriviaSource) =
        "wiki|v2|${meta.id}|${source.fingerprint()}"

    private const val BLOCKED = "blocked"
    private fun blockKey(meta: MetaDetails) = "wiki-block|${meta.id}"

    /** The viewer reported this film's Wikipedia trivia as wrong; it is not shown again. */
    private fun isBlocked(meta: MetaDetails): Boolean =
        TriviaCache.get(blockKey(meta))?.mode == BLOCKED

    /**
     * "Not right?": drops the cached pack and remembers not to build another for this film.
     * Fetching again would return the same article, so unlike an AI pack there is nothing to
     * regenerate; the pre-show falls through to the film's own details instead.
     */
    fun block(meta: MetaDetails) {
        val source = meta.toTriviaSource()
        TriviaCache.remove(cacheKey(meta, source))
        TriviaCache.put(
            blockKey(meta),
            TriviaCacheEntry(builtAtEpochMs = System.currentTimeMillis(), model = "wikipedia", mode = BLOCKED),
        )
    }

    fun peek(meta: MetaDetails): TriviaPack? {
        val source = meta.toTriviaSource()
        if (!source.isUsable || isBlocked(meta)) return null
        return TriviaCache.get(cacheKey(meta, source))?.pack?.takeUnless { it.isEmpty }
    }

    /** The Wikipedia pack, from cache or one search plus one article request. Null on any miss. */
    suspend fun packFor(meta: MetaDetails): TriviaPack? {
        peek(meta)?.let { return it }
        val source = meta.toTriviaSource()
        if (!source.isUsable || isBlocked(meta)) return null
        val isSeries = !meta.type.equals("movie", ignoreCase = true)
        val pack = runCatching {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS * 3) { fetch(source, isSeries) }
        }.onFailure { log.w { "wikipedia trivia for ${source.title} failed: $it" } }.getOrNull()
            ?: return null
        TriviaCache.put(
            cacheKey(meta, source),
            TriviaCacheEntry(
                pack = pack,
                builtAtEpochMs = System.currentTimeMillis(),
                model = "wikipedia",
                mode = TriviaMode.ModelKnowledge.name,
            ),
        )
        return pack
    }

    /**
     * Searches by the full title first, then by the part before a colon (a subtitle is often not
     * in the article's name). The short form is only tried when the year is known, because the
     * year check on the article's lead is what keeps it from picking the wrong film.
     */
    private suspend fun fetch(source: TriviaSource, isSeries: Boolean): TriviaPack? {
        val (title, text) = findArticle(source, isSeries) { buildWikipediaSlides(it).size >= 3 } ?: return null
        val slides = buildWikipediaSlides(text)
        log.i { "wikipedia trivia: ${slides.size} slides from \"$title\"" }
        return TriviaPack(
            slides = slides + TriviaSlide(ATTRIBUTION, TriviaCategory.Trivia),
            questions = buildMetadataQuestions(source),
            origin = TriviaOrigin.Wikipedia,
        ).withFallbackTheme(source.genres)
    }

    /**
     * The first search hit that really is this film or series and that [accept] likes, as its
     * title and plain text. Shared with the post-show, which reads other sections of the same article.
     */
    internal suspend fun findArticle(
        source: TriviaSource,
        isSeries: Boolean,
        accept: (String) -> Boolean,
    ): Pair<String, String>? {
        val wanted = alternateTitles(source)
        for (searchTitle in wanted) {
            val search = buildString {
                append(API).append("?action=query&list=search&format=json&srlimit=8&srnamespace=0")
                append("&srsearch=").append(encodeUrl("$searchTitle ${source.year.orEmpty()} film OR series"))
            }
            val body = withTimeoutOrNull(REQUEST_TIMEOUT_MS) { httpGetTextWithHeaders(search, headers) } ?: continue
            val titles = rankTitles(parseSearchTitles(body), wanted, isSeries)

            for (title in titles.take(3)) {
                val article = buildString {
                    append(API).append("?action=query&prop=extracts&explaintext=1&exsectionformat=wiki")
                    append("&redirects=1&format=json&titles=").append(encodeUrl(title))
                }
                val text = parseExtract(
                    withTimeoutOrNull(REQUEST_TIMEOUT_MS) { httpGetTextWithHeaders(article, headers) } ?: continue,
                ) ?: continue
                if (!looksLikeTheTitle(text, source)) continue
                if (!accept(text)) continue
                return title to text
            }
        }
        return null
    }

    internal fun parseSearchTitles(body: String): List<String> = runCatching {
        (json.parseToJsonElement(body).jsonObject["query"]?.jsonObject?.get("search") as? JsonArray)
            .orEmpty()
            .mapNotNull { ((it as? JsonObject)?.get("title") as? JsonPrimitive)?.contentOrNull }
    }.getOrDefault(emptyList())

    internal fun parseExtract(body: String): String? = runCatching {
        val pages = json.parseToJsonElement(body).jsonObject["query"]?.jsonObject?.get("pages")?.jsonObject
        pages?.values?.firstNotNullOfOrNull {
            ((it as? JsonObject)?.get("extract") as? JsonPrimitive)?.contentOrNull
        }?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

private fun encodeUrl(value: String) = value.encodeURLParameter()

/**
 * "Rocky (1976 film)" matches "Rocky"; "Rocky II" and "Rocky Balboa" do not. "&" and "and" are the
 * same word and a leading "The" is optional, since Wikipedia and addons disagree on both.
 */
internal fun titleMatches(candidate: String, wanted: String): Boolean {
    fun norm(s: String): String {
        var t = s.lowercase().replace("&", " and ").trim()
        if (t.startsWith("the ")) t = t.removePrefix("the ")
        return t.filter { it.isLetterOrDigit() }
    }
    val base = norm(candidate.substringBefore(" (").trim())
    return base.isNotEmpty() && base == norm(wanted)
}

/** The titles worth searching for: the full one, plus the part before a colon when the year is known. */
internal fun alternateTitles(source: TriviaSource): List<String> {
    val full = source.title.trim()
    val short = full.split(":", " \u2013 ", " - ").first().trim()
    return if (!source.year.isNullOrBlank() && short.length >= 4 && !short.equals(full, ignoreCase = true)) {
        listOf(full, short)
    } else {
        listOf(full)
    }
}

/** Article kinds that share a title with a film or series but are not it. */
private val WRONG_KIND = Regex(
    "\\b(season|episode|episodes|soundtrack|album|song|novel|book|game|musical|play|comic|comics|character|franchise|band)\\b",
)

/**
 * The search results that are this title, best first: an article about the film or series itself
 * ("Rocky (1976 film)", "Fargo (TV series)") ahead of an unsuffixed one, and season, album, game
 * and similar articles dropped, since "Breaking Bad (season 1)" is not the show.
 */
internal fun rankTitles(titles: List<String>, wanted: List<String>, isSeries: Boolean): List<String> {
    fun suffix(t: String) = t.substringAfter(" (", "").substringBefore(")").lowercase()
    return titles
        .filter { t -> wanted.any { titleMatches(t, it) } }
        .filterNot { WRONG_KIND.containsMatchIn(suffix(it)) }
        .sortedBy { t ->
            val s = suffix(t)
            when {
                s.isEmpty() -> 1
                isSeries && ("series" in s || "tv" in s || "television" in s) -> 0
                !isSeries && "film" in s -> 0
                else -> 2
            }
        }
}

/**
 * Guards against a same-named article about something else: the lead must name the release year
 * (when known, give or take one) and say it is a film or series.
 */
internal fun looksLikeTheTitle(extract: String, source: TriviaSource): Boolean {
    val lead = extract.substringBefore("\n==").take(900).lowercase()
    val kind = listOf("film", "movie", "series", "miniseries", "television", "anime", "documentary")
    if (kind.none { it in lead }) return false
    // A year either side is accepted: festival, regional and addon dates differ by one.
    val year = source.year?.toIntOrNull() ?: return true
    return listOf(year, year - 1, year + 1).any { it.toString() in lead }
}

private val ALLOWED_SECTIONS = mapOf(
    "production" to TriviaCategory.Production,
    "development" to TriviaCategory.Production,
    "pre-production" to TriviaCategory.Production,
    "writing" to TriviaCategory.Production,
    "casting" to TriviaCategory.Cast,
    "filming" to TriviaCategory.Production,
    "principal photography" to TriviaCategory.Production,
    "cinematography" to TriviaCategory.Production,
    "design" to TriviaCategory.Production,
    "visual effects" to TriviaCategory.Production,
    "music" to TriviaCategory.Production,
    "soundtrack" to TriviaCategory.Production,
    "release" to TriviaCategory.Legacy,
    "marketing" to TriviaCategory.Legacy,
    "reception" to TriviaCategory.Legacy,
    "critical response" to TriviaCategory.Legacy,
    "legacy" to TriviaCategory.Legacy,
    "accolades" to TriviaCategory.Awards,
    "awards" to TriviaCategory.Awards,
    "awards and nominations" to TriviaCategory.Awards,
)

private val SPOILER_WORDS = Regex(
    "\\b(twist|ending|finale|spoiler|dies|died|death|deaths|killed|kills|murder\\w*|betray\\w*|" +
        "reveal\\w*|turns out|sequel|remake)\\b",
    RegexOption.IGNORE_CASE,
)
private val DANGLING_START = Regex(
    "^(He|She|His|Her|Him|They|Their|Them|These|This|That|Those|However|Also|Additionally|" +
        "Meanwhile|Later|Then|But|And|Instead|Despite|Although|In addition|As a result|Subsequently)\\b",
)
private val ABBREVIATIONS = setOf("mr", "mrs", "ms", "dr", "st", "jr", "sr", "vs", "no", "inc", "ltd", "co", "mt", "u.s", "u.k")
private val PARENTHESES = Regex("\\s*\\([^()]*\\)")
private val HEADING = Regex("^(=+)\\s*(.+?)\\s*\\1\\s*$")

private const val MIN_SENTENCE_CHARS = 40
private const val MAX_SENTENCE_CHARS = 180
private const val MAX_PER_SECTION = 2
private const val MAX_WIKI_SLIDES = 8

/**
 * Turns an article's plain text (`== Heading ==` style) into slides: a couple of good sentences
 * per allowed section, round-robin so the pack is not all about one topic, in article order.
 */
internal fun buildWikipediaSlides(extract: String): List<TriviaSlide> {
    // section -> body, keeping only allowed top-level and sub-sections. A sub-section is allowed
    // when its own name is allowed or it sits under an allowed parent.
    val sections = mutableListOf<Pair<TriviaCategory, String>>()
    var currentCategory: TriviaCategory? = null
    var parentCategory: TriviaCategory? = null
    val body = StringBuilder()
    fun flush() {
        currentCategory?.let { if (body.isNotBlank()) sections += it to body.toString() }
        body.clear()
    }
    for (line in extract.lines()) {
        val heading = HEADING.matchEntire(line.trim())
        if (heading != null) {
            flush()
            val level = heading.groupValues[1].length
            val name = heading.groupValues[2].lowercase()
            val own = ALLOWED_SECTIONS[name]
            if (level == 2) parentCategory = own
            currentCategory = own ?: if (level > 2) parentCategory else null
        } else if (currentCategory != null) {
            body.appendLine(line)
        }
    }
    flush()

    val perSection = sections.map { (category, text) ->
        splitSentences(text).mapNotNull { cleanSentence(it) }.take(MAX_PER_SECTION).map { TriviaSlide(it, category) }
    }
    val picked = mutableListOf<TriviaSlide>()
    var round = 0
    while (picked.size < MAX_WIKI_SLIDES && perSection.any { it.size > round }) {
        perSection.forEach { list -> list.getOrNull(round)?.let { if (picked.size < MAX_WIKI_SLIDES) picked += it } }
        round++
    }
    return picked.distinctBy { it.text }
}

internal fun splitSentences(text: String): List<String> {
    val flat = text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
    val out = mutableListOf<String>()
    var start = 0
    var i = 0
    while (i < flat.length) {
        val c = flat[i]
        if ((c == '.' || c == '!' || c == '?') && i + 2 < flat.length && flat[i + 1] == ' ' &&
            (flat[i + 2].isUpperCase() || flat[i + 2] == '"' || flat[i + 2] == '“')
        ) {
            val lastWord = flat.substring(start, i).substringAfterLast(' ').lowercase()
            val isInitial = lastWord.length == 1 && lastWord[0].isLetter()
            if (lastWord !in ABBREVIATIONS && !isInitial) {
                out += flat.substring(start, i + 1)
                start = i + 2
            }
        }
        i++
    }
    if (start < flat.length) out += flat.substring(start)
    return out
}

internal fun cleanSentence(raw: String): String? {
    var s = raw.trim()
    // Parentheses hold pronunciations, dates and asides that read badly on a slide; peel
    // innermost-first until none remain.
    while (PARENTHESES.containsMatchIn(s)) s = PARENTHESES.replace(s, "")
    s = s.replace(Regex("\\s+"), " ").replace(" ,", ",").trim()
    if (s.length !in MIN_SENTENCE_CHARS..MAX_SENTENCE_CHARS) return null
    if (!s.last().let { it == '.' || it == '!' || it == '"' || it == '”' }) return null
    if (DANGLING_START.containsMatchIn(s)) return null
    if (SPOILER_WORDS.containsMatchIn(s)) return null
    if (s.contains('$') || s.contains('£') || s.contains('€')) return null
    if (s.any { it == '=' || it == '|' || it == '{' || it == '}' }) return null
    return s
}
