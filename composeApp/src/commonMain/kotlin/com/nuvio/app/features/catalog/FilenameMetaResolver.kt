package com.nuvio.app.features.catalog

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.locallibrary.FilenameParser
import com.nuvio.app.features.metadata.pickBestTmdbMatch
import com.nuvio.app.features.tmdb.TmdbSearchResult
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.tmdb.TmdbSettings
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.tmdb.customPosterTemplateNeedsImdbId
import com.nuvio.app.features.tmdb.customPosterUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Gives catalog items a real title and poster when the addon supplies neither.
 *
 * Cloud-library catalogs (TorBox, the AIOStreams library addon, …) list what is in the user's
 * account, not what is in a metadata database, so a row arrives as
 * `The.Matrix.1999.2160p.UHD.BluRay.x265-GROUP.mkv` with no poster at all. The release name still
 * carries a title and usually a year, so we parse it the same way the local library and the
 * direct-play Discord presence do, look it up on TMDB, and swap in the proper name and artwork.
 *
 * Deliberately conservative:
 * - only items whose name actually looks like a release filename are touched;
 * - the TMDB hit has to clear the shared auto-accept score, so a bad guess leaves the row alone;
 * - every failure (no key, no match, network error, budget exceeded) degrades to the original item.
 */
internal object FilenameMetaResolver {

    private data class ResolvedTitle(
        val title: String,
        val year: Int?,
        val poster: String?,
        val posterFallback: String?,
        val backdrop: String?,
        val overview: String?,
    )

    // null value = a confirmed miss, cached so scrolling a 100-item cloud library doesn't re-ask
    // TMDB about the same unparseable name on every page. Insertion-ordered and capped: a large
    // library browsed all session long would otherwise grow this without limit.
    private val cache = linkedMapOf<String, ResolvedTitle?>()
    private val inFlight = mutableMapOf<String, Deferred<ResolvedTitle?>>()
    private val mutex = Mutex()
    private val gate = Semaphore(MAX_PARALLEL_LOOKUPS)

    // Lookups live in their own scope so a page that runs out of budget still finishes filling the
    // cache in the background — the next page/refresh then resolves instantly instead of restarting.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun enrich(items: List<MetaPreview>): List<MetaPreview> {
        if (items.isEmpty()) return items
        if (!isEnabled()) return items

        val queries = HashMap<Int, FilenameQuery>()
        items.forEachIndexed { index, item ->
            if (!looksLikeReleaseFilename(item.name)) return@forEachIndexed
            parseFilenameQuery(item.name, item.type)?.let { queries[index] = it }
        }
        if (queries.isEmpty()) return items

        val resolved = resolveAll(queries.values.distinctBy { it.cacheKey })
        if (resolved.isEmpty()) return items

