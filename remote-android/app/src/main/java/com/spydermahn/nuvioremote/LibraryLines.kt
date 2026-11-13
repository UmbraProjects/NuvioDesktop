package com.spydermahn.nuvioremote

/**
 * The PC's library, as the phone browses it. Mirrors `PhoneRemoteLibraryLines.kt` in Nuvio Desktop
 * (composeApp/src/desktopMain/.../features/remote); change both together.
 *
 * ```
 * pc: LIBRARY video <ready 0|1> <titles>
 *     LIBRARY music <ready 0|1> <artists> <albums>
 * ```
 * A list is records joined by `;`, a record's fields by `,`, each field URL-encoded; `-` is empty.
 */
data class LibraryTitle(
    val section: String,
    val type: String,
    val id: String,
    val name: String,
    val year: String?,
    val poster: String?,
    val posterFallback: String?,
    val watched: Boolean,
) {
    val key: String get() = "$type:$id"

    /** A show (its season list opens on the phone) rather than a film. */
    val isShow: Boolean get() = type == "series" || type == "anime" || type == "tv"
}

/** A Continue Watching title on the PC (`CONTINUE` line): a film, or the episode of a show it stopped in. */
data class ResumeItem(
    val type: String,
    val parentId: String,
    val videoId: String,
    val title: String,
    /** "S2 E5 · Breakage"; null for a film. */
    val episode: String?,
    val poster: String?,
    val still: String?,
    val positionMs: Long,
    val durationMs: Long,
    val lastSource: String?,
    /** 0 to 1. */
    val progress: Float,
) {
    val key: String get() = "$parentId|$videoId"

    /** "1h 12m left", or null when the PC only knows a share of it. */
    val timeLeft: String?
        get() {
            if (durationMs <= 0L) return null
            val minutes = ((durationMs - positionMs).coerceAtLeast(0L) / 60_000L).toInt()
            return when {
                minutes < 1 -> "Almost done"
                minutes < 60 -> "${minutes}m left"
                else -> "${minutes / 60}h ${minutes % 60}m left"
            }
        }
}

/** One of the three cards on the TV's Tonight picker (`TONIGHT` line). [number] is what `TONIGHT_DO play` sends back. */
data class TonightCardInfo(
    val number: Int,
    val slot: String,
    val name: String,
    val why: String,
    val meta: String,
    val poster: String?,
    /** On the launcher, whether the mode is built; in Ends By, whether it ends before bed (Nov 11). */
    val flag: Boolean = false,
)

/**
 * The TV's Tonight picker as the phone mirrors it (`TONIGHT` line): [view] is "launcher", "question", "cards", one of
 * the modes ("knockout", "cointoss", "flick", "endsby", "pile", "map"; Nov 11) or "other" ([mode] names it).
 */
data class TonightScreenInfo(
    val view: String,
    val mode: String?,
    val loading: Boolean,
    val number: Int,
    val total: Int,
    val countFrom: Int,
    val countLeft: Int,
    val reshuffleLeft: Boolean,
    val prompt: String?,
    val leftLabel: String?,
    val leftDetail: String?,
    val rightLabel: String?,
    val rightDetail: String?,
    val answers: List<String>,
    val cards: List<TonightCardInfo>,
    /** A line under the title (Coin Toss's lean, Channel Flick's note, The Map's reading, Ends By's bedtime). */
    val extra: String? = null,
    /** A countdown (Coin Toss, Knockout's winner); -1 when none runs. */
    val seconds: Int = -1,
    val secondsTotal: Int = 0,
    val paused: Boolean = false,
    /** The row the TV has selected (Ends By, The Pile), from 1; 0 for none. */
    val selected: Int = 0,
)

/** One row of the source list on the TV (`SOURCES` line), in the order the TV draws them. */
data class SourceInfo(
    /** Its place in the list, from 1: what PICK_SOURCE sends back. */
    val number: Int,
    val name: String,
    val detail: String?,
    val addon: String,
    val sizeBytes: Long?,
    val instant: Boolean,
    val playable: Boolean,
) {
    val sizeLabel: String?
        get() {
            val bytes = sizeBytes ?: return null
            val gb = bytes / 1_000_000_000.0
            return if (gb >= 1.0) "%.1f GB".format(gb) else "%d MB".format((bytes / 1_000_000L).coerceAtLeast(1L))
        }
}

