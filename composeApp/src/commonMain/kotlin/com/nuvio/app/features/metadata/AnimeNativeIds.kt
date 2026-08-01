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
