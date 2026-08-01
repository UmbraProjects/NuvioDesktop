package com.nuvio.app.features.locallibrary

/**
 * Extracts titles, years and season/episode numbers from Plex/Jellyfin-style names, tolerating
 * the usual release-group variations (dots, underscores, `1x02`, `Season 01/E02`, etc.).
 *
 * Pure and platform-agnostic so it can be unit-tested off the scanner.
 */
object FilenameParser {

    private val yearRegex = Regex("""(?<![0-9])(19\d{2}|20\d{2})(?![0-9])""")

    // Ordered by specificity — the first that matches a name wins. The episode marker accepts
    // `E`/`Ep`/`Episode` in every form: archival packs routinely spell it out long-hand
    // (`Pokemon Season 14 - Ep01 - BW001`), and reading only `E<digits>` left those files with no
    // season at all, so a whole multi-season pack collapsed onto one episode.
    private val seasonEpisodeRegexes = listOf(
        Regex("""(?i)s(\d{1,2})[ ._-]*e(?:pisode|p)?[ ._-]*(\d{1,3})"""),      // S01E02, s1.e2, S14 - Ep01
        Regex("""(?i)season[ ._-]*(\d{1,2})[ ._-]*e(?:pisode|p)?[ ._-]*(\d{1,3})"""), // Season 14 - Ep01
        Regex("""(?i)(?<![0-9])(\d{1,2})x(\d{1,3})(?![0-9])"""),               // 1x02
    )
    // The leading boundary matters: without it the trailing `s` of a pack name like
    // `[Seasons 14-16]` matched the bare `s` branch and stamped season 14 onto every file in it.
    private val seasonFolderRegex = Regex("""(?i)(?:^|[^a-z0-9])(?:season|series|s)[ ._-]*(\d{1,3})""")
    // A pack spanning several seasons (`Seasons 14-16`, `S01-S03`, `S01-03`) has no single season,
    // so it must not lend one to files that carry no season of their own.
    private val seasonRangeRegex =
        Regex("""(?i)(?:^|[^a-z0-9])(?:seasons|series|s)[ ._-]*(\d{1,3})[ ._]*-[ ._]*(?:s)?(\d{1,3})(?![0-9])""")
    private val episodeOnlyRegex =
        Regex("""(?i)(?:^|[ ._-])e(?:p(?:isode)?)?[ ._-]*(\d{1,4})(?![0-9p])""")

    // --- Anime absolute-numbering helpers (only consulted for anime folders) ---
    private val bracketGroupRegex = Regex("""[\[({][^\])}]*[\])}]""")
    // A ` - 12 ` / `_12_` / `Ep. 12` delimiter is a strong episode signal in anime releases. The
    // lookbehind keeps the `e`/`ep` branch from matching a title word that merely ends in 'e'
    // before a number (e.g. "the 12").
    private val delimitedEpisodeRegex = Regex("""(?i)(?:[ ._]-[ ._]*|(?<![a-z])ep?[ ._]*)(\d{1,4})(?:v\d)?(?![0-9])""")
    // Resolution / codec / audio tokens that carry stray digits we must not read as episodes.
    private val resolutionTokenRegex =
        Regex("""(?i)\b(?:\d{3,4}p|4k|uhd|x26[45]|h\.?26[45]|hevc|10bit|8bit|aac|flac|opus|ddp?\d(?:\.\d)?)\b""")
    private val standaloneNumberRegex = Regex("""(?<![0-9])\d{1,4}(?![0-9])""")

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
        // The episode name that follows an explicit SxxExx marker (e.g. the
        // `The One with the Princess Leia Fantasy` in `Friends.S03E01.The.One…`), cleaned of the
        // trailing release tags. Null when no marker is present or nothing follows it.
        val episodeTitle: String? = null,
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
     * as a fallback when the file name only carries an episode number. When [isAnime] is set and
     * no explicit SxxExx/EPxx marker is present, a bare trailing number is read as an *absolute*
     * episode (season left null) — anime is routinely numbered `Show - 1075` across a whole run.
     */
    fun parseEpisode(fileName: String, seasonFolderName: String? = null, isAnime: Boolean = false): ParsedEpisode {
        val base = stripExtension(fileName)

        for (regex in seasonEpisodeRegexes) {
            val match = regex.find(base) ?: continue
            val season = match.groupValues[1].toIntOrNull()
            val episode = match.groupValues[2].toIntOrNull()
            if (season != null && episode != null) {
                return ParsedEpisode(
                    showTitle = cleanTitle(base.substring(0, match.range.first)).takeIf { it.isNotBlank() },
                    // Anime keeps an explicit SxxExx verbatim too: LocalAnimeEpisodeMatcher
                    // translates franchise season/episode ↔ per-entry absolute numbering through
                    // the anime-list mapping at play time, so the season is information, not noise.
                    season = season,
                    episode = episode,
                    episodeTitle = cleanTitle(base.substring(match.range.last + 1)).takeIf { it.isNotBlank() },
                )
            }
        }

        // No SxxExx — try a bare episode number, taking the season from the folder. An anime file
        // with no season marker anywhere (`Show - 1075`) stays absolute-numbered: season null.
        val folderSeason = seasonFolderName
            ?.takeUnless { seasonRangeRegex.containsMatchIn(it) }
            ?.let { seasonFolderRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val episode = episodeOnlyRegex.find(base)?.groupValues?.get(1)?.toIntOrNull()
            ?: if (isAnime) absoluteAnimeEpisode(base) else null
        return ParsedEpisode(
            showTitle = null,
            season = folderSeason,
            episode = episode,
        )
    }

    /**
     * Best-effort absolute episode number from an anime file with no SxxExx/EPxx marker, e.g.
     * `[Group] Frieren - 12 [1080p]` or `One Piece 1075`. Release-group brackets, the year, and
     * resolution/checksum tokens are removed first so the remaining standalone integer is the
     * episode. Returns null when nothing looks like an episode number.
     */
    private fun absoluteAnimeEpisode(base: String): Int? {
        val cleaned = base
            .replace(bracketGroupRegex, " ")   // [SubsGroup], (BD), {crc}
            .replace(yearRegex, " ")           // a bracketed/inline year is not the episode
            .replace(resolutionTokenRegex, " ") // 1080p, 720p, 10bit, x265, etc.
        // Prefer a `- 12` / `_12_` / `Ep 12`–style delimiter; fall back to the last standalone int.
        delimitedEpisodeRegex.findAll(cleaned).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return standaloneNumberRegex.findAll(cleaned).lastOrNull()?.value?.toIntOrNull()
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
