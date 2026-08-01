package com.nuvio.app.features.streams

import co.touchlab.kermit.Logger
import com.nuvio.app.features.debrid.DebridStreamAudioChannel
import com.nuvio.app.features.debrid.DebridStreamAudioTag
import com.nuvio.app.features.debrid.DebridStreamEncode
import com.nuvio.app.features.debrid.DebridStreamLanguage
import com.nuvio.app.features.debrid.DebridStreamQuality
import com.nuvio.app.features.debrid.DebridStreamResolution
import com.nuvio.app.features.debrid.DebridStreamVisualTag

/**
 * Everything the app can work out about a release from its metadata: resolution, source quality,
 * HDR/audio tags, codec, languages, release group and size.
 *
 * Preference-free by design — this says what a stream *is*, never whether it is wanted. Ranking
 * against debrid preferences lives in `DebridStreamFacts`; scoring lives in [StreamScorer].
 *
 * The enum types still live in the debrid package because they are persisted in debrid settings and
 * referenced by its settings UI; only the detection moved here. They are not debrid-specific in
 * meaning, and [StreamTraitDetector] works on any [StreamItem] from any addon.
 */
data class StreamTraits(
    val resolution: DebridStreamResolution,
    val quality: DebridStreamQuality,
    val visualTags: List<DebridStreamVisualTag>,
    val audioTags: List<DebridStreamAudioTag>,
    val audioChannels: List<DebridStreamAudioChannel>,
    val encode: DebridStreamEncode,
    val languages: List<DebridStreamLanguage>,
    val releaseGroup: String,
    val size: Long?,
    /** Playback length in seconds when the addon supplied one — see [StreamTraitDetector.durationSeconds]. */
    val durationSeconds: Long?,
    /** Confirmed present on the user's debrid service, so playback starts without a download wait. */
    val isDebridCached: Boolean,
    /**
     * Reputation flags resolved against the whole release name, not just [releaseGroup] — groups
     * frequently appear mid-string where the trailing-token parse cannot see them.
     */
    val isTrustedGroup: Boolean,
    val isLowQualityGroup: Boolean,
    /**
     * TRaSH tier of the matched group (1 = best), or null when unknown. Never surfaced in the UI —
     * it silently weights the "trusted group" score so a T1 remux group beats a T5 one.
     */
    val groupTier: Int?,
    /**
     * AI-upscaled video or AI-generated HDR/DV. Distinct from a low-quality *group*: this is detected
     * from the release name's own AI/upscale/regrade markers, so an AI upscale from any group is
     * caught even when the group itself is unknown.
     */
    val isAiEnhanced: Boolean = false,
    /** An untouched full disc image (ISO / BDMV / BD25-100 / "Full BluRay"). */
    val isFullDisc: Boolean = false,
    /** A whole-season release rather than a single episode. Only meaningful for episodes. */
    val isSeasonPack: Boolean = false,
    /**
     * Size of the whole torrent/folder behind this row, as opposed to [size] which is the one file
     * it resolved to. Null when nothing published it — plenty of rows carry only a file size, and
     * inventing one from the file would defeat the point of showing it.
     */
    val packSizeBytes: Long? = null,
    /**
     * Seasons the pack covers, when its name says so. Empty means "unknown, assume any" — a
     * "Complete Series" or a bare "Season Pack" label names no season, and neither does a
     * non-pack. Callers that care about relevance (is this pack the season I am browsing?) must
     * treat empty as a match rather than a miss.
     */
    val packSeasons: Set<Int> = emptySet(),
    /** A corrected re-release: Repack, Proper or Rerip (including numbered variants). */
    val isRepackOrProper: Boolean = false,
    /** A hybrid release that combines multiple sources. */
    val isHybrid: Boolean = false,
    /** An alternate cut or edition: Extended, Director's, Theatrical, Unrated, IMAX, Collector's… */
    val isSpecialEdition: Boolean = false,
    /** Bonus features / extras (featurettes, deleted scenes, bonus clips) rather than the feature. */
    val isExtras: Boolean = false,
)

/**
 * True when the season-pack route is on offer for this stream — the single predicate behind both
 * the right-click action and the card's pack indicator, so the badge can never promise an action
 * the menu will not show (or stay silent about one it does).
 *
 * [isEpisodeView] is the hard precondition: a movie has no seasons, so nothing about it can be a
 * season pack however the release is named or sized. The structural detectors read file counts and
 * container-vs-file sizes, which a movie in a box-set torrent — or one shipped beside its extras —
 * satisfies happily, so without this gate a movie row really does offer to pick episodes out of a
 * pack. Detection is left alone; it is the *offer* that is nonsense off a series.
 *
 * [browsedSeason] is the season the list is being viewed for, or null when the episode has no
 * franchise season (an anime entry addressed by a kitsu-style id numbers straight through).
 *
 * The torrent-identity half is deliberately optimistic: a direct-debrid row exposes the pack's
 * infohash only once resolved, so a candidate that *might* resolve to one counts. The click path
 * resolves for real and reports a specific failure rather than silently doing nothing.
 */
internal fun StreamItem.offersSeasonPackRoute(
    traits: StreamTraits,
    browsedSeason: Int?,
    isEpisodeView: Boolean,
): Boolean = isEpisodeView &&
    traits.isSeasonPack &&
    traits.coversSeason(browsedSeason) &&
    canInspectSource

/**
 * True when the source behind this row could be opened on the debrid provider to read its contents.
 *
 * Pack *detection* decides how confidently the action is labelled, never whether it is offered:
 * every detector here works from names and addon metadata, so there will always be a torrent it
 * reads wrong. Opening the source is the ground truth, and it has to stay reachable on rows the
 * heuristics missed — otherwise a detection gap becomes a dead end instead of an extra click.
 */
