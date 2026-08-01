package com.nuvio.app.features.locallibrary

import com.nuvio.app.features.metadata.AnimeIdMapping
import com.nuvio.app.features.metadata.AnimeIdMappingRepository
import com.nuvio.app.features.metadata.animeMappingCoordinateSystemFor

/**
 * Maps clicked video ids onto local anime files.
 *
 * Local anime files are parsed into one of two coordinate spaces: franchise `SxxEyy`
 * (season + episode, how TMDB/TVDB-style metas number a whole franchise) or bare absolute
 * numbers (how kitsu/mal number a single entry — one entry per season, episodes restarting
 * at 1). The clicked video id arrives in either space too: `kitsu:<id>:<ep>` is entry-relative
 * while `tt…:<season>:<episode>` / `tmdb:<id>:<season>:<episode>` are franchise coordinates.
 * The offline anime-list mapping carries each entry's franchise season and split-cour episode
 * offset, which is what lets both spaces be compared here.
 */
internal object LocalAnimeEpisodeMatcher {

    private val nativeEpisodeIdRegex = Regex("""(?i)^(kitsu|mal|myanimelist|al|anilist|anidb):(\d+):(\d+)$""")
    private val nativeMetaIdRegex = Regex("""(?i)^(kitsu|mal|myanimelist|al|anilist|anidb):(\d+)$""")

    /**
     * The files of [item] that back [videoId]. Null when the id carries no episode coordinates
     * at all (the caller picks a fallback); empty when it does but the episode isn't on disk.
     */
    fun matchFiles(item: LocalMediaItem, videoId: String): List<LocalMediaFile>? {
        val target = resolveTarget(item, videoId) ?: return null
        return item.files.filter { it.isEpisodePlayable && it.matches(target) }
    }

    /**
     * Whether [item] belongs to the franchise behind a native anime meta id (`kitsu:123`).
     * Sibling seasons are separate kitsu/mal entries; the mapping ties them together through
     * their shared TVDB/TMDB show id, so a local item also surfaces on its other seasons' pages.
     */
    fun matchesFranchiseMeta(item: LocalMediaItem, metaId: String): Boolean {
        // Series only: anime movies share their franchise's TVDB id too (they map to TVDB
        // season 0), but a movie surfacing on the series page has no episode to offer there —
        // it is reachable through its own ids instead.
        if (!item.isAnime || item.type != LocalFolderType.SERIES) return false
        val match = nativeMetaIdRegex.find(metaId) ?: return false
        val id = match.groupValues[2].toIntOrNull() ?: return false
        val entry = entryFor(match.groupValues[1], id) ?: return false
        val base = item.mappingEntry() ?: return false
        if (entry == base) return true
        return (entry.tvdbId != null && entry.tvdbId == base.tvdbId) ||
            (entry.tmdbTvId != null && entry.tmdbTvId == base.tmdbTvId)
    }

    /** The clicked episode expressed in both coordinate spaces (a side is null when unknowable). */
    private data class Target(
        val franchiseSeason: Int?,
        val franchiseEpisode: Int?,
        /**
         * Episode number relative to the item's own kitsu/mal entry — only set when the click
         * resolves to that same entry, because absolute file numbering is entry-relative and a
         * sibling season's episode 1 is not this entry's episode 1.
         */
        val entryEpisode: Int?,
    )

    private fun LocalMediaFile.matches(target: Target): Boolean {
        val fileEpisode = effectiveEpisode ?: return false
        return if (effectiveSeason != null) {
            effectiveSeason == target.franchiseSeason && fileEpisode == target.franchiseEpisode
        } else {
            fileEpisode == target.entryEpisode
        }
    }

    private fun resolveTarget(item: LocalMediaItem, videoId: String): Target? {
        nativeEpisodeIdRegex.find(videoId)?.let { match ->
            val id = match.groupValues[2].toIntOrNull() ?: return null
            val episode = match.groupValues[3].toIntOrNull() ?: return null
            return nativeTarget(item, match.groupValues[1], id, episode)
        }
        return franchiseTarget(item, videoId)
    }

