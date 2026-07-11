package com.nuvio.app.features.locallibrary

/**
 * Extracts titles, years and season/episode numbers from Plex/Jellyfin-style names, tolerating
 * the usual release-group variations (dots, underscores, `1x02`, `Season 01/E02`, etc.).
 *
 * Pure and platform-agnostic so it can be unit-tested off the scanner.
 */
object FilenameParser {

    private val yearRegex = Regex("""(?<![0-9])(19\d{2}|20\d{2})(?![0-9])""")

    // Ordered by specificity — the first that matches a name wins.
    private val seasonEpisodeRegexes = listOf(
        Regex("""(?i)s(\d{1,2})[ ._-]*e(\d{1,3})"""),                         // S01E02, s1.e2
        Regex("""(?i)season[ ._-]*(\d{1,2})[ ._-]*episode[ ._-]*(\d{1,3})"""), // Season 1 Episode 2
        Regex("""(?i)(?<![0-9])(\d{1,2})x(\d{1,3})(?![0-9])"""),               // 1x02
    )
    private val seasonFolderRegex = Regex("""(?i)(?:season|series|s)[ ._-]*(\d{1,3})""")
    private val episodeOnlyRegex =
        Regex("""(?i)(?:^|[ ._-])e(?:p(?:isode)?)?[ ._-]*(\d{1,4})(?![0-9p])""")

    // Tokens that mark the start of release metadata; the title ends before the first one.
    private val junkTokenRegex = Regex(
        "(?i)^(" +
            "1080p|2160p|720p|480p|4k|uhd|hdr|hdr10|dolby|dv|remux|bluray|blu-ray|brrip|bdrip|" +
            "webrip|web-dl|webdl|web|hdtv|dvdrip|dvd|xvid|divx|x264|x265|h264|h265|hevc|avc|" +
            "aac|ac3|dts|dts-hd|truehd|atmos|ddp?5|flac|mp3|10bit|8bit|hdcam|cam|ts|proper|" +
            "repack|extended|unrated|remastered|imax|multi|dual|ita|eng|complete" +
            ")$",
    )

    data class ParsedTitle(val title: String, val year: Int?)

    data class ParsedEpisode(
        val showTitle: String?,
        val season: Int?,
        val episode: Int?,
    )

    /**
     * Parses a movie title + year from a folder or file name. Plex layout puts these in the
     * folder name (`Title (2021)`); we fall back to the file name when needed.
     */
    fun parseTitle(rawName: String): ParsedTitle {
        val base = stripExtension(rawName)
        val year = yearRegex.findAll(base).lastOrNull()?.value?.toIntOrNull()
        val beforeYear = if (year != null) base.substringBeforeLast(year.toString()) else base
        return ParsedTitle(title = cleanTitle(beforeYear.ifBlank { base }), year = year)
    }

    /**
     * Parses episode coordinates for a series file. [seasonFolderName] (e.g. `Season 01`) is used
     * as a fallback when the file name only carries an episode number.
     */
    fun parseEpisode(fileName: String, seasonFolderName: String? = null): ParsedEpisode {
        val base = stripExtension(fileName)

        for (regex in seasonEpisodeRegexes) {
            val match = regex.find(base) ?: continue
            val season = match.groupValues[1].toIntOrNull()
            val episode = match.groupValues[2].toIntOrNull()
            if (season != null && episode != null) {
                return ParsedEpisode(
                    showTitle = cleanTitle(base.substring(0, match.range.first)).takeIf { it.isNotBlank() },
                    season = season,
                    episode = episode,
                )
            }
        }

        // No SxxExx — try a bare episode number, taking the season from the folder.
        val folderSeason = seasonFolderName?.let { seasonFolderRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val episode = episodeOnlyRegex.find(base)?.groupValues?.get(1)?.toIntOrNull()
        return ParsedEpisode(showTitle = null, season = folderSeason, episode = episode)
    }

    /** A stable, comparison-friendly key for an item so rescans keep manual corrections. */
    fun normalizeKey(title: String, year: Int?): String {
        val slug = title.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString("-")
        return if (year != null) "$slug-$year" else slug
    }

    fun cleanTitle(raw: String): String {
        val spaced = raw
            .replace('.', ' ')
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
        val tokens = spaced.split(Regex("\\s+")).filter { it.isNotBlank() }
        val kept = ArrayList<String>(tokens.size)
        for (token in tokens) {
            if (junkTokenRegex.matches(token)) break
            kept += token
        }
        val result = kept.joinToString(" ").trim()
        // Drop a trailing bracketed group left over after a title (e.g. "Title [group]").
        return result.trim().trimEnd('(', '[', '{', '-', ' ').trim()
    }

    private fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        // Only treat a short trailing token as an extension, not part of the title.
        return if (dot in (name.length - 5) until name.length && dot > 0) name.substring(0, dot) else name
    }
}
