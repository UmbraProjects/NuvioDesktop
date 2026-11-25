package com.nuvio.app.core.ui

import coil3.disk.DiskCache
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

class ShardedImageDiskCacheTest {

    private val root: Path = Files.createTempDirectory("nuvio-shard-test")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun DiskCache.put(key: String, body: String) {
        val editor = checkNotNull(openEditor(key))
        fileSystem.write(editor.metadata) { writeUtf8("meta-$key") }
        fileSystem.write(editor.data) { writeUtf8(body) }
        editor.commit()
    }

    private fun DiskCache.read(key: String): String? = openSnapshot(key)?.use { snapshot ->
        fileSystem.source(snapshot.data).buffer().use { it.readUtf8() }
    }

    private val keys = (1..200).map { "https://image.tmdb.org/t/p/w342/poster-$it.jpg" }

    @Test
    fun coilNamesEntryFilesByTheShaTheRouterUses() {
        // The migration routes journal keys (Coil's hashed names) and the live cache routes the
        // original keys; both must land on the same shard, so Coil's file name must be this hash.
        val dir = root.resolve("single")
        val single = DiskCache.Builder().directory(dir.toOkioPath()).maxSizeBytes(10_000_000).build()
        single.put("hello", "world")
        single.shutdown()
        val names = dir.listDirectoryEntries().map { it.name }.filter { !it.startsWith("journal") }
        assertTrue(names.isNotEmpty())
        val hashed = names.first().substringBefore('.')
        assertEquals(64, hashed.length)
        assertTrue(names.all { it.startsWith(hashed) })
        assertEquals("0123456789abcdef".indexOf(hashed[0]), imageCacheShardIndex("hello"))
    }

    @Test
    fun migratedEntriesAreReadBackFromTheirShards() {
        val dir = root.resolve("image_cache")
        val single = DiskCache.Builder().directory(dir.toOkioPath()).maxSizeBytes(100_000_000).build()
        keys.forEach { single.put(it, "body-$it") }
        single.remove(keys[0])
        single.shutdown()

        ImageCacheShardMigration.migrateIfNeeded(dir)

        assertFalse(dir.resolve("journal").exists(), "the old journal is gone once split")
        val entryFiles = Regex("^[0-9a-f]{64}\\..*")
        val topFilesBefore = dir.listDirectoryEntries().count { it.name.matches(entryFiles) }
        assertEquals((keys.size - 1) * 2, topFilesBefore, "entry files stay where they were")
        val sharded = ShardedImageDiskCache.build(dir, 100_000_000)
        sharded.warmAll()
        assertNull(sharded.read(keys[0]), "a removed entry stays removed")
        keys.drop(1).forEach { key -> assertEquals("body-$key", sharded.read(key)) }
        assertTrue(sharded.size > 0)
        // Every shard folder holds only its journal; the entries spread over the shards.
        (0 until ImageCacheShardCount).forEach { index ->
            val names = dir.resolve(imageCacheShardName(index)).listDirectoryEntries().map { it.name }
            assertTrue(names.none { it.matches(entryFiles) }, "shard $index holds entry files: $names")
        }
        val used = (0 until ImageCacheShardCount).count { index ->
            Files.size(dir.resolve(imageCacheShardName(index)).resolve("journal")) > 200
        }
        assertTrue(used >= 12, "only $used shards used")
        // New entries also land at the top, and the PartitionedImageDiskCache truncation check
        // (which reads sizes through this file system) sees their real size.
        sharded.put("https://example.org/new.jpg", "fresh")
        assertEquals(topFilesBefore + 2, dir.listDirectoryEntries().count { it.name.matches(entryFiles) })
        sharded.openSnapshot("https://example.org/new.jpg")!!.use { snapshot ->
            assertEquals(5L, sharded.fileSystem.metadata(snapshot.data).size)
        }
        sharded.shutdown()
    }

