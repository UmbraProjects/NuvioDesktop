package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's Now Playing song notes, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import com.nuvio.app.features.music.booklet.bookletNorm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// fork:story - the English Wikipedia article about one song, for the Stage's song notes: how it was written, what it means,
// who sampled or covered it. Pure functions here; the requests are in WikipediaClient.songText.

private val SONG_JSON = Json { ignoreUnknownKeys = true }

private fun JsonElement?.obj() = this as? JsonObject
private fun JsonElement?.arr() = this as? JsonArray
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()

/** The title of the song's article in a search answer (intro extracts), or null when no page fits. */
internal fun pickWikipediaSongTitle(body: String, song: String, artist: String): String? {
    val pages = runCatching { SONG_JSON.parseToJsonElement(body) }.getOrNull().obj()?.get("query").obj()?.get("pages").arr()?.mapNotNull { it.obj() } ?: return null
    val wantedSong = bookletNorm(song.replace(Regex("""\s*[(\[].*?[)\]]"""), ""))
    val wantedArtist = bookletNorm(artist)
    if (wantedSong.isEmpty() || wantedArtist.isEmpty()) return null
    val notASong = listOf(" album", " film", " tv series", " novel", " band", " ep")
    return pages
        .sortedBy { (it["index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: Int.MAX_VALUE }
        .firstNotNullOfOrNull { page ->
            val raw = page.text("title").orEmpty()
            val title = bookletNorm(raw)
            val extract = bookletNorm(page.text("extract").orEmpty().take(500))
            val ok = title.startsWith(wantedSong) &&
                notASong.none { title.endsWith(it) } &&
                (title.endsWith(" song") || title.endsWith(" single") || Regex("""\b(song|single)\b""").containsMatchIn(extract)) &&
                extract.contains(wantedArtist)
            if (ok) raw else null
        }
}

private val SKIP_SECTIONS = setOf(
    "track listing", "personnel", "credits", "charts", "weekly charts", "year end charts", "certifications", "references",
    "notes", "sources", "external links", "see also", "release history", "format", "formats", "sales and certifications",
    "accolades", "awards", "chart performance", "commercial performance", "music video",
)

/** The article text's sections by heading ("Background" and so on; the lead is ""), without the lists nobody reads. */
internal fun songSections(text: String): Map<String, String> {
    val out = LinkedHashMap<String, StringBuilder>()
    var heading = ""
    for (line in text.lines()) {
        val m = Regex("""^=+\s*(.*?)\s*=+$""").find(line.trim())
        if (m != null) { heading = m.groupValues[1]; continue }
        if (line.isBlank()) continue
        out.getOrPut(heading) { StringBuilder() }.append(line.trim()).append('\n')
    }
    return out.filterKeys { it.lowercase() !in SKIP_SECTIONS }.mapValues { it.value.toString().trim() }.filterValues { it.isNotEmpty() }
}

/** The article for the AI: the sections in order, each under its heading, cut to [max] characters. */
internal fun songArticleText(sections: Map<String, String>, max: Int): String = buildString {
    for ((heading, body) in sections) {
        if (length >= max) break
        if (heading.isNotEmpty()) append("## ").append(heading).append('\n')
        append(body).append("\n\n")
    }
}.take(max).trim()

private val MADE_HEADINGS = listOf("background", "writing", "composition", "recording", "production", "development", "origin", "conception", "writing and recording", "writing and inspiration", "inspiration")
private val MEANING_HEADINGS = listOf("lyrics", "lyrical content", "themes", "meaning", "lyrical themes", "interpretation", "music and lyrics", "lyrics and music", "content")
private val AFTER_HEADINGS = listOf("legacy", "cover versions", "covers", "samples", "sampling", "in popular culture", "influence", "impact", "remixes", "legacy and influence", "samples and covers", "cover versions and samples", "other versions", "reception")

/** What the article says about [heads], cut at a sentence to [max]; null when it has no such section. Used with no AI to write the notes. */
internal fun songSectionText(sections: Map<String, String>, heads: List<String>, max: Int): String? {
    val body = sections.entries.firstOrNull { it.key.lowercase() in heads }?.value ?: return null
    return com.nuvio.app.features.music.booklet.cutAtSentence(body.replace('\n', ' ').replace(Regex("""\s+"""), " "), max)
}

internal fun songMadeText(s: Map<String, String>, max: Int) = songSectionText(s, MADE_HEADINGS, max)
internal fun songMeaningText(s: Map<String, String>, max: Int) = songSectionText(s, MEANING_HEADINGS, max)
internal fun songAfterText(s: Map<String, String>, max: Int) = songSectionText(s, AFTER_HEADINGS, max)

/** True when [quote] stands word for word in [source] (ignoring case, spacing and punctuation): a quote the AI made up is dropped. */
internal fun quoteIsVerbatim(quote: String, source: String): Boolean {
    val q = bookletNorm(quote)
    return q.length >= 12 && bookletNorm(source).contains(q)
}
