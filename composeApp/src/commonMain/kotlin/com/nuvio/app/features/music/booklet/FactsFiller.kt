package com.nuvio.app.features.music.booklet
// FORK UI (Spyder): Nick's album page facts, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import co.touchlab.kermit.Logger
import com.nuvio.app.features.discover.DiscoverAiClient
import com.nuvio.app.features.discover.DiscoverAiProvider
import com.nuvio.app.features.discover.DiscoverAiSettingsRepository
import com.nuvio.app.features.music.defaultMusicCatalog
import com.nuvio.app.features.music.MusicPlayback
import com.nuvio.app.features.music.bookletRequestFor
import com.nuvio.app.features.music.library.LibraryArtist
import com.nuvio.app.features.music.library.BackgroundWork
import com.nuvio.app.features.music.library.PeopleCreditsBook
import com.nuvio.app.features.music.library.MusicLibraryRepository
import com.nuvio.app.features.music.library.ReleaseKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

/*
 * fork:facts-filler - fills the album page's facts (Liner Notes, track notes, song stories) for the whole library in the
 * background, so opening an album finds them already there. It uses the same loaders the album page uses, one lookup at a
 * time, so what it writes is exactly what the page would have written; the cache is the record of progress. The rules
 * (order, allowance, pacing) are in FactsFill.kt. It is off until the person turns it on in Settings > Music.
 */

/** What the settings page shows. */
internal data class FillStatus(
    val enabled: Boolean = false,
    /** What it is doing right now, or why it is waiting. */
    val activity: String = "",
    val albumsDone: Int = 0,
    val albumsTotal: Int = 0,
    val songsKept: Int = 0,
    /** Tokens used in the last 24 hours, and the most the filler may use in that time. */
    val used: Long = 0L,
    val cap: Long = FILL_CAP_DEFAULT,
    /** Lookups that failed a moment ago; each is tried again by itself after two hours. */
    val retry: List<String> = emptyList(),
    /** The credits sweep's own line ("Credits: 120 of 9461 albums checked"); it runs apart from the AI facts, so an AI limit never stops it. */
    val credits: String = "",
)

/** True while the player owns the screen (a film or episode): the filler waits, so it never competes with playback. Set by the player screen. */
internal object FilmPlaying {
    val active = MutableStateFlow(false)
}

internal object FactsFiller {
    private val log = Logger.withTag("FactsFiller")
    private val _status = MutableStateFlow(
        FillStatus(
            enabled = FillStorage.get("enabled") == "1",
            cap = decodeCap(FillStorage.get("cap")),
            retry = decodeRetry(FillStorage.get(RETRY_KEY)).map { it.title },
        ),
    )
    val status: StateFlow<FillStatus> = _status.asStateFlow()

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    fun setEnabled(on: Boolean) {
        FillStorage.put("enabled", if (on) "1" else "0")
        _status.update { it.copy(enabled = on, activity = if (on) "Starting" else "") }
    }

    fun setCap(cap: Long) {
        FillStorage.put("cap", cap.toString())
        _status.update { it.copy(cap = cap) }
    }

    /** Pass-to-pass sleep that "Retry now" and "Check again" cut short. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** The failed lookups, kept between runs of the app (each step names the artist and album its failure is kept under). */
    @Volatile
    private var retryItems: List<FillStep> = decodeRetry(FillStorage.get(RETRY_KEY))

    private fun keepRetry(steps: List<FillStep>) {
        retryItems = steps
        FillStorage.put(RETRY_KEY, encodeRetry(steps).ifEmpty { null })
        _status.update { it.copy(retry = steps.map { s -> s.title }) }
    }

    /** What [forgetMiss] needs: the cache keys are the artist's and album's names only. */
    private fun requestOf(step: FillStep) = BookletRequest(step.artist, step.album, null, emptyList())

    /** Asks again now for what failed: forgets each failure (so the loaders no longer believe it) and starts a pass. */
    fun retryNow() {
        retryItems.forEach { step ->
            val request = requestOf(step)
            when (step.kind) {
                FillKind.Notes -> AlbumBookletRepository.forgetMiss(request)
                FillKind.TrackNotes -> TrackNotes.forgetMiss(request)
                FillKind.Story -> SongStories.forgetMiss(request.artist, step.song.orEmpty())
            }
            FillStorage.put("done1|${step.albumId}", null)
        }
        keepRetry(emptyList())
        setActivity("Retrying")
        wake.trySend(Unit)
    }

