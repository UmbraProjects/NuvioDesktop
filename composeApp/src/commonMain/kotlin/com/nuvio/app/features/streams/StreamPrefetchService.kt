package com.nuvio.app.features.streams

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.debrid.DebridPrepareBudget
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.DirectDebridStreamPreparer
import com.nuvio.app.features.debrid.LocalDebridAvailabilityService
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.locallibrary.LocalLibraryRepository
import com.nuvio.app.features.metadata.MediaIdResolver
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsUiState
import com.nuvio.app.features.player.PlayerStreamsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs the stream search ahead of the user, so the play they are about to make starts like a local
 * file instead of paying full scrape latency.
 *
 * Results go into [StreamPrefetchCache], which the interactive fetch paths already read from. This
 * object never touches [StreamsRepository] or [PlayerStreamsRepository] state — it produces, they
 * consume, and if it never runs the app behaves exactly as it did before.
 *
 * ### Why it is this cautious
 *
 * Every request made here is speculative: the user has not asked for it and may never press play.
 * Spending provider requests on a guess is the one way this feature can do harm, by earning the
 * rate limits and 429s that a real playback then runs into. So the work is fenced by a dwell, a
 * single-flight job, a per-target cooldown, a sweep budget, and a set of conditions under which
 * searching is simply pointless. It is off by default, and the scope that turns it on is an
 * explicit user choice.
 */
internal object StreamPrefetchService {
    private val log = Logger.withTag("StreamPrefetch")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * What asked for the prefetch, and how long the user has to stay put before it is believed.
     *
     * Dwell is the cheapest guard there is: scrolling past ten Continue Watching cards must cost
     * nothing, and only stopping on one is evidence of intent.
     */
    enum class Trigger(val dwellMs: Long) {
        /** A details page settled on its primary-action episode. */
        Details(700L),

        /**
         * A Continue Watching / Up Next card took focus or hover. Longer than [Details] — a details
         * page was navigated to deliberately, whereas a card can pass under the cursor by accident.
         * Matches the home hero's own trailer dwell.
         */
        ContinueWatching(1_200L),
    }

    data class Target(
        val trigger: Trigger,
        val type: String,
        val parentMetaId: String,
        val videoId: String,
        val title: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
    )

    /** At most this many sweeps may start within [BUDGET_WINDOW_MS]. */
    private const val MAX_SWEEPS_PER_WINDOW = 4
    private const val BUDGET_WINDOW_MS = 10L * 60L * 1000L

    private val stateLock = Any()
    private var activeJob: Job? = null
    private var activeContentKey: String? = null

    /** True once the active job is past its dwell and has requests out. See [cancel]. */
    private var sweepStarted = false

    /**
     * When a target was last *attempted*, hit or miss.
     *
     * Distinct from what the cache holds: a sweep that came back with nothing stores nothing (see
     * [StreamPrefetchCache]), and without this the next dwell on the same card would search again
     * immediately, and every dwell after that.
     */
    private val attemptedAtMsByContentKey = mutableMapOf<String, Long>()

    private val sweepStarts = ArrayDeque<Long>()

    /**
     * Set while the player owns the screen. Playback is the thing this feature exists to make feel
     * instant; competing with it for bandwidth would trade the win for a stutter.
     */
    @Volatile
    private var playbackActive: Boolean = false

    fun setPlaybackActive(active: Boolean) {
        playbackActive = active
        if (active) cancel()
    }

    /**
     * Abandons a target whose dwell has not elapsed.
     *
     * Deliberately does **not** stop a sweep whose requests are already in flight. Those calls are
     * spent the moment they leave; killing the coroutine does not un-send them, it only throws away
     * the answers — and it used to do exactly that every time focus moved along a Continue Watching
     * row, burning a provider request per card and then marking each one cooled down so it would not
     * be retried. Letting an started sweep finish is what turns those calls back into something.
     *
     * Rate limiting is the dwell's job, and the sweep budget's; it was never this.
     */
    fun cancel() {
        synchronized(stateLock) {
            if (sweepStarted) return
            activeJob?.cancel()
            activeJob = null
            activeContentKey = null
        }
    }

