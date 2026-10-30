package com.nuvio.app.features.music

import co.touchlab.kermit.Logger

private val log = Logger.withTag("MusicSpike")

/**
 * Phase 0 measurement: for each album search, how many of its tracks have a music video that
 * YouTube Music links directly. Call it from a debug action or a scratch test and read the report
 * (also written to the log). It touches only the network, never the player.
 *
 * The number that matters is the last line: the share of available tracks with a counterpart.
 * The roadmap's go/no-go for video mode is about 80% on popular albums.
 */
internal object MusicSpike {
    suspend fun run(albumQueries: List<String>): String {
        val lines = ArrayList<String>()
        var tracksTotal = 0
        var withVideo = 0
        for (query in albumQueries) {
            val hit = MusicMetadataClient.searchAlbums(query).firstOrNull()
            if (hit == null) {
                lines += "$query: no album found"
                continue
            }
            val album = MusicMetadataClient.album(hit.browseId)
            if (album == null) {
                lines += "$query: ${hit.title} (${hit.browseId}): page did not parse"
                continue
            }
            val tracks = album.tracks.filter { it.isAvailable }
            var matched = 0
            val missing = ArrayList<String>()
            for (track in tracks) {
                if (MusicMetadataClient.counterpartOf(track.videoId) != null) matched++ else missing += track.title
            }
            tracksTotal += tracks.size
            withVideo += matched
            lines += "$query: ${album.title} by ${album.artists.joinToString { it.name }}: $matched/${tracks.size} tracks have a video" +
                if (missing.isEmpty()) "" else " (none for: ${missing.take(5).joinToString()})"
        }
        val percent = if (tracksTotal == 0) 0 else withVideo * 100 / tracksTotal
        lines += "TOTAL: $withVideo/$tracksTotal tracks have a linked video ($percent%)"
        return lines.joinToString("\n").also { log.i { "\n$it" } }
    }
}
