package com.nuvio.app.features.remote

/**
 * fork:phone-remote — the library as the phone sees it, and how it travels.
 *
 * ```
 * phone: LIBRARY video | LIBRARY music
 * pc:    LIBRARY video <ready 0|1> <titles>
 *        LIBRARY music <ready 0|1> <artists> <albums>
 * phone: OPEN_TITLE <type> <id>                 opens the title's details on the PC
 * phone: PLAY_ALBUM <browseId> play|shuffle|queue
 * phone: ARTIST <channelId> mix|radio
 * pc:    OPENING <what> | CANT_OPEN             (for OPEN_TITLE, PLAY_ALBUM, ARTIST and RESUME)
 * phone: CONTINUE
 * pc:    CONTINUE <items>                       Continue Watching, newest first
 * phone: RESUME <parentId> <videoId>            plays it again from the same source, from where it stopped
 * ```
 * Each list is one field: records joined by `;`, a record's fields joined by `,`, every field URL-encoded (which
 * encodes `,` and `;`), so a whole library is a single line and a STATE push can never land in the middle of it.
 * An empty list is `-`, as is an empty field. `ready` is 0 while the PC is still loading the library; the phone asks
 * again shortly. Same version 1: an older PC ignores these lines, an older phone never sends them.
 *
 * The Android side reads these in the remote app's `RemoteClient.kt` (`LibraryLines`); change both together.
 */
internal data class RemoteTitle(
    /** The library row it sits in ("Movies", "Shows", a Trakt list, a local catalog). */
    val section: String,
    val type: String,
    val id: String,
    val name: String,
    val year: String?,
    val poster: String?,
    /** A second poster to try when [poster] (often a poster service) does not load. */
    val posterFallback: String?,
    val watched: Boolean,
)

/** One Continue Watching title: a film, or the episode of a show it stopped in. */
internal data class RemoteResume(
    val type: String,
    val parentId: String,
    val videoId: String,
    val title: String,
    /** "S2 E5 · The Episode" for a show; null for a film. */
    val episode: String?,
    val poster: String?,
    /** A landscape picture: the episode's still or the backdrop. */
    val still: String?,
    val positionMs: Long,
    val durationMs: Long,
    /** The source it last played from, as the source list names it; null when not kept. */
    val lastSource: String?,
    /** How far through, 0 to 1000 (some progress, from Trakt say, comes as a share without a duration). */
    val progressPermille: Int,
)

/** A lyric line for the phone: [words] (text, start, end in ms) when the source timed each word. */
internal data class RemoteLyricLine(val timeMs: Long?, val text: String, val words: List<Triple<String, Long, Long>> = emptyList())

/** A track in the music player's queue, as the phone lists it. */
internal data class RemoteQueueTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val seconds: Int,
    val cover: String?,
    /** After the playing one: can be moved next or removed. */
    val upcoming: Boolean,
)

/** An episode of a show, for the phone's season list. [videoId] is the id the details page plays it by. */
internal data class RemoteEpisode(
    val season: Int?,
    val episode: Int?,
    val title: String,
    val videoId: String,
    val thumbnail: String?,
    val released: String?,
    /** 0..1000 through it; 1000 when watched. */
    val progressPermille: Int,
)

internal data class RemoteArtist(val id: String, val name: String, val photo: String?, val starred: Boolean)

internal data class RemoteAlbum(
    val artistId: String,
    val browseId: String,
    val title: String,
    /** "Album", "EP", "Single" and the like. */
    val kind: String,
    val year: String?,
    val cover: String?,
    val starred: Boolean,
)

internal object PhoneRemoteLibraryLines {
    const val VIDEO = "video"
    const val MUSIC = "music"

    /** Posters and covers longer than this are dropped rather than sent: a data: URL, say. */
    private const val MAX_URL_CHARS = 1500
    private const val MAX_TEXT_CHARS = 200

    fun videoLine(ready: Boolean, titles: List<RemoteTitle>): String =
        "LIBRARY $VIDEO ${flag(ready)} " + records(titles) { title ->
            listOf(
                text(title.section), text(title.type), text(title.id), text(title.name), text(title.year),
                url(title.poster), url(title.posterFallback), flag(title.watched),
            )
        }

    /**
     * `SEARCH_RESULTS <loading 0|1> <query> <titles>`: what Nuvio's search finds in the addons' catalogs, sent to the
     * phone that asked (`SEARCH <query>`) as results arrive. Titles as in the video library, the section being the
     * catalog they came from; the phone opens one with OPEN_TITLE.
     */
    fun searchLine(query: String, loading: Boolean, titles: List<RemoteTitle>): String =
        "SEARCH_RESULTS ${flag(loading)} ${text(query)} " + records(titles) { title ->
            listOf(
                text(title.section), text(title.type), text(title.id), text(title.name), text(title.year),
                url(title.poster), url(title.posterFallback), flag(title.watched),
            )
        }

    /** `QUEUE <playing index> <tracks>`; see PhoneRemoteMusic. */
    fun queueLine(playing: Int, tracks: List<RemoteQueueTrack>): String =
        "QUEUE $playing " + records(tracks) { t ->
            listOf(text(t.videoId), text(t.title), text(t.artist), t.seconds.coerceAtLeast(0).toString(), url(t.cover), flag(t.upcoming))
        }

