package com.nuvio.app.features.watchprogress

/**
 * Whether a Continue Watching card's episode still is worth another look.
 *
 * A non-empty thumbnail is not necessarily an episode still, and a well-formed URL is not
 * necessarily an image. Both halves of Continue Watching — the Up Next cards resolved in
 * `HomeScreen` and the in-progress entries resolved by [WatchProgressRepository] — have to agree on
 * this, or a card is retried on one screen and declared finished on the other.
 *
 * @param hasArtworkLoadFailed the session's record of URLs that did not load. Injected rather than
 *   read directly so the rule stays a pure function for tests.
 */
internal fun needsEpisodeStillRefresh(
    episodeThumbnail: String?,
    poster: String?,
    background: String?,
    contentId: String,
    hasArtworkLoadFailed: (String) -> Boolean = ContinueWatchingArtworkFailures::hasFailed,
    claimLowResolutionLook: (String) -> Boolean = LowResolutionStillLooks::claim,
): Boolean {
    val thumbnail = episodeThumbnail?.trim()?.takeIf(String::isNotBlank) ?: return true

    // The one authoritative signal that a still is bad: the image loader tried it and got nothing.
    // Until this fed back into resolution the card could only ever fall through to the show
    // backdrop, for the whole life of the seed, no matter how many times it was re-rendered.
    if (hasArtworkLoadFailed(thumbnail)) {
        return true
    }

    // A TheTVDB or Kitsu still (400x225 or 640x360) works but is soft on a large card, and the
    // details refetch can now swap in TMDB's still for the same episode (keptRecordThumbnail). A
    // working still was never re-resolved, so the stored card kept the small one for good: it gets
    // one look per session, and keeps the small still only when TMDB has nothing for that episode.
    if (isLowResolutionSourceStill(thumbnail) && claimLowResolutionLook(thumbnail)) {
        return true
    }

    val thumbnailIdentity = thumbnail.artworkResourceIdentity() ?: return true
    val copiesSeriesArtwork = sequenceOf(background, poster)
        .mapNotNull { seriesArtwork -> seriesArtwork.artworkResourceIdentity() }
        .any { seriesArtworkIdentity -> seriesArtworkIdentity == thumbnailIdentity }
    if (copiesSeriesArtwork) return true

    val genericArtworkRule = seriesLevelArtworkRule(thumbnail) ?: return false
    ContinueWatchingArtworkDiagnostics.logGenericArtworkDetected(
        contentId = contentId,
        episodeThumbnail = thumbnail,
        matchedRule = genericArtworkRule,
    )
    return true
}

/**
 * Comparing the thumbnail against the card's own poster and backdrop only catches a provider that
 * copies *Nuvio's* series artwork into the still field. AIOMetadata does something the comparison
 * cannot see: it serves a TVDB series background as `episodeThumbnail` while Nuvio's backdrop came
 * from TMDB. The two URLs are unrelated, so the card was declared complete and never retried, and
 * the show backdrop stuck for the life of the seed.
 *
 * These providers name the artwork *kind* in the path, so the URL itself says it is show-level.
 * Only show- and season-level directories are listed — TVDB episode stills live under `/episodes/`
 * and `/episode/`, which deliberately do not match, and TMDB paths (`/t/p/<size>/`) carry no kind
 * at all and so never match either.
 */
private fun seriesLevelArtworkRule(url: String): String? = SeriesLevelArtworkRules
    .firstOrNull { (_, pattern) -> pattern.containsMatchIn(url) }
    ?.first

private val SeriesLevelArtworkRules: List<Pair<String, Regex>> = listOf(
    "thetvdb series artwork" to Regex(
        "/series/[^/]+/(?:backgrounds|posters|banners|icons|clearlogo|clearart|fanart)/",
        RegexOption.IGNORE_CASE,
    ),
    "fanart.tv show artwork" to Regex(
        "/(?:showbackground|tvposter|tvbanner|tvthumb|hdtvlogo|clearlogo|clearart|hdclearart" +
            "|characterart|seasonposter|seasonthumb)/",
        RegexOption.IGNORE_CASE,
    ),
)

/** TheTVDB's and Kitsu's episode stills: real, but small (see [needsEpisodeStillRefresh]). */
internal fun isLowResolutionSourceStill(url: String): Boolean {
    val lower = url.lowercase()
    return "thetvdb.com" in lower || "kitsu.app" in lower || "kitsu.io" in lower
}

/**
 * The small stills already sent for a sharper one this session. The first ask for a still claims it;
 * every later ask is refused, so a card whose episode TMDB does not have is not refetched forever.
 */
internal object LowResolutionStillLooks {
    private val claimed = HashSet<String>()

    fun claim(url: String): Boolean = synchronized(claimed) { claimed.add(url) }
}

private fun String?.artworkResourceIdentity(): String? = this
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.substringBefore('#')
    ?.substringBefore('?')
    // TMDB serves the same file below size-specific paths such as /w500/ and /original/.
    ?.replace(Regex("/t/p/(?:original|w\\d+)/", RegexOption.IGNORE_CASE), "/t/p/")
    ?.lowercase()