    /**
     * Asks for [target] to be searched after its dwell.
     *
     * Cancel-and-replace: a second call for a different target abandons the first, so moving along a
     * row costs one sweep for wherever the user stopped rather than one per card. A repeat call for
     * the target already in flight is a no-op, so a recomposition does not restart the dwell.
     */
    fun request(target: Target) {
        PlayerSettingsRepository.ensureLoaded()
        val settings = PlayerSettingsRepository.uiState.value
        val prefetchScope = settings.streamPrefetchScope
        if (!prefetchScope.isEnabled) return
        if (target.trigger == Trigger.ContinueWatching && !prefetchScope.includesContinueWatching) return
        if (playbackActive) return

        val resolved = MediaIdResolver.resolveLocalEpisodeIdentity(
            contentType = target.type,
            parentMetaId = target.parentMetaId,
            videoId = target.videoId,
            title = target.title,
            season = target.season,
            episode = target.episode,
            isAnimeHint = target.type.equals("anime", ignoreCase = true),
        )
        val contentKey = StreamPrefetchCache.contentKey(
            type = target.type,
            videoId = resolved.videoId,
            season = resolved.streamSeason,
            episode = resolved.streamEpisode,
        )

        synchronized(stateLock) {
            if (contentKey == activeContentKey && activeJob?.isActive == true) return
            if (!sweepStarted) activeJob?.cancel()
            sweepStarted = false
            activeContentKey = contentKey
            activeJob = scope.launch {
                delay(target.trigger.dwellMs)
                synchronized(stateLock) { sweepStarted = true }
                runCatchingUnlessCancelled {
                    sweep(
                        target = target,
                        contentKey = contentKey,
                        effectiveVideoId = resolved.videoId,
                        effectiveSeason = resolved.streamSeason,
                        effectiveEpisode = resolved.streamEpisode,
                    )
                }.onFailure { log.w(it) { "prefetch sweep failed for $contentKey" } }
                synchronized(stateLock) {
                    if (activeContentKey == contentKey) {
                        activeContentKey = null
                        activeJob = null
                        sweepStarted = false
                    }
                }
            }
        }
    }

    private suspend fun sweep(
        target: Target,
        contentKey: String,
        effectiveVideoId: String,
        effectiveSeason: Int?,
        effectiveEpisode: Int?,
    ) {
        // Re-read everything after the dwell: the settings, the screen and the in-flight work can
        // all have changed while we were waiting, and the pre-dwell checks are only an early exit.
        val settings = PlayerSettingsRepository.uiState.value
        val retentionMs = settings.streamPrefetchCacheMinutes * 60L * 1000L
        val now = epochMs()

        val decision = evaluatePrefetchGate(
            PrefetchGateInputs(
                trigger = target.trigger,
                scope = settings.streamPrefetchScope,
                playbackActive = playbackActive,
                // Gathered eagerly so the decision itself stays a pure function. The two costly
                // inputs are in-memory repository reads plus one small disk read, and the budget
                // caps this whole block at a handful of runs per ten minutes.
                withinCooldown = isWithinCooldown(contentKey, now, retentionMs),
                interactiveFetchRunning = isInteractiveFetchRunning(),
                hasInstantSourceAlready = hasInstantSourceAlready(
                    target = target,
                    effectiveVideoId = effectiveVideoId,
                    effectiveSeason = effectiveSeason,
                    effectiveEpisode = effectiveEpisode,
                    settings = settings,
                ),
            ),
        )
        if (decision != PrefetchDecision.Proceed) {
            log.d { "prefetch skipped ($decision) for $contentKey" }
            return
        }

        if (!consumeSweepBudget(now)) {
            log.d { "prefetch skipped (${PrefetchDecision.BudgetReached}) for $contentKey" }
            return
        }
        recordAttempt(contentKey, now)
        log.i { "prefetch sweep start $contentKey (${target.trigger})" }

        // Register each provider request as it goes out, so an interactive load that starts while
        // this is running waits on these instead of sending its own copy. Results are published the
        // moment they land — a sweep the user interrupts halfway is still worth what it has.
        val claimed = mutableSetOf<String>()
        val observer = object : ProviderFetchObserver {
            override fun onProviderStarted(providerId: String) {
                val cacheId = providerId.toCacheProviderId()
                if (StreamPrefetchCache.claimInFlight(contentKey, cacheId)) {
                    synchronized(claimed) { claimed += cacheId }
                }
            }

            override fun onProviderFinished(providerId: String, streams: List<StreamItem>?) {
                val cacheId = providerId.toCacheProviderId()
                if (!synchronized(claimed) { claimed.remove(cacheId) }) return
                StreamPrefetchCache.releaseInFlight(contentKey, cacheId, streams)
            }
        }

        val groups = try {
            StreamSearchService.searchGrouped(
                type = target.type,
                videoId = target.videoId,
                parentMetaId = target.parentMetaId,
                season = target.season,
                episode = target.episode,
                // One title, queried the way the streams screen would query it. Pacing this would
                // only make it slower without making it lighter — the request count is identical.
                pacing = ProviderPacing.Concurrent,
                observer = observer,
            )
        } finally {
            // Belt and braces: anything still claimed here never reached onProviderFinished, and a
            // waiter blocked on a claim nobody completes is worse than one that fetches for itself.
            val stranded = synchronized(claimed) { claimed.toList().also { claimed.clear() } }
            stranded.forEach { StreamPrefetchCache.releaseInFlight(contentKey, it, null) }
        }

        var stored = 0
        // Kept because link preparation needs the *annotated* rows, not the raw ones: its candidate
        // filter accepts a torrent only once a cache check has marked it CACHED, so handing it the
        // pre-annotation groups silently produced no candidates at all.
        val annotatedGroups = mutableListOf<AddonStreamGroup>()
        for (group in groups) {
            // A provider that errored has nothing to publish; one that answered with nothing was
            // already published by the observer and has nothing to annotate.
            if (group.error != null || group.streams.isEmpty()) continue
            // Annotating here rather than on hydrate is what lets the interactive path skip its own
            // availability call: pre-annotated rows report no pending check.
            val annotated = runCatchingUnlessCancelled {
                if (LocalDebridAvailabilityService.hasPendingCacheCheck(listOf(group), setOf(group.addonId))) {
                    LocalDebridAvailabilityService
                        .annotateCachedAvailability(listOf(group), setOf(group.addonId))
                        .firstOrNull()
                        ?: group
                } else {
                    group
                }
            }.getOrDefault(group)

            annotatedGroups += annotated
            StreamPrefetchCache.put(
                contentKey = contentKey,
                providerId = annotated.addonId.toCacheProviderId(),
                streams = annotated.streams,
            )
            stored += annotated.streams.size
        }

        log.i {
            "prefetched $stored streams across ${groups.count { it.streams.isNotEmpty() }} providers " +
                "for $contentKey (${target.trigger})"
        }

        prepareTopLink(target, annotatedGroups, effectiveSeason, effectiveEpisode, settings)
    }

