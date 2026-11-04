package com.nuvio.app.features.music.booklet
// FORK UI (Spyder): Nick's album page facts, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import co.touchlab.kermit.Logger
import com.nuvio.app.features.discover.DiscoverAiSettingsRepository
import com.nuvio.app.features.music.defaultMusicCatalog
import com.nuvio.app.features.music.MusicPlayback
import com.nuvio.app.features.music.bookletRequestFor
import com.nuvio.app.features.music.library.LibraryArtist
import com.nuvio.app.features.music.library.MusicLibraryRepository
import com.nuvio.app.features.music.library.ReleaseKind
import kotlinx.coroutines.CancellationException
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
    val used: Int = 0,
    val cap: Int = FILL_CAP_DEFAULT,
    /** Lookups that failed a moment ago; each is tried again by itself after two hours. */
    val retry: List<String> = emptyList(),
)

/** True while the player owns the screen (a film or episode): the filler waits, so it never competes with playback. Set by the player screen. */
internal object FilmPlaying {
    val active = MutableStateFlow(false)
}

internal object FactsFiller {
    private val log = Logger.withTag("FactsFiller")
    private val _status = MutableStateFlow(FillStatus(enabled = FillStorage.get("enabled") == "1", cap = decodeCap(FillStorage.get("cap"))))
    val status: StateFlow<FillStatus> = _status.asStateFlow()

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    fun setEnabled(on: Boolean) {
        FillStorage.put("enabled", if (on) "1" else "0")
        _status.update { it.copy(enabled = on, activity = if (on) "Starting" else "") }
    }

    fun setCap(cap: Int) {
        FillStorage.put("cap", cap.toString())
        _status.update { it.copy(cap = cap) }
    }

    /** Pass-to-pass sleep that "Retry now" and "Check again" cut short. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** The failed lookups of the last pass, with what is needed to forget their failure. */
    @Volatile
    private var retryItems: List<Pair<FillStep, BookletRequest>> = emptyList()

    /** Asks again now for what failed: forgets each failure (so the loaders no longer believe it) and starts a pass. */
    fun retryNow() {
        retryItems.forEach { (step, request) ->
            when (step.kind) {
                FillKind.Notes -> AlbumBookletRepository.forgetMiss(request)
                FillKind.TrackNotes -> TrackNotes.forgetMiss(request)
                FillKind.Story -> SongStories.forgetMiss(request.artist, step.song.orEmpty())
            }
            FillStorage.put("done1|${step.albumId}", null)
        }
        retryItems = emptyList()
        _status.update { it.copy(retry = emptyList(), activity = "Retrying") }
        wake.trySend(Unit)
    }

    /** Waits while a film is playing. */
    private suspend fun waitForQuiet() {
        if (!FilmPlaying.active.value) return
        setActivity("Waiting while a film is playing")
        FilmPlaying.active.first { !it }
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
        var failStreak = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!_status.value.enabled) { delay(5_000); continue }
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
        var done = order.count { FillStorage.get("done1|${it.albumId}") == model }
        _status.update { it.copy(albumsTotal = order.size, albumsDone = done) }
        val retry = LinkedHashMap<String, Pair<FillStep, BookletRequest>>()
        val unfillable = HashSet<String>()
        val catalog = defaultMusicCatalog()
        for (c in order) {
            currentCoroutineContext().ensureActive()
            if (!_status.value.enabled) return failStreak
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
            plan.missed.forEach { retry[it.title] = it to request }
            for (step in plan.steps) {
                currentCoroutineContext().ensureActive()
                if (!_status.value.enabled) return failStreak
                var budget = decodeBudget(FillStorage.get("budget")).at(now())
                val cap = _status.value.cap
                budget.resumesAtMs(cap, now())?.let { until ->
                    retryItems = retry.values.toList()
                    _status.update { it.copy(used = budget.used, retry = retry.keys.toList()) }
                    setActivity("Reached today's limit of $cap lookups; carries on in ${(until - now()) / 3_600_000L + 1} h")
                    return failStreak
                }
                waitForQuiet()
                setActivity("Filling ${step.title}")
                runStep(step, request)
                val after = stateOf(step, request)
                if (after == FactState.Missing) { unfillable += step.title; continue } // nothing to write from (no page); no request was made
                budget = budget.spend(requestsFor(step.kind), now())
                FillStorage.put("budget", encodeBudget(budget))
                _status.update { it.copy(used = budget.used) }
                if (after == FactState.Have) {
                    failStreak = 0
                    retry.remove(step.title)
                } else {
                    failStreak += 1
                    retry[step.title] = step to request
                }
                retryItems = retry.values.toList()
                _status.update { it.copy(retry = retry.keys.toList()) }
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
        retryItems = retry.values.toList()
        _status.update { it.copy(retry = retry.keys.toList(), songsKept = songs) }
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