/** One row of the film player's subtitle list (`SUBTITLES` line); [number] is what PICK_SUBTITLE sends back (Off is 1). */
data class SubtitleChoice(val number: Int, val label: String, val detail: String?, val group: String, val selected: Boolean)

data class SourceList(val title: String, val loading: Boolean, val sources: List<SourceInfo>)

/** An episode in a show's season list (`EPISODES` line); [videoId] is what PLAY_EPISODE sends back. */
data class EpisodeInfo(
    val season: Int?,
    val episode: Int?,
    val title: String,
    val videoId: String,
    val thumbnail: String?,
    val released: String?,
    /** 0..1; 1 when watched. */
    val progress: Float,
)

/** A track in the music player's queue on the PC (`QUEUE` line). */
data class QueueTrack(val videoId: String, val title: String, val artist: String, val seconds: Int, val cover: String?, val upcoming: Boolean)

/** A word the lyric source timed: when it is sung, in ms into the song. */
data class WordTime(val text: String, val startMs: Long, val endMs: Long)

/** One line of the playing song's lyrics; [timeMs] is null for words without timing, [words] empty unless every word was timed. */
data class LyricLine(val timeMs: Long?, val text: String, val words: List<WordTime> = emptyList())

data class LibraryArtistInfo(val id: String, val name: String, val photo: String?, val starred: Boolean)

data class LibraryAlbum(
    val artistId: String,
    val browseId: String,
    val title: String,
    val kind: String,
    val year: String?,
    val cover: String?,
    val starred: Boolean,
)

/**
 * What a long press points at (a library poster, a Continue Watching card, an episode row, a season header) and how
 * the PC names it on the wire: MENU and DO carry `<kind> <type> <id> <ref>`. Mirrors `RemoteMenuTarget` in the PC's
 * PhoneRemoteLibraryLines.kt.
 */
data class MenuTarget(val kind: String, val type: String, val id: String, val ref: String?) {
    fun wire(): String = "$kind ${RemoteProtocol.encode(type)} ${RemoteProtocol.encode(id)} ${ref?.let(RemoteProtocol::encode) ?: "-"}"

    companion object {
        const val TITLE = "title"
        const val RESUME = "resume"
        const val EPISODE = "episode"
        const val SEASON = "season"

        fun title(title: LibraryTitle) = MenuTarget(TITLE, title.type, title.id, null)
        fun resume(item: ResumeItem) = MenuTarget(RESUME, item.type, item.parentId, item.videoId)
        fun episode(show: LibraryTitle, episode: EpisodeInfo) = MenuTarget(EPISODE, show.type, show.id, episode.videoId)
        fun season(show: LibraryTitle, season: Int) = MenuTarget(SEASON, show.type, show.id, season.toString())
    }
}

/** A row of the long-press sheet: [code] goes back in DO, [label] is what the row says (the PC words it). */
data class MenuAction(val code: String, val label: String)

/** The action codes the phone knows by sight (for icons and follow-ups); the PC's RemoteMenuCode. */
object MenuCode {
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
    const val PLAYLIST = "playlist"
    const val PLAYLIST_SEASON = "playlist_season"
    const val ADD_TO_PLAYLIST = "add_to_playlist"
    const val NOT_INTERESTED = "not_interested"
    const val REMOVE = "remove"
    const val DETAILS = "details"

    /** Rows that start something on the TV: the phone goes back to the Remote tab after them. */
    val OPENS_ON_TV = setOf(RESUME, FROM_START, CHOOSE_SOURCE, DETAILS)

    /** A new playlist, named after what is added. */
    const val NEW_PLAYLIST = "+"
}

data class PlaylistOption(val id: String, val name: String, val count: Int)

object LibraryLines {
    const val VIDEO = "video"
    const val MUSIC = "music"

    /** What a DONE line can ask the phone to load again; the PC's PhoneRemoteLibraryLines.REFRESH_*. */
    const val REFRESH_LIBRARY = "library"
    const val REFRESH_CONTINUE = "continue"
    const val REFRESH_EPISODES = "episodes"

