package com.nuvio.app.core.ui

import coil3.PlatformContext
import coil3.annotation.ExperimentalCoilApi
import coil3.annotation.InternalCoilApi
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.CacheNetworkResponse
import coil3.network.NetworkHeaders
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.request.Options
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two halves of the "a Continue Watching card keeps the show backdrop forever" fix.
 *
 * A metadata provider that has not published an episode still yet answers 404, and Coil's default
 * strategy both stored that 404 and served it back without ever going to the network again. These
 * tests pin the two behaviours that stop it: the strategy never writes or serves a failure, and the
 * purge gets the ones already on disk out of the cache without breaking Coil's journal.
 */
@OptIn(ExperimentalCoilApi::class)
class ImageDiskCacheStrategyTest {

    private val options = Options(context = PlatformContext.INSTANCE)
    private val request = NetworkRequest(url = "https://episodes.metahub.space/tt10986410/4/9/w780.jpg")

    @Test
    fun `a failure response is never written to the cache`() = runBlocking<Unit> {
        val result = ImageDiskCacheStrategy.write(
            cacheResponse = null,
            networkRequest = request,
            networkResponse = response(
                code = 404,
                // metahub really does send this on a missing still: four days of negative caching.
                "cache-control" to "public, max-age=345600",
            ),
            options = options,
        )

        assertEquals(CacheStrategyWriteDisabled, result)
    }

    @Test
    fun `a failure with a copy on disk stores that copy back unchanged`() = runBlocking<Unit> {
        // DISABLED here would leak Coil's snapshot of the entry and lock it for the session.
        val cached = response(code = 200, "cache-control" to "max-age=60", "etag" to "\"a\"")
            .copy(requestMillis = 1_000L, responseMillis = 2_000L)

        val stored = ImageDiskCacheStrategy.write(
            cacheResponse = cached,
            networkRequest = request,
            networkResponse = response(code = 503),
            options = options,
        ).response

        assertNotNull(stored)
        assertEquals(200, stored.code)
        assertNull(stored.body, "a null body leaves the existing data file in place")
        assertEquals(cached.headers, stored.headers)
        assertEquals(2_000L, stored.responseMillis, "a failure does not make the copy any fresher")
    }

    @Test
    fun `a success response is written to the cache`() = runBlocking<Unit> {
        val networkResponse = response(code = 200, "cache-control" to "max-age=31536000, immutable")

        val result = ImageDiskCacheStrategy.write(
            cacheResponse = null,
            networkRequest = request,
            networkResponse = networkResponse,
            options = options,
        )

        assertEquals(networkResponse.code, result.response?.code)
        assertEquals(networkResponse.headers, result.response?.headers)
    }

    @Test
    fun `a revalidated entry keeps its cached code and body and takes the fresh headers`() = runBlocking<Unit> {
        val cached = response(
            code = 200,
            "cache-control" to "max-age=60",
            "content-type" to "image/jpeg",
        ).copy(requestMillis = 1_000L, responseMillis = 2_000L)
        val notModified = response(code = 304, "cache-control" to "max-age=86400")
            .copy(requestMillis = 900_000L, responseMillis = 901_000L)

        ImageFreshnessRegistry.nowMillis = { 901_000L }
        val stored = try {
            ImageDiskCacheStrategy.write(
                cacheResponse = cached,
                networkRequest = request,
                networkResponse = notModified,
                options = options,
            ).response
        } finally {
            ImageFreshnessRegistry.nowMillis = System::currentTimeMillis
        }

        assertNotNull(stored)
        // 304 is not what the body on disk is; storing it would make the entry look like a failure
        // to the read side and re-request it on every load.
        assertEquals(200, stored.code)
        assertNull(stored.body, "a null body leaves the existing data file in place")
        assertEquals("max-age=86400", stored.headers["cache-control"])
        assertEquals("image/jpeg", stored.headers["content-type"])
        // Age must be measured from the revalidation, or the entry is stale again immediately.
        assertEquals(901_000L, stored.responseMillis)
    }

