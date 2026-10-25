package com.nuvio.app.features.streams

/**
 * Builds the filename the next episode of the same release should have, by replacing the episode
 * (and season) numbers in the current one. Used to pick the same release again when a binge group
 * alone is ambiguous: one addon often gives every 1080p WEB-DL row the same group.
 *
 * Ported from upstream (d2cc0fb8a, skoruppa).
 */
object FilenameEpisodeMatcher {

    fun buildNextFilename(
        filename: String,
        currentSeason: Int?,
        currentEpisode: Int,
        nextSeason: Int?,
        nextEpisode: Int,
    ): String? {
        if (filename.isBlank()) return null
        for (pattern in patterns) {
            val result = pattern.replace(filename, currentSeason, currentEpisode, nextSeason, nextEpisode)
            if (result != null) return result
        }
        return null
    }

    fun matches(candidateFilename: String?, expectedFilename: String?): Boolean {
        if (candidateFilename.isNullOrBlank() || expectedFilename.isNullOrBlank()) return false
        return candidateFilename.equals(expectedFilename, ignoreCase = true)
    }

    fun extractSeasonEpisode(filename: String): Pair<Int?, Int>? {
        if (filename.isBlank()) return null
        sXeYRegex.find(filename)?.let { match ->
            val season = match.groupValues[2].toIntOrNull()
            val episode = match.groupValues[4].toIntOrNull() ?: return null
            return season to episode
        }
        seasonXepisodeRegex.find(filename)?.let { match ->
            val season = match.groupValues[1].toIntOrNull()
            val episode = match.groupValues[2].toIntOrNull() ?: return null
            return season to episode
        }
        episodeOnlyRegex.find(filename)?.let { match ->
            val episode = match.groupValues[2].toIntOrNull() ?: return null
            return null to episode
        }
        return null
    }

    private val sXeYRegex = Regex("""(?i)(S)\s*(\d{1,3})\s*(E)\s*(\d{1,4})""")

    // 01x03, 1x03 (the digit lookbehind keeps 1920x1080 out)
    private val seasonXepisodeRegex = Regex("""(?<!\d)(\d{1,2})[xX](\d{1,4})""")

    private val episodeOnlyRegex = Regex("""(?i)\b(E[Pp]?)[.\s]?(\d{1,4})\b""")

    private fun interface EpisodePattern {
        fun replace(filename: String, currentSeason: Int?, currentEpisode: Int, nextSeason: Int?, nextEpisode: Int): String?
    }

    private val sXeY = EpisodePattern { filename, currentSeason, currentEpisode, nextSeason, nextEpisode ->
        if (currentSeason == null) return@EpisodePattern null
        val match = sXeYRegex.find(filename) ?: return@EpisodePattern null
        if (match.groupValues[2].toIntOrNull() != currentSeason || match.groupValues[4].toIntOrNull() != currentEpisode) {
            return@EpisodePattern null
        }
        val seasonWidth = match.groupValues[2].length
        val episodeWidth = match.groupValues[4].length
        val replacement = match.groupValues[1] +
            (nextSeason ?: currentSeason).toString().padStart(seasonWidth, '0') +
            match.groupValues[3] +
            nextEpisode.toString().padStart(episodeWidth, '0')
        filename.replaceRange(match.range, replacement)
    }

    private val seasonXepisode = EpisodePattern { filename, currentSeason, currentEpisode, nextSeason, nextEpisode ->
        if (currentSeason == null) return@EpisodePattern null
        val match = seasonXepisodeRegex.find(filename) ?: return@EpisodePattern null
        if (match.groupValues[1].toIntOrNull() != currentSeason || match.groupValues[2].toIntOrNull() != currentEpisode) {
            return@EpisodePattern null
        }
        val seasonWidth = match.groupValues[1].length
        val episodeWidth = match.groupValues[2].length
        val separator = filename[match.range.first + match.groupValues[1].length]
        val replacement = (nextSeason ?: currentSeason).toString().padStart(seasonWidth, '0') +
            separator +
            nextEpisode.toString().padStart(episodeWidth, '0')
        filename.replaceRange(match.range, replacement)
    }

    private val episodeOnly = EpisodePattern { filename, _, currentEpisode, _, nextEpisode ->
        val match = episodeOnlyRegex.find(filename) ?: return@EpisodePattern null
        if (match.groupValues[2].toIntOrNull() != currentEpisode) return@EpisodePattern null
        val episodeWidth = match.groupValues[2].length
        val prefix = match.groupValues[1]
        val separator = match.value.substring(prefix.length, match.value.length - match.groupValues[2].length)
        val replacement = prefix + separator + nextEpisode.toString().padStart(episodeWidth, '0')
        filename.replaceRange(match.range, replacement)
    }

    private val patterns = listOf(sXeY, seasonXepisode, episodeOnly)
}

/**
 * The release the viewer was last watching within a binge group, aimed at the episode about to be
 * picked: its filename rewritten to that episode, and its infohash (the same torrent is a season
 * pack). [StreamAutoPlaySelector] tries rows of the binge group matching the filename first, then
 * the infohash, then the rest in their usual order.
 */
data class BingeReleaseHint(
    val expectedFilename: String?,
    val infoHash: String?,
) {
    /** 0 for a filename match, 1 for an infohash match, 2 for neither. */
    fun rank(stream: StreamItem): Int = when {
        FilenameEpisodeMatcher.matches(stream.behaviorHints.filename, expectedFilename) -> 0
        infoHash != null && stream.p2pInfoHash?.equals(infoHash, ignoreCase = true) == true -> 1
        else -> 2
    }

    companion object {
        /**
         * Null when there is nothing to match on. When the last filename carries no recognisable
         * episode number it is kept as it is, which still matches a replay of the same file.
         */
        fun forEpisode(
            lastFilename: String?,
            lastInfoHash: String?,
            season: Int?,
            episode: Int?,
        ): BingeReleaseHint? {
            val filename = lastFilename?.takeIf { it.isNotBlank() }
            val hash = lastInfoHash?.takeIf { it.isNotBlank() }
            if (filename == null && hash == null) return null
            val expected = filename?.let { name ->
                val current = FilenameEpisodeMatcher.extractSeasonEpisode(name)
                if (current != null && episode != null) {
                    FilenameEpisodeMatcher.buildNextFilename(name, current.first, current.second, season, episode) ?: name
                } else {
                    name
                }
            }
            return BingeReleaseHint(expectedFilename = expected, infoHash = hash)
        }
    }
}