internal val StreamItem.canInspectSource: Boolean
    get() = resolvedSourceInfoHash != null || isAddonDebridCandidate || torrentMagnetUri != null

/**
 * Whether a pack is relevant to [season]. A pack that names no season at all — a bare "Season Pack"
 * label, a complete series — is assumed to cover it; only a pack that explicitly names *other*
 * seasons is ruled out.
 */
internal fun StreamTraits.coversSeason(season: Int?): Boolean =
    season == null || packSeasons.isEmpty() || season in packSeasons

/**
 * Derives [StreamTraits] from a stream.
 *
 * Dual-path on purpose: structured fields from `clientResolve.stream.raw.parsed` are trusted first
 * (addons that speak the AIOStreams shape provide them), and everything falls back to token matching
 * over the stream's visible text so releases from plain addons are classified too.
 */
object StreamTraitDetector {

    private val traitLog = Logger.withTag("StreamTraits")

    fun detect(stream: StreamItem): StreamTraits {
        val parsed = stream.clientResolve?.stream?.raw?.parsed
        val searchText = streamSearchText(stream)
        val group = parsed?.group?.takeIf { it.isNotBlank() } ?: releaseGroupFromText(searchText)
        // The parsed group is the high-confidence signal; the text scan catches groups sitting
        // mid-name where the trailing-token parse cannot see them.
        val tier = StreamReleaseGroups.tierOf(group) ?: StreamReleaseGroups.bestTierInText(searchText)
        val packEvidence = detectSeasonPack(stream)
        return StreamTraits(
            resolution = streamResolution(parsed?.resolution, parsed?.quality, searchText),
            quality = streamQuality(parsed?.quality, searchText),
            visualTags = streamVisualTags(parsed?.hdr.orEmpty(), searchText),
            audioTags = streamAudioTags(parsed?.audio.orEmpty(), searchText),
            audioChannels = streamAudioChannels(parsed?.channels.orEmpty(), searchText),
            encode = streamEncode(parsed?.codec, searchText),
            languages = parsed?.languages.orEmpty().mapNotNull { languageFor(it) }.ifEmpty {
                DebridStreamLanguage.entries.filter { searchText.hasToken(it.code) }
            },
            releaseGroup = group,
            size = streamSize(stream),
            durationSeconds = durationSeconds(parsed?.duration),
            isDebridCached = isDebridCached(stream),
            isTrustedGroup = tier != null,
            isLowQualityGroup = tier == null && (
                StreamReleaseGroups.isLowQuality(group) ||
                    StreamReleaseGroups.textMentionsLowQuality(searchText)
                ),
            groupTier = tier,
            isAiEnhanced = AI_ENHANCED.containsMatchIn(searchText),
            isFullDisc = FULL_DISC.containsMatchIn(searchText),
            isSeasonPack = packEvidence.isPack,
            packSeasons = packEvidence.seasons,
            packSizeBytes = containerSizeBytes(stream),
            isRepackOrProper = REPACK_PROPER.containsMatchIn(searchText),
            isHybrid = HYBRID.containsMatchIn(searchText),
            isSpecialEdition = SPECIAL_EDITION.containsMatchIn(searchText),
            isExtras = EXTRAS_BONUS.containsMatchIn(searchText),
        )
    }

    // Detection patterns for the release-flag traits. All run over the lower-cased [streamSearchText].

    /**
     * AI upscales / AI-generated HDR-DV / regrades. Bare "AI" is deliberately *not* matched — it is a
     * common substring — so an AI marker must be followed by an upscale/enhance/remaster/HDR keyword,
     * or one of the unambiguous standalone tokens (upscaled, regraded, uprez).
     */
    private val AI_ENHANCED = Regex(
        "(^|[^a-z0-9])(" +
            "ai[ ._-]?(up[ ._-]?scal(e|ed|ing)|enhanced?|remaster(ed)?|hdr|dv|dovi|dolby[ ._-]?vision)" +
            "|up[ ._-]?scaled?" +
            "|(up[ ._-]?scale[ ._-]?)?re[ ._-]?graded?" +
            "|uprez" +
            ")([^a-z0-9]|\$)",
    )

    private val FULL_DISC = Regex(
        "(^|[^a-z0-9])(" +
            "br[ ._-]?disk|bd(25|50|66|100)|full[ ._-]?blu[ ._-]?ray|complete[ ._-]?blu[ ._-]?ray|bdmv" +
            "|(blu[ ._-]?ray|uhd)[ ._-]?iso" +
            ")([^a-z0-9]|\$)|\\.iso([^a-z0-9]|\$)",
    )

    private val REPACK_PROPER = Regex("(^|[^a-z0-9])(repack|proper|rerip)[0-9]?([^a-z0-9]|\$)")

    private val HYBRID = Regex("(^|[^a-z0-9])hybrid([^a-z0-9]|\$)")

    private val SPECIAL_EDITION = Regex(
        "(^|[^a-z0-9])(" +
            "extended|director'?s[ ._-]?cut|theatrical|unrated|uncut|uncensored|redux|final[ ._-]?cut" +
            "|collector'?s|ultimate[ ._-]?edition|special[ ._-]?edition|imax[ ._-]?edition" +
            "|anniversary[ ._-]?edition|diamond[ ._-]?edition|criterion" +
            ")([^a-z0-9]|\$)",
    )

    private val EXTRAS_BONUS = Regex(
        "(^|[^a-z0-9])(extras?|bonus|featurettes?|extended[ ._-]?clip|deleted[ ._-]?scenes?)([^a-z0-9]|\$)",
    )

