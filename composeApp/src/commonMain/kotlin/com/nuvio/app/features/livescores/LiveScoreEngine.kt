package com.nuvio.app.features.livescores

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the platform does with a pop-up besides the Compose card: the HUD over a playing video, and the chime. */
internal expect object LiveScorePlatform {
    /** Shows [event] in every live player's HUD (null hides it). The video window covers the Compose layer. */
    fun showOnPlayers(event: ScoreEvent?, look: ScoreLook, durationMs: Long)
    fun chime(favourite: Boolean)
}

/**
 * fork:live-scores - polls the feeds while the switch is on and something is followed, and shows each new event.
 *
 * Cadence (light on purpose): today's fixtures every 10 minutes (every minute when a followed match starts within
 * 15 minutes); a followed match that is live, or due to start, every 20 seconds: favourites through ESPN's match
 * report (which also carries missed penalties and VAR), others through their competition's scoreboard, one call per
 * competition. Fight nights every minute while on, boxing every 5 minutes. When ESPN's football answers fail three
 * times running and an API-Football key is set, football comes from that instead, at most once a minute and 90
 * calls a day (its free plan allows 100).
 */
object LiveScoreEngine {
    private val log = Logger.withTag("LiveScores")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var presenter: Job? = null

    private val _events = MutableSharedFlow<ScoreEvent>(extraBufferCapacity = 32)
    /** Every event that passed the user's filters, as it happens (the phone remote listens here). */
    val events: SharedFlow<ScoreEvent> = _events.asSharedFlow()

    private val _showing = MutableStateFlow<ShownScore?>(null)
    /** The pop-up on screen now, for the Compose overlay. */
    val showing: StateFlow<ShownScore?> = _showing.asStateFlow()

    private val _status = MutableStateFlow("Off")
    /** One line for Settings: what is being watched and when the feed last answered. */
    val status: StateFlow<String> = _status.asStateFlow()

    private val _followed = MutableStateFlow<List<LiveMatch>>(emptyList())
    /** fork:live-tv-on-now - the followed matches as of the last round (empty while off), for the Live TV wall. */
    val followed: StateFlow<List<LiveMatch>> = _followed.asStateFlow()

    private val queue = Channel<ScoreEvent>(capacity = 8)

    private val matches = mutableMapOf<String, LiveMatch>()
    private var seen = mutableSetOf<String>()
    private var lastDiscoveryMs = 0L
    private var espnFootballFailures = 0
    private var apiFootballDay = ""
    private var apiFootballCalls = 0
    private var lastApiFootballMs = 0L
    private var lastFightsMs = 0L
    private var lastBoxingMs = 0L

    data class ShownScore(val event: ScoreEvent, val look: ScoreLook, val durationMs: Long, val id: Long)

    /** Starts watching the settings; polling runs only while the switch is on. Safe to call more than once. */
    fun start() {
        if (job != null) return
        LiveScoreSettingsRepository.ensureLoaded()
        presenter = scope.launch { present() }
        job = scope.launch {
            LiveScoreSettingsRepository.state
                .map { Triple(it.enabled, it.favourites, it.followed) to it.apiFootballKey }
                .distinctUntilChanged()
                .collectLatest { (watch, _) ->
                    val (enabled, favourites, followed) = watch
                    if (!enabled) {
                        _status.value = "Off"
                        _followed.value = emptyList()
                        return@collectLatest
                    }
                    if (favourites.isEmpty() && followed.isEmpty()) {
                        _status.value = "Pick a favourite team or a competition to follow."
                        _followed.value = emptyList()
                        return@collectLatest
                    }
                    lastDiscoveryMs = 0L
                    runLoop()
                }
        }
    }