    /** Null for anything that is not a LIBRARY line. */
    fun parse(line: String): ServerLine? {
        val parts = line.trim().split(' ')
        if (parts.size < 3 || parts[0] != "LIBRARY") return null
        val ready = parts[2] == "1"
        return when (parts[1]) {
            VIDEO -> ServerLine.VideoLibrary(ready = ready, titles = titles(parts.getOrNull(3)))
            MUSIC -> ServerLine.MusicLibrary(
                ready = ready,
                artists = records(parts.getOrNull(3)).mapNotNull { f ->
                    if (f.size < 4) return@mapNotNull null
                    LibraryArtistInfo(
                        id = f[0] ?: return@mapNotNull null,
                        name = f[1] ?: return@mapNotNull null,
                        photo = f[2],
                        starred = f[3] == "1",
                    )
                },
                albums = records(parts.getOrNull(4)).mapNotNull { f ->
                    if (f.size < 7) return@mapNotNull null
                    LibraryAlbum(
                        artistId = f[0] ?: return@mapNotNull null,
                        browseId = f[1] ?: return@mapNotNull null,
                        title = f[2] ?: return@mapNotNull null,
                        kind = f[3] ?: "Album",
                        year = f[4],
                        cover = f[5],
                        starred = f[6] == "1",
                    )
                },
            )
            else -> null
        }
    }

    /** `CONTINUE <items>`; null for anything else. Mirrors `continueLine` in the PC's PhoneRemoteLibraryLines.kt. */
    fun parseContinue(line: String): ServerLine? {
        val parts = line.trim().split(' ')
        if (parts.isEmpty() || parts[0] != "CONTINUE") return null
        return ServerLine.Continue(
            records(parts.getOrNull(1)).mapNotNull { f ->
                if (f.size < 11) return@mapNotNull null
                ResumeItem(
                    type = f[0] ?: return@mapNotNull null,
                    parentId = f[1] ?: return@mapNotNull null,
                    videoId = f[2] ?: return@mapNotNull null,
                    title = f[3] ?: return@mapNotNull null,
                    episode = f[4],
                    poster = f[5],
                    still = f[6],
                    positionMs = f[7]?.toLongOrNull() ?: 0L,
                    durationMs = f[8]?.toLongOrNull() ?: 0L,
                    lastSource = f[9],
                    progress = ((f[10]?.toIntOrNull() ?: 0) / 1000f).coerceIn(0f, 1f),
                )
            },
        )
    }

    /**
     * `TONIGHT 0`, or `TONIGHT 1 <view> <mode> <loading> <number> <total> <countFrom> <countLeft> <reshuffleLeft> <prompt>
     * <leftLabel> <leftDetail> <rightLabel> <rightDetail> <answers> <cards>`; mirrors `tonightLine` in the PC's
     * PhoneRemoteLibraryLines.kt.
     */
    fun parseTonight(line: String): ServerLine? {
        val parts = line.trim().split(' ')
        if (parts.size < 2 || parts[0] != "TONIGHT") return null
        if (parts[1] != "1") return ServerLine.Tonight(null)
        if (parts.size < 17) return null
        fun f(i: Int) = parts[i].takeUnless { it == "-" || it.isEmpty() }?.let(RemoteProtocol::decode)
        val cards = records(parts[16]).mapIndexedNotNull { index, r ->
            if (r.size < 5) return@mapIndexedNotNull null
            TonightCardInfo(index + 1, r[0] ?: "", r[1] ?: return@mapIndexedNotNull null, r[2] ?: "", r[3] ?: "", r[4], r.getOrNull(5) == "1")
        }
        return ServerLine.Tonight(
            TonightScreenInfo(
                view = f(2) ?: return null,
                mode = f(3),
                loading = parts[4] == "1",
                number = parts[5].toIntOrNull() ?: 0,
                total = parts[6].toIntOrNull() ?: 0,
                countFrom = parts[7].toIntOrNull() ?: 0,
                countLeft = parts[8].toIntOrNull() ?: 0,
                reshuffleLeft = parts[9] == "1",
                prompt = f(10),
                leftLabel = f(11),
                leftDetail = f(12),
                rightLabel = f(13),
                rightDetail = f(14),
                answers = records(parts[15]).mapNotNull { it.firstOrNull() },
                cards = cards,
                // Added Nov 11; a PC from before sends sixteen fields.
                extra = parts.getOrNull(17)?.let { if (it == "-" || it.isEmpty()) null else RemoteProtocol.decode(it) },
                seconds = parts.getOrNull(18)?.toIntOrNull() ?: -1,
                secondsTotal = parts.getOrNull(19)?.toIntOrNull() ?: 0,
                paused = parts.getOrNull(20) == "1",
                selected = parts.getOrNull(21)?.toIntOrNull() ?: 0,
            ),
        )
    }

