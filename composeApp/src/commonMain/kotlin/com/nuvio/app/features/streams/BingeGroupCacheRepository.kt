package com.nuvio.app.features.streams

object BingeGroupCacheRepository {

    /**
     * Remembers the binge group last played for a show and, when known, the release within it
     * (its filename and infohash), so the next play can pick that same release again. A missing
     * filename or infohash clears the stored one: it belonged to a different release.
     */
    fun save(contentId: String, bingeGroup: String, filename: String? = null, infoHash: String? = null) {
        BingeGroupCacheStorage.save(hashedKey(contentId), bingeGroup)
        saveOrRemove(filenameKey(contentId), filename)
        saveOrRemove(infoHashKey(contentId), infoHash)
    }

    fun get(contentId: String): String? {
        return BingeGroupCacheStorage.load(hashedKey(contentId))
    }

    /** The release last played within the stored binge group, aimed at [season]/[episode]. */
    fun releaseHint(contentId: String, season: Int?, episode: Int?): BingeReleaseHint? =
        BingeReleaseHint.forEpisode(
            lastFilename = BingeGroupCacheStorage.load(filenameKey(contentId)),
            lastInfoHash = BingeGroupCacheStorage.load(infoHashKey(contentId)),
            season = season,
            episode = episode,
        )

    fun remove(contentId: String) {
        BingeGroupCacheStorage.remove(hashedKey(contentId))
        BingeGroupCacheStorage.remove(filenameKey(contentId))
        BingeGroupCacheStorage.remove(infoHashKey(contentId))
    }

    private fun saveOrRemove(key: String, value: String?) {
        val trimmed = value?.takeIf { it.isNotBlank() }
        if (trimmed != null) BingeGroupCacheStorage.save(key, trimmed) else BingeGroupCacheStorage.remove(key)
    }

    private fun contentHash(contentId: String): ULong = contentId.fold(0L) { acc, c -> acc * 31 + c.code }.toULong()

    private fun hashedKey(contentId: String): String = "binge_group_${contentHash(contentId)}"

    private fun filenameKey(contentId: String): String = "binge_group_fn_${contentHash(contentId)}"

    private fun infoHashKey(contentId: String): String = "binge_group_ih_${contentHash(contentId)}"
}
