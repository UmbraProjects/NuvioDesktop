package com.nuvio.app.features.preroll

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetails
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Post-show cards from the film's English Wikipedia article: the fallback when no AI provider is
 * set up, or when it fails or is too slow for the credits.
 *
 * Unlike the pre-show, this reads the plot (the film has just been watched) and the themes
 * sections. Films only: a series article has no per-episode plot, and the per-season spoiler gate
 * cannot be honoured from it, so finales stay AI-only. Text is CC BY-SA 4.0, so the last card says
 * where it came from.
 */
internal object PostShowWikipedia {
    private val log = Logger.withTag("PostShow")
    private const val TIMEOUT_MS = 20_000L
    internal const val MODEL = "wikipedia"
    internal const val ATTRIBUTION = "From the film's Wikipedia article, licensed CC BY-SA 4.0."

    suspend fun packFor(meta: MetaDetails): PostShowPack? {
        if (!meta.type.equals("movie", ignoreCase = true)) return null
        val source = meta.toTriviaSource()
        if (!source.isUsable) return null
        val found = runCatching {
            withTimeoutOrNull(TIMEOUT_MS) {
                TriviaWikipedia.findArticle(source, isSeries = false) { buildPostShowCardsFromArticle(it).isNotEmpty() }
            }
        }.onFailure { log.w { "wikipedia post-show for ${source.title} failed: $it" } }.getOrNull() ?: return null
        val cards = buildPostShowCardsFromArticle(found.second)
        log.i { "wikipedia post-show: ${cards.size} cards from \"${found.first}\"" }
        return PostShowPack(
            cards = cards + PostShowCard(PostShowCardKind.Source, ATTRIBUTION),
            questions = buildMetadataQuestions(source),
            theme = fallbackTheme(source.genres),
        )
    }
}

private val SECTION_HEADING = Regex("^(=+)\\s*(.+?)\\s*\\1\\s*$")
private val ASIDE = Regex("\\s*\\([^()]*\\)")
private val PLOT_SECTIONS = setOf("plot", "synopsis")
private val THEME_SECTIONS = setOf("themes", "theme", "analysis", "interpretation", "themes and analysis", "style and themes", "themes and interpretations")
private const val MAX_ENDING_CHARS = 330
private const val MAX_CARD_CHARS = 200

/** Top-level section name (lower case) to its text, sub-sections folded into their parent. */
internal fun topLevelSections(extract: String): Map<String, String> {
    val out = linkedMapOf<String, StringBuilder>()
    var current: StringBuilder? = null
    for (line in extract.lines()) {
        val m = SECTION_HEADING.matchEntire(line.trim())
        if (m != null) {
            if (m.groupValues[1].length == 2) {
                current = out.getOrPut(m.groupValues[2].lowercase()) { StringBuilder() }
            }
        } else {
            current?.appendLine(line)
        }
    }
    return out.mapValues { it.value.toString() }
}

/**
 * Ending, themes and a couple of making-of facts, in that order. Empty when the article has too
 * little to be worth showing (fewer than two cards).
 */
internal fun buildPostShowCardsFromArticle(extract: String): List<PostShowCard> {
    val sections = topLevelSections(extract)
    val cards = mutableListOf<PostShowCard>()

    val plot = sections.entries.firstOrNull { it.key in PLOT_SECTIONS }?.value
    endingOf(plot)?.let { cards += PostShowCard(PostShowCardKind.Ending, it) }

    sections.entries.firstOrNull { it.key in THEME_SECTIONS }?.value
        ?.let { splitSentences(it).mapNotNull(::cardSentence).firstOrNull() }
        ?.let { cards += PostShowCard(PostShowCardKind.Themes, it) }

    buildWikipediaSlides(extract)
        .filter { it.category == TriviaCategory.Production || it.category == TriviaCategory.Cast }
        .take(2)
        .forEach { cards += PostShowCard(PostShowCardKind.BehindTheScenes, it.text) }

    return if (cards.size >= 2) cards else emptyList()
}

/** The last one or two sentences of the plot: how the film ends. Null when they read badly alone. */
internal fun endingOf(plot: String?): String? {
    val sentences = plot?.let(::splitSentences)
        ?.map { ASIDE.replace(it, "").replace(Regex("\\s+"), " ").trim() }
        ?.filter { it.length >= 25 }
        .orEmpty()
    val last = sentences.lastOrNull() ?: return null
    val pair = sentences.getOrNull(sentences.size - 2)?.let { "$it $last" }
    return listOfNotNull(pair, last).firstOrNull { it.length <= MAX_ENDING_CHARS }
}

private fun cardSentence(raw: String): String? {
    val s = raw.trim().replace(ASIDE, "").replace(Regex("\\s+"), " ")
    if (s.length !in 40..MAX_CARD_CHARS) return null
    if (!s.last().let { it == '.' || it == '"' || it == '”' }) return null
    if (Regex("^(He|She|His|Her|They|Their|These|This|That|However|Also|But|And)\\b").containsMatchIn(s)) return null
    return s
}
