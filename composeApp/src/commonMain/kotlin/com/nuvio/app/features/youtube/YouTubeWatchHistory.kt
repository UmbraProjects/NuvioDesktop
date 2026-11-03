package com.nuvio.app.features.youtube

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.skip.currentDateComponents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * fork:youtube-history
 *
 * A copy of the account's YouTube watch history kept on this PC, since YouTube drops old entries (and
 * keeps none while history is paused). Filled from the History tab's pages as they load (with the day
 * each heading names) and from every video played or marked watched in Nuvio. Written as plain,
 * indented JSON (YouTubeHistoryFile) so it can be read without Nuvio, and exported as CSV.
 */

/** One watched video. [watchedOn] is the last day it was watched, as an ISO date. */
@Serializable
data class YouTubeHistoryEntry(
    val videoId: String,
    val title: String,
    val channel: String? = null,
    val channelId: String? = null,
    val duration: String? = null,
    val watchedOn: String,
    val url: String = "https://www.youtube.com/watch?v=$videoId",
)

@Serializable
private data class YouTubeHistoryDocument(
    val about: String = "Nuvio's copy of your YouTube watch history, newest first. watchedOn is the last day each video was watched.",
    val videos: List<YouTubeHistoryEntry>,
)

/** The history file on this PC: read and written whole. */
internal expect object YouTubeHistoryFile {
    /** Where it lives, for the settings page. */
    val location: String
    fun read(): String?
    fun write(text: String)

    /** Asks where to save [contents] (a CSV); the saved file's name, or null when cancelled. */
    suspend fun saveExport(suggestedName: String, contents: String): String?
}

object YouTubeWatchHistory {
    private val log = Logger.withTag("YouTubeHistory")
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val writeLock = Mutex()

    private val _entries = MutableStateFlow<Map<String, YouTubeHistoryEntry>>(emptyMap())
    private var loaded = false

    /** Every video in the copy, by id. */
    val entries: StateFlow<Map<String, YouTubeHistoryEntry>>
        get() {
            ensureLoaded()
            return _entries.asStateFlow()
        }

    /** Videos opened in the player, kept until their playback ends ([noteWatched]) for their title and channel. */
    private val opened = MutableStateFlow<Map<String, YouTubeVideo>>(emptyMap())

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val text = runCatching { YouTubeHistoryFile.read() }.getOrNull() ?: return
        val videos = runCatching { json.decodeFromString<YouTubeHistoryDocument>(text).videos }
            .onFailure { log.w { "history file unreadable; starting a new one: ${it.message}" } }
            .getOrNull() ?: return
        _entries.value = videos.associateBy { it.videoId }
        log.i { "history: ${videos.size} videos on this PC" }
    }

    fun today(): String = currentDateComponents().isoDate()

    /** [video] is about to play: its details are kept for when the playback is recorded. */
    fun noteOpened(video: YouTubeVideo) {
        opened.update { it + (video.videoId to video) }
    }

    /** [videoId]'s playback in Nuvio ended (it was watched at least in part): recorded as watched today. */
    fun noteWatched(videoId: String) {
        val video = opened.value[videoId]
        opened.update { it - videoId }
        val known = entries.value[videoId]
        record(
            listOf(
                YouTubeHistoryEntry(
                    videoId = videoId,
                    title = video?.title?.takeIf { it.isNotBlank() } ?: known?.title ?: videoId,
                    channel = video?.channel ?: known?.channel,
                    channelId = video?.channelId ?: known?.channelId,
                    duration = video?.duration ?: known?.duration,
                    watchedOn = today(),
                ),
            ),
        )
    }

    /** [video] was marked watched: recorded as watched today. */
    fun noteMarkedWatched(video: YouTubeVideo) = record(listOf(entryFor(video, today())))

    /** A History page: each video under the day its heading names ([YouTubeFeedPage.sections]). */
    fun noteHistoryPage(page: YouTubeFeedPage) {
        val today = currentDateComponents()
        record(
            page.videos.mapNotNull { video ->
                val day = page.sections[video.videoId]?.let { youTubeHistoryDate(it, today) } ?: return@mapNotNull null
                entryFor(video, day)
            },
        )
    }

    private fun entryFor(video: YouTubeVideo, day: String) = YouTubeHistoryEntry(
        videoId = video.videoId,
        title = video.title,
        channel = video.channel,
        channelId = video.channelId,
        duration = video.duration,
        watchedOn = day,
    )

    /** Adds [new] to the copy: a video already in it keeps its latest day, and takes the newer details. */
    private fun record(new: List<YouTubeHistoryEntry>) {
        if (new.isEmpty()) return
        ensureLoaded()
        var changed = false
        _entries.update { current ->
            val next = current.toMutableMap()
            for (entry in new) {
                val old = next[entry.videoId]
                val merged = when {
                    old == null -> entry
                    // ISO dates compare as text.
                    old.watchedOn > entry.watchedOn -> old.copy(
                        title = entry.title.ifBlank { old.title },
                        channel = entry.channel ?: old.channel,
                        channelId = entry.channelId ?: old.channelId,
                        duration = entry.duration ?: old.duration,
                    )
                    else -> entry.copy(
                        channel = entry.channel ?: old.channel,
                        channelId = entry.channelId ?: old.channelId,
                        duration = entry.duration ?: old.duration,
                    )
                }
                if (merged != old) {
                    next[entry.videoId] = merged
                    changed = true
                }
            }
            next
        }
        if (changed) save()
    }

    private fun save() {
        scope.launch {
            writeLock.withLock {
                val document = YouTubeHistoryDocument(videos = newestFirst())
                runCatching { YouTubeHistoryFile.write(json.encodeToString(YouTubeHistoryDocument.serializer(), document)) }
                    .onFailure { log.w { "history file not written: ${it.message}" } }
            }
        }
    }

    private fun newestFirst(): List<YouTubeHistoryEntry> =
        _entries.value.values.sortedWith(compareByDescending<YouTubeHistoryEntry> { it.watchedOn }.thenBy { it.title })

    /** The copy as CSV (date, title, channel, length, link), newest first, for a spreadsheet. */
    fun exportCsv(): String = buildString {
        ensureLoaded()
        appendLine("Watched on,Title,Channel,Length,URL")
        for (entry in newestFirst()) {
            appendLine(listOf(entry.watchedOn, entry.title, entry.channel.orEmpty(), entry.duration.orEmpty(), entry.url).joinToString(",") { csvField(it) })
        }
    }
}

/** [value] as one CSV field: quoted when it holds a comma, a quote or a line break, quotes doubled. */
internal fun csvField(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value
