package com.nuvio.app.core.ui

import co.touchlab.kermit.Logger
import coil3.disk.DiskCache
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path.Companion.toOkioPath

private val shardLog = Logger.withTag("ImageDiskCache")

/**
 * The stills cache as [ImageCacheShardCount] Coil disk caches, one per leading hex digit of the
 * key's SHA-256 (the name Coil gives an entry's files).
 *
 * One cache meant one journal, and opening a cache replays its whole journal on one thread before
 * any image can be read: 76,000 entries took 1.2–1.6 s in the native image (2026-11-24), finishing
 * just as Home asked for its first posters. Sixteen journals replay side by side (see [warmAll]),
 * and an image that arrives first waits only for its own shard. Each shard gets an even share of the
 * size budget; SHA-256 spreads keys evenly, so the LRU is unchanged in practice.
 *
 * Only the journals are per shard (`image_cache/<digit>/journal`). The entry files stay together in
 * `image_cache` itself, reached through [ShardEntryFileSystem]: moving 160,000 files into shard
 * folders ran for minutes on this PC (every file touched was scanned), while splitting the journal
 * is a few MB of text.
 */
internal class ShardedImageDiskCache(
    private val shards: List<DiskCache>,
    override val directory: okio.Path,
) : DiskCache {

    init {
        require(shards.size == ImageCacheShardCount)
    }

    override val size: Long get() = shards.sumOf { it.size }
    override val maxSize: Long get() = shards.sumOf { it.maxSize }
    override val fileSystem: FileSystem get() = shards.first().fileSystem

    private fun shardFor(key: String): DiskCache = shards[imageCacheShardIndex(key)]

    override fun openSnapshot(key: String): DiskCache.Snapshot? = shardFor(key).openSnapshot(key)
    override fun openEditor(key: String): DiskCache.Editor? = shardFor(key).openEditor(key)
    override fun remove(key: String): Boolean = shardFor(key).remove(key)
    override fun clear() = shards.forEach { it.clear() }
    override fun shutdown() = shards.forEach { it.shutdown() }

    /** Opens every shard (each replays its journal) in parallel; returns once all are open. */
    fun warmAll() {
        val threads = shards.mapIndexed { index, shard ->
            Thread({ runCatching { shard.openSnapshot(ShardWarmupKey)?.close() } }, "nuvio-image-cache-open-$index")
                .apply { isDaemon = true; start() }
        }
        threads.forEach { it.join() }
    }

    companion object {
        private const val ShardWarmupKey = "nuvio-disk-cache-warmup"

        /** The shards under [cacheDir], built (not yet opened), with [maxSizeBytes] split evenly. */
        fun build(cacheDir: Path, maxSizeBytes: Long): ShardedImageDiskCache {
            val perShard = (maxSizeBytes / ImageCacheShardCount).coerceAtLeast(1L)
            val root = cacheDir.toOkioPath()
            val fileSystem = ShardEntryFileSystem(root)
            val shards = (0 until ImageCacheShardCount).map { index ->
                DiskCache.Builder()
                    .fileSystem(fileSystem)
                    .directory(root / imageCacheShardName(index))
                    .maxSizeBytes(perShard)
                    .build()
            }
            return ShardedImageDiskCache(shards, root)
        }
    }
}

/**
 * Sends an entry file a shard asks for (`<root>/<digit>/<hash>.<n>[.tmp]`) to `<root>/<hash>.<n>[.tmp]`,
 * where every entry file lives; the shard's own journal files stay in its folder.
 */
internal class ShardEntryFileSystem(private val root: okio.Path) : ForwardingFileSystem(SYSTEM) {
    override fun onPathParameter(path: okio.Path, functionName: String, parameterName: String): okio.Path {
        val parent = path.parent ?: return path
        if (parent.parent != root || parent.name.length != 1 || parent.name[0] !in HexDigits) return path
        return if (isEntryFileName(path.name)) root / path.name else path
    }
}

internal const val ImageCacheShardCount = 16

/** The folder of shard [index]: its hex digit. */
internal fun imageCacheShardName(index: Int): String = HexDigits[index].toString()

/** Which shard holds [key]: the first hex digit of the SHA-256 Coil names the entry by. */
internal fun imageCacheShardIndex(key: String): Int =
    (key.encodeUtf8().sha256()[0].toInt() ushr 4) and 0x0F

