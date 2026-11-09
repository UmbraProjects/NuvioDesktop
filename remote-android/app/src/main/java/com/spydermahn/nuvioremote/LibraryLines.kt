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

object LibraryLines {
    const val VIDEO = "video"
    const val MUSIC = "music"

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
