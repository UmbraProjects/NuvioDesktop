package com.nuvio.app.features.debrid

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.PlayerSettingsUiState
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamScoreRepository
import com.nuvio.app.features.streams.StreamScoreProfile
import com.nuvio.app.features.streams.StreamScoreContext
import com.nuvio.app.features.streams.StreamScoreContexts
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.epochMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Which allowance a preparation run spends.
 *
 * Speculative work gets its own so it can never consume the allowance a play the user actually
 * asked for is about to need — the whole point of preparing links ahead is defeated if the guess
 * exhausts the budget before the real request arrives.
 */
enum class DebridPrepareBudget {
    /** A load the user triggered. */
    Interactive,

    /** A background stream prefetch. */
    Speculative,
}

object DirectDebridStreamPreparer {
    private val log = Logger.withTag("DirectDebridPreparer")
    private val budgetMutex = Mutex()
    private val budgetWindows = DebridPrepareBudget.entries.associateWith {
        BudgetWindows(minuteStarts = ArrayDeque(), hourStarts = ArrayDeque())
    }

    private class BudgetWindows(
        val minuteStarts: ArrayDeque<Long>,
        val hourStarts: ArrayDeque<Long>,
    )

    suspend fun prepare(
        streams: List<StreamItem>,
        season: Int?,
        episode: Int?,
        playerSettings: PlayerSettingsUiState,
        installedAddonNames: Set<String>,
        contentId: String? = null,
        contentType: String? = null,
        /**
         * Caps how many candidates are resolved, below the user's configured
         * [DebridSettings.instantPlaybackPreparationLimit]. A speculative run prepares only the one
         * link it believes will be played; resolving a whole shortlist on a guess is not worth it.
         */
        limitOverride: Int? = null,
        budget: DebridPrepareBudget = DebridPrepareBudget.Interactive,
        onPrepared: (original: StreamItem, prepared: StreamItem) -> Unit,
    ) {
        val settings = DebridSettingsRepository.snapshot()
        val limit = limitOverride?.coerceAtMost(settings.instantPlaybackPreparationLimit)
            ?: settings.instantPlaybackPreparationLimit
        if (!settings.canResolvePlayableLinks || limit <= 0) return

        val candidates = prioritizeCandidates(
            streams = streams.filter(DirectDebridPlaybackResolver::shouldResolveToPlayableStream),
            limit = limit,
            playerSettings = playerSettings,
            installedAddonNames = installedAddonNames,
            scoreProfile = StreamScoreRepository.profile,
            scoreContext = StreamScoreContexts.forPlayback(
                isEpisode = episode != null,
                contentId = contentId,
                contentType = contentType,
            ),
        )
        for (stream in candidates) {
            DirectDebridPlaybackResolver.cachedPlayableStream(stream, season, episode)?.let { cached ->
                onPrepared(stream, cached)
                continue
            }

            if (!consumeBackgroundBudget(budget)) {
                log.d { "Skipping instant playback preparation; local debrid budget reached" }
                return
            }

            try {
                when (val result = DirectDebridPlaybackResolver.resolveToPlayableStream(stream, season, episode)) {
                    is DirectDebridPlayableResult.Success -> {
                        if (result.stream.playableDirectUrl != null) {
                            onPrepared(stream, result.stream)
                        }
                    }
                    else -> Unit
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.d(error) { "Instant playback preparation failed" }
            }
        }
    }

    internal fun prioritizeCandidates(
        streams: List<StreamItem>,
        limit: Int,
        playerSettings: PlayerSettingsUiState,
        installedAddonNames: Set<String>,
        scoreProfile: StreamScoreProfile = StreamScoreProfile(),
        scoreContext: StreamScoreContext = StreamScoreContext.MOVIE,
    ): List<StreamItem> {
        if (limit <= 0) return emptyList()
        val candidates = streams
            .filter { stream ->
                stream.playableDirectUrl == null &&
                    stream.isAddonDebridCandidate &&
                    (stream.isDirectDebridStream || stream.isCachedDebridTorrentStream)
            }
            .distinctBy { it.preparationKey() }
        if (candidates.isEmpty()) return emptyList()

        val prioritized = mutableListOf<StreamItem>()
        val autoPlaySelection = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = streams,
            mode = playerSettings.streamAutoPlayMode,
            regexPattern = playerSettings.streamAutoPlayRegex,
            source = playerSettings.streamAutoPlaySource,
            installedAddonNames = installedAddonNames,
            selectedAddons = playerSettings.streamAutoPlaySelectedAddons,
            selectedPlugins = playerSettings.streamAutoPlaySelectedPlugins,
            scoreProfile = scoreProfile,
            scoreContext = scoreContext,
        )
        if (autoPlaySelection?.let { it.isAddonDebridCandidate && (it.isDirectDebridStream || it.isCachedDebridTorrentStream) } == true) {
            candidates.firstOrNull { it.preparationKey() == autoPlaySelection.preparationKey() }
                ?.let(prioritized::add)
        }

        if (playerSettings.streamAutoPlayMode == StreamAutoPlayMode.REGEX_MATCH) {
            val regex = runCatching {
                Regex(playerSettings.streamAutoPlayRegex.trim(), RegexOption.IGNORE_CASE)
            }.getOrNull()
            if (regex != null) {
                candidates
                    .filter { candidate ->
                        prioritized.none { it.preparationKey() == candidate.preparationKey() } &&
                            regex.containsMatchIn(candidate.searchableText())
                    }
                    .forEach(prioritized::add)
            }
        }

        candidates
            .filter { candidate -> prioritized.none { it.preparationKey() == candidate.preparationKey() } }
            .forEach(prioritized::add)

        return prioritized.take(limit)
    }