    @Test
    fun `a cached failure is re-requested rather than served`() = runBlocking<Unit> {
        val result = ImageDiskCacheStrategy.read(
            cacheResponse = response(code = 404, "cache-control" to "public, max-age=345600")
                .copy(responseMillis = System.currentTimeMillis()),
            networkRequest = request,
            options = options,
        )

        assertNull(result.response, "a cached 404 must never be served")
        assertNotNull(result.request)
    }

    @Test
    fun `an immutable response is served from the cache without a request`() = runBlocking<Unit> {
        val cached = response(code = 200, "cache-control" to "public, max-age=31536000, immutable")
            .copy(responseMillis = 0L)

        val result = ImageDiskCacheStrategy.read(cached, request, options)

        assertEquals(cached, result.response)
    }

    @Test
    fun `a fresh response is served from the cache`() = runBlocking<Unit> {
        val cached = response(code = 200, "cache-control" to "max-age=3600")
            .copy(responseMillis = System.currentTimeMillis())

        val result = ImageDiskCacheStrategy.read(cached, request, options)

        assertEquals(cached, result.response)
    }

    @Test
    fun `an expired response with a validator is revalidated`() = runBlocking<Unit> {
        val cached = response(
            code = 200,
            "cache-control" to "max-age=60",
            "etag" to "W/\"abc\"",
        ).copy(responseMillis = System.currentTimeMillis() - 600_000L)

        val result = ImageDiskCacheStrategy.read(cached, request, options)

        assertNull(result.response)
        assertEquals("W/\"abc\"", result.request?.headers?.get("if-none-match"))
    }

    @Test
    fun `the reported age counts against freshness`() = runBlocking<Unit> {
        // Stored a moment ago, but the CDN had already been holding it for two hours.
        val cached = response(
            code = 200,
            "cache-control" to "max-age=3600",
            "age" to "7200",
            "etag" to "W/\"abc\"",
        ).copy(responseMillis = System.currentTimeMillis())

        val result = ImageDiskCacheStrategy.read(cached, request, options)

        assertNull(result.response, "an entry already past max-age at the origin is not fresh")
    }

    @Test
    fun `an expired response without a validator is re-downloaded, not kept until the backstop`() = runBlocking<Unit> {
        // The origin said one day; that is the whole point of letting a poster service set it.
        val cached = response(code = 200, "cache-control" to "public, max-age=86400")
            .copy(responseMillis = System.currentTimeMillis() - 2 * 86_400_000L)

        val result = ImageDiskCacheStrategy.read(cached, request, options)

        assertNull(result.response)
        assertNull(result.request?.headers?.get("if-none-match"), "nothing to validate against")
    }

    @Test
    fun `a declared lifetime is honoured past the backstop`() = runBlocking<Unit> {
        // Sixty days asked for, forty days held: still fresh, whatever the default would say.
        val cached = response(code = 200, "cache-control" to "max-age=5184000")
            .copy(responseMillis = System.currentTimeMillis() - 40 * 86_400_000L)

        assertEquals(cached, ImageDiskCacheStrategy.read(cached, request, options).response)
    }

    @Test
    fun `a response that declared nothing gets the backstop`() = runBlocking<Unit> {
        val young = response(code = 200, "etag" to "\"x\"")
            .copy(responseMillis = System.currentTimeMillis() - 10 * 86_400_000L)
        val old = young.copy(responseMillis = System.currentTimeMillis() - 40 * 86_400_000L)

        assertEquals(young, ImageDiskCacheStrategy.read(young, request, options).response)
        assertEquals("\"x\"", ImageDiskCacheStrategy.read(old, request, options).request?.headers?.get("if-none-match"))
    }

    @Test
    fun `the reported age does not count against the backstop`() = runBlocking<Unit> {
        // artworks.thetvdb.com: no Cache-Control at all, but CloudFront has held it for five months.
        // The backstop is our own policy about how long to trust a stable URL, so only the time we
        // have held the entry counts — otherwise it is stale on arrival and revalidates on every read.
        val cached = response(code = 200, "age" to "12704009", "etag" to "\"x\"")
            .copy(responseMillis = System.currentTimeMillis() - 10 * 86_400_000L)

        assertEquals(cached, ImageDiskCacheStrategy.read(cached, request, options).response)
    }