        return items.mapIndexed { index, item ->
            val query = queries[index] ?: return@mapIndexed item
            val match = resolved[query.cacheKey] ?: return@mapIndexed item
            item.withResolvedMeta(query, match)
        }
    }

    /**
     * Resolves bare names, for surfaces that aren't addon catalogs — the built-in debrid cloud
     * library lists torrent names straight from the provider's account, with no metadata at all.
     * Returns only the names that resolved, keyed by the name that was passed in.
     */
    suspend fun resolveNames(names: Collection<String>, catalogType: String): Map<String, ResolvedName> {
        if (names.isEmpty() || !isEnabled()) return emptyMap()
        val queries = names.distinct().mapNotNull { name ->
            if (!looksLikeReleaseFilename(name)) return@mapNotNull null
            parseFilenameQuery(name, catalogType)?.let { name to it }
        }
        if (queries.isEmpty()) return emptyMap()

        val resolved = resolveAll(queries.map { it.second }.distinctBy { it.cacheKey })
        if (resolved.isEmpty()) return emptyMap()

        return buildMap {
            queries.forEach { (name, query) ->
                val match = resolved[query.cacheKey] ?: return@forEach
                put(
                    name,
                    ResolvedName(
                        displayName = query.displayName(match.title),
                        poster = match.poster,
                        posterFallback = match.posterFallback,
                        backdrop = match.backdrop,
                        year = match.year,
                        overview = match.overview,
                    ),
                )
            }
        }
    }

    private fun isEnabled(): Boolean {
        val settings = TmdbSettingsRepository.snapshot()
        // TMDB search needs the user's own key; without one there is nothing to resolve against.
        return settings.resolveFilenameCatalogs && settings.hasApiKey
    }

    private suspend fun resolveAll(queries: List<FilenameQuery>): Map<String, ResolvedTitle> {
        val pending = queries.mapNotNull { query ->
            val known = mutex.withLock { cache.containsKey(query.cacheKey) }
            if (known) null else query
        }
        if (pending.isNotEmpty()) {
            val lookups = pending.map { query -> lookupAsync(query) }
            // A cold cloud library is a lot of lookups; cap how long a catalog page waits on them.
            // Whatever misses the budget keeps running in `scope` and lands in the cache for later.
            withTimeoutOrNull(RESOLVE_BUDGET_MS) { lookups.forEach { it.await() } }
        }
        val keys = queries.mapTo(mutableSetOf()) { it.cacheKey }
        return mutex.withLock {
            buildMap {
                keys.forEach { key -> cache[key]?.let { put(key, it) } }
            }
        }
    }

    /** Single-flight per parsed title: one TMDB search even when 40 episode files share a show. */
    private suspend fun lookupAsync(query: FilenameQuery): Deferred<ResolvedTitle?> = mutex.withLock {
        inFlight.getOrPut(query.cacheKey) {
            scope.async {
                try {
                    gate.withPermit { lookup(query) }
                } finally {
                    withContext(NonCancellable) {
                        mutex.withLock { inFlight.remove(query.cacheKey) }
                    }
                }
            }
        }
    }

    private suspend fun lookup(query: FilenameQuery): ResolvedTitle? {
        val best = search(query.title, query)
            ?: query.alternateTitle?.let { alternate -> search(alternate, query) }
        val resolved = best?.toResolvedTitle()
        mutex.withLock {
            cache[query.cacheKey] = resolved
            while (cache.size > MAX_CACHE_ENTRIES) {
                cache.remove(cache.keys.first())
            }
        }
        return resolved
    }

    private suspend fun search(title: String, query: FilenameQuery): TmdbSearchResult? {
        // The year is scored, never sent: TMDB's primary_release_year is a hard filter, and a
        // release name's year is off by one often enough (festival vs wide release, a late-December
        // air date) that filtering on it turns a good match into no match at all.
        val results = runCatching { TmdbService.searchTitles(title, query.mediaType) }
            .getOrDefault(emptyList())
        // fuzzy: a release name is a lossy spelling of a title, and the cost of a slightly-wrong
        // poster on a row that currently reads as a filename is much lower than showing nothing.
        return pickBestTmdbMatch(title = title, year = query.year, results = results, fuzzy = true)
    }

    private suspend fun TmdbSearchResult.toResolvedTitle(): ResolvedTitle {
        val settings = TmdbSettingsRepository.snapshot()
        val tmdbPoster = TmdbService.tmdbImageUrl(posterPath)
        val custom = customPosterUrl(settings, imdbId = imdbIdForPosterTemplate(settings), tmdbId = id.toString(), type = posterType())
        return ResolvedTitle(
            title = displayTitle,
            year = year,
            poster = custom ?: tmdbPoster,
            // The custom service may not have art for everything; the plain TMDB poster backs it up.
            posterFallback = tmdbPoster.takeIf { custom != null },
            backdrop = TmdbService.tmdbImageUrl(backdropPath, size = "w1280"),
            overview = overview?.takeIf { it.isNotBlank() },
        )
    }

    /** Only worth a /find round-trip when the user's template actually has an `{imdb_id}` slot. */
    private suspend fun TmdbSearchResult.imdbIdForPosterTemplate(settings: TmdbSettings): String? {
        if (!settings.customPosterTemplateNeedsImdbId()) return null
        return runCatching { TmdbService.tmdbToImdb(tmdbId = id, mediaType = if (isTv) "tv" else "movie") }
            .getOrNull()
    }

    private fun TmdbSearchResult.posterType(): String = if (isTv) "series" else "movie"

    private fun MetaPreview.withResolvedMeta(query: FilenameQuery, match: ResolvedTitle): MetaPreview =
        copy(
            name = query.displayName(match.title),
            poster = match.poster ?: poster,
            // Whatever we displaced becomes the fallback, so a custom poster service with a gap in
            // its coverage falls back to plain TMDB, and a TMDB miss falls back to the addon image.
            posterFallback = when {
                match.poster == null -> posterFallback
                else -> match.posterFallback ?: poster ?: posterFallback
            },
            // A TMDB poster is portrait. These catalogs often declare landscape/square shapes for
            // what were file thumbnails, and leaving that in place crops the poster in half.
            posterShape = if (match.poster != null) PosterShape.Poster else posterShape,
            banner = banner ?: match.backdrop,
            description = description?.takeIf { it.isNotBlank() } ?: match.overview,
            releaseInfo = releaseInfo?.takeIf { it.isNotBlank() } ?: match.year?.toString(),
        )

    // --- Parsing / detection (pure, unit-tested) ---

    private val videoExtensionRegex =
        Regex("""(?i)\.(mkv|mp4|m4v|avi|mov|ts|m2ts|wmv|flv|webm|mpg|mpeg|iso)$""")

    // Resolution / source / codec / audio tokens. Only unambiguous release vocabulary — words a
    // real title might contain (proper, multi, extended…) are left out so we never rewrite a row
    // that was fine to begin with.
    private val releaseTokenRegex = Regex(
        "(?i)(?<![a-z0-9])(" +
            "480p|540p|576p|720p|1080p|1440p|2160p|4320p|uhd|hdr10\\+?|hdr|dolby[ ._-]?vision|dovi|" +
            "remux|blu-?ray|bd-?rip|br-?rip|bd-?remux|web-?dl|web-?rip|webdl|hdtv|dvd-?rip|" +
            "x26[45]|h[ .]?26[45]|hevc|xvid|divx|10bit|8bit|" +
            "ddp?[257](?:[ .][01])?|dts(?:-?hd)?|true-?hd|atmos|aac2[ .]0|ac-?3|hdcam" +
            ")(?![a-z0-9])",
    )

    private val seasonEpisodeMarkerRegex =
        Regex("""(?i)(?<![a-z0-9])(s\d{1,2}[ ._-]?e\d{1,3}|\d{1,2}x\d{2,3})(?![0-9])""")

    // `Show.Name.S01E02` / `Show_Name_S01E02` — separators where spaces belong is itself a filename
    // tell, and paired with an episode marker it is enough on its own.
    private val separatorNameRegex = Regex("""^\S+[._]\S*[._]\S+$""")

    /**
     * True when a catalog item's name reads as a release filename rather than a title. Requires an
     * unambiguous signal — a video extension, release vocabulary, or an episode marker in a
     * dot/underscore-separated name — so ordinary catalogs short-circuit with no work at all.
     */
    internal fun looksLikeReleaseFilename(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.length < 4) return false
        if (videoExtensionRegex.containsMatchIn(trimmed)) return true
        if (releaseTokenRegex.containsMatchIn(trimmed)) return true
        // Archive-style dumps carry no quality tags at all — `friends-1994-2004-full-series_20250419`.
        // A whole-run year range or a "complete series" tag is just as unambiguous.
        if (yearRangeRegex.containsMatchIn(trimmed) || completeRunRegex.containsMatchIn(trimmed)) return true
        return seasonEpisodeMarkerRegex.containsMatchIn(trimmed) && separatorNameRegex.matches(trimmed)
    }

    // Tracker/site stamps glued to the front of a torrent name (`www.UIndex.org   -   Show S01E01`).
    // Left in place they dominate the title and every match fails.
    private val sitePrefixRegex = Regex(
        """(?i)^\s*(?:www\.)?[a-z0-9][a-z0-9-]*\.(?:org|com|net|to|me|info|io|nl|se|cc|tv|eu|it|ws|pw|xyz|club|la)\s*[-–:]+\s*""",
    )

    // A season pack (`From.S01.2022…`, `Show.Seasons.1-3`, `Show Season 2`) with no episode marker.
    // The season token has to come off the title — searching TMDB for "From S01" matches nothing.
    private val seasonPackRegex = Regex(
        """(?i)(?<![a-z0-9])(?:seasons?|series)[ ._-]*(\d{1,2})(?:[ ._]*-[ ._]*(?:s(?:eason)?)?\d{1,2})?(?![0-9])|(?<![a-z0-9])s(\d{1,2})(?:[ ._]*-[ ._]*s?\d{1,2})?(?![0-9ep])""",
    )

    /**
     * Splits a release name into what TMDB search needs. [catalogType] is the addon's own type for
     * the row; when it is neither movie nor series (cloud libraries often use `other`) the season or
     * episode markers decide, and failing that both TMDB endpoints are searched.
     */
    internal fun parseFilenameQuery(name: String, catalogType: String): FilenameQuery? {
        val raw = sitePrefixRegex.replace(name.trim(), "").trim().ifBlank { name.trim() }
        val episode = FilenameParser.parseEpisode(raw)
        val hasEpisodeCoordinates = episode.season != null && episode.episode != null
        // A run range (`friends-1994-2004-full-series`) is the show's whole life, not a release year.
        // FilenameParser reads the *last* year, which is right for a movie and wrong here, so the
        // range is handled first: the title ends where the range starts and the start year is ours.
        val runRange = if (hasEpisodeCoordinates) null else yearRangeRegex.find(raw)
        val rangeStartYear = runRange?.groupValues?.get(1)?.toIntOrNull()
        val beforeRange = runRange?.let { raw.substring(0, it.range.first) }
        // Season packs carry no episode, so parseEpisode gives nothing to cut the title at.
        val seasonPack = if (hasEpisodeCoordinates) null else seasonPackRegex.find(beforeRange ?: raw)
        val packSeason = seasonPack
            ?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }
            ?.toIntOrNull()
            // A multi-season pack has no single season to display.
            ?.takeIf { !seasonPack.value.contains('-') }

        val titleSource = when {
            !episode.showTitle.isNullOrBlank() -> episode.showTitle
            seasonPack != null -> (beforeRange ?: raw).substring(0, seasonPack.range.first)
            beforeRange != null -> beforeRange
            else -> raw
        }
        val parsed = FilenameParser.parseTitle(titleSource)
        val title = parsed.title.stripTrailingNonTitleTokens()
        // A name that cleans down to nothing can't be searched sensibly.
        if (title.length < 2) return null
        if (title.none { it.isLetter() }) {
            // Numbers can be titles — 1917, 300, 9-1-1 — but a long digit run is a date stamp and a
            // one or two digit leftover (a stray `5 1` from `DTS-HD.MA.5.1`) is noise.
            val digits = title.count { it.isDigit() }
            if (digits < 3) return null
            if (title.all { it.isDigit() } && title.length > 4) return null
        }

        val mediaType = when {
            catalogType.equals("movie", ignoreCase = true) -> "movie"
            catalogType.equals("series", ignoreCase = true) ||
                catalogType.equals("tv", ignoreCase = true) ||
                catalogType.equals("anime", ignoreCase = true) -> "tv"
            hasEpisodeCoordinates || seasonPack != null -> "tv"
            // A whole-run year range or a "full series" tag only ever describes a show.
            runRange != null || completeRunRegex.containsMatchIn(raw) -> "tv"
            else -> null
        }

        return FilenameQuery(
            title = title,
            // Cutting the title at a season marker also cuts off the year behind it
            // (`From.S01.2022.WEB-DL`), so fall back to the year anywhere in the full name.
            year = rangeStartYear ?: parsed.year ?: FilenameParser.parseTitle(raw).year,
            mediaType = mediaType,
            season = if (hasEpisodeCoordinates) episode.season else packSeason,
            episode = episode.episode.takeIf { hasEpisodeCoordinates },
            // Foreign releases often read `International Title - Local Title (year) […]`, which
            // scores far too low as one string. Kept as a second attempt, tried only on a miss.
            alternateTitle = titleSource.alternateTitleBeforeDash(title),
        )
    }

    // A year range covering a show's run: `friends-1994-2004`, `Show (2011 - 2019)`.
    private val yearRangeRegex =
        Regex("""(?<![0-9])(19\d{2}|20\d{2})[ ._]*-[ ._]*(19\d{2}|20\d{2})(?![0-9])""")

    private val completeRunRegex = Regex("""(?i)(?:full|complete)[ ._-]*(?:series|seasons?|collection|run)""")

    // Tokens that trail a title without being part of it. FilenameParser's own junk list stops the
    // title at the first quality tag; these are what can survive in front of one — a stray year or
    // archive date stamp, a language stamp, a "complete series" tag.
    private val trailingNonTitleTokenRegex = Regex(
        "(?i)^(" +
            "19\\d{2}|20\\d{2}|\\d{6,8}|" +
            "full|complete|series|seasons?|collection|run|" +
            "spanish|castellano|latino|french|truefrench|vostfr|german|italian|portuguese|" +
            "japanese|korean|chinese|russian|hindi|dutch|polish|swedish|danish|norwegian|" +
            "finnish|czech|turkish|arabic|thai|subbed|dubbed|sub|dub" +
            ")$",
    )

    /**
     * Drops trailing tokens that describe the release rather than the title. Never strips the whole
     * thing: a title that is only a year or a number ("1917", "2012") has to survive.
     */
    private fun String.stripTrailingNonTitleTokens(): String {
        val tokens = trim().split(' ').filter { it.isNotBlank() }.toMutableList()
        while (tokens.size > 1 && trailingNonTitleTokenRegex.matches(tokens.last())) {
            tokens.removeAt(tokens.lastIndex)
        }
        return tokens.joinToString(" ")
    }

    /** The part before a spaced dash, when that is a plausible shorter title than [primary]. */
    private fun String.alternateTitleBeforeDash(primary: String): String? {
        val prefix = substringBefore(" - ", missingDelimiterValue = "")
            .let(FilenameParser::cleanTitle)
            .trim()
        if (prefix.length < 2 || prefix.none { it.isLetter() }) return null
        return prefix.takeIf { !it.equals(primary, ignoreCase = true) && it.length < primary.length }
    }

    private const val MAX_PARALLEL_LOOKUPS = 6
    private const val RESOLVE_BUDGET_MS = 4_000L
    private const val MAX_CACHE_ENTRIES = 2_000
}

/** What a resolved release name turned into, for callers that don't hold a [MetaPreview]. */
internal data class ResolvedName(
    val displayName: String,
    val poster: String?,
    val posterFallback: String?,
    val backdrop: String?,
    val year: Int?,
    val overview: String?,
)

internal data class FilenameQuery(
    val title: String,
    val year: Int?,
    /** "movie", "tv", or null to search both. */
    val mediaType: String?,
    val season: Int?,
    val episode: Int?,
    /** Shorter title to retry with when [title] finds nothing (foreign `Intl - Local` releases). */
    val alternateTitle: String? = null,
) {
    val cacheKey: String
        get() = "${mediaType ?: "any"}|${title.lowercase()}|${year ?: ""}"

    /**
     * Keeps the season/episode coordinates the filename carried: a cloud library lists one row per
     * file or pack, so several entries for one show would otherwise render as identical cards.
     */
    fun displayName(resolvedTitle: String): String {
        if (season == null) return resolvedTitle
        val s = season.toString().padStart(2, '0')
        val e = episode?.toString()?.padStart(2, '0') ?: return "$resolvedTitle S$s"
        return "$resolvedTitle S${s}E$e"
    }
}
