package com.nuvio.app.features.preroll

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One part of the pre-show. The user chooses the order; the gate plays them in that order. */
enum class PreshowSlot(val key: String, val folderName: String?) {
    CinemaIntro("cinema_intro", "cinema-intro"),
    Bumper("bumper", "bumper"),
    Trailers("trailers", null),
    Trivia("trivia", null),
    RatingCard("rating_card", "rating"),
    FeaturePresentation("feature_presentation", "feature-presentation"),
    ;

    /** Clip slots are the ones fed by local files / AVID; trailers and trivia have their own sources. */
    val isClipSlot: Boolean get() = folderName != null

    /** Folder under the local pre-show folder. The rating card has one per rating system. */
    fun localSubfolder(region: RatingRegion): String? = when (this) {
        RatingCard -> "rating-${region.key}"
        else -> folderName
    }
}

enum class RatingRegion(val key: String) {
    Bbfc("bbfc"),
    Mpaa("mpaa"),
}

internal expect object PreshowSettingsStorage {
    fun load(key: String): String?
    fun save(key: String, value: String?)
}

const val AVID_BBFC_PAGE = "https://avid.wiki/BBFC"

/**
 * The bumper slot is a cinema sound-format trailer. Until another source is set it draws from six: three THX
 * Deep Note trailers and three Dolby Atmos ones, all bare YouTube links so no page has to be fetched first.
 */
val AVID_BUMPER_LINKS = listOf(
    "https://youtu.be/c4-VLk03lQk", // THX
    "https://youtu.be/QYU8zydUqD8", // THX
    "https://youtu.be/sPY3Y2qhyXk", // THX
    "https://youtu.be/iTHtXgnhqfM", // Dolby Atmos
    "https://youtu.be/pd_6WN9GVtQ", // Dolby Atmos
    "https://youtu.be/HZS5a3cm7zU", // Dolby Atmos
)
private val AVID_BUMPER_DEFAULT = AVID_BUMPER_LINKS.joinToString(" ")

// Declared before the object so its initialisers can read it.
private val AVID_KEYS = listOf("cinema_intro", "bumper", "feature_presentation", "rating_bbfc", "rating_mpaa")

/**
 * Pre-show layout and sources.
 *
 * Trailers and trivia keep their own on/off settings ([PrerollSettings], the AI trivia switch);
 * here they only have a place in the running order. The four clip slots (cinema intro, bumper,
 * rating card, feature presentation) are off until switched on.
 */
object PreshowSettings {
    private val storage = PreshowSettingsStorage

    private val _order = MutableStateFlow(parseOrder(storage.load("order")))
    val order: StateFlow<List<PreshowSlot>> = _order.asStateFlow()

    private val _enabledSlots = MutableStateFlow(parseEnabled(storage.load("enabled")))
    val enabledSlots: StateFlow<Set<PreshowSlot>> = _enabledSlots.asStateFlow()

    private val _ratingRegion = MutableStateFlow(
        RatingRegion.entries.firstOrNull { it.key == storage.load("rating_region") } ?: RatingRegion.Bbfc,
    )
    val ratingRegion: StateFlow<RatingRegion> = _ratingRegion.asStateFlow()

    private val _keepAudioAlive = MutableStateFlow(storage.load("keep_audio_alive") != "false")
    /**
     * Holds the audio output open with an inaudible signal for the whole pre-show, so a receiver
     * does not drop and re-detect its input between items. On unless switched off.
     */
    val keepAudioAlive: StateFlow<Boolean> = _keepAudioAlive.asStateFlow()

    private val _musicKeepAudioAlive = MutableStateFlow(storage.load("music_keep_audio_alive") == "true")
    /**
     * Holds the audio output open while music plays, so an HDMI receiver does not drop its input
     * between tracks or when switching audio and video. Off unless switched on: it is the pre-show
     * fix applied to music, and has not been checked on a receiver yet.
     */
    val musicKeepAudioAlive: StateFlow<Boolean> = _musicKeepAudioAlive.asStateFlow()

    private val _postShowEnabled = MutableStateFlow(storage.load("postshow_enabled") == "true")
    /**
     * Cards after the film's credits start, with the video shrunk into a corner. Its own switch,
     * off until chosen: it spends requests on the viewer's own AI key.
     */
    val postShowEnabled: StateFlow<Boolean> = _postShowEnabled.asStateFlow()

    private val _previouslyOnEnabled = MutableStateFlow(storage.load("previously_on_enabled") == "true")
    /** "Previously on" cards before an episode. Its own switch, off until chosen: it can spend requests on the viewer's AI key. */
    val previouslyOnEnabled: StateFlow<Boolean> = _previouslyOnEnabled.asStateFlow()

    private val _previouslyOnBreakOnly = MutableStateFlow(storage.load("previously_on_break_only") != "false")
    /** On: the recap shows only after a break of [previouslyOnBreakHours]. Off: before every episode that has one. */
    val previouslyOnBreakOnly: StateFlow<Boolean> = _previouslyOnBreakOnly.asStateFlow()

    private val _previouslyOnBreakHours = MutableStateFlow(
        storage.load("previously_on_break_hours")?.toIntOrNull()?.takeIf { it in PREVIOUSLY_ON_BREAK_CHOICES } ?: DEFAULT_PREVIOUSLY_ON_BREAK_HOURS,
    )
    val previouslyOnBreakHours: StateFlow<Int> = _previouslyOnBreakHours.asStateFlow()