    @Test
    fun `no-cache without a validator is re-downloaded on every read`() = runBlocking<Unit> {
        val cached = response(code = 200, "cache-control" to "no-cache")
            .copy(responseMillis = System.currentTimeMillis())

        assertNull(ImageDiskCacheStrategy.read(cached, request, options).response)
    }

    @Test
    fun `what the strategy decides is what the memory cache is told`() = runBlocking<Unit> {
        ImageFreshnessRegistry.clear()
        val now = 1_000_000_000_000L
        ImageFreshnessRegistry.nowMillis = { now }
        try {
            // A download: a day of freshness, first body.
            ImageDiskCacheStrategy.write(
                cacheResponse = null,
                networkRequest = request,
                networkResponse = response(code = 200, "cache-control" to "public, max-age=86400", "etag" to "\"a\"")
                    .copy(responseMillis = now),
                options = options,
            )
            val downloaded = assertNotNull(ImageFreshnessRegistry.get(request.url))
            assertEquals(now + 86_400_000L, downloaded.expiresAtMillis)
            assertEquals(1, downloaded.bodyVersion)

            // Served from disk later: the deadline is the remaining life, not a new full one.
            ImageFreshnessRegistry.nowMillis = { now + 3_600_000L }
            ImageDiskCacheStrategy.read(
                cacheResponse = response(code = 200, "cache-control" to "public, max-age=86400", "etag" to "\"a\"")
                    .copy(responseMillis = now),
                networkRequest = request,
                options = options,
            )
            val served = assertNotNull(ImageFreshnessRegistry.get(request.url))
            assertEquals(now + 86_400_000L, served.expiresAtMillis)
            assertEquals(1, served.bodyVersion, "the same bytes")

            // Revalidated with a 304 carrying a new deadline: freshness moves, the body does not.
            ImageFreshnessRegistry.nowMillis = { now + 90_000_000L }
            ImageDiskCacheStrategy.write(
                cacheResponse = response(code = 200, "cache-control" to "public, max-age=86400", "etag" to "\"a\"")
                    .copy(responseMillis = now),
                networkRequest = request,
                networkResponse = response(code = 304, "cache-control" to "public, max-age=3600", "etag" to "\"a\"")
                    .copy(responseMillis = now + 90_000_000L),
                options = options,
            )
            val revalidated = assertNotNull(ImageFreshnessRegistry.get(request.url))
            assertEquals(now + 90_000_000L + 3_600_000L, revalidated.expiresAtMillis)
            assertEquals(1, revalidated.bodyVersion)

            // A new body: every bitmap decoded from the old one is retired.
            ImageDiskCacheStrategy.write(
                cacheResponse = null,
                networkRequest = request,
                networkResponse = response(code = 200, "cache-control" to "no-store")
                    .copy(responseMillis = now + 90_000_000L),
                options = options,
            )
            val replaced = assertNotNull(ImageFreshnessRegistry.get(request.url))
            assertEquals(2, replaced.bodyVersion)
            // no-store is refused on disk, but the memory copy is held a minute so a card that
            // scrolls in and out does not re-download it each time.
            assertEquals(now + 90_000_000L + 60_000L, replaced.expiresAtMillis)

            // A custom disk-cache key is what Coil stores on the memory value, so it is the key here too.
            ImageDiskCacheStrategy.write(
                cacheResponse = null,
                networkRequest = request,
                networkResponse = response(code = 200, "cache-control" to "max-age=10").copy(responseMillis = now + 90_000_000L),
                options = options.copy(diskCacheKey = "custom-key"),
            )
            assertNotNull(ImageFreshnessRegistry.get("custom-key"))
        } finally {
            ImageFreshnessRegistry.nowMillis = System::currentTimeMillis
            ImageFreshnessRegistry.clear()
        }
    }