    /**
     * "Season" as a noun, across the languages that show up in release names. Longer forms come
     * first so an alternation cannot match a shorter prefix and strand the rest of the word
     * ("sezonul" must not match as "sezon" + "ul").
     */
    private const val SEASON_NOUNS =
        "seasons|season|temporadas|temporada|stagioni|stagione|saisons|saison|staffeln|staffel" +
            "|seizoenen|seizoen|sezonul|sezony|sezon|säsong|sasong|sesong|sæson|sezona|sezone"

    /** "Complete" as an adjective, in the same languages. Prefixes last, for the reason above. */
    private const val COMPLETE_WORDS =
        "completas|completos|completes|completa|completo|complete|complets|complet" +
            "|complètes|complète|kompletne|kompletna|kompletny|komplette|komplett|komplet" +
            "|volledige|volledig|compleet|intégrale|integrale|integral"

    /** Nouns that pair with [COMPLETE_WORDS] to mean a whole run, beyond the season nouns. */
    private const val SERIES_NOUNS = "series|serien|serie|séries|série|serial|serials"

    // Season-pack markers, and the single-episode marker that vetoes only inferred packs.
    /**
     * An outright declaration, in either word order — English puts the adjective first ("Complete
     * Season"), the Romance languages put it second ("Temporada Completa", "Serie Completa",
     * "Stagione Completa"), and German/Dutch/Polish go back to first ("Komplette Staffel").
     * Matching the pair rather than the adjective alone keeps "integral" or "complete" in a title
     * from declaring a pack on its own.
     */
    private val EXPLICIT_SEASON_PACK = Regex(
        "(^|[^a-z0-9])(" +
            "season[ ._-]?pack" +
            "|(?:$COMPLETE_WORDS)[ ._-]?(?:$SEASON_NOUNS|$SERIES_NOUNS)" +
            "|(?:$SEASON_NOUNS|$SERIES_NOUNS)[ ._-]?(?:$COMPLETE_WORDS)" +
            ")([^a-z0-9]|\$)",
    )
    private val SEASON_RANGE = Regex("(^|[^a-z0-9])s(\\d{1,2})[ ._-]?s(\\d{1,2})([^a-z0-9]|\$)")
    /**
     * "S01-03" — a tight hyphen with no spaces reads as a season range in scene naming, while the
     * spaced and dotted forms of the same shape mean an episode (see [SINGLE_EPISODE]). The two are
     * genuinely ambiguous in the abstract; the separator is the only signal that distinguishes them.
     */
    private val SEASON_NUMBER_RANGE = Regex("(^|[^a-z0-9])s(\\d{1,2})-(\\d{1,2})([^a-z0-9]|\$)")
    // The leading boundary is [^a-z] (not [^a-z0-9]) so the "E" in "S01E01-E12" — preceded by the
    // season's digit — still anchors, while a mid-word "…e01e02…" cannot.
    private val EPISODE_RANGE = Regex("(^|[^a-z])e\\d{1,3}[ ._-]?e\\d{1,3}([^a-z0-9]|\$)")
    /**
     * A season named in words — "Season 2", "Temporada 2", "Staffel 3" — or a whole-run phrase that
     * names no number. The number stays capture group 3; every added alternation is non-capturing so
     * the group index does not shift.
     */
    private val SEASON_WORD = Regex(
        "(^|[^a-z0-9])(" +
            "(?:$SEASON_NOUNS)[ ._-]?(\\d{1,2})" +
            "|the[ ._-]?complete" +
            ")([^a-z0-9]|\$)",
    )
    private val SEASON_TOKEN = Regex("(^|[^a-z0-9])s(\\d{1,2})([^a-z0-9]|\$)")
    /**
     * A season token bound to an episode number. Covers S01E05 and S01.E05, plus the separator
     * forms that carry no "E" at all — "S01.05", "S01 05", "S01 - 05" — which are common in anime
     * and softsub releases and used to read as bare season tokens, i.e. as packs.
     */
    private val SINGLE_EPISODE = Regex(
        "(^|[^a-z0-9])s\\d{1,2}(" +
            "[ ._-]?e\\d{1,3}" +
            "|[ ._]\\d{1,3}" +
            "| - \\d{1,3}" +
            ")([^a-z0-9]|\$)",
    )
    /** The season half of an S01E05 marker, for naming the season a pack covers. */
    private val SEASON_OF_EPISODE = Regex("(^|[^a-z0-9])s(\\d{1,2})[ ._-]?e\\d{1,3}")

    /** Seasons a pack covers, and whether it is a pack at all. */
    private data class PackEvidence(val isPack: Boolean, val seasons: Set<Int>)

    /**
     * How much bigger than the selected file the containing folder must be before the remainder is
     * read as more episodes rather than as subtitles, samples and artwork. Doubling is the smallest
     * ratio that can only be explained by a second file of comparable size.
     */
    private const val PACK_FOLDER_SIZE_RATIO = 2L

    /**
     * Is this a pack? Name evidence OR structural evidence — the two are independent and either is
     * sufficient, because each catches what the other cannot.
     */
    private fun detectSeasonPack(stream: StreamItem): PackEvidence {
        val lines = packScopeLines(stream)
        val byName = detectSeasonPackByName(lines)
        val byStructure = detectSeasonPackByStructure(stream, lines)
        val isPack = byName.isPack || byStructure.isPack
        if (!isPack) return PackEvidence(false, emptySet())
        return PackEvidence(true, byName.seasons + byStructure.seasons)
    }

