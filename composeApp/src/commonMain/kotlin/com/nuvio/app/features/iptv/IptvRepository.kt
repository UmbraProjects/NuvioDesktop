package com.nuvio.app.features.iptv

import co.touchlab.kermit.Logger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

/** Everything IPTV keeps for a profile. Local only: playlists hold provider passwords. */
@Serializable
data class IptvSettings(
    val playlists: List<IptvPlaylist> = emptyList(),
    /** `playlistId:channelKey`, in the order they were added. */
    val favourites: List<String> = emptyList(),
    /** `playlistId:channelKey`, most recent first. */
    val recents: List<String> = emptyList(),
    val refreshHours: Int = IptvRefreshPolicy.DEFAULT_HOURS,
    /** [IptvStreamFormat] to try first; null = Auto (TS first). */
    val preferredFormat: String? = null,
    /** Route IPTV traffic through a proxy, for providers the user's ISP blocks. */
    val proxy: IptvProxy = IptvProxy(),
    /** How far back live TV can be rewound, in minutes; 0 = mpv's own default (a few minutes). */
    val rewindMinutes: Int = 0,
    /**
     * Settings > Live TV > Show in navigation. Null until the switch is first used: the tab then shows
     * while a playlist exists, as it always did, so turning the switch into a setting moves nobody's bar.
     */
    val tabEnabled: Boolean? = null,
    /**
     * fork:live-tv-on-now - Settings > Live TV > On Now. The tab opens on what is on (sport with scores, films,
     * the usual channels), copies of one channel become one row, and a programme can be started over.
     */
    val onNow: Boolean = false,
    /** fork:live-tv-on-now - when each channel was put on, newest last, for "Your channels" ([IptvUsuals]). */
    val plays: List<IptvPlay> = emptyList(),
    /** fork:live-tv-triage - by playlist id: the countries and kinds its triage kept. No entry = everything shows. */
    val triage: Map<String, IptvTriage> = emptyMap(),
    /** fork:live-tv-lineup - Settings > Live TV > Your 40. The tab opens on a numbered lineup of about forty channels. */
    val lineupOn: Boolean = false,
    /** fork:live-tv-lineup - by playlist id: the lineup's channel keys in order (the position is the number). No entry = not built yet. */
    val lineup: Map<String, List<String>> = emptyMap(),
    /** fork:live-tv-lineup - by playlist id: the keys of every copy of the channels thrown out with "Never again". */
    val never: Map<String, Set<String>> = emptyMap(),
    /** fork:live-tv-departure - Settings > Live TV > Starting soon. The On Now wall gets a departure board; programmes can have a reminder. */
    val departure: Boolean = false,
    /** fork:live-tv-departure - the reminders waiting, earliest first ([IptvReminderRules]). */
    val reminders: List<IptvReminder> = emptyList(),
    /** fork:live-tv-decide - Settings > Live TV > Decide for me: Three things, I have N minutes, Earlier today, Following. */
    val decide: Boolean = false,
    /** fork:live-tv-decide - the minutes dialled on "I have N minutes". */
    val minutes: Int = IptvDecide.DEFAULT_MINUTES,
    /** fork:live-tv-decide - tonight's reshuffle of Three things. */
    val shuffle: IptvShuffle = IptvShuffle(),
    /** fork:live-tv-decide - what is followed: programme titles, teams, "any Bond film". */
    val follows: List<String> = emptyList(),
    /** fork:live-tv-stage - Live TV as full-screen stages; off by default. */
    val stage: Boolean = false,
    /** fork:live-tv-on-demand - offer a programme under way from the start through the add-ons; off by default. */
    val onDemand: Boolean = false,
    /** fork:live-tv-stage - what was asked on Just ask, newest first. */
    val asks: List<IptvAsked> = emptyList(),
    /** fork:live-tv-weed - overnight checks of the lineup's channels; off by default. */
    val weed: Boolean = false,
    /** fork:live-tv-weed - what the checks found, per playlist and channel key. */
    val weeds: Map<String, Map<String, IptvWeedRecord>> = emptyMap(),
    /** fork:live-tv-evening - Tonight's running order and Just ask; off by default. */
    val evening: Boolean = false,
    /** fork:live-tv-brakes - zap budget and end card; off by default. */
    val brakes: Boolean = false,
    /** fork:live-tv-ask-ai - let the viewer's own AI answer Just ask; off by default, needs the shared AI consent. */
    val askAi: Boolean = false,
    /** fork:live-tv-taste - what was watched and finished, per programme title (see IptvTasteRules). */
    val titles: Map<String, IptvTitleStat> = emptyMap(),
    /** fork:live-tv-subtitles - subtitle add-ons for films and episodes on live channels, synced to the channel; off by default. */
    val liveSubtitles: Boolean = false,
    /** fork:live-tv-subtitles - match those subtitles by their words with the voice control speech model; off by default. */
    val liveSubtitleWords: Boolean = false,
    /** fork:live-tv-views - the extra stages switched on in Settings (IptvStage names; see IptvViewRules.VIEWS). */
    val views: Set<String> = emptySet(),
    /** fork:live-tv-learn - the teams in what was watched, by name key (see IptvTeamRules). */
    val teams: Map<String, IptvTeamStat> = emptyMap(),
    /** fork:live-tv-learn - teams the viewer said no to following; Following does not ask about them again. */
    val teamsDeclined: Set<String> = emptySet(),
    /** fork:live-tv-brakes - the honest tally, one entry per night (key: the night's number). */
    val tally: Map<String, IptvNight> = emptyMap(),
    /** fork:live-tv-brakes - the night the zap-budget card was last offered. */
    val brakeOffered: Long? = null,
    /** fork:live-tv-front-door - the pick shows a live still of its channel (refreshed while nothing plays); off by default. */
    val doorPreview: Boolean = false,
    /** fork:live-tv-front-door - the front door's scores hidden, so it never spoils a result. */
    val doorHideScores: Boolean = false,
    /** fork:live-tv-polish - the viewer's country (ISO code): its channels rank higher, other countries' news lower. Null = from the triage. */
    val homeRegion: String? = null,
) {
    /** Whether the Live TV entry belongs in the navigation. */
    val tabShown: Boolean
        get() = tabEnabled ?: playlists.isNotEmpty()
}