    private val _localFolder = MutableStateFlow(storage.load("local_folder").orEmpty())
    /** Blank means the default folder inside the app's data directory. */
    val localFolder: StateFlow<String> = _localFolder.asStateFlow()

    private val _avidUrls = MutableStateFlow(
        AVID_KEYS.associateWith { storage.load("avid_$it").orEmpty() }
            .let { saved -> if (saved["rating_bbfc"].isNullOrBlank()) saved + ("rating_bbfc" to AVID_BBFC_PAGE) else saved }
            .let { saved -> if (saved["bumper"].isNullOrBlank()) saved + ("bumper" to AVID_BUMPER_DEFAULT) else saved },
    )
    val avidUrls: StateFlow<Map<String, String>> = _avidUrls.asStateFlow()

    /** Moves [slot] to [position] (1-based); the others shift along. */
    fun moveTo(slot: PreshowSlot, position: Int) {
        val rest = _order.value.filter { it != slot }
        val index = (position - 1).coerceIn(0, rest.size)
        val next = rest.toMutableList().apply { add(index, slot) }
        if (next == _order.value) return
        _order.value = next
        storage.save("order", next.joinToString(",") { it.key })
    }

    fun setEnabled(slot: PreshowSlot, enabled: Boolean) {
        val next = if (enabled) _enabledSlots.value + slot else _enabledSlots.value - slot
        if (next == _enabledSlots.value) return
        _enabledSlots.value = next
        storage.save("enabled", next.joinToString(",") { it.key })
    }

    fun setRatingRegion(region: RatingRegion) {
        if (_ratingRegion.value == region) return
        _ratingRegion.value = region
        storage.save("rating_region", region.key)
    }

    fun setKeepAudioAlive(enabled: Boolean) {
        if (_keepAudioAlive.value == enabled) return
        _keepAudioAlive.value = enabled
        storage.save("keep_audio_alive", if (enabled) null else "false")
    }

    fun setMusicKeepAudioAlive(enabled: Boolean) {
        if (_musicKeepAudioAlive.value == enabled) return
        _musicKeepAudioAlive.value = enabled
        storage.save("music_keep_audio_alive", if (enabled) "true" else null)
    }

    fun setPostShowEnabled(enabled: Boolean) {
        if (_postShowEnabled.value == enabled) return
        _postShowEnabled.value = enabled
        storage.save("postshow_enabled", if (enabled) "true" else null)
    }

    fun setPreviouslyOnEnabled(enabled: Boolean) {
        if (_previouslyOnEnabled.value == enabled) return
        _previouslyOnEnabled.value = enabled
        storage.save("previously_on_enabled", if (enabled) "true" else null)
    }

    fun setPreviouslyOnBreakOnly(breakOnly: Boolean) {
        if (_previouslyOnBreakOnly.value == breakOnly) return
        _previouslyOnBreakOnly.value = breakOnly
        storage.save("previously_on_break_only", if (breakOnly) null else "false")
    }

    fun setPreviouslyOnBreakHours(hours: Int) {
        val clean = hours.takeIf { it in PREVIOUSLY_ON_BREAK_CHOICES } ?: return
        _previouslyOnBreakHours.value = clean
        storage.save("previously_on_break_hours", clean.toString())
    }

    fun setLocalFolder(path: String) {
        val clean = path.trim()
        _localFolder.value = clean
        storage.save("local_folder", clean.ifEmpty { null })
    }

    fun avidKey(slot: PreshowSlot, region: RatingRegion): String? = when (slot) {
        PreshowSlot.RatingCard -> "rating_${region.key}"
        PreshowSlot.CinemaIntro, PreshowSlot.Bumper, PreshowSlot.FeaturePresentation -> slot.key
        else -> null
    }

    fun setAvidUrl(key: String, url: String) {
        val clean = url.trim()
        _avidUrls.value = _avidUrls.value + (key to clean)
        storage.save("avid_$key", clean.ifEmpty { null })
    }

    fun avidUrl(slot: PreshowSlot, region: RatingRegion): String =
        avidKey(slot, region)?.let { _avidUrls.value[it] }.orEmpty()

    internal fun parseOrder(raw: String?): List<PreshowSlot> {
        val saved = raw.orEmpty().split(',')
            .mapNotNull { key -> PreshowSlot.entries.firstOrNull { it.key == key.trim() } }
            .distinct()
        // Slots added in a later version, or a missing/garbled value, still land somewhere.
        return saved + PreshowSlot.entries.filter { it !in saved }
    }

    internal fun parseEnabled(raw: String?): Set<PreshowSlot> =
        raw.orEmpty().split(',')
            .mapNotNull { key -> PreshowSlot.entries.firstOrNull { it.key == key.trim() } }
            .filter { it.isClipSlot }
            .toSet()
}

/** How long a break must be before the recap shows: a day, three days, a week, two weeks. */
val PREVIOUSLY_ON_BREAK_CHOICES = listOf(24, 72, 168, 336)
const val DEFAULT_PREVIOUSLY_ON_BREAK_HOURS = 72
