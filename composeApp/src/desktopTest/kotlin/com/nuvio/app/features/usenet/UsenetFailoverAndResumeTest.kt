package com.nuvio.app.features.usenet

import com.nuvio.app.features.player.nextFailoverStream
import com.nuvio.app.features.player.playerSourceIdentityKey
import com.nuvio.app.features.streams.CachedStreamLink
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamLinkCacheRepository
import com.nuvio.app.features.streams.StreamProxyHeaders
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/** Usenet failover (a release that will not open hands on) and Continue watching replays. */
class UsenetFailoverAndResumeTest {
    @AfterTest
    fun reset() = UsenetFailedReleases.clear()

    private fun release(name: String, size: Long = 4_000_000_000L) = StreamItem(
        name = "📀 2160p",
        addonName = BuiltInUsenet.GROUP_NAME,
        addonId = BuiltInUsenet.GROUP_ID,
        streamType = "usenet",
        behaviorHints = StreamBehaviorHints(filename = "$name.mkv", videoSize = size, bingeGroup = "Built-in Usenet|$name"),
        nzbUrl = "https://indexer.example/api?t=get&id=$name",
    )

    @Test
    fun `failover can pick a Usenet release that has no URL yet`() {
        val failed = release("a")
        val next = release("b")
        assertTrue(next.needsUsenetPreparation)
        assertEquals(
            next,
            nextFailoverStream(
                streams = listOf(failed, next),
                activeIdentityKey = failed.playerSourceIdentityKey(),
                triedIdentityKeys = setOfNotNull(failed.playerSourceIdentityKey()),
            ),
        )
    }

    @Test
    fun `failover skips a release that already failed to open`() {
        val broken = release("broken")
        val good = release("good")
        UsenetFailedReleases.record(broken, "missing-article")
        assertEquals(
            good,
            nextFailoverStream(streams = listOf(broken, good), activeIdentityKey = null, triedIdentityKeys = emptySet()),
        )
        UsenetFailedReleases.record(good, "blank-content")
        assertNull(nextFailoverStream(streams = listOf(broken, good), activeIdentityKey = null, triedIdentityKeys = emptySet()))
    }

    @Test
    fun `an engine or provider failure passes over every Usenet row`() {
        val usenet = release("u")
        val direct = StreamItem(name = "direct", url = "https://cdn.example/film.mkv", addonName = "Addon", addonId = "addon",
            behaviorHints = StreamBehaviorHints(filename = "film.mkv", videoSize = 1L))
        val engineDown = UsenetPreparationException("The Usenet engine did not start", UsenetPreparationException.Scope.ENGINE)
        val badRelease = UsenetPreparationException("missing", UsenetPreparationException.Scope.SOURCE, "missing-article", releaseUnplayable = true)
        assertTrue(usenetUnavailable(engineDown))
        assertTrue(usenetUnavailable(UsenetPreparationException("auth", UsenetPreparationException.Scope.PROVIDER)))
        assertFalse(usenetUnavailable(badRelease))
        assertFalse(usenetUnavailable(IllegalStateException()))
        assertEquals(
            direct,
            nextFailoverStream(streams = listOf(usenet, direct), activeIdentityKey = null, triedIdentityKeys = emptySet(), excludeUsenet = true),
        )
    }

    @Test
    fun `two uploads of one release are different sources`() {
        val first = release("Friends.S01E05.FraMeSToR").copy(nzbUrl = "https://indexer.example/get/1")
        val second = first.copy(nzbUrl = "https://indexer.example/get/2")
        assertEquals(first.behaviorHints.filename, second.behaviorHints.filename)
        assertTrue(first.playerSourceIdentityKey() != second.playerSourceIdentityKey())
        // The opened stream (engine URL added) is still the same source.
        assertEquals(first.playerSourceIdentityKey(), first.copy(url = "http://127.0.0.1:1/stream/x/a.mkv").playerSourceIdentityKey())
        assertFalse(first.playerSourceIdentityKey()!!.contains("indexer.example"), "no indexer link in the key")
        // One dead upload no longer makes failover skip the other.
        assertEquals(
            second,
            nextFailoverStream(
                streams = listOf(first, second),
                activeIdentityKey = first.playerSourceIdentityKey(),
                triedIdentityKeys = setOfNotNull(first.playerSourceIdentityKey()),
            ),
        )
    }