    /** `kitsu:<id>:<ep>` and friends: entry-relative → franchise via the entry's mapping. */
    private fun nativeTarget(item: LocalMediaItem, namespace: String, id: Int, episode: Int): Target {
        val entry = entryFor(namespace, id)
        val base = item.mappingEntry()
        val isItemEntry = when (namespace.lowercase()) {
            "kitsu" -> id == item.kitsuId
            "mal", "myanimelist" -> id == item.malId
            else -> false
        } || (entry != null && entry == base)
        // Only the item's own entry or a sibling of its franchise may resolve — an id from an
        // unrelated anime (or one absent from the mapping) must match nothing rather than be
        // guessed at, or a stale meta could play the wrong show's file.
        if (!isItemEntry) {
            val sameFranchise = entry != null && base != null &&
                ((entry.tvdbId != null && entry.tvdbId == base.tvdbId) ||
                    (entry.tmdbTvId != null && entry.tmdbTvId == base.tmdbTvId))
            if (!sameFranchise) return Target(null, null, null)
        }
        return Target(
            franchiseSeason = entry?.franchiseSeason() ?: 1,
            franchiseEpisode = episode + (entry?.franchiseOffset() ?: 0),
            entryEpisode = episode.takeIf { isItemEntry },
        )
    }

    /** `tt…:<s>:<e>` / `tmdb:<id>:<s>:<e>`: franchise coords → entry-relative via siblings. */
    private fun franchiseTarget(item: LocalMediaItem, videoId: String): Target? {
        val parts = videoId.split(':')
        if (parts.size < 3) return null
        val episode = parts[parts.lastIndex].toIntOrNull() ?: return null
        val season = parts[parts.lastIndex - 1].toIntOrNull() ?: return null
        // Franchise coordinates must come from an id the item actually carries (imdb/tmdb/…);
        // otherwise this is another title's episode id reaching us via a stale meta.
        if (!item.ownsVideoId(videoId)) return Target(null, null, null)
        val base = item.mappingEntry()
        val entry = base?.let {
            AnimeIdMappingRepository.franchiseEntryFor(
                base = it,
                season = season,
                episode = episode,
                coordinateSystem = animeMappingCoordinateSystemFor(videoId),
            )
        }
            ?: base?.takeIf { it.franchiseSeason() == season }
        val entryEpisode = when {
            entry != null && entry == base -> episode - entry.franchiseOffset()
            // No mapping data for the item at all — treat it as a plain season-1 entry.
            base == null && season == 1 -> episode
            else -> null
        }
        return Target(season, episode, entryEpisode)
    }

    private fun entryFor(namespace: String, id: Int): AnimeIdMapping? = when (namespace.lowercase()) {
        "kitsu" -> AnimeIdMappingRepository.entryForNativeIds(kitsu = id)
        "mal", "myanimelist" -> AnimeIdMappingRepository.entryForNativeIds(mal = id)
        "al", "anilist" -> AnimeIdMappingRepository.entryForNativeIds(anilist = id)
        else -> AnimeIdMappingRepository.entryForNativeIds(anidb = id)
    }

    private fun LocalMediaItem.mappingEntry(): AnimeIdMapping? =
        if (kitsuId == null && malId == null) null
        else AnimeIdMappingRepository.entryForNativeIds(kitsu = kitsuId, mal = malId)

    // tvdb-first to stay consistent with AnimeIdMappingRepository.franchiseEntryFor.
    private fun AnimeIdMapping.franchiseSeason(): Int = tvdbSeason ?: tmdbSeason ?: 1

    private fun AnimeIdMapping.franchiseOffset(): Int = tvdbEpisodeOffset ?: tmdbEpisodeOffset ?: 0
}