    /** A test pop-up from Settings, shown the way real ones are (overlay, HUD, chime). */
    fun showSample(favourite: Boolean) {
        val settings = LiveScoreSettingsRepository.state.value
        val home = settings.favourites.firstOrNull { it.sport == LiveSport.Football }
            ?: LiveTeam(LiveSport.Football, "360", "Manchester United", "Man United")
        val away = LiveTeam(LiveSport.Football, "364", "Liverpool", "Liverpool")
        val match = LiveMatch(
            feed = LiveFeed.Espn, id = "sample", sport = LiveSport.Football, competitionKey = "eng.1",
            competitionName = "Premier League", home = home, away = away, homeScore = 2, awayScore = 1,
            phase = MatchPhase.Live, statusName = "SAMPLE", clock = "67'", startEpochMs = null,
        )
        queue.trySend(
            ScoreEvent(ScoreEventKind.Goal, match, true, "Sample pop-up", "67'", favourite, "sample|${System.currentTimeMillis()}"),
        )
    }

    private suspend fun runLoop() {
        while (true) {
            val settings = LiveScoreSettingsRepository.state.value
            val waitMs = try {
                tick(settings)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                log.w { "Live scores round failed: ${error.message}" }
                60_000L
            }
            delay(waitMs)
        }
    }

    /** One round; returns how long to wait before the next. */
    private suspend fun tick(settings: LiveScoreSettings): Long {
        val now = System.currentTimeMillis()
        val followedComps = settings.followed.mapNotNull(LiveScoreCatalog::byKey)
        val sports = (settings.favourites.map { it.sport } + followedComps.map { it.sport }).toSet()
        val relevant = { match: LiveMatch -> isFollowedMatch(match, settings.favourites, settings.followed) }

        val soonOrLive = matches.values.filter(relevant).filter { match ->
            match.phase == MatchPhase.Live || match.phase == MatchPhase.HalfTime ||
                (match.phase == MatchPhase.Before && match.startEpochMs != null && match.startEpochMs - now in -3 * 3_600_000L..15 * 60_000L)
        }
        val discoveryEvery = if (soonOrLive.isNotEmpty()) 60_000L else 10 * 60_000L
        if (now - lastDiscoveryMs >= discoveryEvery) {
            lastDiscoveryMs = now
            discover(settings, followedComps)
        }

        // Live refresh: favourites through the match report, others through their scoreboard.
        val live = matches.values.filter(relevant).filter { match ->
            match.sport != LiveSport.Mma && match.sport != LiveSport.Boxing && (
                match.phase == MatchPhase.Live || match.phase == MatchPhase.HalfTime ||
                    (match.phase == MatchPhase.Before && match.startEpochMs != null && match.startEpochMs - now in -3 * 3_600_000L..2 * 60_000L)
                )
        }
        val usingBackup = settings.apiFootballKey.isNotBlank() && espnFootballFailures >= 3
        // Football favourites: the match report (it also has missed penalties and VAR). Everything else, rugby
        // favourites included: their competition's scoreboard, which carries tries and cards (ESPN's rugby match
        // report has none), one call per competition.
        val (reportLive, boardLive) = live.filter { it.feed == LiveFeed.Espn && !(it.sport == LiveSport.Football && usingBackup) }
            .partition { it.sport == LiveSport.Football && (isFavouriteTeam(it.home, settings.favourites) || isFavouriteTeam(it.away, settings.favourites)) }
        for (match in reportLive) {
            fetchEspn("soccer/all/summary?event=${match.id}", match.sport)?.let { text ->
                parseEspnSummary(text, match.sport, match.id)?.let { observe(it, settings) }
            }
        }
        boardLive
            .mapNotNull { match -> scoreboardPath(match)?.let { it to match.sport } }
            .distinct()
            .forEach { (path, sport) ->
                fetchEspn("$path/scoreboard", sport)?.let { text -> parseEspnScoreboard(text, sport).forEach { observe(it, settings) } }
            }
        if (usingBackup && LiveSport.Football in sports && (live.any { it.sport == LiveSport.Football } || soonOrLive.isNotEmpty())) {
            pollApiFootball(settings)
        }

        // Fight nights.
        if (followedComps.any { it.key == "ufc" } && now - lastFightsMs >= 60_000L) {
            lastFightsMs = now
            fetchEspn("mma/ufc/scoreboard", LiveSport.Mma)?.let { text -> parseEspnScoreboard(text, LiveSport.Mma).forEach { observe(it, settings) } }
        }
        if (followedComps.any { it.key == "boxing" } && now - lastBoxingMs >= 5 * 60_000L) {
            lastBoxingMs = now
            pollBoxing(settings)
        }

        prune(now)
        _followed.value = matches.values.filter(relevant)
        val liveCount = matches.values.count { relevant(it) && (it.phase == MatchPhase.Live || it.phase == MatchPhase.HalfTime) }
        val todayCount = matches.values.count(relevant)
        _status.value = when {
            liveCount > 0 -> "Watching $liveCount live ${if (liveCount == 1) "match" else "matches"}" +
                if (usingBackup) " (football from API-Football)" else ""
            todayCount > 0 -> "$todayCount followed ${if (todayCount == 1) "match" else "matches"} today or recently; none live."
            else -> "No followed matches today."
        }
        return if (live.isNotEmpty()) 20_000L else 30_000L
    }