    @Test
    fun migrationIsRepeatableAfterAnInterruption() {
        val dir = root.resolve("image_cache")
        val single = DiskCache.Builder().directory(dir.toOkioPath()).maxSizeBytes(100_000_000).build()
        keys.forEach { single.put(it, "body-$it") }
        single.shutdown()
        // As the earlier build left it: some shard journals written and some entry files moved into
        // shard folders, the old journal still in place.
        val movedFiles = dir.listDirectoryEntries().filter { it.name.length > 64 }.take(30)
        movedFiles.forEach { file ->
            val shardDir = dir.resolve(file.name.substring(0, 1))
            Files.createDirectories(shardDir)
            Files.writeString(shardDir.resolve("journal"), "partial")
            Files.move(file, shardDir.resolve(file.name))
        }

        ImageCacheShardMigration.migrateIfNeeded(dir)

        movedFiles.forEach { assertTrue(it.exists(), "${it.name} put back") }
        val sharded = ShardedImageDiskCache.build(dir, 100_000_000)
        keys.forEach { key -> assertEquals("body-$key", sharded.read(key)) }
        sharded.shutdown()
    }

    /** Answers every key as cached (a non-null snapshot is not needed: a call count is enough). */
    private class FakeShard(private val hangOnOpen: java.util.concurrent.CountDownLatch?) : DiskCache {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        override val size = 0L
        override val maxSize = 1L
        override val directory = "/fake".toPath()
        override val fileSystem = okio.FileSystem.SYSTEM
        override fun openSnapshot(key: String): DiskCache.Snapshot? {
            calls.incrementAndGet()
            hangOnOpen?.await()
            return null
        }
        override fun openEditor(key: String): DiskCache.Editor? { calls.incrementAndGet(); return null }
        override fun remove(key: String) = false
        override fun clear() = Unit
        override fun shutdown() = Unit
    }

    @Test
    fun aShardStuckOpeningIsSkippedAfterTheBudget() {
        val stuckKey = keys.first()
        val stuckIndex = imageCacheShardIndex(stuckKey)
        val release = java.util.concurrent.CountDownLatch(1)
        val shards = (0 until ImageCacheShardCount).map { FakeShard(if (it == stuckIndex) release else null) }
        val cache = ShardedImageDiskCache(shards, "/fake".toPath())
        val warm = Thread { cache.warmAll() }.apply { isDaemon = true; start() }
        try {
            Thread.sleep(50)
            val otherKey = keys.first { imageCacheShardIndex(it) != stuckIndex }
            cache.openSnapshot(otherKey)
            assertEquals(2, shards[imageCacheShardIndex(otherKey)].calls.get(), "an open shard serves at once")

            val startedAt = System.nanoTime()
            assertNull(cache.openSnapshot(stuckKey))
            assertNull(cache.openEditor(stuckKey))
            val waitedMs = (System.nanoTime() - startedAt) / 1_000_000
            assertTrue(waitedMs < ShardedImageDiskCache.ShardOpenBudgetMs + 500, "waited ${waitedMs}ms")
            assertEquals(1, shards[stuckIndex].calls.get(), "the stuck shard is not asked again while it opens")

            release.countDown()
            warm.join(5_000)
            cache.openSnapshot(stuckKey)
            assertEquals(2, shards[stuckIndex].calls.get(), "once open, the shard is used again")
        } finally {
            release.countDown()
        }
    }

    @Test
    fun freshCacheNeedsNoMigrationAndStoresAcrossShards() {
        val dir = root.resolve("image_cache")
        ImageCacheShardMigration.migrateIfNeeded(dir)
        val sharded = ShardedImageDiskCache.build(dir, 100_000_000)
        keys.forEach { sharded.put(it, "body-$it") }
        sharded.shutdown()
        val reopened = ShardedImageDiskCache.build(dir, 100_000_000)
        reopened.warmAll()
        keys.forEach { key -> assertNotNull(reopened.read(key)) }
        reopened.shutdown()
    }
}
