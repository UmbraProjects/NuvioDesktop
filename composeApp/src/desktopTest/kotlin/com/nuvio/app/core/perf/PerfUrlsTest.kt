package com.nuvio.app.core.perf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PerfUrlsTest {
    @Test
    fun tmdbKeyIsMaskedAndIdsGroupInThePattern() {
        val redacted = PerfUrls.redact("https://api.themoviedb.org/3/tv/1399/season/2?api_key=abc123secret&language=en-US")
        assertEquals("api.themoviedb.org/3/tv/1399/season/2?api_key=***&language=en-US", redacted.display)
        assertEquals("api.themoviedb.org/{n}/tv/{n}/season/{n}?api_key&language", redacted.pattern)
    }

    @Test
    fun addonConfigInThePathNeverReachesTheLog() {
        val key = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdef"
        val urls = listOf(
            "https://torrentio.strem.fun/realdebrid=$key/stream/series/tt0944947:1:2.json",
            "https://aiostreams.example/stremio/$key/manifest.json",
            "https://addon.example/0f8fad5b-d9cb-469f-a165-70867728950e/catalog/movie/top.json",
            "https://user:pass@host.example/x?token=$key",
        )
        urls.forEach { url ->
            val redacted = PerfUrls.redact(url)
            assertFalse(key in redacted.display, redacted.display)
            assertFalse(key in redacted.pattern, redacted.pattern)
            assertFalse("0f8fad5b" in redacted.display, redacted.display)
            assertFalse("pass" in redacted.display, redacted.display)
        }
        assertEquals(
            "torrentio.strem.fun/{cfg}/stream/series/{tt}:{n}:{n}.json",
            PerfUrls.redact(urls[0]).pattern,
        )
    }

    @Test
    fun xtreamLoginInALiveStreamPathNeverReachesTheLog() {
        val redacted = PerfUrls.redact("http://provider.example:8080/live/someuser/s3cretpw/325781.ts")
        assertFalse("someuser" in redacted.display, redacted.display)
        assertFalse("s3cretpw" in redacted.display, redacted.display)
        assertFalse("s3cretpw" in redacted.pattern, redacted.pattern)
        val api = PerfUrls.redact("http://provider.example/player_api.php?username=someuser&password=s3cretpw")
        assertFalse("someuser" in api.display, api.display)
        assertFalse("s3cretpw" in api.display, api.display)
    }

    @Test
    fun posterFileNamesStayReadable() {
        val redacted = PerfUrls.redact("https://image.tmdb.org/t/p/w500/kqjL17yufvn9OVLyXYpvtyrFfak.jpg")
        assertEquals("image.tmdb.org/t/p/w500/kqjL17yufvn9OVLyXYpvtyrFfak.jpg", redacted.display)
    }
}