    private suspend fun discover(settings: LiveScoreSettings, followedComps: List<LiveCompetition>) {
        // Favourites wherever they play: the sport's "all leagues" feed.
        val favouriteSports = settings.favourites.map { it.sport }.toSet()
        for (sport in favouriteSports) {
            val path = LiveScoreCatalog.espnAllPath(sport) ?: continue
            if (sport == LiveSport.Mma) continue
            fetchEspn("$path/scoreboard", sport, large = true)?.let { text ->
                parseEspnScoreboard(text, sport).forEach { observe(it, settings) }
            }
        }
        // Followed competitions: each one's own scoreboard (small), unless the "all" feed already covered the sport.
        followedComps
            .filter { it.espnPath != null && it.sport !in favouriteSports && it.sport != LiveSport.Mma }
            .forEach { competition ->
                fetchEspn("${competition.espnPath}/scoreboard", competition.sport)?.let { text ->
                    parseEspnScoreboard(text, competition.sport).forEach { observe(it, settings) }
                }
            }
        // The "all" feed may leave out a followed competition's match: check those leagues too, once per discovery.
        followedComps
            .filter { it.espnPath != null && it.sport in favouriteSports && it.sport != LiveSport.Mma }
            .filter { competition -> matches.values.none { it.competitionKey == competition.key } }
            .forEach { competition ->
                fetchEspn("${competition.espnPath}/scoreboard", competition.sport)?.let { text ->
                    parseEspnScoreboard(text, competition.sport).forEach { observe(it, settings) }
                }
            }
    }

    /** A match's own competition scoreboard: the catalogue's path, or ESPN's league id for one not listed. */
    private fun scoreboardPath(match: LiveMatch): String? {
        LiveScoreCatalog.byKey(match.competitionKey.orEmpty())?.espnPath?.let { return it }
        val leagueId = match.competitionKey?.takeIf { key -> key.all { it.isDigit() } } ?: return null
        return when (match.sport) {
            // A rugby league id in a uid is not its URL slug: an unlisted competition is read from the "all" feed.
            LiveSport.Rugby -> "rugby/all"
            LiveSport.RugbyLeague -> "rugby-league/3"
            // ESPN football paths are slugs; an unlisted league's favourite match uses the match report instead.
            else -> null
        }
    }

    private suspend fun fetchEspn(path: String, sport: LiveSport, large: Boolean = false): String? {
        val url = "https://site.api.espn.com/apis/site/v2/sports/$path"
        val text = get(url, emptyMap(), large)
        if (sport == LiveSport.Football) {
            espnFootballFailures = if (text == null) espnFootballFailures + 1 else 0
        }
        return text
    }

    private suspend fun pollApiFootball(settings: LiveScoreSettings) {
        val now = System.currentTimeMillis()
        val day = (now / 86_400_000L).toString()
        if (day != apiFootballDay) {
            apiFootballDay = day
            apiFootballCalls = 0
        }
        if (apiFootballCalls >= 90 || now - lastApiFootballMs < 60_000L) return
        lastApiFootballMs = now
        apiFootballCalls++
        val text = get(
            "https://v3.football.api-sports.io/fixtures?live=all",
            mapOf("x-apisports-key" to settings.apiFootballKey),
        ) ?: return
        parseApiFootballLive(text).forEach { observe(it, settings) }
        // A good ESPN answer later puts football back on ESPN (fetchEspn resets the count).
    }

