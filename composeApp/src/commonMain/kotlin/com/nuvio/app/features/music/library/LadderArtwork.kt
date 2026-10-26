package com.nuvio.app.features.music.library

/**
 * Ladder phase 5: a picture for each rung. A rung is a MusicBrainz artist, which has no image, so the picture comes from
 * Deezer's artist search (exact name only, so a namesake's face never shows). Kept for the session, including "none".
 */
internal object LadderArtwork {
    private val pictures = HashMap<String, String?>()

    suspend fun picture(name: String): String? {
        val key = genreKey(name)
        if (pictures.containsKey(key)) return pictures[key]
        val url = DeezerClient.searchArtist(name).firstOrNull { sameArtistName(it.name, name) }?.picture
        pictures[key] = url
        return url
    }
}
