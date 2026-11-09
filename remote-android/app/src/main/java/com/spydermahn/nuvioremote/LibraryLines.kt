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

data class SourceList(val title: String, val loading: Boolean, val sources: List<SourceInfo>)

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