    /**
     * Pack evidence stated as data rather than as a name — the AIOStreams-shaped
     * `clientResolve.stream.raw` fields, plus the magnet's own `xl`. Worth more than the name
     * heuristics where it exists, and it catches the case no name can: a torrent whose selected file
     * is honestly called S01E01 and which really does contain the rest of the season alongside it.
     *
     * Checked in order of authority: the producer's own verdict, then the folder's parse, then the
     * tells below. Only the last group is inference.
     *
     * Three independent tells:
     * - **Container size vs file size.** The chosen file against everything in the torrent. A
     *   container at least [PACK_FOLDER_SIZE_RATIO]× the file holds more than subtitles. Read from
     *   structured fields where they exist and from the addon's printed sizes where they do not —
     *   StremThru/Torz rows carry no `raw.folderSize` at all and show "💾 12 GB 📦 158 GB" as plain
     *   description text, so the structured path alone misses them entirely.
     * - **A season named with no episode.** `parsed` resolved seasons but no episode number, i.e.
     *   the release addresses a whole season.
     * - **More than one episode parsed.** A file spanning E01-E02 is already not a single episode.
     *
     * Deliberately *not* vetoed by a single-episode file name, for the same reason an explicit
     * "Season Pack" label is not: the file name describes one file, these fields describe the
     * torrent behind it, and the pack route opens the torrent.
     */
    private fun detectSeasonPackByStructure(stream: StreamItem, lines: List<String>): PackEvidence {
        val raw = stream.clientResolve?.stream?.raw
        val parsed = raw?.parsed
        val seasons = parsed?.seasons.orEmpty().toSet() + parsed?.folderSeasons.orEmpty()
        val episodes = parsed?.episodes.orEmpty()

        // Counted from the provider's own file listing, so this is not a reading of a name at all —
        // a series torrent holding more than one video file is a pack, whatever it is called.
        stream.sourceVideoFileCount?.let { videoFiles ->
            if (videoFiles > 1) return PackEvidence(true, seasons)
        }

        // The producer already answered this — see StreamClientResolveParsed.seasonPack. Taken as
        // given rather than re-derived: it was computed with the folder listing in hand, which is
        // strictly more than any name here can show.
        if (parsed?.seasonPack == true) return PackEvidence(true, seasons)

        // The folder naming a season with no episode is a pack outright, whatever the file is
        // called. Mirrors how AIOStreams derives the flag above, for producers that publish the
        // folder's parse but not the verdict.
        val folderNamesSeasonOnly = parsed?.folderSeasons.orEmpty().isNotEmpty() &&
            parsed?.folderEpisodes.orEmpty().isEmpty()
        if (folderNamesSeasonOnly) return PackEvidence(true, seasons)

        val fileSize = raw?.size ?: stream.behaviorHints.videoSize
        // The provider's own measurement of the torrent, the addon's folder size, or the magnet's
        // own `xl` — all three describe everything in the torrent, against a file size that
        // describes one entry in it.
        val containerSize = stream.sourceTotalSizeBytes
            ?: raw?.folderSize
            ?: stream.torrentTotalSizeBytes
        val containerHoldsMoreThanTheFile = if (fileSize != null && containerSize != null && fileSize > 0L) {
            containerSize >= fileSize * PACK_FOLDER_SIZE_RATIO
        } else {
            // Nothing structured to compare, so fall back to the sizes the addon printed.
            textSizeSpread(lines)
                ?.let { (smallest, largest) -> largest >= smallest * PACK_FOLDER_SIZE_RATIO }
                ?: false
        }

        val isPack = containerHoldsMoreThanTheFile ||
            (seasons.isNotEmpty() && episodes.isEmpty()) ||
            episodes.size > 1
        return PackEvidence(isPack, if (isPack) seasons else emptySet())
    }

    /**
     * How big the container behind this row is — the torrent/folder, not the file inside it.
     *
     * Same sources [detectSeasonPackByStructure] weighs, in the same order of authority: the debrid
     * provider's measurement of the torrent it actually holds, the addon's structured `folderSize`,
     * the magnet's own `xl`, and finally the largest size the addon printed in its description. That
     * last one is only trusted when the addon printed more than one size and they differ ("💾 12 GB
     * 📦 158 GB") — a single printed size is the file's, and reading it as the folder's would put a
     * misleading number under the pack icon on every ordinary row.
     *
     * The provider figure leads because it is the only one on already-resolved rows: an addon that
     * picked a file out of a pack publishes that file's size and stops, so the structured and text
     * paths both come up empty there and the icon would show with no number at all.
     */
    private fun containerSizeBytes(stream: StreamItem): Long? {
        val raw = stream.clientResolve?.stream?.raw
        stream.sourceTotalSizeBytes?.takeIf { it > 0L }?.let { return it }
        raw?.folderSize?.takeIf { it > 0L }?.let { return it }
        stream.torrentTotalSizeBytes?.takeIf { it > 0L }?.let { return it }
        return textSizeSpread(packScopeLines(stream))?.second
    }

    private val SIZE_UNIT_BYTES = mapOf(
        "b" to 1L,
        "kb" to 1_024L, "kib" to 1_024L,
        "mb" to 1_048_576L, "mib" to 1_048_576L,
        "gb" to 1_073_741_824L, "gib" to 1_073_741_824L,
        "tb" to 1_099_511_627_776L, "tib" to 1_099_511_627_776L,
    )

