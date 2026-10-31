package com.nuvio.app.features.music

import com.nuvio.app.features.trailer.YouTubeSearchHit

/*
 * fork:music-youtube - the Music tab's YouTube search: the query as typed, on plain YouTube rather than YouTube Music,
 * for songs that are not on any album there (a one-off single, a live session, an upload that never got a release).
 * Hits are raw videos, so their titles are tidied into a song and an artist the player and lyrics lookup can use.
 */

/** A YouTube video as the player's track: the title tidied, the channel (or the title's "Artist - ") as the artist. */
internal fun YouTubeSearchHit.toMusicTrack(): MusicTrack {
    val (artist, song) = splitYouTubeMusicTitle(title, channel)
    return MusicTrack(
        videoId = videoId,
        title = song,
        artists = listOf(MusicArtistRef(artist, null)),
        durationSeconds = durationSeconds,
        trackNumber = 1,
        isAvailable = true,
        coverUrl = youTubeThumbnail(videoId),
    )
}

/** What the player takes: [track] alone, as a one-track album that has no page of its own. */
internal fun youTubeSingleAlbum(track: MusicTrack): MusicAlbum = MusicAlbum(
    browseId = "youtube:${track.videoId}",
    title = track.title,
    artists = track.artists,
    year = null,
    kind = "Single",
    thumbnailUrl = track.coverUrl,
    tracks = listOf(track),
    audioPlaylistId = null,
)

internal fun youTubeThumbnail(videoId: String): String = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

/** "3:45", or "1:02:10" past the hour; null when YouTube gave no length (a live stream). */
internal fun youTubeLengthLabel(seconds: Int): String? {
    if (seconds <= 0) return null
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = (seconds % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$s" else "$m:$s"
}

/**
 * Artist and song from a video's [title] and [channel]. "Artist - Song (Official Video)" splits on the dash, with the
 * upload's labels taken off the song; otherwise the whole title is the song and the channel the artist, without
 * YouTube's " - Topic" and the VEVO suffix.
 */
internal fun splitYouTubeMusicTitle(title: String, channel: String): Pair<String, String> {
    val cleanTitle = stripUploadLabels(title)
    val channelArtist = channel
        .removeSuffix(" - Topic")
        .replace(VEVO_SUFFIX, "")
        .trim()
    val dash = DASH_SEPARATOR.find(cleanTitle)
    if (dash != null) {
        val artist = cleanTitle.substring(0, dash.range.first).trim()
        val song = cleanTitle.substring(dash.range.last + 1).trim().trim('"', '\'', '“', '”')
        if (artist.isNotEmpty() && song.isNotEmpty()) return artist to song
    }
    return channelArtist.ifEmpty { channel.trim() } to cleanTitle.trim('"', '\'', '“', '”').ifEmpty { title.trim() }
}

private fun stripUploadLabels(title: String): String {
    var out = title
    // "(Official Music Video)", "[Lyrics]", "(Audio)", "【MV】" and the like, wherever they sit.
    out = BRACKETED.replace(out) { match ->
        if (UPLOAD_LABEL.containsMatchIn(match.value)) "" else match.value
    }
    // "Song | Official Video", "Song // Lyric Video": the label after a bar or slashes.
    out = out.replace(TRAILING_BAR_LABEL, "")
    return out.replace(Regex("\\s{2,}"), " ").trim()
}

private val DASH_SEPARATOR = Regex("\\s+[-–—]\\s+")
private val VEVO_SUFFIX = Regex("VEVO$", RegexOption.IGNORE_CASE)
private val BRACKETED = Regex("\\s*[(\\[【][^)\\]】]*[)\\]】]")
private val UPLOAD_LABEL = Regex(
    "official|video|audio|lyric|visuali[sz]er|\\bmv\\b|\\bhd\\b|\\bhq\\b|\\b4k\\b|remaster|explicit|clean",
    RegexOption.IGNORE_CASE,
)
private val TRAILING_BAR_LABEL = Regex(
    "\\s*(\\||//)\\s*(official|lyric|music video|audio|visuali[sz]er).*$",
    RegexOption.IGNORE_CASE,
)
