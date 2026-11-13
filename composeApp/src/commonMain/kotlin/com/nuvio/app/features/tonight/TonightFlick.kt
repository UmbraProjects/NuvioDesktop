package com.nuvio.app.features.tonight

import kotlin.random.Random

/*
 * fork:tonight - Channel Flick (design-notes/tonight-picker.md, phase 3): trailers back to back.
 * The running order favours what the user has not seen yet and suits their taste, with enough
 * shuffle that two evenings do not open on the same channel.
 */

fun tonightFlickOrder(pool: List<TonightTitle>, taste: TonightTaste, seed: Long): List<TonightTitle> {
    val random = Random(seed)
    return pool.distinctBy { it.key }
        .map { t ->
            val unseen = if (t.started || t.watched) 0f else 0.4f
            val score = unseen + taste.score(t) * 0.4f + ((t.rating ?: 6.5) / 10.0).toFloat() * 0.3f + random.nextFloat() * 0.5f
            t to score
        }
        .sortedByDescending { it.second }
        .map { it.first }
}

/** "FROM YOUR LIBRARY", "TRENDING THIS WEEK", "YOU'RE ON S2 E4"... the line over a channel's title. */
fun TonightTitle.flickOriginLine(): String = when {
    started && nextEpisodeLabel != null -> "YOU'RE ON $nextEpisodeLabel"
    started -> "YOU'RE PART WAY THROUGH"
    watched -> "SEEN BEFORE"
    TonightOrigin.Library in origins -> "FROM YOUR LIBRARY"
    TonightOrigin.Recommended in origins && recommendedBy != null -> recommendedBy.uppercase()
    TonightOrigin.Trending in origins -> "TRENDING THIS WEEK"
    else -> ""
}
