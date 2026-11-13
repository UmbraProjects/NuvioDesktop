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

/**
 * fork:phone-remote menus - what a long press on the phone points at: a library poster (`title`), a Continue Watching
 * card (`resume`, [ref] = its video id), an episode row (`episode`, [ref] = the id it plays by) or a season header
 * (`season`, [ref] = the season number). [type] and [id] are the title's (the show's, for the last three).
 */
internal enum class RemoteMenuKind(val wire: String) {
    Title("title"), Resume("resume"), Episode("episode"), Season("season");

    companion object {
        fun fromWire(wire: String): RemoteMenuKind? = entries.firstOrNull { it.wire == wire }
    }
}

internal data class RemoteMenuTarget(val kind: RemoteMenuKind, val type: String, val id: String, val ref: String?)

/** One row of the phone's long-press sheet: [code] is what DO sends back, [label] what the row says. */
internal data class RemoteMenuAction(val code: String, val label: String)

/** A playlist the phone can add to. */
internal data class RemotePlaylistChoice(val id: String, val name: String, val count: Int)

/** The action codes. Stable on the wire; the phone picks an icon by them and shows any it does not know plainly. */
internal object RemoteMenuCode {
    const val WATCHED = "watched"
    const val PREVIOUS_WATCHED = "previous_watched"
    const val SEASON_WATCHED = "season_watched"
    const val EARLIER_SEASONS_WATCHED = "earlier_seasons_watched"
    const val LIBRARY = "library"
    const val ALL_LIBRARIES = "all_libraries"
    const val REWATCH = "rewatch"
    const val RESUME = "resume"
    const val FROM_START = "from_start"
    const val CHOOSE_SOURCE = "choose_source"
    const val RECAP = "recap"
    /** Opens the phone's playlist picker for [PLAYLIST_SCOPE_TITLE], `_EPISODE` or `_SEASON` (the DO's first argument). */
    const val PLAYLIST = "playlist"
    /** The playlist picker for the season an episode row or season header is in. */
    const val PLAYLIST_SEASON = "playlist_season"
    /** Adds to a playlist: arguments are the playlist id (`+` makes a new one), the scope, and a season (0 = every season). */
    const val ADD_TO_PLAYLIST = "add_to_playlist"
    const val NOT_INTERESTED = "not_interested"
    const val REMOVE = "remove"
    const val DETAILS = "details"

    const val PLAYLIST_SCOPE_TITLE = "title"
    const val PLAYLIST_SCOPE_EPISODE = "episode"
    const val PLAYLIST_SCOPE_SEASON = "season"
}

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

    /** `AUDIO 0 <choices>`: the film player's audio tracks, the same fields as [subtitlesLine] (group empty, no Off). */
    fun audioLine(list: com.nuvio.app.features.remote.RemoteSubtitleList?): String {
        if (list == null) return "AUDIO 0 -"
        return "AUDIO 0 " + records(list.choices) { c -> listOf(text(c.label), text(c.detail), text(c.group), flag(c.selected)) }
    }

    /** A source's description runs to several lines (file name, size, seeders); kept whole, up to a point. */
    /**
     * fork:phone-remote tonight - `TONIGHT 0` while the Tonight picker is not on screen, else
     * `TONIGHT 1 <view> <mode> <loading> <number> <total> <countFrom> <countLeft> <reshuffleLeft> <prompt> <leftLabel>
     * <leftDetail> <rightLabel> <rightDetail> <answers> <cards> <extra> <seconds> <secondsTotal> <paused> <selected>`:
     * always twenty-one fields, `-` for an empty one (the last five came with every mode on the phone, Nov 11; older
     * apps read the first sixteen and ignore them). Answers are records of one field; each card is slot, name, why,
     * meta, poster, flag (older apps read the first five). The phone answers with
     * `TONIGHT_DO <action> [n]` (see RemoteTonightAction). Mirrored by `parseTonight` in the app's LibraryLines.kt.
     */
    fun tonightLine(screen: com.nuvio.app.features.remote.RemoteTonightScreen?): String {
        if (screen == null) return "TONIGHT 0"
        return listOf(
            "TONIGHT", "1",
            text(screen.view), text(screen.mode), flag(screen.loading),
            screen.number.toString(), screen.total.toString(), screen.countFrom.toString(), screen.countLeft.toString(),
            flag(screen.reshuffleLeft),
            text(screen.prompt), text(screen.leftLabel), text(screen.leftDetail), text(screen.rightLabel), text(screen.rightDetail),
            records(screen.answers) { listOf(text(it)) },
            records(screen.cards) { c -> listOf(text(c.slot), text(c.name), text(c.why), text(c.meta), url(c.poster), flag(c.flag)) },
            text(screen.extra),
            screen.seconds.toString(),
            screen.secondsTotal.toString(),
            flag(screen.paused),
            screen.selected.toString(),
        ).joinToString(" ")
    }

    // --- fork:phone-remote menus: the long-press sheet (see PhoneRemoteActions). ---

    /** What the phone should reload after a menu action changed something. */
    const val REFRESH_LIBRARY = "library"
    const val REFRESH_CONTINUE = "continue"
    const val REFRESH_EPISODES = "episodes"

    private fun target(target: RemoteMenuTarget): String =
        "${target.kind.wire} ${text(target.type)} ${text(target.id)} ${text(target.ref)}"

    /** `MENU <kind> <type> <id> <ref> <heading> <detail> <actions>`: the sheet for a long press, actions as `code,label`. */
    fun menuLine(target: RemoteMenuTarget, heading: String, detail: String?, actions: List<RemoteMenuAction>): String =
        "MENU ${target(target)} ${text(heading)} ${text(detail)} " + records(actions) { listOf(text(it.code), text(it.label)) }

    /** `DONE <ok 0|1> <message> <refresh>`: a menu action finished; refresh is a comma list of what to ask for again. */
    fun doneLine(ok: Boolean, message: String, refresh: Collection<String> = emptyList()): String =
        "DONE ${flag(ok)} ${text(message)} ${refresh.joinToString(",").ifEmpty { "-" }}"

    /**
     * `PLAYLISTS <kind> <type> <id> <ref> <scope> <label> <seasons> <playlists>`: the phone's playlist picker. Seasons
     * (a show added from its poster: pick one or all) are numbers; playlists are `id,name,entries`.
     */
    fun playlistsLine(target: RemoteMenuTarget, scope: String, label: String, seasons: List<Int>, playlists: List<RemotePlaylistChoice>): String =
        "PLAYLISTS ${target(target)} ${text(scope)} ${text(label)} " +
            records(seasons) { listOf(it.toString()) } + " " +
            records(playlists) { listOf(text(it.id), text(it.name), it.count.coerceAtLeast(0).toString()) }

    /** `RECAP <state 0 writing|1 ready|2 failed> <heading> <text>`: the AI recap the episode or season menu asked for. */
    fun recapLine(state: Int, heading: String, body: String?): String =
        "RECAP ${state.coerceIn(0, 2)} ${text(heading)} " +
            (body?.trim()?.take(MAX_RECAP_CHARS)?.takeIf { it.isNotEmpty() }?.let(PhoneRemoteProtocol::encode) ?: "-")

    private const val MAX_RECAP_CHARS = 6000

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