    /** Waits while a film is playing, and while the person is waiting on a lookup of their own (and a moment after). */
    private suspend fun waitForQuiet() {
        while (true) {
            if (FilmPlaying.active.value) {
                setActivity("Waiting while a film is playing")
                FilmPlaying.active.first { !it }
                continue
            }
            if (FactsPriority.waiting.value > 0) {
                setActivity("Waiting while you look at an album")
                FactsPriority.waiting.first { it == 0 }
                continue
            }
            val left = FactsPriority.lastForegroundEndMs + FILL_YIELD_QUIET_MS - now()
            if (left > 0) { delay(left); continue }
            return
        }
    }

    /**
     * Runs one step as the filler's own work. If the person starts waiting on a lookup meanwhile, the step is cut short
     * (which frees the loader's lock for them at once) and false is returned; the caller waits and runs it again.
     */
    private suspend fun runYielding(step: FillStep, request: BookletRequest): Boolean = coroutineScope {
        val work = async(FillerWork()) { runStep(step, request) }
        val watch = launch { FactsPriority.waiting.first { it > 0 }; work.cancel() }
        try {
            work.await()
            true
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // the filler itself was stopped: pass it on
            false
        } finally {
            watch.cancel()
        }
    }

    /** Forgets what the filler has noted as filled, so the next pass looks at every album again. */
    fun checkAgain() {
        FillStorage.keys().filter { it.startsWith("done1|") }.forEach { FillStorage.put(it, null) }
        wake.trySend(Unit)
    }

    // region play counts

    private fun playKey(albumId: String) = "play1|$albumId"

    private fun playsNow(): Map<String, PlayStat> = FillStorage.keys().filter { it.startsWith("play1|") }
        .mapNotNull { key -> decodePlayStat(FillStorage.get(key))?.let { key.removePrefix("play1|") to it } }.toMap()

    private fun recordPlay(albumId: String) {
        val old = decodePlayStat(FillStorage.get(playKey(albumId)))
        FillStorage.put(playKey(albumId), encodePlayStat(PlayStat((old?.count ?: 0) + 1, now())))
    }

    // endregion

