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
 * pc:    OPENING <what> | CANT_OPEN             (for OPEN_TITLE, PLAY_ALBUM and ARTIST)
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

    fun musicLine(ready: Boolean, artists: List<RemoteArtist>, albums: List<RemoteAlbum>): String =
        "LIBRARY $MUSIC ${flag(ready)} " +
            records(artists) { artist -> listOf(text(artist.id), text(artist.name), url(artist.photo), flag(artist.starred)) } + " " +
            records(albums) { album ->
                listOf(
                    text(album.artistId), text(album.browseId), text(album.title), text(album.kind),
                    text(album.year), url(album.cover), flag(album.starred),
                )
            }

    private fun flag(value: Boolean) = if (value) "1" else "0"

    private fun text(value: String?): String = value?.trim()?.take(MAX_TEXT_CHARS)?.takeIf { it.isNotEmpty() }
        ?.let(PhoneRemoteProtocol::encode) ?: "-"

    private fun url(value: String?): String = value?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_URL_CHARS }
        ?.let(PhoneRemoteProtocol::encode) ?: "-"

    private fun <T> records(items: List<T>, fields: (T) -> List<String>): String =
        if (items.isEmpty()) "-" else items.joinToString(";") { fields(it).joinToString(",") }
}