    /** `SOURCES <open> <loading> <title> <sources>`; mirrors `sourcesLine` in the PC's PhoneRemoteLibraryLines.kt. */
    fun parseSources(line: String): ServerLine? {
        val parts = line.trim().split(' ')
        if (parts.size < 3 || parts[0] != "SOURCES") return null
        if (parts[1] != "1") return ServerLine.Sources(null)
        val sources = records(parts.getOrNull(4)).mapIndexedNotNull { index, f ->
            if (f.size < 6) return@mapIndexedNotNull null
            SourceInfo(
                number = index + 1,
                name = f[0] ?: "Source ${index + 1}",
                detail = f[1],
                addon = f[2] ?: "",
                sizeBytes = f[3]?.toLongOrNull(),
                instant = f[4] == "1",
                playable = f[5] == "1",
            )
        }
        val title = parts.getOrNull(3)?.takeUnless { it == "-" }?.let(RemoteProtocol::decode) ?: "Sources"
        return ServerLine.Sources(SourceList(title, loading = parts[2] == "1", sources = sources))
    }

    /** `SUBTITLES <loading> <choices>`; mirrors `subtitlesLine` in the PC's PhoneRemoteLibraryLines.kt. Off is number 1. */
    fun parseSubtitles(line: String): ServerLine? {
        val parts = line.trim().split(' ')
        if (parts.size < 2 || parts[0] != "SUBTITLES") return null
        val choices = records(parts.getOrNull(2)).mapIndexedNotNull { index, f ->
            if (f.size < 4) return@mapIndexedNotNull null
            SubtitleChoice(number = index + 1, label = f[0] ?: "Track ${index + 1}", detail = f[1], group = f[2] ?: "", selected = f[3] == "1")
        }
        return ServerLine.Subtitles(loading = parts[1] == "1", choices = choices)
    }

    /** `AUDIO 0 <choices>`; the same fields as SUBTITLES (the group is empty, there is no Off). */
    fun parseAudio(line: String): ServerLine? {
        val parts = line.trim().split(' ')
        if (parts.size < 2 || parts[0] != "AUDIO") return null
        val choices = records(parts.getOrNull(2)).mapIndexedNotNull { index, f ->
            if (f.size < 4) return@mapIndexedNotNull null
            SubtitleChoice(number = index + 1, label = f[0] ?: "Track ${index + 1}", detail = f[1], group = f[2] ?: "", selected = f[3] == "1")
        }
        return ServerLine.Audio(choices)
    }

    private fun titles(field: String?): List<LibraryTitle> = records(field).mapNotNull { f ->
        if (f.size < 8) return@mapNotNull null
        LibraryTitle(
            section = f[0] ?: "Library",
            type = f[1] ?: return@mapNotNull null,
            id = f[2] ?: return@mapNotNull null,
            name = f[3] ?: return@mapNotNull null,
            year = f[4],
            poster = f[5],
            posterFallback = f[6],
            watched = f[7] == "1",
        )
    }

    /** `SEARCH_RESULTS <loading> <query> <titles>`; mirrors `searchLine` in the PC's PhoneRemoteLibraryLines.kt. */
    fun parseSearch(line: String): ServerLine? {
        val parts = line.trim().split(' ')
        if (parts.size < 3 || parts[0] != "SEARCH_RESULTS") return null
        return ServerLine.SearchResults(
            loading = parts[1] == "1",
            query = RemoteProtocol.decode(parts[2]),
            titles = titles(parts.getOrNull(3)),
        )
    }