    /** Runs for the life of the app (started from the root hosts); cancelled with it. */
    suspend fun run() = coroutineScope {
        // Counts an album each time it starts playing, so "most played first" has something to go by.
        launch {
            MusicPlayback.player.state.map { if (it.track != null) it.album?.browseId else null }.distinctUntilChanged()
                .collect { id -> if (isBookletAlbum(id)) recordPlay(id!!) }
        }
        launch(Dispatchers.Default) { creditsSweep() }
        var failStreak = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!_status.value.enabled || !notesOn()) { delay(5_000); continue }
            try {
                failStreak = pass(failStreak)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.w { "pass failed: $e" }
                setActivity("Stopped by an error; trying again soon")
            }
            withTimeoutOrNull(10 * 60_000L) { wake.receive() }
        }
    }

    /**
     * fork:credits - every library album's MusicBrainz credits (Who made it), on its own track beside the AI facts: no AI is
     * involved, so a Groq rate limit, a missing key, a used-up allowance or the 30-minute pause never holds it up. It still
     * waits for a film and for the person's own lookups, and MusicBrainz's own pacing (one call a second) applies.
     */
    private suspend fun creditsSweep() {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!_status.value.enabled || !notesOn()) { delay(5_000); continue }
            var failed = false
            try {
                val order = fillOrder(candidates().map { it.first }, playsNow())
                var checked = withContext(Dispatchers.Default) { order.count { !PeopleCreditsBook.missing(it.artist, it.album) } }
                _status.update { it.copy(credits = creditsLine(checked, order.size)) }
                for (c in order) {
                    currentCoroutineContext().ensureActive()
                    if (!_status.value.enabled) break
                    if (!PeopleCreditsBook.missing(c.artist, c.album)) continue
                    waitForQuiet()
                    val kept = withContext(FillerWork() + BackgroundWork()) { PeopleCreditsBook.load(c.artist, c.album, c.albumId) }
                    if (kept != null) checked += 1 else { failed = true; delay(30_000) } // MusicBrainz unreachable: slow down
                    _status.update { it.copy(credits = creditsLine(checked, order.size)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.w { "credits sweep failed: $e" }
                failed = true
            }
            // A finished sweep looks again in 10 minutes (new albums); one that hit a failure, in 2.
            delay(if (failed) 2 * 60_000L else 10 * 60_000L)
        }
    }

    private fun creditsLine(checked: Int, total: Int) = when {
        total == 0 -> ""
        checked >= total -> "Credits: all $total albums checked"
        else -> "Credits: $checked of $total albums checked"
    }

    private fun setActivity(text: String) = _status.update { it.copy(activity = text) }

    private fun candidates(): List<Pair<FillCandidate, LibraryArtist>> {
        MusicLibraryRepository.ensureLoaded()
        return MusicLibraryRepository.state.value.artists.filter { !it.channelMismatch }.flatMap { artist ->
            artist.releases
                .filter { (it.kind == ReleaseKind.Album || it.kind == ReleaseKind.EP) && it.ytBrowseId != null && isBookletAlbum(it.ytBrowseId) && (it.trackCount ?: 2) > 1 }
                .map { FillCandidate(it.ytBrowseId!!, artist.id, artist.name, it.title, artist.favourite, it.year?.take(4)?.toIntOrNull()) to artist }
        }
    }

    /** One walk through the library; returns the failure streak to carry on with. */
    private suspend fun pass(startStreak: Int): Int {
        var failStreak = startStreak
        val plays = playsNow()
        val all = candidates()
        val byId = all.associate { it.first.albumId to it.second }
        val order = fillOrder(all.map { it.first }, plays)
        val ai0 = DiscoverAiSettingsRepository.snapshot()
        if (!ai0.isConfigured) { setActivity("Needs an AI key and consent (Settings > Discover > AI)"); return failStreak }
        val model = ai0.effectiveModel
        var songs = 0
        // Tokens the provider has counted that no step has been charged for yet: stories, notes and recaps the person asked for share
        // the same daily allowance, so the budget counts them too (a free Groq key stops at 200k a day whoever spent it).
        var tokensSeen = DiscoverAiClient.tokensUsed
        // fork:ai-minute - what the last step of each kind cost, for the minute check before the next one.
        val lastCost = HashMap<FillKind, Long>()
        var done = order.count { FillStorage.get("done1|${it.albumId}") == model }
        _status.update { it.copy(albumsTotal = order.size, albumsDone = done) }
        // Starts from the kept list, so what failed before a restart stays listed until it is filled.
        val retry = LinkedHashMap<String, FillStep>().apply { retryItems.forEach { put(it.title, it) } }
        val candidateIds = order.map { it.albumId }.toSet()
        retry.values.removeAll { it.albumId !in candidateIds || FillStorage.get("done1|${it.albumId}") == model }
        val unfillable = HashSet<String>()
        val catalog = defaultMusicCatalog()
        for (c in order) {
            currentCoroutineContext().ensureActive()
            if (!_status.value.enabled || !notesOn()) return failStreak
            if (FillStorage.get("done1|${c.albumId}") == model) continue
            waitForQuiet()
            val ai = DiscoverAiSettingsRepository.snapshot()
            if (!ai.isConfigured || ai.effectiveModel != model) return failStreak
            val album = runCatching { catalog.album(c.albumId) }.getOrNull()
            if (album == null || album.tracks.isEmpty()) { delay(FILL_GAP_MS); continue }
            val request = bookletRequestFor(album, byId[c.albumId]) ?: continue
            songs += album.tracks.size
            val states = AlbumFactStates(
                AlbumBookletRepository.factState(request),
                TrackNotes.factState(request),
                request.tracks.map { it.title to SongStories.factState(request.artist, it.title) },
            )
            val plan = planAlbum(request.artist, request.album, c.albumId, states)
            plan.missed.forEach { retry[it.title] = it }
            for (step in plan.steps) {
                currentCoroutineContext().ensureActive()
                if (!_status.value.enabled || !notesOn()) return failStreak
                var budget = decodeBudget(FillStorage.get("budget")).at(now())
                val cap = _status.value.cap
                budget.resumesAtMs(cap, now())?.let { until ->
                    keepRetry(retry.values.toList())
                    _status.update { it.copy(used = budget.used) }
                    setActivity("Reached today's allowance of ${capLabel(cap)}; carries on in ${(until - now()) / 3_600_000L + 1} h")
                    return failStreak
                }
                waitForQuiet()
                // The provider has said when to come back (its day or minute is full): wait that long, which is not a failure.
                while (true) {
                    val left = DiscoverAiClient.rateLimitedForSeconds(ai)
                    if (left <= 0) break
                    setActivity("The AI provider's limit is reached; carries on in ${(left + 59) / 60} min")
                    delay(minOf(left, 60) * 1_000L)
                    currentCoroutineContext().ensureActive()
                    if (!_status.value.enabled || !notesOn()) return failStreak
                }
                // fork:ai-minute - keep the key under its minute limit: wait until this step's usual cost fits in the last minute's
                // spending (every request on the key counts, the person's own too).
                while (true) {
                    val wait = minuteRoomInMs(DiscoverAiClient.recentTokenSpends(ai), expectedCost(step.kind, lastCost), FILL_TOKENS_PER_MIN, now())
                    if (wait <= 0L) break
                    setActivity("Keeping under the AI key's minute limit; next lookup in ${(wait + 999) / 1_000} s")
                    delay(wait)
                    currentCoroutineContext().ensureActive()
                    if (!_status.value.enabled || !notesOn()) return failStreak
                    waitForQuiet()
                }
                setActivity("Filling ${step.title}")
                val tokensBefore = tokensSeen
                while (!runYielding(step, request)) waitForQuiet()
                val after = stateOf(step, request)
                // What the provider said the step cost (cut-short attempts included, they were real requests). A provider that
                // does not report tokens (Anthropic here) is charged the usual cost of the step.
                tokensSeen = DiscoverAiClient.tokensUsed
                val spent = (tokensSeen - tokensBefore).takeIf { it > 0L }
                    ?: if (ai.provider == DiscoverAiProvider.Anthropic) estimateTokens(step.kind) else 0L
                if (after == FactState.Missing) { unfillable += step.title; continue } // nothing to write from (no page); no request was made
                if (spent > 0L) lastCost[step.kind] = spent
                budget = budget.spend(spent, now())
                FillStorage.put("budget", encodeBudget(budget))
                _status.update { it.copy(used = budget.used) }
                if (after == FactState.Have) {
                    failStreak = 0
                    retry.remove(step.title)
                } else {
                    failStreak += 1
                    retry[step.title] = step
                }
                keepRetry(retry.values.toList())
                // A short breather between lookups, longer after failures; the minute's token limit is kept before each lookup above.
                val gap = nextGapMs(failStreak)
                if (failStreak >= FILL_FAILS_BEFORE_PAUSE) {
                    setActivity("Several lookups failed in a row (rate limit or key?); pausing for 30 minutes")
                    delay(gap)
                    failStreak = 0
                } else delay(gap)
            }
            val finished = planAlbum(request.artist, request.album, c.albumId, AlbumFactStates(
                AlbumBookletRepository.factState(request),
                TrackNotes.factState(request),
                request.tracks.map { it.title to SongStories.factState(request.artist, it.title) },
            ))
            if (finished.steps.all { it.title in unfillable } && finished.missed.isEmpty()) {
                FillStorage.put("done1|${c.albumId}", model)
                done += 1
                _status.update { it.copy(albumsDone = done, songsKept = songs) }
            }
        }
        setActivity(if (retry.isEmpty()) "Up to date" else "Up to date, except what failed (it is tried again after two hours)")
        keepRetry(retry.values.toList())
        _status.update { it.copy(songsKept = songs) }
        return failStreak
    }

    private fun stateOf(step: FillStep, request: BookletRequest): FactState = when (step.kind) {
        FillKind.Notes -> AlbumBookletRepository.factState(request)
        FillKind.TrackNotes -> TrackNotes.factState(request)
        FillKind.Story -> SongStories.factState(request.artist, step.song.orEmpty())
    }

    private suspend fun runStep(step: FillStep, request: BookletRequest) {
        when (step.kind) {
            FillKind.Notes -> AlbumBookletRepository.bookletFor(request)
            FillKind.TrackNotes -> TrackNotes.load(request, AlbumBookletRepository.peek(request)?.intro)
            FillKind.Story -> SongStories.load(request.artist, step.song.orEmpty(), wantAi = true)
        }
    }
}

/** Where the retry list is kept in the filler's store. */
private const val RETRY_KEY = "retry1"

/** Settings > Music > Album notes and facts: off, the filler does nothing (nothing it writes would be shown). */
private fun notesOn(): Boolean = com.nuvio.app.features.music.library.MusicLibraryRepository.state.value.settings.albumNotes
