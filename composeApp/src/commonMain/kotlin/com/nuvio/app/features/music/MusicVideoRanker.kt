package com.nuvio.app.features.music

import com.nuvio.app.features.trailer.YouTubeSearchHit
import kotlin.math.abs
import kotlin.math.log10

private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
private val ARTICLES = setOf("the", "a", "an")

private fun tokens(text: String): List<String> = text.lowercase().split(NON_ALNUM).filter { it.isNotEmpty() }
private fun squash(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

/** Uploads that are not the track's music video, unless the word is part of the track's own title. */
private val UNWANTED = setOf(
    "lyrics", "lyric", "live", "cover", "remix", "reaction", "reacts", "karaoke", "instrumental",
    "audio", "visualizer", "visualiser", "slowed", "reverb", "nightcore", "sped", "8d", "tutorial",
    "lesson", "mashup", "shorts", "tiktok", "acoustic", "piano", "guitar", "behind", "making",
)

/** "Get Lucky (feat. X) - 2011 Remaster" to "Get Lucky": the part a video title is sure to carry. */
internal fun coreTrackTitle(title: String): String =
    title.replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ").substringBefore(" - ").trim()

/**
 * Ranks YouTube search results for the official music video of [title] by [artist], best first, up
 * to [limit]. A hit must carry every significant word of the track's title and name the artist in
 * its title or channel, must not be lyrics, live, a cover, a remix or just the audio, and must run
 * about as long as the track ([durationSeconds], 0 when unknown). Among those, official uploads from
 * verified or VEVO channels win, a "- Topic" channel (the audio upload) is marked down, and views
 * break ties.
 */
internal fun rankMusicVideoHits(
    hits: List<YouTubeSearchHit>,
    title: String,
    artist: String,
    durationSeconds: Int,
    limit: Int = 3,
): List<YouTubeSearchHit> {
    val wanted = tokens(coreTrackTitle(title)).filterNot { it in ARTICLES }.toSet()
    val artistKey = squash(artist)
    if (wanted.isEmpty() || artistKey.isEmpty()) return emptyList()
    val ownWords = tokens(title).toSet()
    return hits.withIndex().mapNotNull { (index, hit) ->
        val words = tokens(hit.title).toSet()
        if (!words.containsAll(wanted)) return@mapNotNull null
        if (artistKey !in squash(hit.title + hit.channel)) return@mapNotNull null
        if (words.any { it in UNWANTED && it !in ownWords }) return@mapNotNull null
        if (durationSeconds > 0) {
            val allowed = maxOf(20, durationSeconds / 5)
            if (hit.durationSeconds <= 0 || abs(hit.durationSeconds - durationSeconds) > allowed) return@mapNotNull null
        }
        val channel = hit.channel.lowercase()
        var score = 0
        if ("official" in words) score += 3
        if ("video" in words || "mv" in words) score += 2
        if (hit.verified) score += 2
        if (channel.endsWith("vevo")) score += 2
        if ("topic" in tokens(hit.channel)) score -= 5
        if (hit.views > 0) score += (log10(hit.views.toDouble()) / 2.5).toInt().coerceIn(0, 3)
        if (durationSeconds > 0 && abs(hit.durationSeconds - durationSeconds) * 20 <= durationSeconds) score += 2
        Triple(score, index, hit)
    }
        .sortedWith(compareByDescending<Triple<Int, Int, YouTubeSearchHit>> { it.first }.thenBy { it.second })
        .take(limit)
        .map { it.third }
}
