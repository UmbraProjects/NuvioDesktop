package com.nuvio.app.features.watchprogress

import co.touchlab.kermit.Logger

/**
 * Traces why a Continue Watching card shows the artwork it does.
 *
 * A blank card is the single most-reported Continue Watching defect and the hardest to act on,
 * because every cause looks identical from the outside: the provider never published an episode
 * still; it published a show backdrop in the still's place; the refresh replaced good cached
 * artwork with a partial metadata payload; or the URL is fine and simply fails to load. The user
 * cannot tell those apart, and neither can a screenshot.
 *
 * Emits under the `CwArtDiag` tag, which lands in `nuvio.log` via the desktop stdout tee. Set
 * [ENABLED] to false to compile it out of the hot path.
 */
internal object ContinueWatchingArtworkDiagnostics {

    const val ENABLED = true

    private val log = Logger.withTag("CwArtDiag")

    /**
     * Copy-on-write behind a volatile reference: cards resolve from several `Dispatchers.Default`
     * coroutines at once and image loads report failures off the main thread, so a shared mutable
     * set would eventually turn a diagnostic into a ConcurrentModificationException. Losing a race
     * here only costs a duplicated line.
     */
    @Volatile
    private var loggedKeys: Set<String> = emptySet()

    fun reset() {
        if (!ENABLED) return
        loggedKeys = emptySet()
    }

    /**
     * One line per card whose artwork changed during a refresh, naming what was on the card before
     * and what replaced it. This is where a refresh that overwrites good artwork with nothing shows
     * up as a concrete before/after pair rather than as "the thumbnail went blank".
     */
    fun logArtworkRefresh(
        contentId: String,
        title: String,
        reason: String,
        cachedEpisodeThumbnail: String?,
        freshEpisodeThumbnail: String?,
        mergedEpisodeThumbnail: String?,
        mergedPoster: String?,
        mergedBackground: String?,
    ) {
        if (!ENABLED) return
        val selectedField = when {
            !mergedEpisodeThumbnail.isNullOrBlank() -> "episodeThumbnail"
            !mergedBackground.isNullOrBlank() -> "background"
            !mergedPoster.isNullOrBlank() -> "poster"
            else -> "NONE — this card will render blank"
        }
        val verdict = when {
            freshEpisodeThumbnail.isNullOrBlank() && !cachedEpisodeThumbnail.isNullOrBlank() ->
                "fresh meta lost the still; cached value retained"
            freshEpisodeThumbnail != cachedEpisodeThumbnail -> "still replaced"
            else -> "unchanged"
        }
        emit(
            key = "artwork-refresh:$contentId:$mergedEpisodeThumbnail:$mergedPoster:$mergedBackground",
            line = "ARTWORK $contentId ($title) reason=$reason | $verdict | " +
                "still: ${describe(cachedEpisodeThumbnail)} -> ${describe(freshEpisodeThumbnail)} " +
                "=> ${describe(mergedEpisodeThumbnail)} | " +
                "poster=${describe(mergedPoster)} background=${describe(mergedBackground)} | " +
                "card renders from $selectedField",
        )
    }

    /** The provider handed back show-level art in the episode-still field. */
    fun logGenericArtworkDetected(
        contentId: String,
        episodeThumbnail: String,
        matchedRule: String,
    ) {
        if (!ENABLED) return
        emit(
            key = "generic-artwork:$contentId:$episodeThumbnail",
            line = "GENERIC-STILL $contentId is show artwork, not an episode still ($matchedRule): " +
                "$episodeThumbnail | queued for another resolve",
        )
    }

    /**
     * The URL was well-formed and the card still came up empty. Logged once per URL per session,
     * from the image loader's error callback.
     */
    fun logArtworkLoadFailure(url: String) {
        if (!ENABLED) return
        emit(
            key = "load-failure:$url",
            line = "LOAD-FAILED $url | falling back to the next artwork this card has",
        )
    }

    private fun describe(url: String?): String =
        url?.trim()?.takeIf(String::isNotBlank) ?: "<none>"

    private fun emit(key: String, line: String) {
        if (key in loggedKeys) return
        loggedKeys = loggedKeys + key
        log.i { line }
    }
}
