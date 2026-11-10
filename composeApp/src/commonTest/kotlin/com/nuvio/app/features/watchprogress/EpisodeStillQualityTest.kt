package com.nuvio.app.features.watchprogress

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The in-progress half of Continue Watching used to ask only whether the episode still was blank,
 * so a card holding the show backdrop — or a URL that could not be drawn — looked finished and was
 * never resolved again. Up Next had already learned better; this pins the two to the same rule.
 */
class EpisodeStillQualityTest {

    private val tmdbBackdrop = "https://image.tmdb.org/t/p/original/backdrop.jpg"
    private val tmdbPoster = "https://image.tmdb.org/t/p/w500/poster.jpg"

    private fun entry(
        episodeThumbnail: String?,
        contentType: String = "series",
        background: String? = tmdbBackdrop,
    ) = WatchProgressEntry(
        contentType = contentType,
        parentMetaId = "tt1",
        parentMetaType = contentType,
        videoId = "tt1:1:4",
        title = "Show",
        poster = tmdbPoster,
        background = background,
        episodeThumbnail = episodeThumbnail,
        lastPositionMs = 0L,
        durationMs = 0L,
        lastUpdatedEpochMs = 0L,
    )

    @Test
    fun `a real episode still is left alone`() {
        assertFalse(entry("https://image.tmdb.org/t/p/w500/still.jpg").needsEpisodeStillRefresh())
    }

    @Test
    fun `a missing still is resolved`() {
        assertTrue(entry(null).needsEpisodeStillRefresh())
        assertTrue(entry("   ").needsEpisodeStillRefresh())
    }

    @Test
    fun `a show backdrop served as the still is resolved`() {
        // AIOMetadata's actual behaviour: a TVDB series background in the episodeThumbnail field
        // while Nuvio's own backdrop came from TMDB, so comparing the two URLs finds nothing.
        assertTrue(
            entry("https://artworks.thetvdb.com/banners/v4/series/465664/backgrounds/6a769.jpg")
                .needsEpisodeStillRefresh(),
        )
    }

    @Test
    fun `the same artwork at a different TMDB size is still the backdrop`() {
        assertTrue(entry("https://image.tmdb.org/t/p/w780/backdrop.jpg").needsEpisodeStillRefresh())
    }

    @Test
    fun `a TVDB episode still gets one look for a sharper one, then is kept`() {
        // Not show artwork, but 400x225 or 640x360: worth one refetch a session, where TMDB's still
        // for the same episode can replace it. If TMDB has none, the small one stays.
        val still = "https://artworks.thetvdb.com/banners/v4/episodes/465664/1234.jpg"
        val claimed = HashSet<String>()
        fun ask() = needsEpisodeStillRefresh(
            episodeThumbnail = still,
            poster = tmdbPoster,
            background = tmdbBackdrop,
            contentId = "tt1",
            hasArtworkLoadFailed = { false },
            claimLowResolutionLook = { url -> claimed.add(url) },
        )
        assertTrue(ask(), "the first ask sends it for a sharper still")
        assertFalse(ask(), "later asks keep it, so a card TMDB cannot help is not refetched forever")
    }

    @Test
    fun `a TMDB still is not sent for a sharper one`() {
        assertFalse(
            needsEpisodeStillRefresh(
                episodeThumbnail = "https://image.tmdb.org/t/p/w1280/still.jpg",
                poster = tmdbPoster,
                background = tmdbBackdrop,
                contentId = "tt1",
                hasArtworkLoadFailed = { false },
                claimLowResolutionLook = { true },
            ),
        )
    }

    @Test
    fun `a movie has no episode still to be missing`() {
        assertFalse(entry(episodeThumbnail = null, contentType = "movie").needsEpisodeStillRefresh())
    }

    @Test
    fun `a still that failed to load is resolved again`() {
        val dead = "https://episodes.metahub.space/tt10986410/4/9/w780.jpg"

        assertFalse(
            needsEpisodeStillRefresh(
                episodeThumbnail = dead,
                poster = tmdbPoster,
                background = tmdbBackdrop,
                contentId = "tt1",
                hasArtworkLoadFailed = { false },
            ),
            "a metahub still that loads is a perfectly good still",
        )
        assertTrue(
            needsEpisodeStillRefresh(
                episodeThumbnail = dead,
                poster = tmdbPoster,
                background = tmdbBackdrop,
                contentId = "tt1",
                hasArtworkLoadFailed = { url -> url == dead },
            ),
            "the image loader failing is the one hard proof the still is no good",
        )
    }
}
