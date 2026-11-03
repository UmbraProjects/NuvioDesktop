package com.nuvio.app.features.youtube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * fork:youtube-mode
 *
 * YouTube's seek-bar previews ("storyboards"): grids of small frames on JPEG sheets at i.ytimg.com,
 * which YouTube's own player shows over the seek bar. The `player` answer describes them in
 * `storyboards.playerStoryboardSpecRenderer.spec`:
 *
 *   https://i.ytimg.com/sb/<id>/storyboard3_L$L/$N.jpg?sqp=…|48#27#100#10#10#0#default#rs$…|80#45#…|160#90#…
 *
 * The URL template, then one level per size: width#height#frames#columns#rows#interval ms#name#sigh.
 * `$L` is the level's index (from 0, after the template), `$N` its name, and a name's `$M` the
 * sheet's number. An interval of 0 means the frames are spread evenly over the video.
 *
 * They come from YouTube's image servers, not the video's stream, so they cost playback nothing,
 * unlike the native decoder's previews, which open the stream a second time.
 */

/** One storyboard level, as the HUD draws it: frame `k` is on sheet `k / (columns × rows)`. */
data class YouTubeStoryboard(
    /** A sheet's URL with `$M` where its number goes. */
    val sheetUrl: String,
    val width: Int,
    val height: Int,
    val columns: Int,
    val rows: Int,
    val count: Int,
    val intervalMs: Long,
)

/** [videoId]'s storyboard (the largest level), or null when YouTube has none for it. */
internal expect suspend fun youTubeStoryboard(videoId: String): YouTubeStoryboard?

private val storyboardJson = Json { ignoreUnknownKeys = true }

/** The largest storyboard level a `player` answer [body] describes, or null. */
internal fun parseYouTubeStoryboard(body: String): YouTubeStoryboard? {
    val root = runCatching { storyboardJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    val spec = ((root["storyboards"] as? JsonObject)?.get("playerStoryboardSpecRenderer") as? JsonObject)
        ?.let { (it["spec"] as? JsonPrimitive)?.content }
        ?: return null
    val lengthSeconds = ((root["videoDetails"] as? JsonObject)?.get("lengthSeconds") as? JsonPrimitive)
        ?.content?.toLongOrNull()
    return parseYouTubeStoryboardSpec(spec, lengthSeconds)
}

internal fun parseYouTubeStoryboardSpec(spec: String, lengthSeconds: Long?): YouTubeStoryboard? {
    val parts = spec.split('|')
    val template = parts.firstOrNull()?.takeIf { it.startsWith("http") } ?: return null
    val levels = parts.drop(1).mapIndexedNotNull { index, level ->
        val fields = level.split('#')
        if (fields.size < 8) return@mapIndexedNotNull null
        val width = fields[0].toIntOrNull() ?: return@mapIndexedNotNull null
        val height = fields[1].toIntOrNull() ?: return@mapIndexedNotNull null
        val count = fields[2].toIntOrNull()?.takeIf { it > 0 } ?: return@mapIndexedNotNull null
        val columns = fields[3].toIntOrNull()?.takeIf { it > 0 } ?: return@mapIndexedNotNull null
        val rows = fields[4].toIntOrNull()?.takeIf { it > 0 } ?: return@mapIndexedNotNull null
        val interval = fields[5].toLongOrNull()?.takeIf { it > 0 }
            ?: lengthSeconds?.takeIf { it > 0 }?.let { it * 1000 / count }
            ?: return@mapIndexedNotNull null
        val name = fields[6]
        val sigh = fields[7]
        val url = template.replace("\$L", index.toString()).replace("\$N", name)
        val signed = if (sigh.isBlank()) url else url + (if ('?' in url) "&" else "?") + "sigh=" + sigh.replace("$", "%24")
        YouTubeStoryboard(signed, width, height, columns, rows, count, interval)
    }
    return levels.maxByOrNull { it.width }
}