    /**
     * Resolves the single link most likely to be played, so the debrid round trip is already done
     * when it is.
     *
     * Nothing is stored here. The prepared stream is discarded on purpose: a resolved URL expires,
     * and `DirectDebridResolver` already owns that lifetime with its own 15-minute cache. Running
     * the preparation is the point — it warms that cache, and the interactive play path reads it
     * through the same resolver it always uses. Storing the URL ourselves would mean owning expiry
     * in a second place and eventually handing the player a dead link.
     *
     * A no-op unless the user has a debrid resolver configured; providers that hand back
     * already-playable URLs (AIOStreams and friends) never reach it.
     */
    private suspend fun prepareTopLink(
        target: Target,
        groups: List<AddonStreamGroup>,
        effectiveSeason: Int?,
        effectiveEpisode: Int?,
        settings: PlayerSettingsUiState,
    ) {
        val debrid = DebridSettingsRepository.snapshot()
        val streams = groups.flatMap { it.streams }
        val eligible = streams.filter(DirectDebridPlaybackResolver::shouldResolveToPlayableStream)
        // `eligible` only means "the app could resolve this". The preparer additionally insists a
        // torrent be known-cached with the provider, which is what a cache check establishes — so
        // this is the count that predicts whether anything will actually be prepared.
        val preparable = eligible.count { it.isDirectDebridStream || it.isCachedDebridTorrentStream }

        // Every reason to stop is reported. Each of these was previously a silent return, and the
        // whole path then looked identical from the log whether it was switched off, unsupported by
        // the user's sources, or simply working — which cost three test runs to untangle.
        val skipReason = when {
            !settings.streamPrefetchResolveLinks -> "turned off for prefetch"
            !debrid.canResolvePlayableLinks -> "no debrid resolver configured"
            debrid.instantPlaybackPreparationLimit <= 0 ->
                "Debrid > Link Preparation is off, which gates all link preparation"
            streams.isEmpty() -> "the sweep found no streams"
            eligible.isEmpty() ->
                "none of ${streams.size} streams need resolving (already-playable URLs)"
            preparable == 0 ->
                "none of ${eligible.size} resolvable streams are cached with the provider"
            else -> null
        }
        if (skipReason != null) {
            log.i { "prefetch link preparation skipped: $skipReason" }
            return
        }

        var warmed = 0
        runCatchingUnlessCancelled {
            DirectDebridStreamPreparer.prepare(
                streams = streams,
                season = effectiveSeason,
                episode = effectiveEpisode,
                playerSettings = settings,
                installedAddonNames = groups.map { it.addonName }.toSet(),
                contentId = target.parentMetaId,
                contentType = target.type,
                // One link, not a shortlist — this is a guess about what will be played.
                limitOverride = 1,
                budget = DebridPrepareBudget.Speculative,
            ) { _, prepared ->
                warmed++
                log.i { "prefetch warmed a playable link via ${prepared.addonName}" }
            }
        }.onFailure { log.w(it) { "prefetch link preparation failed" } }

        log.i {
            "prefetch link preparation: warmed=$warmed, preparable=$preparable, " +
                "resolvable=${eligible.size} of ${streams.size} streams"
        }
    }