    @Test
    fun `an offline cache-only request is answered from the cache`() = runBlocking<Unit> {
        val cached = response(code = 200, "cache-control" to "max-age=60")
            .copy(responseMillis = 0L)
        val offlineRequest = request.copy(
            headers = NetworkHeaders.Builder()
                .apply { set("Cache-Control", "only-if-cached, max-stale=2147483647") }
                .build(),
        )

        val result = ImageDiskCacheStrategy.read(cached, offlineRequest, options)

        assertEquals(cached, result.response, "offline must still get the stale image, not nothing")
    }

    @Test
    fun `the purge drops cached failures and leaves Coil able to read the survivors`() {
        val cacheDir = newCacheDir()
        val hitUrl = "https://image.tmdb.org/t/p/w500/still.jpg"
        val missUrl = "https://episodes.metahub.space/tt10986410/4/9/w780.jpg"

        writeEntries(cacheDir, hitUrl to 200, missUrl to 404)
        ImageDiskCachePurge.purgeCachedFailures(cacheDir)

        openDiskCache(cacheDir).use { cache ->
            assertNotNull(cache.openSnapshot(hitUrl), "the good entry must survive the purge")
                .close()
            assertNull(cache.openSnapshot(missUrl), "the cached failure must be gone")
        }
        assertTrue(
            cacheDir.resolve("journal").readText().contains("REMOVE "),
            "the journal must record the eviction, not just lose the files",
        )
    }

    @Test
    fun `the purge runs once per cache directory`() {
        val cacheDir = newCacheDir()
        writeEntries(cacheDir, "https://image.tmdb.org/t/p/w500/still.jpg" to 200)

        ImageDiskCachePurge.purgeCachedFailures(cacheDir)
        val afterFirstRun = cacheDir.resolve("journal").readText()
        // A failure written after the purge is the strategy's job to refuse, not the purge's to
        // keep sweeping up: re-scanning 20,000 files on every launch buys nothing.
        writeEntries(cacheDir, "https://images.metahub.space/poster/small/tt1/img" to 404)
        ImageDiskCachePurge.purgeCachedFailures(cacheDir)

        assertTrue(cacheDir.resolve(".nuvio-cached-failure-purge-v1").exists())
        assertEquals(
            afterFirstRun.count { it == '\n' } + 2,
            cacheDir.resolve("journal").readText().count { it == '\n' },
            "only the DIRTY/CLEAN pair from writing the new entry, no second purge",
        )
    }

    private val tempDirs = mutableListOf<Path>()

    @AfterTest
    fun cleanUp() {
        for (dir in tempDirs) {
            runCatching {
                Files.walk(dir).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
        }
    }

    private fun newCacheDir(): Path =
        Files.createTempDirectory("nuvio-image-cache").also(tempDirs::add)

    private fun openDiskCache(cacheDir: Path): DiskCache = DiskCache.Builder()
        .directory(cacheDir.toOkioPath())
        .build()

    @OptIn(InternalCoilApi::class)
    private fun writeEntries(cacheDir: Path, vararg entries: Pair<String, Int>) {
        openDiskCache(cacheDir).use { cache ->
            for ((url, code) in entries) {
                val editor = assertNotNull(cache.openEditor(url))
                cache.fileSystem.write(editor.metadata) {
                    CacheNetworkResponse.writeTo(response(code = code), this)
                }
                cache.fileSystem.write(editor.data) { writeUtf8("image-bytes") }
                editor.commit()
            }
        }
    }

    private fun response(code: Int, vararg headers: Pair<String, String>) = NetworkResponse(
        code = code,
        headers = NetworkHeaders.Builder()
            .apply { for ((name, value) in headers) set(name, value) }
            .build(),
    )

    /** Named so the assertion reads as intent rather than as a companion lookup. */
    private val CacheStrategyWriteDisabled
        get() = coil3.network.CacheStrategy.WriteResult.DISABLED
}

private inline fun <T> DiskCache.use(block: (DiskCache) -> T): T = try {
    block(this)
} finally {
    shutdown()
}
