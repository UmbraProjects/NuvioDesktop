package com.nuvio.app.features.discover

import co.touchlab.kermit.Logger
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.tmdb.TmdbService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Generating one AI row end to end — plan §5, phase 6.
 *
 * prompt → provider → parse → resolve against TMDB → items. Every stage is allowed to lose entries
 * and none of them is allowed to throw: a row that comes back with eleven of the twenty-four titles
 * asked for is a working row, and a row that comes back empty is a message, not a crash.
 */
object DiscoverAiGenerator {
    private val log = Logger.withTag("DiscoverAi")

    /** Flattens a multi-line reply onto the single log line that reports it. */
    private val whitespaceRun = Regex("""\s+""")

    /**
     * TMDB searches in flight at once. Same ceiling the seed resolver settled on: enough to turn
     * two dozen sequential round trips into four waves, few enough not to be the shape that draws
     * a 429.
     */
    private const val RESOLVE_CONCURRENCY = 6

    suspend fun generate(
        row: AiDiscoverRow,
        seeds: List<DiscoverAiSeed>,
        exclusions: List<String>,
    ): Result<List<AiDiscoverItem>> {
        // Before the settings read, because nothing about the configuration can rescue this.
        if (aiRowLacksItsHistorySlice(row.preset, seeds.size)) {
            log.w { "AI row '${row.title}': ${row.preset} has no history slice to send" }
            return Result.failure(DiscoverAiException(DiscoverAiError.NoHistorySlice))
        }
        val settings = DiscoverAiSettingsRepository.snapshot()
        val prompt = buildDiscoverAiPrompt(
            preset = row.promptPreset,
            customInstruction = row.customInstruction,
            seeds = seeds,
            exclusions = exclusions,
        )

        val text = DiscoverAiClient.complete(settings, prompt).getOrElse { error ->
            return Result.failure(error)
        }

        val suggestions = parseDiscoverAiSuggestions(text)
        if (suggestions.isEmpty()) {
            // The provider answered and the answer was not a JSON array. Everything needed to tell
            // that apart from "the provider sent nothing" is here, so log it: without the sample,
            // this failure and the client's two blank-response failures all reach the user as the
            // same sentence and none of them says which fix to reach for. The sample is the model's
            // own words about films — the prompt, which carries watch history, is never logged.
            log.w {
                "AI row '${row.title}': ${suggestions.size} parsed from ${text.length} chars " +
                    "(preset=${row.preset} model=${settings.effectiveModel} " +
                    "seeds=${seeds.size}) sample=${text.take(300).replace(whitespaceRun, " ")}"
            }
            return Result.failure(DiscoverAiException(DiscoverAiError.Empty))
        }

        val resolved = resolve(suggestions)
        log.i { "AI row '${row.title}': ${suggestions.size} suggested, ${resolved.size} resolved" }
        if (resolved.isEmpty()) {
            // The model answered and nothing it named exists. Distinct from Empty on purpose: the
            // fix is a different prompt or a better model, not a retry.
            return Result.failure(DiscoverAiException(DiscoverAiError.NothingResolved))
        }
        return Result.success(resolved.take(AI_DISCOVER_ITEMS_PER_ROW))
    }

    private suspend fun resolve(suggestions: List<DiscoverAiSuggestion>): List<AiDiscoverItem> {
        val resolved = mutableListOf<AiDiscoverItem>()
        for (chunk in suggestions.chunked(RESOLVE_CONCURRENCY)) {
            val batch = coroutineScope {
                chunk.map { suggestion -> async { resolveOne(suggestion) } }.awaitAll()
            }
            resolved += batch.filterNotNull()
        }
        // The model repeats itself, and two different suggested titles can resolve to one film.
        return resolved.distinctBy { it.id }
    }

    private suspend fun resolveOne(suggestion: DiscoverAiSuggestion): AiDiscoverItem? {
        val mediaType = if (suggestion.type == "series") "tv" else "movie"
        val results = runCatching {
            TmdbService.searchTitles(
                query = suggestion.title,
                mediaType = mediaType,
                year = suggestion.year,
            )
        }.getOrElse { return null }

        // Retried without the year rather than given up on: a year the model got wrong filters out
        // the correct title at the API, and the matcher can judge the year for itself afterwards.
        val candidates = results.ifEmpty {
            if (suggestion.year == null) return null
            runCatching {
                TmdbService.searchTitles(query = suggestion.title, mediaType = mediaType)
            }.getOrElse { return null }
        }

        val match = pickBestTmdbMatch(suggestion.title, suggestion.year, candidates) ?: return null
        return AiDiscoverItem(
            // Same address Discover uses everywhere else, so the details screen and every export
            // understand it without a special case.
            id = "tmdb:${match.id}",
            type = suggestion.type,
            name = match.displayTitle,
            poster = TmdbService.tmdbImageUrl(match.posterPath),
            background = TmdbService.tmdbImageUrl(match.backdropPath, size = "w1280"),
            description = match.overview?.takeIf { it.isNotBlank() },
            releaseInfo = match.year?.toString(),
            rating = tmdbVoteAverageLabel(match.voteAverage),
            reason = suggestion.reason,
        )
    }
}

/**
 * The row as Discover renders it.
 *
 * No network at all, so an AI row publishes alongside the local row and the imported ones, before
 * the TMDB fan-out starts.
 */
fun AiDiscoverRow.toRecommendationRow(): DiscoverRecommendationRow = DiscoverRecommendationRow(
    key = aiDiscoverEntryId(id),
    title = title,
    entryId = aiDiscoverEntryId(id),
    items = items.map { item ->
        MetaPreview(
            id = item.id,
            type = item.type,
            name = item.name,
            poster = item.poster,
            banner = item.background,
            logo = item.logo,
            description = item.description,
            releaseInfo = item.releaseInfo,
            imdbRating = item.rating,
            // The one thing an AI row has that no other row does. Carried on the preview rather
            // than handed to the renderer as a side map, because the shelf takes a list of
            // MetaPreview and nothing else — see NuvioPosterCard's hover tooltip.
            recommendationReason = item.reason,
        )
    },
)

/** The reason text for one item in a built AI row, keyed the way the row addresses it. */
fun AiDiscoverRow.reasonsById(): Map<String, String> =
    items.mapNotNull { item -> item.reason?.let { item.id to it } }.toMap()
