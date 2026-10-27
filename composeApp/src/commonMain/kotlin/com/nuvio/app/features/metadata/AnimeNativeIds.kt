package com.nuvio.app.features.metadata

/**
 * Whether a content id addresses an **anime-native** catalogue (kitsu / MyAnimeList / AniList /
 * AniDB) rather than a franchise one (imdb / tmdb / tvdb).
 *
 * The distinction decides which coordinate space an episode lives in, and it is not cosmetic: those
 * catalogues have no seasons at all. One franchise season is one *entry* there, with episodes
 * restarting at 1 — Pokémon B&W and its Rival Destinies continuation are separate kitsu ids, not
 * seasons 14 and 15 of one show. The offline mapping agrees: across every entry in
 * `anime-list-mini.json`, `season` and `episode_offset` are keyed only `tvdb` and `tmdb`, never by
 * an anime-native id. It is also why native stream ids stay two-part (`kitsu:<id>:<ep>`) — there is
 * no season component to put in a third.
 *
 * So a season number carried by a *file* is meaningless once the grab is addressed by one of these
 * ids, and must not be used to build a path or an episode id.
 */
private val ANIME_NATIVE_ID_NAMESPACES = setOf(
    "kitsu",
    "mal",
    "myanimelist",
    "anilist",
    "al",
    "anidb",
)

fun String?.isAnimeNativeId(): Boolean {
    val namespace = this?.trim()?.substringBefore(':', missingDelimiterValue = "")?.lowercase()
    return !namespace.isNullOrEmpty() && namespace in ANIME_NATIVE_ID_NAMESPACES
}

/**
 * The episode's number inside the native anime entry that was opened, or null when the episode is
 * not addressed in that entry's own numbering.
 *
 * The stream picker and player are handed canonical TMDB/TVDB coordinates. For a native anime entry
 * past its first cour those are a franchise pair the entry itself does not have: Apothecary Diaries
 * season 3 (`kitsu:50021`) is TMDB S1E49 onward, because TMDB files the whole show as one season.
 * The id itself is unambiguous, so whoever needs the entry's numbering reads it from there: a library
 * file is matched back to the opened entry, and tracking converts from entry-local numbers (with a
 * mapped season of 1, the canonical pair would be indistinguishable from them and offset twice).
 *
 * Only a 3-part `prefix:id:episode` id under its own parent qualifies: a 4-part id is franchise
 * numbered by construction, and an id for a different entry came from a franchise remap.
 */
internal fun nativeAnimeEntryEpisode(parentMetaId: String, videoId: String): Int? {
    if (!parentMetaId.isAnimeNativeId()) return null
    val parts = videoId.trim().split(':')
    val parentParts = parentMetaId.trim().split(':')
    if (parts.size != 3 || parentParts.size < 2) return null
    if (!parts[0].equals(parentParts[0], ignoreCase = true) || parts[1] != parentParts[1]) return null
    return parts[2].toIntOrNull()?.takeIf { it > 0 }
}