    @Test
    fun `a known compressed 7z scores by kind and is remembered across restarts`() {
        val dir = kotlin.io.path.createTempDirectory("compressed").toFile()
        UsenetCompressedReleases.directory = { dir }
        UsenetCompressedReleases.reset()
        try {
            val stream = release("Friends.S01E05.2160p.BluRay.REMUX-FraMeSToR")
            val profile = com.nuvio.app.features.streams.StreamScoreProfile(enabled = true, points = emptyMap())
            val episode = com.nuvio.app.features.streams.StreamScoreContext(isEpisode = true)
            val movie = com.nuvio.app.features.streams.StreamScoreContext(isEpisode = false)
            val anime = com.nuvio.app.features.streams.StreamScoreContext(isEpisode = false, isAnimation = true)
            fun total(ctx: com.nuvio.app.features.streams.StreamScoreContext) =
                com.nuvio.app.features.streams.StreamScorer.score(stream, profile, ctx).total
            val before = Triple(total(episode), total(movie), total(anime))
            UsenetCompressedReleases.record(stream.nzbUrl)
            UsenetCompressedReleases.reset() // read back from disk
            assertTrue(UsenetCompressedReleases.isCompressed(stream))
            assertEquals(before.first - 200, total(episode), "series default -200, even in a profile that never set it")
            assertEquals(before.second - 100, total(movie), "movie default -100")
            assertEquals(before.third - 200, total(anime), "anime films count as series/anime")
            // Set by hand, including 0, it sticks.
            val zeroed = profile.withPoints(com.nuvio.app.features.streams.StreamScoreTrait.COMPRESSED_ARCHIVE_SERIES, 0)
            assertEquals(0, zeroed.pointsFor(com.nuvio.app.features.streams.StreamScoreTrait.COMPRESSED_ARCHIVE_SERIES))
            assertEquals(before.first, com.nuvio.app.features.streams.StreamScorer.score(stream, zeroed, episode).total)
            assertFalse(java.io.File(dir, "usenet-compressed-7z.json").readText().contains("indexer.example"), "links stored hashed")
            assertFalse(UsenetCompressedReleases.isCompressed(release("other")))
        } finally {
            UsenetCompressedReleases.reset()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the compressed 7z size limit`() {
        assertEquals(UsenetConfiguration.COMPRESSED_SKIP_ALL, UsenetConfiguration().compressedSkipGb, "default: always skip (Nov 23)")
        val five = UsenetConfiguration(compressedSkipGb = 5)
        assertEquals(5_000_000_000L, five.maxCompressedBytes)
        assertFalse(five.skipsCompressed(4_000_000_000L))
        assertTrue(five.skipsCompressed(8_000_000_000L))
        assertTrue(five.skipsCompressed(null), "unknown size counts as too large")
        val playAll = UsenetConfiguration(compressedSkipGb = UsenetConfiguration.COMPRESSED_PLAY_ALL)
        assertEquals(0L, playAll.maxCompressedBytes)
        assertFalse(playAll.skipsCompressed(90_000_000_000L))
        val skipAll = UsenetConfiguration(compressedSkipGb = UsenetConfiguration.COMPRESSED_SKIP_ALL)
        assertEquals(1L, skipAll.maxCompressedBytes)
        assertTrue(skipAll.skipsCompressed(1L))
        assertEquals(
            UsenetConfiguration.COMPRESSED_SKIP_ALL,
            UsenetConfiguration(compressedSkipGb = 7).normalized().compressedSkipGb,
            "unknown choice falls back to the default",
        )
    }

    @Test
    fun `the engine's compressed skip is a release failure that failover passes`() {
        val skipped = UsenetPreparationException(
            "compressed 7z release, too large to seek in",
            UsenetPreparationException.Scope.SOURCE,
            "archive-compressed",
            releaseUnplayable = UsenetEngine.isUnplayableRelease(422, "archive-compressed"),
        )
        assertTrue(skipped.releaseUnplayable)
        assertTrue(usenetCompressedSkip(skipped))
        assertFalse(usenetUnavailable(skipped), "other Usenet rows stay candidates")
        assertFalse(usenetCompressedSkip(UsenetPreparationException("x", UsenetPreparationException.Scope.SOURCE, "missing-article")))
    }

    @Test
    fun `rows already listed switch to the book when their NZB is kept`() {
        val subject = UsenetStreamFormat.Subject(title = "Friends", series = true)
        val played = UsenetStreamFormat.format(release("Friends.S01E05.2160p"), subject, "AltHub", 0L, false)
        val other = UsenetStreamFormat.format(release("Friends.S01E05.1080p"), subject, "AltHub", 0L, false)
        val groups = listOf(com.nuvio.app.features.streams.AddonStreamGroup(addonName = "U", addonId = "u", streams = listOf(played, other)))
        val marked = UsenetKeptRows.markKept(groups, played.nzbUrl!!)
        assertTrue(marked[0].streams[0].description!!.startsWith("📖"))
        assertTrue(marked[0].streams[1].description!!.startsWith("⚡"), "only that release")
        assertEquals(played.playerSourceIdentityKey(), marked[0].streams[0].playerSourceIdentityKey(), "still the same source")
        assertTrue(UsenetKeptRows.markKept(marked, played.nzbUrl!!) === marked, "nothing to change: same list")
    }

    @Test
    fun `a kept NZB reads with a book`() {
        val subject = UsenetStreamFormat.Subject(title = "Friends", series = true)
        assertEquals("⚡Friends | S01 E05", UsenetStreamFormat.titleLine(subject, "Friends.S01E05.2160p"))
        assertEquals("📖Friends | S01 E05", UsenetStreamFormat.titleLine(subject, "Friends.S01E05.2160p", kept = true))
    }

    @Test
    fun `a release counts as kept while the engine's file is there`() {
        val dir = kotlin.io.path.createTempDirectory("kept").toFile()
        UsenetKeptNzbs.directory = { dir }
        UsenetKeptNzbs.reset()
        try {
            val link = "https://indexer.example/api?t=get&id=9&apikey=secret"
            val key = "a".repeat(64)
            assertFalse(UsenetKeptNzbs.isKept(link))
            UsenetKeptNzbs.remember(link, key)
            assertFalse(UsenetKeptNzbs.isKept(link), "remembered, but the engine has not written it")
            java.io.File(dir, "$key.nzb").writeText("x")
            assertTrue(UsenetKeptNzbs.isKept(link))
            UsenetKeptNzbs.reset() // read back from disk
            assertTrue(UsenetKeptNzbs.isKept(link))
            assertFalse(java.io.File(dir, "kept-index.json").readText().contains("secret"), "the link is stored hashed")
            UsenetKeptNzbs.remember("https://other", "not-a-key")
            assertFalse(UsenetKeptNzbs.isKept("https://other"))
            java.io.File(dir, "$key.nzb").delete()
            assertFalse(UsenetKeptNzbs.isKept(link), "evicted by the engine")
        } finally {
            UsenetKeptNzbs.reset()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `decode progress is read only while a read waits for the decoder`() {
        val json = Json { ignoreUnknownKeys = true }
        fun diag(text: String) = json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        val waiting = assertNotNull(usenetDecodeProgress(diag(
            """{"compressed7z":true,"compressed7zDecodedBytes":829666422,"compressed7zWaitingFor":3900000000,"compressed7zSize":7432700000}""",
        )))
        assertEquals("830 MB / 3.9 GB", usenetDecodeProgressLabel(waiting))
        assertNull(usenetDecodeProgress(diag("""{"compressed7z":true,"compressed7zDecodedBytes":5,"compressed7zWaitingFor":0}""")))
        assertNull(usenetDecodeProgress(diag("""{"compressed7zDecodedBytes":5,"compressed7zWaitingFor":9}""")))
    }

    @Test
    fun `the engine's stream failure is read from its diagnostics`() {
        val json = Json { ignoreUnknownKeys = true }
        fun diag(text: String) = json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        assertNull(usenetStreamFailure(null))
        assertNull(usenetStreamFailure(diag("""{"streamFailure":"","compressed7z":true}""")))
        assertEquals("missing-article", usenetStreamFailure(diag("""{"streamFailure":"missing-article"}""")))
        assertTrue(usenetCompressed7z(diag("""{"streamFailure":"","compressed7z":true}""")))
        assertFalse(usenetCompressed7z(diag("""{"archiveExtents":3}""")))
        assertNull(usenetStreamFailure(diag("""{"streamFailure":{"odd":1}}""")))
    }

    @Test
    fun `a release that opens is forgotten, and keys keep the file pick apart`() {
        val pack = release("pack")
        val episodeOne = pack.copy(fileIdx = 1)
        UsenetFailedReleases.record(pack, "missing-article")
        assertTrue(UsenetFailedReleases.hasFailed(pack))
        assertFalse(UsenetFailedReleases.hasFailed(episodeOne))
        UsenetFailedReleases.forget(pack)
        assertFalse(UsenetFailedReleases.hasFailed(pack))
        assertFalse(UsenetFailedReleases.hasFailed(StreamItem(name = "direct", url = "https://x/y.mkv", addonName = "a", addonId = "a")))
    }

    @Test
    fun `only the engine's 422 for the release counts as unplayable`() {
        assertTrue(UsenetEngine.isUnplayableRelease(422, "missing-article"))
        assertTrue(UsenetEngine.isUnplayableRelease(422, null)) // e.g. "encrypted 7z needs a password"
        assertFalse(UsenetEngine.isUnplayableRelease(422, "provider"))
        assertFalse(UsenetEngine.isUnplayableRelease(422, "provider-quota"))
        assertFalse(UsenetEngine.isUnplayableRelease(409, null)) // session busy
        assertFalse(UsenetEngine.isUnplayableRelease(502, null)) // pool could not start
    }

    @Test
    fun `a played release is cached by its NZB and replays as the same source`() {
        val original = release("show.s01e02").copy(
            usenetPassword = "pw",
            fileIdx = 3,
            fileMustInclude = "S01E02",
            behaviorHints = StreamBehaviorHints(
                filename = "show.s01e02.mkv",
                videoSize = 2_000_000_000L,
                bingeGroup = "Built-in Usenet|show",
                proxyHeaders = StreamProxyHeaders(request = mapOf("User-Agent" to "Nuvio")),
            ),
        )
        // What the player holds after the engine opened it: the engine's URL, which dies with it.
        val playing = original.copy(url = "http://127.0.0.1:5000/stream/abc/show.s01e02.mkv")
        val key = original.playerSourceIdentityKey()
        val entry = assertNotNull(StreamLinkCacheRepository.usenetEntry(playing, key, nowMs = 1_000L))
        assertEquals("", entry.url)
        assertEquals(original.nzbUrl, entry.nzbUrl)

        val json = Json { ignoreUnknownKeys = true }
        val stored = json.decodeFromString(CachedStreamLink.serializer(), json.encodeToString(CachedStreamLink.serializer(), entry))
        val replay = assertNotNull(stored.toUsenetStream())
        assertTrue(replay.needsUsenetPreparation, "the replay must go through the engine again")
        assertEquals(original.nzbUrl, replay.nzbUrl)
        assertEquals(3, replay.fileIdx)
        assertEquals("S01E02", replay.fileMustInclude)
        assertEquals("pw", replay.usenetPassword, "a name's password survives the replay")
        assertEquals(mapOf("User-Agent" to "Nuvio"), replay.behaviorHints.proxyHeaders?.request)
        assertEquals(key, stored.sourceIdentityKey)
        assertEquals(key, replay.playerSourceIdentityKey(), "the sources list must still mark the replayed row")
    }

    @Test
    fun `a release with its own NNTP servers is not written to disk`() {
        val withServers = release("x").copy(usenetServers = listOf("nntps://user:pass@news.example:563/20"))
        assertNull(StreamLinkCacheRepository.usenetEntry(withServers, null, nowMs = 1L))
    }

    @Test
    fun `other cached links are not Usenet replays`() {
        val direct = CachedStreamLink(url = "https://cdn.example/a.mkv", streamName = "a", addonName = "x", addonId = "x", cachedAtMs = 1L)
        assertNull(direct.toUsenetStream())
    }
}