/** The rewind buffer choices, in minutes; anything above 0 is kept on disk, not in memory. */
val IPTV_REWIND_OPTIONS = listOf(0, 10, 30, 60)

@Serializable
enum class IptvProxyType { Off, Http, Socks }

/**
 * A proxy for IPTV requests only (the rest of the app connects directly). Playlists, guides and
 * account checks honour both types; mpv can only use an HTTP proxy, so SOCKS covers loading but
 * not playback ([mpvHttpProxyUrl] is null for it).
 */
@Serializable
data class IptvProxy(
    val type: IptvProxyType = IptvProxyType.Off,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
) {
    val enabled: Boolean get() = type != IptvProxyType.Off && host.isNotBlank() && port in 1..65535

    /** `http://user:pass@host:port` for mpv's `http-proxy` option; null unless an HTTP proxy is set. */
    fun mpvHttpProxyUrl(): String? {
        if (!enabled || type != IptvProxyType.Http) return null
        val auth = if (username.isNotEmpty()) {
            fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
            "${enc(username)}:${enc(password)}@"
        } else {
            ""
        }
        return "http://$auth$host:$port"
    }

    companion object {
        /** Accepts `host`, `host:port` or a pasted `http://` / `socks5://` URL; returns host and port (0 if absent). */
        fun parseHostAndPort(raw: String): Pair<String, Int> {
            val trimmed = raw.trim().substringAfter("://").substringBefore('/').substringAfterLast('@')
            if (trimmed.startsWith("[")) return trimmed to 0 // IPv6 literal; port stays separate
            val colon = trimmed.lastIndexOf(':')
            if (colon <= 0) return trimmed to 0
            val port = trimmed.substring(colon + 1).toIntOrNull() ?: return trimmed to 0
            return trimmed.substring(0, colon) to port
        }
    }
}

/** A playlist's channels as the UI sees them: what is cached, and whether a refresh is running or failed. */
data class IptvContentState(
    val content: IptvPlaylistContent? = null,
    val loading: Boolean = false,
    val error: IptvSourceException? = null,
)

/**
 * IPTV playlists for the active profile and their loaded channels. The settings follow the
 * PlaylistRepository shape (an in-memory StateFlow, loaded lazily, written through). Channel lists
 * are cached on disk and refreshed behind by [IptvRefreshPolicy].
 */
object IptvRepository {
    private val log = Logger.withTag("Iptv")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private const val MAX_RECENTS = 30
    private const val CACHE_VERSION = 1

