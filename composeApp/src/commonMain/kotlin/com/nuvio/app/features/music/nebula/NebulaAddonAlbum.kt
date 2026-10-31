package com.nuvio.app.features.music.nebula

import com.nuvio.app.features.music.MusicAlbum
import com.nuvio.app.features.music.MusicArtistRef
import com.nuvio.app.features.music.MusicTrack
import com.nuvio.app.features.music.library.LibraryArtist
import com.nuvio.app.features.music.library.LibraryRelease

/*
 * An album only the music addon has: a release the artist page lists (from MusicBrainz) that YouTube Music does not.
 * It opens on the ordinary album page under an id of its own, and is built from the addon's own track list, so there
 * is nothing to match against. Pure (the id, the decoding, the album), so it is unit tested; NebulaMusicSource fetches.
 */

/** Marks an album id as an addon-only album. A YouTube Music album id never starts this way (`MPREb_...`). */
internal const val ADDON_ALBUM_PREFIX = "addon:"

/**
 * What an addon-only album's id carries: the addon's album, and the library release it stands for (the artist's id and
 * the release's key). The release is how the page finds the cover, the artist's colours and the details panel.
 */
internal data class AddonAlbumRef(val albumId: String, val artistId: String, val releaseKey: String)

/** The id the album page opens for [ref]: `addon:` and the three parts percent-encoded and joined with ':'. */
internal fun addonAlbumId(ref: AddonAlbumRef): String =
    ADDON_ALBUM_PREFIX + listOf(ref.albumId, ref.artistId, ref.releaseKey).joinToString(":") { urlEncodeQuery(it) }

/** The [AddonAlbumRef] an album id carries, or null when it is not an addon-only album's id (or is damaged). */
internal fun parseAddonAlbumId(id: String): AddonAlbumRef? {
    if (!id.startsWith(ADDON_ALBUM_PREFIX)) return null
    val parts = id.removePrefix(ADDON_ALBUM_PREFIX).split(':')
    if (parts.size != 3) return null
    val decoded = parts.map { urlDecodeQuery(it) ?: return null }
    if (decoded.any { it.isEmpty() }) return null
    return AddonAlbumRef(albumId = decoded[0], artistId = decoded[1], releaseKey = decoded[2])
}

/** The reverse of [urlEncodeQuery]: percent-escapes back to UTF-8 text. Null for a bad escape or a character the encoder never leaves bare. */
internal fun urlDecodeQuery(text: String): String? {
    val bytes = ByteArray(text.length)
    var n = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '%') {
            if (i + 2 >= text.length) return null
            val high = text[i + 1].digitToIntOrNull(16) ?: return null
            val low = text[i + 2].digitToIntOrNull(16) ?: return null
            bytes[n++] = (high * 16 + low).toByte()
            i += 3
        } else {
            if (c.code >= 128) return null
            bytes[n++] = c.code.toByte()
            i++
        }
    }
    return bytes.copyOf(n).decodeToString()
}

/**
 * The album page's album for an addon-only release. [detail] is the addon's album (its tracks, in order); [artist] and
 * [release] are what the library knows (either may be gone, as a previewed artist is dropped after a while), in which case
 * the addon's own title, artist and year stand in. Every track is `nebula:<id>`, so the player asks the addon for its
 * link when it plays, and every track is available: the addon has it.
 */
internal fun buildAddonAlbum(
    ref: AddonAlbumRef,
    detail: NebulaAlbum,
    artist: LibraryArtist?,
    release: LibraryRelease?,
): MusicAlbum {
    val artistName = artist?.name ?: detail.summary.artist
    // The library's artist id is the artist's YouTube Music channel id, so the page can link to the artist.
    val albumArtist = MusicArtistRef(name = artistName, browseId = artist?.id)
    return MusicAlbum(
        browseId = addonAlbumId(ref),
        title = release?.title ?: detail.summary.title,
        artists = listOf(albumArtist),
        year = release?.year ?: release?.releaseDate?.take(4) ?: detail.summary.year?.toString(),
        kind = release?.kind?.label ?: "Album",
        thumbnailUrl = release?.art?.cover,
        tracks = detail.tracks.mapIndexed { index, track ->
            MusicTrack(
                videoId = NebulaMusicSource.ID_PREFIX + track.id,
                title = track.title,
                artists = listOf(
                    if (track.artist.isBlank() || track.artist.equals(artistName, ignoreCase = true)) albumArtist
                    else MusicArtistRef(name = track.artist),
                ),
                durationSeconds = track.durationSeconds,
                trackNumber = index + 1,
                isAvailable = true,
            )
        },
        audioPlaylistId = null,
    )
}
