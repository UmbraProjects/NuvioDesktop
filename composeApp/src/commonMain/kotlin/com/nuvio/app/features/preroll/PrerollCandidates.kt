package com.nuvio.app.features.preroll

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.home.MetaPreview

/**
 * The More Like This titles that are fit to play as trailers before [this] film.
 *
 * A trailer for the sequel before the original is a spoiler and a bad pre-show, so anything from
 * the same franchise is dropped: the film's own collection, and anything whose title starts with
 * the film's own (so "The Godfather Part II" goes even when the collection list is missing).
 */
fun MetaDetails.prerollTrailerCandidates(): List<MetaPreview> {
    val excludedIds = buildSet {
        add(id)
        collectionItems.forEach { add(it.id) }
    }
    val baseTokens = franchiseTokens(name)
    return moreLikeThis
        .asSequence()
        .filter { it.type != "collection" }
        .filter { it.id !in excludedIds }
        .filterNot { sharesFranchise(baseTokens, it.name) }
        .distinctBy { "${it.type}:${it.id}" }
        .toList()
}

/**
 * The words that identify the franchise: the title before any subtitle ("Spider-Man: Brand New
 * Day" -> "spider man"), lowercased, punctuation removed, with trailing sequel numbering
 * ("2", "part ii") dropped so "Rocky II" still identifies "Rocky".
 */
internal fun franchiseTokens(title: String): List<String> {
    val beforeSubtitle = title.split(Regex("\\s*[:\\u2013\\u2014]\\s*|\\s+-\\s+")).first()
    val tokens = tokenize(beforeSubtitle).toMutableList()
    while (tokens.size > 1 && isSequelMarker(tokens.last())) tokens.removeAt(tokens.lastIndex)
    // A trailing "part" left over once its number was removed ("godfather part").
    if (tokens.size > 1 && tokens.last() in setOf("part", "chapter", "volume", "vol")) tokens.removeAt(tokens.lastIndex)
    return tokens
}

internal fun sharesFranchise(baseTokens: List<String>, candidateTitle: String): Boolean {
    if (baseTokens.isEmpty()) return false
    val candidate = tokenize(candidateTitle)
    if (candidate.size < baseTokens.size) return false
    return baseTokens.indices.all { candidate[it] == baseTokens[it] }
}

private fun tokenize(text: String): List<String> =
    text.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }

private val ROMAN = Regex("^(ii|iii|iv|v|vi|vii|viii|ix|x)$")

private fun isSequelMarker(token: String): Boolean =
    token.all(Char::isDigit) || ROMAN.matches(token)