    private suspend fun pollBoxing(settings: LiveScoreSettings) {
        val now = System.currentTimeMillis()
        listOf("eventsnextleague", "eventspastleague").forEach { list ->
            get("https://www.thesportsdb.com/api/v1/json/123/$list.php?id=4445", emptyMap())?.let { text ->
                parseSportsDbFights(text, now)
                    .filter { it.startEpochMs == null || kotlin.math.abs(it.startEpochMs - now) < 2 * 86_400_000L }
                    .forEach { observe(it, settings) }
            }
        }
    }

    private suspend fun get(url: String, headers: Map<String, String>, large: Boolean = false): String? = try {
        val response = withTimeoutOrNull(15_000L) {
            httpRequestRaw(
                method = "GET",
                url = url,
                headers = mapOf("Accept" to "application/json", "User-Agent" to "Mozilla/5.0 NuvioHTPC") + headers,
                body = "",
                allowLargeResponse = large,
                callTimeoutMs = 12_000L,
            )
        }
        when {
            response == null -> null.also { log.i { "Live scores: no answer from ${url.substringBefore('?')}" } }
            response.status !in 200..299 -> null.also { log.i { "Live scores: HTTP ${response.status} from ${url.substringBefore('?')}" } }
            else -> response.body
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Throwable) {
        log.i { "Live scores: ${url.substringBefore('?')} failed: ${error.message}" }
        null
    }

    private fun observe(current: LiveMatch, settings: LiveScoreSettings) {
        val previous = matches[current.id]
        // A followed match keeps its state; anything else is only remembered (a favourite's opponent's league...).
        val followed = isFollowedMatch(current, settings.favourites, settings.followed)
        matches[current.id] = current
        if (!followed) return
        val favourite = isFavouriteTeam(current.home, settings.favourites) || isFavouriteTeam(current.away, settings.favourites)
        val result = detectScoreEvents(previous, current, favourite, seen)
        seen = result.seen.toMutableSet()
        result.events
            .filter { it.kind.group in settings.groups }
            .forEach { event ->
                log.i { "Live scores: ${event.kind.label} ${scoreLine(event.match)} ${event.clock.orEmpty()} ${event.detail.orEmpty()}" }
                _events.tryEmit(event)
                queue.trySend(event)
            }
    }

    private fun prune(now: Long) {
        val stale = matches.values.filter { match ->
            val start = match.startEpochMs ?: return@filter false
            (match.phase == MatchPhase.Finished || match.phase == MatchPhase.Postponed) && now - start > 6 * 3_600_000L ||
                now - start > 30 * 3_600_000L
        }.map { it.id }.toSet()
        if (stale.isEmpty()) return
        stale.forEach(matches::remove)
        seen.removeAll { key -> key.substringBefore('|') in stale }
    }

    /** Shows queued events one at a time; several waiting are shown more briefly so none is stale. */
    private suspend fun present() {
        var counter = 0L
        for (event in queue) {
            val settings = LiveScoreSettingsRepository.state.value
            val look = settings.look
            val waiting = queue.isEmpty.not()
            val baseMs = when {
                look == ScoreLook.BigFlash && event.favourite -> 7_000L
                look == ScoreLook.Ticker -> 7_000L
                else -> 8_000L
            }
            val durationMs = if (waiting) baseMs * 3 / 4 else baseMs
            val shown = ShownScore(event, look, durationMs, ++counter)
            _showing.value = shown
            runCatching { LiveScorePlatform.showOnPlayers(event, look, durationMs) }
            val chime = when (settings.chime) {
                ScoreChime.Always -> true
                ScoreChime.Favourites -> event.favourite
                ScoreChime.Never -> false
            }
            if (chime) runCatching { LiveScorePlatform.chime(event.favourite) }
            delay(durationMs)
            if (_showing.value?.id == shown.id) _showing.value = null
            delay(400L)
        }
    }

    /** Hides the card on screen now (Back on the overlay). */
    fun dismiss() {
        _showing.value = null
        runCatching { LiveScorePlatform.showOnPlayers(null, ScoreLook.Corner, 0L) }
    }
}