    /** `EPISODES <type> <id> <found> <episodes>`; mirrors `episodesLine` in the PC's PhoneRemoteLibraryLines.kt. */
    fun parseEpisodes(line: String): ServerLine? {
        val parts = line.trim().split(' ')
        if (parts.size < 4 || parts[0] != "EPISODES") return null
        val episodes = records(parts.getOrNull(4)).mapNotNull { f ->
            if (f.size < 7) return@mapNotNull null
            EpisodeInfo(
                season = f[0]?.toIntOrNull(),
                episode = f[1]?.toIntOrNull(),
                title = f[2].orEmpty(),
                videoId = f[3] ?: return@mapNotNull null,
                thumbnail = f[4],
                released = f[5],
                progress = ((f[6]?.toIntOrNull() ?: 0) / 1000f).coerceIn(0f, 1f),
            )
        }
        return ServerLine.Episodes(RemoteProtocol.decode(parts[1]), RemoteProtocol.decode(parts[2]), parts[3] == "1", episodes)
    }

    /** `QUEUE <playing> <tracks>`; mirrors `queueLine` in the PC's PhoneRemoteLibraryLines.kt. */
    fun parseQueue(line: String): ServerLine? {
        val parts = line.trim().split(' ')
        if (parts.size < 2 || parts[0] != "QUEUE") return null
        val tracks = records(parts.getOrNull(2)).mapNotNull { f ->
            if (f.size < 6) return@mapNotNull null
            QueueTrack(
                videoId = f[0] ?: return@mapNotNull null,
                title = f[1] ?: return@mapNotNull null,
                artist = f[2].orEmpty(),
                seconds = f[3]?.toIntOrNull() ?: 0,
                cover = f[4],
                upcoming = f[5] == "1",
            )
        }
        return ServerLine.Queue(playing = parts[1].toIntOrNull() ?: -1, tracks = tracks)
    }

    /** `ALBUM_TRACKS <browseId> <tracks>`; mirrors `albumTracksLine` in the PC's PhoneRemoteLibraryLines.kt. */
    fun parseAlbumTracks(line: String): ServerLine? {
        val parts = line.trim().split(' ')
        if (parts.size < 2 || parts[0] != "ALBUM_TRACKS") return null
        val tracks = records(parts.getOrNull(2)).mapNotNull { f ->
            if (f.size < 5) return@mapNotNull null
            QueueTrack(
                videoId = f[0] ?: return@mapNotNull null,
                title = f[1] ?: return@mapNotNull null,
                artist = f[2].orEmpty(),
                seconds = f[3]?.toIntOrNull() ?: 0,
                cover = null,
                // Here: whether YouTube Music lets it play.
                upcoming = f[4] == "1",
            )
        }
        return ServerLine.AlbumTracks(RemoteProtocol.decode(parts[1]), tracks)
    }

    /** `LYRICS synced|plain|none <title> <lines>`; mirrors `lyricsLine` in the PC's PhoneRemoteLibraryLines.kt. */
    fun parseLyrics(line: String): ServerLine? {
        val parts = line.trim().split(' ')
        if (parts.size < 3 || parts[0] != "LYRICS") return null
        val lines = records(parts.getOrNull(3)).map { f ->
            val words = f.getOrNull(2)?.split('|')?.mapNotNull { piece ->
                val bits = piece.split(':')
                if (bits.size != 3) return@mapNotNull null
                WordTime(bits[0], bits[1].toLongOrNull() ?: return@mapNotNull null, bits[2].toLongOrNull() ?: return@mapNotNull null)
            }.orEmpty()
            LyricLine(f.getOrNull(0)?.toLongOrNull(), f.getOrNull(1).orEmpty(), words)
        }
        return ServerLine.LyricsText(
            synced = parts[1] == "synced",
            title = parts[2].takeUnless { it == "-" }?.let(RemoteProtocol::decode),
            lines = if (parts[1] == "none") emptyList() else lines,
        )
    }

