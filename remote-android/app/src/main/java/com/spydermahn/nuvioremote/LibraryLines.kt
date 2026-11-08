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
            VIDEO -> ServerLine.VideoLibrary(
                ready = ready,
                titles = records(parts.getOrNull(3)).mapNotNull { f ->
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
                },
            )
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