    /**
     * A size written in text. The negative lookahead drops transfer rates — "5.0 MB/s" is a speed,
     * not a size — which is the only token in a typical description that would otherwise be read as
     * one.
     */
    private val SIZE_TOKEN = Regex(
        "(\\d+(?:[.,]\\d+)?)\\s*(b|kb|kib|mb|mib|gb|gib|tb|tib)(?![a-z0-9])(?!\\s*/)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Smallest and largest size the addon printed, when it printed more than one.
     *
     * Addons that speak no structured dialect still routinely show both — "💾 12 GB 📦 158 GB" is
     * the selected file and the folder holding it — and on those rows this text is the *only* place
     * that fact exists. Two sizes an order of magnitude apart mean the file is one of many, exactly
     * as a structured `folderSize` would.
     *
     * Only the spread matters, so the decimal-vs-binary reading of "GB" is irrelevant as long as it
     * is applied consistently.
     */
    private fun textSizeSpread(lines: List<String>): Pair<Long, Long>? {
        val sizes = lines.asSequence()
            .flatMap { SIZE_TOKEN.findAll(it) }
            .mapNotNull { match ->
                val value = match.groupValues[1].replace(',', '.').toDoubleOrNull()
                val unit = SIZE_UNIT_BYTES[match.groupValues[2].lowercase()]
                if (value == null || unit == null) return@mapNotNull null
                (value * unit).toLong().takeIf { it > 0L }
            }
            .toList()
        if (sizes.size < 2) return null
        val smallest = sizes.min()
        val largest = sizes.max()
        return if (largest > smallest) smallest to largest else null
    }

    /**
     * The name-ish fields that describe the *source*, split into individual lines and with the
     * per-file fields deliberately excluded.
     *
     * Scoping matters more than the patterns do. Detection used to run over one blob pooling every
     * field ([streamSearchText]), so a pack whose addon had resolved one episode out of it carried
     * both "Show.S01" and "Show.S01E03.mkv" — and the single-episode veto, matching anywhere, threw
     * the pack away. The same torrent was then classified differently depending only on whether a
     * file had been resolved yet. A resolved file name says nothing about what its torrent holds, so
     * it does not participate; a multi-line addon title is evaluated one line at a time so a torrent
     * name and the file name below it cannot contaminate each other.
     */
    private fun packScopeLines(stream: StreamItem): List<String> {
        val resolve = stream.clientResolve
        val raw = resolve?.stream?.raw
        return listOfNotNull(
            resolve?.torrentName,
            raw?.torrentName,
            // Looked up from the debrid provider for already-resolved rows, which carry no torrent
            // name of their own — see LocalDebridAvailabilityService.annotateTorrentNames.
            stream.resolvedTorrentName,
            // The magnet's own `dn`. On rows that label themselves with the selected file this is
            // the only field naming the torrent, and it is where an unqualified "S01" shows up.
            stream.torrentDisplayName,
            stream.name,
            stream.title,
            stream.description,
            stream.debridCacheStatus?.cachedName,
        ).flatMap { it.split('\n') }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
    }

    /**
     * A line is a pack when it declares one outright ("Season Pack" / "Complete Season"), spans a
     * season range (S01-S03, S01-03), spans an episode range (E01-E12), or names a season in words
     * ("Season 2" / "Complete Series"). Failing all of those, a bare season token (S01) infers one,
     * but only on a line that carries no single-episode marker of its own.
     *
     * Any one line is enough — an addon that labels the row "Season Pack" in its description is
     * authoritative even when the file it resolved is named S01E01.
     */
    private fun detectSeasonPackByName(lines: List<String>): PackEvidence {
        var isPack = false
        val seasons = mutableSetOf<Int>()
        for (line in lines) {
            var linePack = EXPLICIT_SEASON_PACK.containsMatchIn(line)
            if (EPISODE_RANGE.containsMatchIn(line)) linePack = true
            for (range in listOf(SEASON_RANGE, SEASON_NUMBER_RANGE)) {
                range.find(line)?.let { match ->
                    linePack = true
                    addSeasonRange(seasons, match.groupValues[2], match.groupValues[3])
                }
            }
            SEASON_WORD.find(line)?.let { match ->
                linePack = true
                match.groupValues[3].toIntOrNull()?.let { seasons += it }
            }
            if (!linePack && !SINGLE_EPISODE.containsMatchIn(line)) {
                linePack = SEASON_TOKEN.containsMatchIn(line)
            }
            if (!linePack) continue
            isPack = true
            // Name the covered season from whichever form this line used, so a caller can tell a
            // pack of the season being browsed from a pack of some other one.
            SEASON_TOKEN.find(line)?.groupValues?.get(2)?.toIntOrNull()?.let { seasons += it }
            SEASON_OF_EPISODE.find(line)?.groupValues?.get(2)?.toIntOrNull()?.let { seasons += it }
        }
        return PackEvidence(isPack, seasons)
    }

    /** Expands "S01-S03" into the seasons it covers, ignoring reversed or implausibly long spans. */
    private fun addSeasonRange(into: MutableSet<Int>, fromRaw: String, toRaw: String) {
        val from = fromRaw.toIntOrNull() ?: return
        val to = toRaw.toIntOrNull() ?: return
        if (to < from || to - from > MAX_SEASON_SPAN) return
        for (season in from..to) into += season
    }

    private const val MAX_SEASON_SPAN = 30

    /**
     * Normalises the addon-supplied `duration`, whose unit is **not specified by any addon contract**
     * and is not consumed anywhere else in the app — so it is inferred from magnitude rather than
     * assumed. Feature-length content is ~1–5 hours, which separates the plausible units cleanly:
     * minutes land in the tens/hundreds, seconds in the thousands, milliseconds in the millions.
     *
     * Anything that lands outside a believable 1-minute-to-12-hour window after conversion is
     * discarded rather than guessed at, because a wrong duration silently scales the size-sanity
     * check by orders of magnitude.
     */
    internal fun durationSeconds(raw: Long?): Long? {
        val value = raw?.takeIf { it > 0L } ?: return null
        val seconds = when {
            value >= 100_000L -> value / 1000L   // milliseconds
            value >= 1_000L -> value             // seconds
            else -> value * 60L                  // minutes
        }
        return seconds.takeIf { it in 60L..43_200L }
    }

    private fun streamResolution(vararg values: String?): DebridStreamResolution =
        values.firstNotNullOfOrNull { resolutionValue(it) } ?: DebridStreamResolution.UNKNOWN

    private fun resolutionValue(value: String?): DebridStreamResolution? {
        val normalized = value?.lowercase().orEmpty()
        return when {
            normalized.hasResolutionToken("2160p?", "4k", "uhd") -> DebridStreamResolution.P2160
            normalized.hasResolutionToken("1440p?", "2k") -> DebridStreamResolution.P1440
            normalized.hasResolutionToken("1080p?", "fhd") -> DebridStreamResolution.P1080
            normalized.hasResolutionToken("720p?", "hd") -> DebridStreamResolution.P720
            normalized.hasResolutionToken("576p?") -> DebridStreamResolution.P576
            normalized.hasResolutionToken("480p?", "sd") -> DebridStreamResolution.P480
            normalized.hasResolutionToken("360p?") -> DebridStreamResolution.P360
            else -> null
        }
    }

    private fun streamQuality(parsedQuality: String?, searchText: String): DebridStreamQuality {
        val text = listOfNotNull(parsedQuality, searchText).joinToString(" ").lowercase()
        return when {
            text.contains("remux") -> DebridStreamQuality.BLURAY_REMUX
            text.contains("blu-ray") || text.contains("bluray") || text.contains("bdrip") || text.contains("brrip") -> DebridStreamQuality.BLURAY
            text.contains("web-dl") || text.contains("webdl") -> DebridStreamQuality.WEB_DL
            text.contains("webrip") || text.contains("web-rip") -> DebridStreamQuality.WEBRIP
            text.contains("hdrip") -> DebridStreamQuality.HDRIP
            text.contains("hd-rip") || text.contains("hcrip") -> DebridStreamQuality.HD_RIP
            text.contains("dvdrip") -> DebridStreamQuality.DVDRIP
            text.contains("hdtv") -> DebridStreamQuality.HDTV
            text.hasToken("cam") -> DebridStreamQuality.CAM
            text.hasToken("ts") -> DebridStreamQuality.TS
            text.hasToken("tc") -> DebridStreamQuality.TC
            text.hasToken("scr") -> DebridStreamQuality.SCR
            else -> DebridStreamQuality.UNKNOWN
        }
    }

    private fun streamVisualTags(parsedHdr: List<String>, searchText: String): List<DebridStreamVisualTag> {
        val text = (parsedHdr + searchText).joinToString(" ").lowercase()
        val tags = mutableListOf<DebridStreamVisualTag>()
        val hasDv = parsedHdr.any { it.isDolbyVisionToken() } ||
            Regex("(^|[^a-z0-9])(dv|dovi|dolby[ ._-]?vision)([^a-z0-9]|\$)").containsMatchIn(searchText)
        val hasHdr = parsedHdr.any { it.isHdrToken() } ||
            Regex("(^|[^a-z0-9])(hdr|hdr10|hdr10plus|hdr10\\+|hlg)([^a-z0-9]|\$)").containsMatchIn(searchText)
        if (hasDv && hasHdr) tags += DebridStreamVisualTag.HDR_DV
        if (hasDv && !hasHdr) tags += DebridStreamVisualTag.DV_ONLY
        if (hasHdr && !hasDv) tags += DebridStreamVisualTag.HDR_ONLY
        if (text.contains("hdr10+") || text.contains("hdr10plus")) tags += DebridStreamVisualTag.HDR10_PLUS
        if (text.contains("hdr10")) tags += DebridStreamVisualTag.HDR10
        if (hasDv) tags += DebridStreamVisualTag.DV
        if (hasHdr) tags += DebridStreamVisualTag.HDR
        if (text.hasToken("hlg")) tags += DebridStreamVisualTag.HLG
        if (text.contains("10bit") || text.contains("10 bit")) tags += DebridStreamVisualTag.TEN_BIT
        if (text.hasToken("3d")) tags += DebridStreamVisualTag.THREE_D
        if (text.hasToken("imax")) tags += DebridStreamVisualTag.IMAX
        if (text.hasToken("ai")) tags += DebridStreamVisualTag.AI
        if (text.hasToken("sdr")) tags += DebridStreamVisualTag.SDR
        if (text.contains("h-ou")) tags += DebridStreamVisualTag.H_OU
        if (text.contains("h-sbs")) tags += DebridStreamVisualTag.H_SBS
        return tags.distinct().ifEmpty { listOf(DebridStreamVisualTag.UNKNOWN) }
    }

    private fun streamAudioTags(parsedAudio: List<String>, searchText: String): List<DebridStreamAudioTag> {
        val text = (parsedAudio + searchText).joinToString(" ").lowercase()
        val tags = mutableListOf<DebridStreamAudioTag>()
        if (text.hasToken("atmos")) tags += DebridStreamAudioTag.ATMOS
        if (text.contains("dd+") || text.contains("ddp") || text.contains("dolby digital plus")) tags += DebridStreamAudioTag.DD_PLUS
        if (text.hasToken("dd") || text.contains("ac3") || text.contains("dolby digital")) tags += DebridStreamAudioTag.DD
        if (text.contains("dts:x") || text.contains("dtsx")) tags += DebridStreamAudioTag.DTS_X
        if (text.contains("dts-hd ma") || text.contains("dtshd ma")) tags += DebridStreamAudioTag.DTS_HD_MA
        if (text.contains("dts-hd") || text.contains("dtshd")) tags += DebridStreamAudioTag.DTS_HD
        if (text.contains("dts-es") || text.contains("dtses")) tags += DebridStreamAudioTag.DTS_ES
        if (text.hasToken("dts")) tags += DebridStreamAudioTag.DTS
        if (text.contains("truehd") || text.contains("true hd")) tags += DebridStreamAudioTag.TRUEHD
        if (text.hasToken("opus")) tags += DebridStreamAudioTag.OPUS
        if (text.hasToken("flac")) tags += DebridStreamAudioTag.FLAC
        if (text.hasToken("aac")) tags += DebridStreamAudioTag.AAC
        return tags.distinct().ifEmpty { listOf(DebridStreamAudioTag.UNKNOWN) }
    }

    private fun streamAudioChannels(parsedChannels: List<String>, searchText: String): List<DebridStreamAudioChannel> {
        val text = (parsedChannels + searchText).joinToString(" ").lowercase()
        val channels = mutableListOf<DebridStreamAudioChannel>()
        if (text.channelDetected("7.1")) channels += DebridStreamAudioChannel.CH_7_1
        if (text.channelDetected("6.1")) channels += DebridStreamAudioChannel.CH_6_1
        if (text.channelDetected("5.1") || text.hasToken("6ch")) channels += DebridStreamAudioChannel.CH_5_1
        if (text.channelDetected("2.0")) channels += DebridStreamAudioChannel.CH_2_0
        return channels.distinct().ifEmpty { listOf(DebridStreamAudioChannel.UNKNOWN) }
    }

    /**
     * Detection plus, under [StreamPayloadDiagnostics], the text that caused it.
     *
     * A surround layout mis-read from a bitrate is invisible in the UI — it looks exactly like a
     * correct detection — and the release that triggers it is whichever one happens to quote 5.1 or
     * 7.1 as a measurement, which cannot be searched for. Logging the surrounding text turns "it
     * still says 5.1" into the literal string the guard has to handle.
     */
    private fun String.channelDetected(token: String): Boolean {
        val match = channelTokenMatch(token) ?: return false
        if (StreamPayloadDiagnostics.enabled) {
            val from = (match.range.first - 24).coerceAtLeast(0)
            val to = (match.range.last + 24).coerceAtMost(lastIndex)
            traitLog.i { "channel $token from \"${substring(from, to + 1)}\"" }
        }
        return true
    }

    private fun streamEncode(parsedCodec: String?, searchText: String): DebridStreamEncode {
        val text = listOfNotNull(parsedCodec, searchText).joinToString(" ").lowercase()
        return when {
            text.hasToken("av1") -> DebridStreamEncode.AV1
            text.hasToken("hevc") || text.hasToken("h265") || text.hasToken("x265") -> DebridStreamEncode.HEVC
            text.hasToken("avc") || text.hasToken("h264") || text.hasToken("x264") -> DebridStreamEncode.AVC
            text.hasToken("xvid") -> DebridStreamEncode.XVID
            text.hasToken("divx") -> DebridStreamEncode.DIVX
            else -> DebridStreamEncode.UNKNOWN
        }
    }

    private fun languageFor(value: String): DebridStreamLanguage? {
        val normalized = value.lowercase()
        return DebridStreamLanguage.entries.firstOrNull {
            normalized == it.code || normalized == it.label.lowercase()
        }
    }

    private fun releaseGroupFromText(text: String): String =
        Regex("-([a-z0-9][a-z0-9._]{1,24})($|\\.)", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()

    private fun String.hasResolutionToken(vararg tokens: String): Boolean =
        Regex("(^|[^a-z0-9])(${tokens.joinToString("|")})([^a-z0-9]|\$)").containsMatchIn(this)

    private fun String.hasToken(token: String): Boolean =
        Regex("(^|[^a-z0-9])${Regex.escape(token.lowercase())}([^a-z0-9]|\$)").containsMatchIn(lowercase())

    /**
     * The gap between a number and its unit. Kotlin's `\s` is ASCII-only on the JVM, so the
     * non-breaking and typographic spaces that formatter templates use to keep "5.1 Mbps" on one
     * line are listed explicitly — without them the unit is invisible to the guard below.
     */
    private const val UNIT_GAP = "[\\s\\u00a0\\u2000-\\u200a\\u202f\\u205f\\u3000]*"

    /**
     * A unit that turns a preceding decimal into a measurement: bit/byte rates and sizes (mbps,
     * mb/s, gb, mib, "5.1 gigabytes", bare "5.1 G"), plus frequency and frame rate. Deliberately
     * unanchored at the front so "5.1Mbps" and "5.1 Mbps" are both caught.
     *
     * The bare `[kmgt]` branch is last and requires a word boundary, so release words that merely
     * start with a unit letter keep their channel token: "5.1 MKV" and "5.1 M2TS" have no boundary
     * after the letter, and "5.1 H.264" never enters the branch at all (h is not a size prefix).
     */
    private const val MEASUREMENT_UNIT = UNIT_GAP +
        "(?:(?:[kmgt]i?)?b(?:it)?(?:ps|/s)?|bps|(?:kilo|mega|giga|tera)?(?:byte|bit)s?|[kmg]?hz|fps|[kmgt])\\b"

    /**
     * Channel layouts are also plain decimals, so any stream text that quotes a bitrate or a size —
     * which custom addon formatters routinely do — reads "5.1 Mbps" or "7.1 GB" as surround sound.
     * Occurrences followed by a measurement unit are rejected; the scan continues, so a release that
     * genuinely carries both ("… DTS 5.1 … 5.1 Mbps") is still detected from its real channel token.
     */
    private val CHANNEL_TOKEN_REGEX: Map<String, Regex> =
        listOf("7.1", "6.1", "5.1", "2.0").associateWith { token ->
            Regex("(^|[^a-z0-9])${Regex.escape(token)}(?!$MEASUREMENT_UNIT)([^a-z0-9]|\$)")
        }

    private fun String.hasChannelToken(token: String): Boolean =
        channelTokenMatch(token) != null

    private fun String.channelTokenMatch(token: String): MatchResult? =
        CHANNEL_TOKEN_REGEX.getValue(token).find(lowercase())

    private fun String.isDolbyVisionToken(): Boolean {
        val normalized = lowercase().replace(Regex("[^a-z0-9]"), "")
        return normalized == "dv" || normalized == "dovi" || normalized == "dolbyvision"
    }

    private fun String.isHdrToken(): Boolean {
        val normalized = lowercase().replace(Regex("[^a-z0-9+]"), "")
        return normalized == "hdr" ||
            normalized == "hdr10" ||
            normalized == "hdr10+" ||
            normalized == "hdr10plus" ||
            normalized == "hlg"
    }

    private fun streamSize(stream: StreamItem): Long? =
        stream.clientResolve?.stream?.raw?.size
            ?: stream.behaviorHints.videoSize
            ?: stream.debridCacheStatus?.cachedSize

    /**
     * Resolves the instant-playback trait using the strongest available signal.
     *
     * Usenet playback services stream directly from NNTP/WebDAV rather than waiting for a torrent
     * to be cached, so a structured `usenet` classification is equivalent to a positive debrid
     * cache result for scoring purposes. AIOStreams exposes both this classification and
     * `service.cached` in `streamData` when the client requests metadata.
     *
     * Formatter text is considered only for addons with a stable, provider-owned format. The
     * originating addon is identified from manifest metadata, never from the displayed stream name.
     * AIOStreams therefore remains structured-only even when its custom formatter displays the
     * name or marker of an upstream addon.
     */
    private fun isDebridCached(stream: StreamItem): Boolean {
        if (stream.isStructuredUsenetStream()) return true
        when (stream.debridCacheStatus?.state) {
            StreamDebridCacheState.CACHED -> return true
            StreamDebridCacheState.NOT_CACHED -> return false
            StreamDebridCacheState.CHECKING,
            StreamDebridCacheState.UNKNOWN,
            null,
            -> Unit
        }
        stream.clientResolve?.isCached?.let { return it }
        stream.streamData?.serviceCached?.let { return it }
        return stream.fixedFormatterSaysCached()
    }

    private fun StreamItem.isStructuredUsenetStream(): Boolean =
        streamType.equals("usenet", ignoreCase = true) ||
            streamData?.type.equals("usenet", ignoreCase = true)

    private fun StreamItem.fixedFormatterSaysCached(): Boolean {
        val formatter = fixedCacheFormatter() ?: return false
        val displayName = name.orEmpty()
        return when (formatter) {
            FixedCacheFormatter.LIGHTNING -> '⚡' in displayName
            FixedCacheFormatter.TORRENTIO -> TORRENTIO_CACHED_SERVICE.containsMatchIn(displayName)
            FixedCacheFormatter.METEOR -> "🌩" in displayName
        }
    }

    /**
     * Uses addon identity supplied by the manifest/fetch pipeline. Do not add checks against
     * [StreamItem.name] or [StreamItem.description] here: AIOStreams can render any upstream addon
     * name in those user-controlled fields.
     */
    private fun StreamItem.fixedCacheFormatter(): FixedCacheFormatter? {
        val identity = listOf(addonName, addonId, sourceName)
            .joinToString(" ")
            .lowercase()
        return when {
            "aiostreams" in identity || "aio streams" in identity -> null
            "torrentio" in identity -> FixedCacheFormatter.TORRENTIO
            "meteor" in identity -> FixedCacheFormatter.METEOR
            "comet" in identity -> FixedCacheFormatter.LIGHTNING
            "mediafusion" in identity || "media fusion" in identity -> FixedCacheFormatter.LIGHTNING
            "debridio" in identity -> FixedCacheFormatter.LIGHTNING
            "stremthru" in identity && "torz" in identity -> FixedCacheFormatter.LIGHTNING
            else -> null
        }
    }

    private enum class FixedCacheFormatter {
        LIGHTNING,
        TORRENTIO,
        METEOR,
    }

    private val TORRENTIO_CACHED_SERVICE = Regex(
        "\\[\\s*(rd|ad|pm|tb|trb|dl|ed|oc|pkp|pp)\\s*\\+\\s*\\]",
        RegexOption.IGNORE_CASE,
    )

    private fun streamSearchText(stream: StreamItem): String {
        val resolve = stream.clientResolve
        val raw = resolve?.stream?.raw
        val parsed = raw?.parsed
        return listOfNotNull(
            stream.name,
            stream.title,
            stream.description,
            stream.behaviorHints.filename,
            stream.debridCacheStatus?.cachedName,
            resolve?.torrentName,
            resolve?.filename,
            raw?.torrentName,
            raw?.filename,
            parsed?.resolution,
            parsed?.quality,
            parsed?.codec,
            parsed?.hdr?.joinToString(" "),
            parsed?.audio?.joinToString(" "),
        ).joinToString(" ").lowercase()
    }
}