    private val _settings = MutableStateFlow(IptvSettings())
    val settings: StateFlow<IptvSettings> = _settings.asStateFlow()

    private var hasLoaded = false

    private val contentStates = ConcurrentHashMap<String, MutableStateFlow<IptvContentState>>()
    private val loadLocks = ConcurrentHashMap<String, Mutex>()
    private val lastFailureAt = ConcurrentHashMap<String, Long>()
    private val checkedThisLaunch: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
        val payload = IptvStorage.loadPayload()
        if (payload.isNullOrBlank()) return
        runCatching {
            _settings.value = json.decodeFromString<IptvSettings>(payload)
            // Their logos are fetched, and logged, before anything else asks for a portal's address.
            _settings.value.playlists.filter { it.type == IptvPlaylistType.Stalker }.forEach { StalkerUrls.base(it) }
        }.onFailure { error ->
            log.e(error) { "Failed to load IPTV settings from storage" }
        }
    }

    fun onProfileChanged() {
        hasLoaded = false
        _settings.value = IptvSettings()
        contentStates.clear()
        lastFailureAt.clear()
        checkedThisLaunch.clear()
        IptvEpgRepository.clear()
    }

    fun clearLocalState() {
        onProfileChanged()
        IptvCacheStorage.clearAll()
    }

    // region Playlists

    fun playlist(id: String): IptvPlaylist? {
        ensureLoaded()
        return _settings.value.playlists.firstOrNull { it.id == id }
    }

    /** Adds [draft] under a new id (its own `id` is ignored) and returns the stored playlist. */
    fun addPlaylist(draft: IptvPlaylist): IptvPlaylist {
        ensureLoaded()
        val playlist = sanitized(draft.copy(id = UUID.randomUUID().toString()))
        mutate { it.copy(playlists = it.playlists + playlist) }
        return playlist
    }

    /** Replaces the playlist with [playlist]'s id. Changed credentials or URLs invalidate its cache. */
    fun updatePlaylist(playlist: IptvPlaylist) {
        ensureLoaded()
        val clean = sanitized(playlist)
        val previous = playlist(clean.id) ?: return
        mutate { s -> s.copy(playlists = s.playlists.map { if (it.id == clean.id) clean else it }) }
        if (previous.withoutName() != clean.withoutName()) {
            contentStates[clean.id]?.value = IptvContentState()
            checkedThisLaunch.remove(clean.id)
            lastFailureAt.remove(clean.id)
            IptvEpgRepository.forget(clean.id)
        }
    }

    fun removePlaylist(id: String) {
        ensureLoaded()
        val prefix = "$id:"
        mutate { s ->
            s.copy(
                playlists = s.playlists.filterNot { it.id == id },
                favourites = s.favourites.filterNot { it.startsWith(prefix) },
                recents = s.recents.filterNot { it.startsWith(prefix) },
                plays = s.plays.filterNot { it.ref.startsWith(prefix) },
                triage = s.triage - id,
                lineup = s.lineup - id,
                never = s.never - id,
                weeds = s.weeds - id,
                reminders = s.reminders.filterNot { it.playlistId == id },
            )
        }
        contentStates.remove(id)
        lastFailureAt.remove(id)
        checkedThisLaunch.remove(id)
        IptvEpgRepository.forget(id)
        runCatching {
            IptvCacheStorage.delete(contentCacheName(id))
            IptvEpgRepository.guideCacheNames(id).forEach { IptvCacheStorage.delete(it) }
        }
    }

    private fun sanitized(p: IptvPlaylist): IptvPlaylist = p.copy(
        name = p.name.trim(),
        server = p.server?.trim()?.takeIf { it.isNotEmpty() },
        username = p.username?.trim()?.takeIf { it.isNotEmpty() }?.let { username ->
            if (p.type == IptvPlaylistType.Stalker) StalkerIdentity.normalizeMac(username) ?: username else username
        },
        // Not trimmed (a password may have spaces), but never a line break: a pasted one kept a trailing newline,
        // which became %0A in every stream URL and the server refused them all (Nov 16).
        password = p.password?.withoutLineBreaks()?.takeIf { it.isNotEmpty() },
        url = p.url?.trim()?.takeIf { it.isNotEmpty() },
        epgUrl = p.epgUrl?.trim()?.takeIf { it.isNotEmpty() },
        serial = p.serial?.trim()?.takeIf { it.isNotEmpty() },
        deviceId = p.deviceId?.trim()?.takeIf { it.isNotEmpty() },
        deviceId2 = p.deviceId2?.trim()?.takeIf { it.isNotEmpty() },
        signature = p.signature?.trim()?.takeIf { it.isNotEmpty() },
    )

    private fun IptvPlaylist.withoutName() = copy(name = "")

    // endregion

    // region Favourites, recents, preferences

    fun channelRef(playlistId: String, channelKey: String) = "$playlistId:$channelKey"

    fun isFavourite(playlistId: String, channelKey: String): Boolean =
        channelRef(playlistId, channelKey) in _settings.value.favourites

    fun toggleFavourite(playlistId: String, channelKey: String) {
        ensureLoaded()
        val ref = channelRef(playlistId, channelKey)
        mutate { s -> s.copy(favourites = if (ref in s.favourites) s.favourites - ref else s.favourites + ref) }
    }

    fun recordRecent(playlistId: String, channelKey: String) {
        ensureLoaded()
        val ref = channelRef(playlistId, channelKey)
        mutate { s ->
            s.copy(
                recents = (listOf(ref) + (s.recents - ref)).take(MAX_RECENTS),
                plays = (s.plays + IptvPlay(ref, System.currentTimeMillis())).takeLast(IptvUsuals.MAX_PLAYS),
            )
        }
    }

    /** fork:live-tv-triage - saves [playlistId]'s triage; null shows everything again. */
    fun setTriage(playlistId: String, triage: IptvTriage?) {
        ensureLoaded()
        mutate { s -> s.copy(triage = if (triage == null) s.triage - playlistId else s.triage + (playlistId to triage)) }
    }

    /** fork:live-tv-decide */
    fun setDecide(enabled: Boolean) {
        ensureLoaded()
        mutate { it.copy(decide = enabled) }
    }

    fun setOnDemand(enabled: Boolean) {
        ensureLoaded()
        mutate { it.copy(onDemand = enabled) }
    }

    /** fork:live-tv-front-door */
    fun setDoorPreview(enabled: Boolean) {
        ensureLoaded()
        mutate { it.copy(doorPreview = enabled) }
    }

    /** fork:live-tv-polish */
    fun setHomeRegion(region: String?) {
        ensureLoaded()
        mutate { it.copy(homeRegion = region) }
    }

    /** fork:live-tv-front-door */
    fun setDoorHideScores(hidden: Boolean) {
        ensureLoaded()
        mutate { it.copy(doorHideScores = hidden) }
    }

    fun setStage(enabled: Boolean) {
        ensureLoaded()
        mutate { it.copy(stage = enabled) }
    }

    /** fork:live-tv-stage - keeps the last eight asks, the same line once. */
    fun rememberAsk(text: String, at: Long = System.currentTimeMillis()) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        ensureLoaded()
        mutate { s -> s.copy(asks = (listOf(IptvAsked(clean, at)) + s.asks.filterNot { it.text.equals(clean, ignoreCase = true) }).take(8)) }
    }

    fun setWeed(enabled: Boolean) {
        ensureLoaded()
        mutate { it.copy(weed = enabled) }
    }

    /** fork:live-tv-weed - one channel's result for [night]. */
    fun weedResult(playlistId: String, key: String, ok: Boolean, night: Long) {
        ensureLoaded()
        mutate { s -> s.copy(weeds = s.weeds + (playlistId to IptvWeedRules.record(s.weeds[playlistId].orEmpty(), key, ok, night))) }
    }

    fun setEvening(enabled: Boolean) {
        ensureLoaded()
        mutate { it.copy(evening = enabled) }
    }

    fun setAskAi(enabled: Boolean) {
        ensureLoaded()
        mutate { it.copy(askAi = enabled) }
    }

    /** fork:live-tv-subtitles */
    fun setLiveSubtitles(enabled: Boolean) {
        ensureLoaded()
        mutate { it.copy(liveSubtitles = enabled) }
    }

    /** fork:live-tv-subtitles */
    fun setLiveSubtitleWords(enabled: Boolean) {
        ensureLoaded()
        mutate { it.copy(liveSubtitleWords = enabled) }
    }

    /** fork:live-tv-views - one extra stage on or off. */
    fun setView(stage: IptvStage, enabled: Boolean) {
        ensureLoaded()
        mutate { it.copy(views = if (enabled) it.views + stage.name else it.views - stage.name) }
    }

    fun setBrakes(enabled: Boolean) {
        ensureLoaded()
        mutate { it.copy(brakes = enabled) }
    }

    /** fork:live-tv-brakes - adds to tonight's tally. */
    fun tally(night: Long, change: (IptvNight) -> IptvNight) {
        ensureLoaded()
        mutate { it.copy(tally = IptvBrakeRules.add(it.tally, night, change)) }
    }

    /** fork:live-tv-taste - a programme seen or finished. */
    fun noteWatch(entry: IptvEpgEntry, event: IptvWatchEvent, now: Long = System.currentTimeMillis()) {
        if (event == IptvWatchEvent.None) return
        ensureLoaded()
        mutate {
            it.copy(
                titles = IptvTasteRules.note(it.titles, entry, event, now),
                teams = IptvTeamRules.note(it.teams, entry, event, now),
            )
        }
    }

    /** fork:live-tv-learn - "Not interested" on a learned team: never offered again. */
    fun declineTeam(key: String) {
        ensureLoaded()
        mutate { it.copy(teamsDeclined = it.teamsDeclined + key) }
    }

    /** fork:live-tv-taste - forget what was watched. */
    fun forgetWatched() {
        ensureLoaded()
        mutate { it.copy(titles = emptyMap(), teams = emptyMap(), teamsDeclined = emptySet()) }
    }

    fun brakeOffered(night: Long) {
        ensureLoaded()
        mutate { it.copy(brakeOffered = night) }
    }

    fun setMinutes(minutes: Int) {
        ensureLoaded()
        if (minutes in IptvDecide.MINUTE_CHOICES) mutate { it.copy(minutes = minutes) }
    }

    /** One reshuffle a night: does nothing once tonight's is used. */
    fun reshuffleThree(night: Long) {
        ensureLoaded()
        mutate { s -> if (IptvDecide.canReshuffle(s.shuffle, night)) s.copy(shuffle = IptvShuffle(night, 1)) else s }
    }

    fun follow(text: String) {
        ensureLoaded()
        val clean = text.trim()
        if (clean.isEmpty()) return
        mutate { s -> if (IptvDecide.normalise(clean) in s.follows.map(IptvDecide::normalise)) s else s.copy(follows = s.follows + clean) }
    }

    /** Stops following everything that [title] counts as. */
    fun unfollow(title: String) {
        ensureLoaded()
        mutate { s -> s.copy(follows = s.follows - IptvDecide.followsOf(s.follows, title).toSet()) }
    }

    /** fork:live-tv-departure */
    fun setDeparture(enabled: Boolean) {
        ensureLoaded()
        mutate { it.copy(departure = enabled) }
    }

    /** fork:live-tv-departure - sets [reminder] (one per programme: setting it again replaces it). */
    fun addReminder(reminder: IptvReminder) {
        ensureLoaded()
        mutate { it.copy(reminders = IptvReminderRules.add(it.reminders, reminder)) }
    }

    fun removeReminder(id: String) {
        ensureLoaded()
        mutate { s -> s.copy(reminders = s.reminders.filterNot { it.id == id }) }
    }

    /** fork:live-tv-departure - takes the reminders that are due at [now] out of the list and returns them; late ones just go. */
    fun takeDueReminders(now: Long): List<IptvReminder> {
        ensureLoaded()
        val due = IptvReminderRules.due(_settings.value.reminders, now)
        val gone = (due + IptvReminderRules.stale(_settings.value.reminders, now)).mapTo(HashSet()) { it.id }
        if (gone.isNotEmpty()) mutate { s -> s.copy(reminders = s.reminders.filterNot { it.id in gone }) }
        return due
    }

    /** fork:live-tv-lineup */
    fun setLineupOn(enabled: Boolean) {
        ensureLoaded()
        mutate { it.copy(lineupOn = enabled) }
    }

    /** fork:live-tv-lineup - stores [playlistId]'s lineup (channel keys in order). */
    fun setLineup(playlistId: String, keys: List<String>) {
        ensureLoaded()
        mutate { it.copy(lineup = it.lineup + (playlistId to keys)) }
    }

    /** fork:live-tv-lineup - throws [channel] out for good: out of the lineup, the favourites and the lists. */
    fun neverAgain(playlistId: String, channel: IptvMergedChannel) {
        ensureLoaded()
        val keys = IptvLineupRules.neverKeys(channel)
        val refs = keys.map { channelRef(playlistId, it) }.toSet()
        mutate { s ->
            s.copy(
                never = s.never + (playlistId to (s.never[playlistId].orEmpty() + keys)),
                lineup = s.lineup[playlistId]?.let { s.lineup + (playlistId to IptvLineupRules.remove(it, channel)) } ?: s.lineup,
                favourites = s.favourites - refs,
            )
        }
    }

    /** fork:live-tv-lineup - brings back every channel thrown out (all playlists). */
    fun bringBackAll() {
        ensureLoaded()
        mutate { it.copy(never = emptyMap(), weeds = emptyMap()) }
    }

    /** fork:live-tv-on-now */
    fun setOnNow(enabled: Boolean) {
        ensureLoaded()
        mutate { it.copy(onNow = enabled) }
    }

    /** fork:live-tv-on-now - stars or unstars a merged channel: any starred copy counts, and unstarring clears them all. */
    fun toggleFavourite(playlistId: String, channel: IptvMergedChannel) {
        ensureLoaded()
        val refs = channel.sources.map { channelRef(playlistId, it.key) }
        mutate { s ->
            if (refs.any { it in s.favourites }) s.copy(favourites = s.favourites - refs.toSet()) else s.copy(favourites = s.favourites + refs.first())
        }
    }

    fun setRefreshHours(hours: Int) {
        ensureLoaded()
        mutate { it.copy(refreshHours = hours.coerceAtLeast(0)) }
    }

    fun setPreferredFormat(format: String?) {
        ensureLoaded()
        mutate { it.copy(preferredFormat = format?.takeIf { f -> f == IptvStreamFormat.TS || f == IptvStreamFormat.HLS }) }
    }

    fun setRewindMinutes(minutes: Int) {
        ensureLoaded()
        mutate { it.copy(rewindMinutes = minutes.takeIf { m -> m in IPTV_REWIND_OPTIONS } ?: 0) }
    }

    fun setTabEnabled(enabled: Boolean) {
        ensureLoaded()
        mutate { it.copy(tabEnabled = enabled) }
    }

    fun setProxy(proxy: IptvProxy) {
        ensureLoaded()
        val clean = proxy.copy(host = proxy.host.trim(), username = proxy.username.trim())
        if (clean == _settings.value.proxy) return
        mutate { it.copy(proxy = clean) }
        // A list that failed because the provider was unreachable should be tried again at once.
        lastFailureAt.clear()
    }

    private fun mutate(change: (IptvSettings) -> IptvSettings) {
        _settings.update(change)
        persist()
    }

    private fun persist() {
        runCatching { IptvStorage.savePayload(json.encodeToString(IptvSettings.serializer(), _settings.value)) }
            .onFailure { log.e(it) { "Failed to save IPTV settings" } }
    }

    // endregion

    // region Channels

    fun contentState(playlistId: String): StateFlow<IptvContentState> =
        contentStates.getOrPut(playlistId) { MutableStateFlow(IptvContentState()) }.asStateFlow()

    /**
     * Makes [playlistId]'s channels available: the disk cache first (shown at once), then a download
     * when the cache is missing, stale by the refresh setting, or [force]d. A failed download keeps
     * whatever was cached and records the error. Concurrent calls for one playlist collapse into one.
     */
    suspend fun refresh(playlistId: String, force: Boolean = false) {
        val playlist = playlist(playlistId) ?: return
        val state = contentStates.getOrPut(playlistId) { MutableStateFlow(IptvContentState()) }
        val lock = loadLocks.getOrPut(playlistId) { Mutex() }
        if (!lock.tryLock()) return
        try {
            if (state.value.content == null) {
                readCachedContent(playlist)?.let { cached -> state.update { it.copy(content = cached) } }
            }
            val now = System.currentTimeMillis()
            val cached = state.value.content
            val due = force || IptvRefreshPolicy.isDue(
                hours = _settings.value.refreshHours,
                fetchedAt = cached?.fetchedAt ?: 0L,
                now = now,
                checkedSinceOpen = playlistId in checkedThisLaunch,
            )
            val backingOff = !force && cached != null &&
                (lastFailureAt[playlistId]?.let { now - it < IptvRefreshPolicy.RETRY_MS } == true)
            if (!due || backingOff) return

            checkedThisLaunch += playlistId
            state.update { it.copy(loading = true, error = null) }
            try {
                val fresh = IptvSources.loadPlaylist(playlist)
                // The playlist may have been edited or removed while this was downloading.
                if (playlist(playlistId) != playlist) return
                lastFailureAt.remove(playlistId)
                state.value = IptvContentState(content = fresh)
                writeCachedContent(fresh)
                log.i { "Loaded IPTV playlist ${playlist.type}: ${fresh.channels.size} channels, ${fresh.categories.size} categories" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Throwable, not Exception: a native-image build reports a missing registration
                // as an Error, and that must end up on screen and in the log, not kill the job.
                val error = e.asIptvError()
                lastFailureAt[playlistId] = System.currentTimeMillis()
                state.update { it.copy(error = error) }
                log.w { "IPTV playlist load failed: ${describeIptvFailure(e)}" }
            } finally {
                state.update { it.copy(loading = false) }
            }
        } finally {
            lock.unlock()
        }
    }

    // Not tied to a screen: a refresh started from Settings keeps going after the page closes.
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** [refresh] then the playlist's guide, off any screen's scope. */
    fun refreshInBackground(playlistId: String, force: Boolean = false) {
        backgroundScope.launch {
            refresh(playlistId, force)
            contentStates[playlistId]?.value?.content?.let { IptvEpgRepository.refresh(it, force) }
        }
    }

    fun refreshAllInBackground(force: Boolean = false) {
        ensureLoaded()
        _settings.value.playlists.forEach { refreshInBackground(it.id, force) }
    }

    /**
     * Checks a playlist the user is about to save: Xtream logs in and reports the account; M3U and
     * Stalker download and parse the list. Nothing is stored.
     */
    suspend fun test(playlist: IptvPlaylist): IptvTestResult {
        val clean = sanitized(playlist)
        log.i { "IPTV test ${clean.type} started" }
        return try {
            when (clean.type) {
                IptvPlaylistType.Xtream -> {
                    val account = XtreamClient.account(clean)
                    IptvTestResult(account = account, channelCount = null)
                }
                IptvPlaylistType.M3u, IptvPlaylistType.Stalker -> {
                    val content = IptvSources.loadPlaylist(clean)
                    IptvTestResult(account = null, channelCount = content.channels.size)
                }
            }.also { log.i { "IPTV test ${clean.type} ok: channels=${it.channelCount} status=${it.account?.status}" } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.w { "IPTV test ${clean.type} failed: ${describeIptvFailure(e)}" }
            throw e.asIptvError()
        }
    }

    // endregion

    // region Disk cache

    @Serializable
    private class CachedContent(
        val version: Int,
        val playlist: IptvPlaylist,
        val categories: List<IptvCategory>,
        val channels: List<IptvChannel>,
        val account: XtreamAccount? = null,
        val guideUrl: String? = null,
        val fetchedAt: Long,
    )

    private fun contentCacheName(playlistId: String) = "playlist_$playlistId.json.gz"

    /** The cached list, only when it was fetched with the same server, credentials and URLs. */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun readCachedContent(playlist: IptvPlaylist): IptvPlaylistContent? = withContext(Dispatchers.IO) {
        runCatching {
            IptvCacheStorage.openRead(contentCacheName(playlist.id))?.use { input ->
                json.decodeFromStream(CachedContent.serializer(), GZIPInputStream(input.buffered()))
            }
        }.onFailure { log.w { "Unreadable IPTV channel cache: ${it.message}" } }.getOrNull()
            ?.takeIf { it.version == CACHE_VERSION && it.playlist.withoutName() == playlist.withoutName() && it.channels.isNotEmpty() }
            ?.let { IptvPlaylistContent(playlist, it.categories, it.channels, it.account, it.guideUrl, it.fetchedAt) }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun writeCachedContent(content: IptvPlaylistContent) = withContext(Dispatchers.IO) {
        runCatching {
            val cached = CachedContent(
                CACHE_VERSION, content.playlist, content.categories, content.channels,
                content.account, content.guideUrl, content.fetchedAt,
            )
            IptvCacheStorage.write(contentCacheName(content.playlist.id)) { out ->
                GZIPOutputStream(out.buffered()).use { json.encodeToStream(CachedContent.serializer(), cached, it) }
            }
        }.onFailure { log.w { "Failed to cache IPTV channels: ${it.message}" } }
    }

    // endregion
}

data class IptvTestResult(val account: XtreamAccount?, val channelCount: Int?)