    /**
     * `ALBUM_TRACKS <browseId> <tracks>`: an album's songs for the phone's album sheet, each videoId, title, artist,
     * seconds and playable 0|1. The phone plays one with `PLAY_TRACK <browseId> <index>` (from 0, in this order).
     */
    fun albumTracksLine(browseId: String, tracks: List<RemoteQueueTrack>): String =
        "ALBUM_TRACKS ${text(browseId)} " + records(tracks) { t ->
            listOf(text(t.videoId), text(t.title), text(t.artist), t.seconds.coerceAtLeast(0).toString(), flag(t.upcoming))
        }

    /**
     * `LYRICS synced|plain|none <title> <lines>`, each line its time in ms (`-` for plain), its text and, when the source
     * timed every word, the words as `word:start:end` joined by `|` (else `-`): what the stage's Loud style lands words on.
     */
    fun lyricsLine(kind: String, title: String?, lines: List<RemoteLyricLine>): String =
        "LYRICS $kind ${text(title)} " + records(lines.take(MAX_LYRIC_LINES)) { line ->
            listOf(
                line.timeMs?.coerceAtLeast(0L)?.toString() ?: "-",
                text(line.text),
                line.words.takeIf { it.isNotEmpty() }
                    ?.joinToString("|") { (word, start, end) -> "${word.replace('|', ' ').replace(':', ' ')}:$start:$end" }
                    ?.let(PhoneRemoteProtocol::encode) ?: "-",
            )
        }

    private const val MAX_LYRIC_LINES = 400

    /**
     * `EPISODES <type> <id> <found 0|1> <episodes>`: a show's episodes for the phone's season list, each season,
     * episode, title, videoId, thumbnail, released and progress (0..1000). The phone plays one with
     * `PLAY_EPISODE <type> <id> <videoId>`.
     */
    fun episodesLine(type: String, id: String, found: Boolean, episodes: List<RemoteEpisode>): String =
        "EPISODES ${text(type)} ${text(id)} ${flag(found)} " + records(episodes) { e ->
            listOf(
                e.season?.toString() ?: "-", e.episode?.toString() ?: "-", text(e.title), text(e.videoId), url(e.thumbnail),
                text(e.released?.take(10)), e.progressPermille.coerceIn(0, 1000).toString(),
            )
        }

    fun musicLine(ready: Boolean, artists: List<RemoteArtist>, albums: List<RemoteAlbum>): String =
        "LIBRARY $MUSIC ${flag(ready)} " +
            records(artists) { artist -> listOf(text(artist.id), text(artist.name), url(artist.photo), flag(artist.starred)) } + " " +
            records(albums) { album ->
                listOf(
                    text(album.artistId), text(album.browseId), text(album.title), text(album.kind),
                    text(album.year), url(album.cover), flag(album.starred),
                )
            }

    fun continueLine(items: List<RemoteResume>): String =
        "CONTINUE " + records(items) { item ->
            listOf(
                text(item.type), text(item.parentId), text(item.videoId), text(item.title), text(item.episode),
                url(item.poster), url(item.still), item.positionMs.coerceAtLeast(0L).toString(),
                item.durationMs.coerceAtLeast(0L).toString(), text(item.lastSource),
                item.progressPermille.coerceIn(0, 1000).toString(),
            )
        }

    /**
     * `SOURCES <open 0|1> <loading 0|1> <title> <sources>`: the source list on screen, pushed whenever it changes, and
     * `SOURCES 0 0 - -` once it closes. Each source: name, detail, addon, size in bytes, instant, playable. The phone
     * picks one with `PICK_SOURCE <n>` (from 1, in this order).
     */
    fun sourcesLine(list: RemoteSourceList?): String {
        if (list == null) return "SOURCES 0 0 - -"
        return "SOURCES 1 ${flag(list.loading)} ${text(list.title)} " + records(list.sources) { source ->
            listOf(
                text(source.name), detail(source.detail), text(source.addon),
                source.sizeBytes?.takeIf { it > 0L }?.toString() ?: "-", flag(source.instant), flag(source.playable),
            )
        }
    }

    /**
     * `SUBTITLES <loading 0|1> <choices>`: the film player's subtitles for the phone's list (long press on its
     * Subtitles tile). Each choice: label, detail, group ("Off", "Built-in" or an add-on's name), selected. The first is
     * always Off. The phone picks one with `PICK_SUBTITLE <n>` (from 1, in this order).
     */
    fun subtitlesLine(list: com.nuvio.app.features.remote.RemoteSubtitleList?): String {
        if (list == null) return "SUBTITLES 0 -"
        return "SUBTITLES ${flag(list.loading)} " + records(list.choices) { c ->
            listOf(text(c.label), text(c.detail), text(c.group), flag(c.selected))
        }
    }

    /** A source's description runs to several lines (file name, size, seeders); kept whole, up to a point. */
    private fun detail(value: String?): String = value?.trim()?.take(MAX_DETAIL_CHARS)?.takeIf { it.isNotEmpty() }
        ?.let(PhoneRemoteProtocol::encode) ?: "-"

    private const val MAX_DETAIL_CHARS = 400

    private fun flag(value: Boolean) = if (value) "1" else "0"

    private fun text(value: String?): String = value?.trim()?.take(MAX_TEXT_CHARS)?.takeIf { it.isNotEmpty() }
        ?.let(PhoneRemoteProtocol::encode) ?: "-"

    private fun url(value: String?): String = value?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_URL_CHARS }
        ?.let(PhoneRemoteProtocol::encode) ?: "-"

    private fun <T> records(items: List<T>, fields: (T) -> List<String>): String =
        if (items.isEmpty()) "-" else items.joinToString(";") { fields(it).joinToString(",") }
}