/** The same shard, from a key as the journal writes it (Coil's 64-character hex hash). */
private fun shardIndexOfHashedKey(hashedKey: String): Int = HexDigits.indexOf(hashedKey[0])

private const val HexDigits = "0123456789abcdef"

/** `<64 hex>.<n>` or `<64 hex>.<n>.tmp`, without a regex: this runs on every cache file access. */
private fun isEntryFileName(name: String): Boolean {
    if (name.length < 66 || name[64] != '.') return false
    for (i in 0 until 64) if (name[i] !in HexDigits) return false
    val rest = name.substring(65).removeSuffix(".tmp")
    return rest.isNotEmpty() && rest.all(Char::isDigit)
}

/**
 * Splits a single-journal stills cache into per-shard journals, once. Runs before any shard is
 * opened, on the cache-open thread. Only the journal is touched: the entry files stay where they
 * are (see [ShardedImageDiskCache]). The old journal is deleted last, so an interrupted split is
 * simply done again from it next launch.
 *
 * Also puts back entry files an earlier build moved into the shard folders (it moved files instead,
 * and was stopped part way on 2026-11-24): only names are read, no file is stat'ed.
 */
internal object ImageCacheShardMigration {

    private const val JournalName = "journal"
    private const val JournalBackupName = "journal.bkp"
    private const val JournalMagic = "libcore.io.DiskLruCache"
    private const val HeaderLines = 5

    fun migrateIfNeeded(cacheDir: Path) {
        runCatching { migrate(cacheDir) }.onFailure { error ->
            shardLog.w(error) { "Shard migration failed; the old single-journal cache is left as it was" }
        }
    }

    private fun migrate(cacheDir: Path) {
        val journal = cacheDir.resolve(JournalName)
        val backup = cacheDir.resolve(JournalBackupName)
        // DiskLruCache itself prefers the backup when the journal is missing (a crash mid-rewrite).
        if (!journal.exists() && backup.exists()) Files.move(backup, journal)
        if (!journal.exists()) return

        val startedAt = System.nanoTime()
        val restored = restoreMovedEntryFiles(cacheDir)
        val lines = Files.readAllLines(journal, StandardCharsets.UTF_8)
        if (lines.size < HeaderLines || lines[0] != JournalMagic) {
            shardLog.w { "Old image cache journal is not a DiskLruCache journal; leaving it" }
            return
        }
        val header = lines.subList(0, HeaderLines)
        val perShard = Array(ImageCacheShardCount) { StringBuilder() }
        var entryLines = 0
        for (index in HeaderLines until lines.size) {
            val line = lines[index]
            val key = line.substringAfter(' ', "").substringBefore(' ')
            if (key.length != 64 || key.any { it !in HexDigits }) continue
            perShard[shardIndexOfHashedKey(key)].append(line).append('\n')
            entryLines++
        }

        for (index in 0 until ImageCacheShardCount) {
            val shardDir = cacheDir.resolve(imageCacheShardName(index))
            Files.createDirectories(shardDir)
            val tmp = shardDir.resolve("$JournalName.tmp")
            val text = buildString {
                header.forEach { append(it).append('\n') }
                append(perShard[index])
            }
            Files.writeString(tmp, text, StandardCharsets.UTF_8)
            Files.move(tmp, shardDir.resolve(JournalName), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            Files.deleteIfExists(shardDir.resolve(JournalBackupName))
        }

        Files.deleteIfExists(backup)
        Files.delete(journal)
        shardLog.i {
            "Split the image cache journal into $ImageCacheShardCount shards: $entryLines lines" +
                (if (restored > 0) ", $restored files put back" else "") +
                " in ${(System.nanoTime() - startedAt) / 1_000_000}ms"
        }
    }

    private fun restoreMovedEntryFiles(cacheDir: Path): Int {
        val moved = (0 until ImageCacheShardCount).flatMap { index ->
            val shardDir = cacheDir.resolve(imageCacheShardName(index))
            if (!Files.isDirectory(shardDir)) return@flatMap emptyList()
            Files.newDirectoryStream(shardDir).use { entries ->
                entries.filter { isEntryFileName(it.fileName.toString()) }
            }
        }
        moved.parallelStream().forEach { file ->
            runCatching { Files.move(file, cacheDir.resolve(file.fileName.toString()), StandardCopyOption.REPLACE_EXISTING) }
        }
        return moved.size
    }
}