    fun replacePreparedStream(
        groups: List<AddonStreamGroup>,
        original: StreamItem,
        prepared: StreamItem,
        eligibleGroupIds: Set<String>? = null,
    ): List<AddonStreamGroup> {
        val key = original.preparationKey()
        return groups.map { group ->
            if (eligibleGroupIds != null && group.addonId !in eligibleGroupIds) return@map group
            var changed = false
            val updatedStreams = group.streams.map { stream ->
                if (stream.preparationKey() == key) {
                    changed = true
                    prepared.copy(
                        addonName = stream.addonName,
                        addonId = stream.addonId,
                        sourceName = stream.sourceName,
                    )
                } else {
                    stream
                }
            }
            if (changed) group.copy(streams = updatedStreams) else group
        }
    }

    internal suspend fun consumeBackgroundBudget(budget: DebridPrepareBudget): Boolean {
        val now = epochMs()
        val windows = budgetWindows.getValue(budget)
        val perMinute = when (budget) {
            DebridPrepareBudget.Interactive -> MAX_BACKGROUND_PREPARES_PER_MINUTE
            DebridPrepareBudget.Speculative -> MAX_SPECULATIVE_PREPARES_PER_MINUTE
        }
        val perHour = when (budget) {
            DebridPrepareBudget.Interactive -> MAX_BACKGROUND_PREPARES_PER_HOUR
            DebridPrepareBudget.Speculative -> MAX_SPECULATIVE_PREPARES_PER_HOUR
        }
        return budgetMutex.withLock {
            windows.minuteStarts.removeOlderThan(now - BACKGROUND_PREPARES_PER_MINUTE_WINDOW_MS)
            windows.hourStarts.removeOlderThan(now - BACKGROUND_PREPARES_PER_HOUR_WINDOW_MS)
            if (windows.minuteStarts.size >= perMinute || windows.hourStarts.size >= perHour) {
                false
            } else {
                windows.minuteStarts.addLast(now)
                windows.hourStarts.addLast(now)
                true
            }
        }
    }

    /** Forgets both allowances. For profile switches and account wipes. */
    suspend fun resetBudgets() {
        budgetMutex.withLock {
            budgetWindows.values.forEach {
                it.minuteStarts.clear()
                it.hourStarts.clear()
            }
        }
    }
}

private const val MAX_BACKGROUND_PREPARES_PER_MINUTE = 6
private const val MAX_BACKGROUND_PREPARES_PER_HOUR = 30

// Smaller, and separate. A prefetch sweep resolves at most one link, and sweeps are already capped
// at four per ten minutes — but sharing the interactive allowance would still let a browsing session
// spend most of the hourly budget on guesses before a real play ever asked for it.
private const val MAX_SPECULATIVE_PREPARES_PER_MINUTE = 2
private const val MAX_SPECULATIVE_PREPARES_PER_HOUR = 12
private const val BACKGROUND_PREPARES_PER_MINUTE_WINDOW_MS = 60L * 1000L
private const val BACKGROUND_PREPARES_PER_HOUR_WINDOW_MS = 60L * 60L * 1000L

private fun ArrayDeque<Long>.removeOlderThan(cutoffMs: Long) {
    while (firstOrNull()?.let { it < cutoffMs } == true) {
        removeFirst()
    }
}

private fun StreamItem.preparationKey(): String {
    val resolve = clientResolve
    if (resolve != null) {
        return listOf(
            resolve.service.orEmpty().lowercase(),
            resolve.infoHash.orEmpty().lowercase(),
            resolve.fileIdx?.toString().orEmpty(),
            resolve.filename.orEmpty().lowercase(),
            resolve.torrentName.orEmpty().lowercase(),
            resolve.magnetUri.orEmpty().lowercase(),
        ).joinToString("|")
    }

    return listOf(
        addonId.lowercase(),
        infoHash.orEmpty().lowercase(),
        fileIdx?.toString().orEmpty(),
        behaviorHints.filename.orEmpty().lowercase(),
        playableDirectUrl.orEmpty().lowercase(),
        name.orEmpty().lowercase(),
        title.orEmpty().lowercase(),
    ).joinToString("|")
}

private fun StreamItem.searchableText(): String =
    buildString {
        append(addonName).append(' ')
        append(name.orEmpty()).append(' ')
        append(title.orEmpty()).append(' ')
        append(description.orEmpty()).append(' ')
        append(playableDirectUrl.orEmpty())
    }
