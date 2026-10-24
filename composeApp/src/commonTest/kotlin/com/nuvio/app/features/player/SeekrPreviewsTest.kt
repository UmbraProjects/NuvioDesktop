package com.nuvio.app.features.player

import com.nuvio.app.features.player.skip.AnimeIdNamespace
import com.nuvio.app.features.player.skip.SkipLookupTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeekrPreviewsTest {

    private fun eligible(
        url: String,
        mode: DesktopSeekThumbnailMode = DesktopSeekThumbnailMode.Streaming,
        isTorrent: Boolean = false,
        preset: DesktopBufferPreset = DesktopBufferPreset.Balanced,
        key: String = "sk_live_test",
    ) = seekrPreviewsEligible(mode, preset, url, isTorrent, key)

    @Test
    fun internetSourcesUseSeekrOnlyInTheModesThatAskForIt() {
        val debrid = "https://nexus-133.neur.tb-cdn.st/dld/abc?token=x"
        assertTrue(eligible(debrid))
        assertTrue(eligible(debrid, mode = DesktopSeekThumbnailMode.LocalAndSeekr))
        assertFalse(eligible(debrid, mode = DesktopSeekThumbnailMode.Local))
    }

    /** Local + Seekr never falls back to the native decoder for a stream. */
    @Test
    fun localAndSeekrKeepsTheNativeDecoderOffStreams() {
        val mode = DesktopSeekThumbnailMode.LocalAndSeekr
        val preset = DesktopBufferPreset.Balanced
        assertFalse(seekThumbnailsAllowed(mode, preset, "https://example-cdn.net/film.mkv", isTorrent = false))
        assertFalse(seekThumbnailsAllowed(mode, preset, "http://127.0.0.1:8097/stream/abc", isTorrent = true))
        assertTrue(seekThumbnailsAllowed(mode, preset, "D:\\Media\\Film.mkv", isTorrent = false))
    }

    @Test
    fun torrentsUseSeekrDespiteTheLoopbackEngineUrl() {
        assertTrue(eligible("http://127.0.0.1:8097/stream/abc", isTorrent = true))
    }

    @Test
    fun userControlledSourcesKeepNativeFrames() {
        assertFalse(eligible("D:\\Media\\Film.mkv"))
        assertFalse(eligible("http://192.168.1.20:8096/Videos/abc/stream.mkv"))
    }

    @Test
    fun noKeyOffOrMeteredMeansNoLookup() {
        val debrid = "https://example-cdn.net/film.mkv"
        assertFalse(eligible(debrid, key = "  "))
        assertFalse(eligible(debrid, mode = DesktopSeekThumbnailMode.Off))
        assertFalse(eligible(debrid, preset = DesktopBufferPreset.Metered))
    }

    @Test
    fun lookupQueryAddressesFilmsAndEpisodesByImdb() {
        assertEquals(
            "imdb_id=tt1130884&duration_ms=8280000",
            SeekrPreviews.lookupQuery(SkipLookupTarget.Movie("tt1130884"), 8_280_000L),
        )
        assertEquals(
            "show_imdb_id=tt0108778&season=10&episode=4&duration_ms=1320000",
            SeekrPreviews.lookupQuery(SkipLookupTarget.Episode("tt0108778", 10, 4), 1_320_000L),
        )
        assertNull(SeekrPreviews.lookupQuery(SkipLookupTarget.Anime(AnimeIdNamespace.KITSU, "1", 3), 1_400_000L))
    }

    @Test
    fun signedUrlExpiryIsReadFromExp() {
        assertEquals(
            1_790_940_221_000L,
            SeekrPreviews.signedUrlExpiryMs("https://sprites.seekr.tv/11324/8285000/thumbnails.vtt?exp=1790940221&sig=abc&scale=1"),
        )
        assertNull(SeekrPreviews.signedUrlExpiryMs("https://sprites.seekr.tv/x/thumbnails.vtt?sig=abc"))
    }

    @Test
    fun retryAfterFallsBackToAnHour() {
        assertEquals(120_000L, SeekrPreviews.retryAfterMs(mapOf("retry-after" to "120")))
        assertEquals(3_600_000L, SeekrPreviews.retryAfterMs(emptyMap()))
    }
}