    // ---------------------------------------------------------------- The long-press sheet

    /** The `<kind> <type> <id> <ref>` that MENU and PLAYLISTS start with (from index 1). */
    private fun menuTarget(parts: List<String>): MenuTarget? {
        if (parts.size < 5) return null
        val kind = parts[1].takeIf { it in setOf(MenuTarget.TITLE, MenuTarget.RESUME, MenuTarget.EPISODE, MenuTarget.SEASON) } ?: return null
        return MenuTarget(
            kind = kind,
            type = RemoteProtocol.decode(parts[2]),
            id = RemoteProtocol.decode(parts[3]),
            ref = parts[4].takeUnless { it == "-" || it.isBlank() }?.let(RemoteProtocol::decode),
        )
    }

    private fun field(value: String?): String? = value?.takeUnless { it == "-" || it.isBlank() }?.let(RemoteProtocol::decode)

    /** `MENU <kind> <type> <id> <ref> <heading> <detail> <actions>`; mirrors `menuLine` in the PC's PhoneRemoteLibraryLines.kt. */
    fun parseMenu(line: String): ServerLine? {
        val parts = line.trim().split(' ')
        if (parts.size < 7 || parts[0] != "MENU") return null
        val target = menuTarget(parts) ?: return null
        val actions = records(parts.getOrNull(7)).mapNotNull { f ->
            MenuAction(code = f.getOrNull(0) ?: return@mapNotNull null, label = f.getOrNull(1) ?: return@mapNotNull null)
        }
        return ServerLine.Menu(target, field(parts[5]).orEmpty(), field(parts[6]), actions)
    }

    /** `DONE <ok> <message> <refresh>`; mirrors `doneLine`. */
    fun parseDone(line: String): ServerLine? {
        val parts = line.trim().split(' ')
        if (parts.size < 3 || parts[0] != "DONE") return null
        val refresh = parts.getOrNull(3)?.takeUnless { it == "-" }?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty()
        return ServerLine.Done(ok = parts[1] == "1", message = field(parts[2]).orEmpty(), refresh = refresh)
    }

    /** `PLAYLISTS <kind> <type> <id> <ref> <scope> <label> <seasons> <playlists>`; mirrors `playlistsLine`. */
    fun parsePlaylists(line: String): ServerLine? {
        val parts = line.trim().split(' ')
        if (parts.size < 7 || parts[0] != "PLAYLISTS") return null
        val target = menuTarget(parts) ?: return null
        val seasons = records(parts.getOrNull(7)).mapNotNull { it.getOrNull(0)?.toIntOrNull() }
        val playlists = records(parts.getOrNull(8)).mapNotNull { f ->
            PlaylistOption(id = f.getOrNull(0) ?: return@mapNotNull null, name = f.getOrNull(1) ?: return@mapNotNull null, count = f.getOrNull(2)?.toIntOrNull() ?: 0)
        }
        return ServerLine.Playlists(target, scope = parts[5], label = field(parts[6]).orEmpty(), seasons = seasons, playlists = playlists)
    }

    /** `RECAP <state 0 writing|1 ready|2 failed> <heading> <text>`; mirrors `recapLine`. */
    fun parseRecap(line: String): ServerLine? {
        val parts = line.trim().split(' ')
        if (parts.size < 3 || parts[0] != "RECAP") return null
        return ServerLine.Recap(state = parts[1].toIntOrNull() ?: 2, heading = field(parts[2]).orEmpty(), text = field(parts.getOrNull(3)))
    }

    /** Each record's fields, decoded; `-` becomes null. */
    private fun records(field: String?): List<List<String?>> {
        if (field == null || field == "-" || field.isEmpty()) return emptyList()
        return field.split(';').map { record ->
            record.split(',').map { value -> if (value == "-" || value.isEmpty()) null else RemoteProtocol.decode(value) }
        }
    }
}

/** What the phone can ask the PC to do with an album or an artist. Wire names match the PC's. */
enum class AlbumAction(val wire: String) { Play("play"), Shuffle("shuffle"), Queue("queue") }

enum class ArtistAction(val wire: String) { Mix("mix"), Radio("radio") }