    /**
     * [StreamSearchService] labels a scraper group `plugin:<id>`, while the interactive paths look
     * it up under [StreamPrefetchCache.scraperProviderId] because their own grouping depends on a
     * display setting. Addon ids pass through unchanged.
     */
    internal fun String.toCacheProviderId(): String =
        if (startsWith("plugin:")) {
            StreamPrefetchCache.scraperProviderId(removePrefix("plugin:"))
        } else {
            this
        }

    private fun isInteractiveFetchRunning(): Boolean =
        StreamsRepository.uiState.value.isAnyLoading ||
            PlayerStreamsRepository.sourceState.value.isAnyLoading ||
            PlayerStreamsRepository.episodeStreamsState.value.isAnyLoading

    /**
     * Whether pressing play would already be instant, in which case searching addons is pure waste.
     *
     * Covers a local-library or embedded file, a completed download, and a still-valid Reuse Last
     * Link entry — each of which short-circuits the scrape on the real play path too.
     */
    private fun hasInstantSourceAlready(
        target: Target,
        effectiveVideoId: String,
        effectiveSeason: Int?,
        effectiveEpisode: Int?,
        settings: PlayerSettingsUiState,
    ): Boolean {
        if (MetaDetailsRepository.findEmbeddedStreams(effectiveVideoId).isNotEmpty()) return true
        if (MetaDetailsRepository.findLocalStreams(effectiveVideoId).isNotEmpty()) return true
        if (LocalLibraryRepository.localStreamsFor(target.parentMetaId, effectiveVideoId).isNotEmpty()) return true
        if (
            AppFeaturePolicy.downloadsEnabled &&
            DownloadsRepository.findPlayableDownload(
                parentMetaId = target.parentMetaId,
                seasonNumber = target.season,
                episodeNumber = target.episode,
                videoId = target.videoId,
            ) != null
        ) {
            return true
        }
        if (settings.streamReuseLastLinkEnabled) {
            val linkKey = StreamLinkCacheRepository.contentKey(
                type = target.type,
                videoId = effectiveVideoId,
                parentMetaId = target.parentMetaId,
                season = effectiveSeason,
                episode = effectiveEpisode,
            )
            val maxAgeMs = settings.streamReuseLastLinkCacheHours * 60L * 60L * 1000L
            if (StreamLinkCacheRepository.getValid(linkKey, maxAgeMs) != null) return true
        }
        return false
    }

    internal fun isWithinCooldown(contentKey: String, nowMs: Long, retentionMs: Long): Boolean =
        synchronized(stateLock) {
            attemptedAtMsByContentKey.entries.removeAll { (_, at) -> nowMs - at !in 0..BUDGET_WINDOW_MS }
            val attemptedAt = attemptedAtMsByContentKey[contentKey] ?: return@synchronized false
            nowMs - attemptedAt in 0..retentionMs
        }

    internal fun recordAttempt(contentKey: String, nowMs: Long) {
        synchronized(stateLock) { attemptedAtMsByContentKey[contentKey] = nowMs }
    }

    /**
     * Deliberately its own budget rather than `DirectDebridStreamPreparer`'s: a guess must never be
     * able to consume the allowance a real playback is about to need.
     */
    internal fun consumeSweepBudget(nowMs: Long): Boolean = synchronized(stateLock) {
        while (sweepStarts.firstOrNull()?.let { it < nowMs - BUDGET_WINDOW_MS } == true) {
            sweepStarts.removeFirst()
        }
        if (sweepStarts.size >= MAX_SWEEPS_PER_WINDOW) {
            false
        } else {
            sweepStarts.addLast(nowMs)
            true
        }
    }

    /** Forgets budget and cooldown history. For profile switches and account wipes. */
    fun reset() {
        cancel()
        synchronized(stateLock) {
            attemptedAtMsByContentKey.clear()
            sweepStarts.clear()
        }
    }
}
